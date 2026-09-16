# Deploy Completo - Fanta Photobooth v3.5

## ✅ O que foi feito:

### 1. **Android APK (v3.5 debug)**
- ✅ Compilado com sucesso
- ✅ Instalado na tablet
- **Mudanças**:
  - Frequência USB: 300ms → **200ms** (mais frames)
  - Resolução: 1280×720 preferível → **640×480** preferível (JPEG menor, RVM rápido)
  - Timeout: agora mais agressivo

### 2. **HTML/JavaScript (index.html)**
- ✅ Modificado
- **Mudanças**:
  - Poll timeout após sucesso: 120ms → **50ms** (mais rápido)
  - Poll timeout senão: 200ms → **150ms** (sync com USB)

### 3. **Python Backend (app.py)**
- ✅ Sem mudanças necessárias (Flask RVM está bem)

---

## 📝 Passos para Deploy na VPS

### Opção A: Via SSH (Recomendado)

```bash
# No seu PC/Mac:
scp DEPLOY_SCRIPT.sh root@72.62.9.136:/tmp/
ssh root@72.62.9.136 "bash /tmp/DEPLOY_SCRIPT.sh"
```

**Se SSH não funcionar**, tente:
```bash
# Via chave SSH específica
ssh -i ~/.ssh/id_ed25519 root@72.62.9.136 "bash /tmp/DEPLOY_SCRIPT.sh"

# Ou forçar password interativa
ssh -o PasswordAuthentication=yes root@72.62.9.136
```

### Opção B: Manual no VPS

**Se tem acesso direto ao servidor:**

```bash
# 1. Entrar no servidor
ssh root@72.62.9.136

# 2. Ir para diretório do projeto
cd /root/fanta-filtro  # (ou aonde estiver)

# 3. Puxar mudanças do git
git pull origin main

# 4. Fazer backup da HTML atual
cp templates/index.html templates/index.html.bak.$(date +%s)

# 5. Reconstruir e reiniciar Docker
docker-compose -f docker-compose.filtro.yml down
docker-compose -f docker-compose.filtro.yml build --no-cache
docker-compose -f docker-compose.filtro.yml up -d

# 6. Verificar se está rodando
docker ps | grep filtro_fanta_web
docker logs filtro_fanta_web | tail -20
```

### Opção C: Via Interface de Controle (EasyPanel/Traefik)

Se a VPS tem painel de controle:
1. Acesse o painel (ex: 72.62.9.136:5000 ou domínio)
2. Encontre o container `filtro_fanta_web` ou `filtro-fanta`
3. Redeploye ou reinicie

---

## 🧪 Testes na Tablet

### Após instalar o novo APK:

1. **Abrir app Filtro Fanta**
   - Splash desaparece em <5s ✓
   - Message: "Aguardando câmera USB…" ou "Aplicando filtro…"

2. **Conectar USB EMEET**
   - Diálogo USB: permitir acesso → escolher Filtro Fanta → Sempre
   - Aguardar "Câmera conectada, aplicando filtro…"

3. **Verificar Preview**
   - ⏱️ **Esperado**: Preview atualiza a cada 1-2s
   - ❌ **Problema**: Ainda preta/laranja após 10s → coletar logcat

4. **Capturar Foto**
   - Clicar botão "CAPTURE"
   - Aguardar Polaroid aparecer
   - ⏱️ **Esperado**: <5s total
   - ❌ **Problema**: Spinner infinito → verificar Flask

---

## 📊 Verificação de Performance

### No tablet (via ADB):

```bash
# Logcat em tempo real
adb logcat | grep "FiltroFanta"

# Procurar por:
# ✅ "snap ok" a cada 200ms (novo)
# ✅ "site ready" logo após splash
# ✅ "camera open 640x480" (novo, em vez de 3840x2160)
# ❌ "USB cancel" ou "surface" erros

# Captura de 10 segundos de log
adb logcat | grep "FiltroFanta" | head -50 > logcat_fanta.txt
```

### Na VPS:

```bash
# Logs do Flask
docker logs filtro_fanta_web | tail -50

# Procurar por:
# - Requests POST /frame
# - Tempo de processamento RVM
# - Erros de conexão

# Teste de latência
time curl -X POST http://localhost:5000/frame -d @test.jpg -H "Content-Type: image/jpeg"
```

---

## 🔍 Se ainda não funcionar:

### Checklist Rápido:

- [ ] APK v3.5 está instalada? `adb shell pm list packages | grep filtrofanta`
- [ ] Tablet Wi-Fi conectada e ping < 50ms?
- [ ] HTML nova está no servidor? `curl https://fanta-filtro.seuprojeto.online/ | grep "pushFrame"`
- [ ] Permissões USB dadas? `adb shell dumpsys usb`
- [ ] EMEET desinstalado USB Camera Viewer?

### Debug Avançado:

1. **Deadlock WebView**:
   ```bash
   # Ver threads bloqueadas
   adb shell "dumpsys activity top | grep -A 20 MainActivity"
   ```

2. **GPU contention**:
   ```bash
   # Ver se TextureView está causando problema
   adb logcat | grep "tex"
   ```

3. **Flask lento**:
   ```bash
   # Testar RVM sem WebView
   # Fazer POST /frame direto com imagem estática
   curl -X POST http://localhost:5000/frame --data-binary "@test.jpg" \
       -H "Content-Type: image/jpeg" -o out.jpg -w "\nLatency: %{time_total}s\n"
   ```

---

## 📋 Rollback (Se der problema)

### Restaurar versão anterior:

```bash
# Na VPS
cd /root/fanta-filtro
cp templates/index.html.bak.TIMESTAMP templates/index.html
docker-compose -f docker-compose.filtro.yml restart
```

### Reinstalar APK anterior:

```bash
# No PC
# Procurar APK v3.4 ou v7
adb install -r FiltroFanta.apk  # ou caminho do antigo
```

---

## ✉️ Próximas Ações

1. **Fazer deploy** seguindo uma das opções acima
2. **Testar na tablet** com USB conectada
3. **Verificar logcat** e Flask logs
4. **Me informar resultados** com:
   - [ ] Preview atualiza em <3s?
   - [ ] Captura em <5s?
   - [ ] Logcat (últimos 50 linhas com "FiltroFanta")
   - [ ] HTML nova está rodando? (`curl` + buscar "pushFrame, 50")

---

**Estimativa**: Deploy + teste = 30 minutos

**Data**: 2026-09-16
