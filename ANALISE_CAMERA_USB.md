# Análise: Photobooth USB Camera — Arquitetura & Problemas

## 1. Arquitetura Atual (Fluxo Completo)

```
[EMEET SmartCam S600L]
        ↓ USB-C
   [Android Tablet]
   ├─ MainActivity.kt (WebView)
   ├─ UVCAndroid 1.0.10 (USB capture)
   └─ window.AndroidBooth.latestJpeg() [JS bridge]
        ↓ Poll a cada 200ms
   [index.html startUsbBridge()]
   └─ POST /frame com JPEG (blob)
        ↓ 
[Flask VPS — app.py]
├─ /frame endpoint
├─ RVM matting (segmentação humano)
├─ Compose (background + frame + overlay)
└─ Retorna JPEG processado
        ↓
   [WebView preview]
   └─ Atualiza <img src=...>
```

## 2. Problema 1: IFrameCallback Não Entrega Frames

### Observação no código (MainActivity.kt, linhas 447-489)

```kotlin
private fun onFrame(frame: ByteBuffer) {
    val stride = if (previewW > 1280) 12 else 3
    if (skip.incrementAndGet() % stride != 0) {
        frame.position(frame.limit())
        return  // ← Pulsa frames: só processa 1 a cada 3 ou 12
    }
    // ... JPEG encode
    latestJpeg.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
}
```

**Por que falha?**

1. **onFrame() chamado de thread background** — via `setFrameCallback(IFrameCallback)`, não main thread
2. **Câmera EMEET S600L pode não enviar NV21 contínuo** — talvez formato MJPEG nativo
3. **Pulso intencional** (`stride=3/12`) reduz carga, mas não gera frames suficientes
4. **Logs indicam**: `snap ok 6157` (takePicture funciona), mas `onFrame` raramente dispara

**Sintoma real**: Preview fica preto por minutos, ou mostra 1 frame a cada segundo.

---

## 3. Problema 2: takePicture() é Lento (~300ms)

### Observação no código (linhas 340-390)

```kotlin
private val snapshotLoop = object : Runnable {
    override fun run() {
        if (previewReady) grabStillPicture()
        mainHandler.postDelayed(this, 300)  // ← 300ms
    }
}

private fun grabStillPicture() {
    if (snapInFlight) return  // Bloqueia capturas concorrentes
    snapInFlight = true
    helper.takePicture(opts, callback) // Gera JPEG
    // onImageSaved: latestJpeg.set(Base64...)
}
```

**Por quê?**

- `takePicture()` é operação nativa (encoder JPEG na câmera USB ou software)
- Overhead: alocação de arquivo, leitura, encoding
- **300ms loop = ~3 FPS MAX** — muito lento para preview fluido

**Esperado**:
- Preview 20-30 FPS = mínimo 33-50ms por frame
- Câmera USB típica entrega 60 FPS nativa

---

## 4. Problema 3: WebView Fica Preta/Laranja Minutos

### Causas Prováveis (em ordem de probabilidade)

#### A. **Deadlock Main Thread** (Mais provável)

**Sequência:**
1. `MainActivity.onSiteReady()` (linhas 125-136) chama:
   ```kotlin
   web.evaluateJavascript(
       "try{if(window.startUsbBridge){window.startUsbBridge();}'ok'}catch(e){String(e)}",
       { v -> Log.i(TAG, "boot $v") }
   )
   ```
2. Mas USB thread continua chamando `web.evaluateJavascript()` para atualizar `#usbWait`
3. Se JS faz operação pesada (RVM em loop?), bloqueia main thread
4. WebView congelado = tela preta/laranja

**Evidência**: Splash timeout 3.5s + fallback 10s sugerem timing precário.

#### B. **GPU/TextureView Contention**

```kotlin
private fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    if (!previewReady) return
    if (textureGrab.incrementAndGet() % 12 != 0) return
    val bmp: Bitmap = tv.bitmap ?: return@post
    // Captura bitmap do TextureView — operação GPU síncrona
    latestJpeg.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
}
```

- `tv.bitmap` pode bloquear até 50-100ms se GPU está em uso
- Chamado a cada frame (~60 FPS / 12 = 5 FPS), pulsa em main thread

#### C. **Incompatibilidade HTML Antigo**

Se VPS tem versão antiga do `index.html`, pode estar:
- Tentando acessar `#cam` (hardcoded em `startUsbBridge`)
- Esperando callbacks que não existem
- Spinner loop infinito (JS não esconda splash)

---

## 5. Timing Incompatível entre Camadas

| Camada | Frequência | Latência |
|--------|-----------|----------|
| USB (takePicture) | ~300ms | 300ms |
| JS poll (latestJpeg) | 200ms | até +200ms |
| Flask /frame (RVM) | ~200-500ms | 200-500ms |
| **Total E2E** | — | **700-1000ms** |

**Problema**: JS poll a 200ms mas USB só gera frames a 300ms = **67% de frames vazios** (`!b64` na linha 851).

---

## 6. Por Que setPreviewSize é Ignorado

```kotlin
try {
    helper.setPreviewSize(pick)  // Loga "setPreviewSize 1280x720 OK"
    previewW = pick.width
    previewH = pick.height
} catch (err: Exception) {
    Log.e(TAG, "setPreviewSize", err)
}
```

Depois:
```kotlin
val sz = helper.previewSize  // Retorna 3840x2160 (!?)
```

**Provável causa**:
- UVCAndroid 1.0.10 expõe múltiplas resoluções simultâneas
- `setPreviewSize()` aplica apenas à captura de preview (TextureView)
- `takePicture()` ignora, usa resolução nativa da câmera (3840x2160)
- `onFrame()` vê resolução máxima (porque fallback `helper.openCamera()` sem parâmetros)

**Efeito**: Frames grandes = processamento RVM mais lento, memória alta.

---

## 7. Plano Mínimo Estável para Evento

### 7.1 Curto Prazo (Hoje)

**Objetivo**: Reduzir latência, garantir preview em <2s mesmo com USB lento.

#### Mudanças necessárias:

1. **MainActivity.kt: Fixar resolução de captura**
   ```kotlin
   // Linha 249: forçar 640x480 em vez de preferir 1280x720
   val prefer = listOf(
       640 to 480,  // ← Mover para topo
       1280 to 720,
       // ...
   )
   ```
   **Efeito**: `takePicture()` retorna JPEG ~2-3 KB, RVM mais rápido.

2. **MainActivity.kt: Aumentar frequência de captura**
   ```kotlin
   mainHandler.postDelayed(snapshotLoop, 200)  // Reduzir de 300 para 200ms
   ```
   **Efeito**: ~5 FPS → ~30 FPS disponíveis, menos frames vazios.

3. **index.html: Aumentar poll e timeout USB**
   ```javascript
   // Linha 817: reduzir de 120ms para 50ms
   setTimeout(pushFrame, 50);  // Mais agressivo após frame encontrado
   
   // Linha 889: reduzir de 200ms para 150ms
   setTimeout(pushFrame, 150);  // Poll mais rápido
   ```
   **Efeito**: Sync com USB 200ms.

4. **app.py: Desabilitar RVM para testes**
   ```python
   # /frame: se ?debug=1, retornar frame raw sem RVM
   if request.args.get("debug"):
       return Response(data, mimetype="image/jpeg")
   ```
   **Efeito**: Medir latência real (Flask + JSON serialization only).

5. **Checklist VPS**:
   - Deploy `index.html` nova
   - Verificar `/vendors/` e `/static/` servem com cache correto
   - CPU não acima de 60% no RVM (senão aumentar timeout de infer_lock)

#### Testes:

- [ ] Logcat: verificar `snap ok` a cada 200ms
- [ ] Chrome dev tools: `POST /frame` latency em Network tab
- [ ] Tablet: preview atualiza em <3s após iniciar app
- [ ] Captura: foto salva em <5s total

---

### 7.2 Médio Prazo (Se tempo permitir)

1. **MJPEG local fallback** — Se IFrameCallback continua falhando, implementar decodificação MJPEG em Java:
   ```kotlin
   // Em vez de onFrame() com NV21, parse MJPEG stream nativo
   helper.setFrameCallback(IFrameCallback { buf ->
       if (isMjpegMagic(buf)) {
           latestJpeg.set(Base64.encodeToString(buf, Base64.NO_WRAP))
       }
   }, UVCCamera.PIXEL_FORMAT_MJPEG)
   ```

2. **Async image encoding** — Mover encode JPEG de main thread:
   ```kotlin
   private val jpegEncoder = Executors.newSingleThreadExecutor()
   
   jpegEncoder.submit {
       val out = ByteArrayOutputStream()
       yuv.compressToJpeg(Rect(...), 50, out)
       latestJpeg.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
   }
   ```

3. **WebView buffering** — Cache 2-3 frames no JS, descartar stale:
   ```javascript
   const frameBuffer = []
   async function pushFrame() {
       frameBuffer.push(latestB64)
       while (frameBuffer.length > 3) frameBuffer.shift()
   }
   ```

---

## 8. Checklist Tablet (Evento)

- [ ] APK v3.4 instalada, versionCode 7
- [ ] Permissões: Câmera + USB granted
- [ ] "USB Camera Viewer" desinstalado (conflito de handler)
- [ ] EMEET conectada via USB-C, luz verde piscando
- [ ] Wi-Fi tablet conectada, ping VPS < 50ms
- [ ] Abrir APK → Splash desaparece em <5s
- [ ] Aguardar "Aplicando filtro…" (não > 15s)
- [ ] Preview Polaroid atualiza a cada 1-2s (preju se demora >3s)
- [ ] Foto capturada salva em <5s

---

## 9. Próximos Passos para Você

1. **Aplicar mudanças curto prazo** (seções 7.1)
2. **Compilar APK nova** com:
   - `snapshotLoop` 200ms (linha 282)
   - `previewSize` preferência 640x480 (linha 394)
3. **Deploy HTML nova** com:
   - `pushFrame` 50ms após sucesso, 150ms senão (linhas 817, 889)
4. **Testar tablet**: preview e captura em <3s
5. **Se falhar**: coletar logcat completo + Network tab (Chrome F12)

---

## Resumo Diagnóstico

| Problema | Causa | Solução |
|----------|-------|---------|
| Preview preta/laranja | Deadlock main thread ou splash race | Aumentar resolve timing, usar async |
| IFrameCallback não chega | Câmera EMEET não envia NV21 contínuo | Fallback takePicture + aumentar freq |
| takePicture lento (300ms) | Encoder JPEG nativa é lento | Reduzir resolução (640x480) + aumentar freq |
| JS poll 67% frames vazios | Misalign 200ms poll vs 300ms USB | Sync 200ms USB ou 200ms poll |
| setPreviewSize ignorado | UVCAndroid 1.0.10 limita com openCamera default | Forçar resolução antes de openCamera |
| WebView freeze | Trace main thread, GPU contention | Ver logcat + profiler |

**Stack recomendado estável para evento**: 640x480 capture, 5 FPS USB, 150ms JS poll, <200ms RVM (resol baixa), total <2s latência.
