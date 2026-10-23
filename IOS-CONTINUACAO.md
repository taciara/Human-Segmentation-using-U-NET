# iPad / iOS — Continuação do desenvolvimento

## Contexto

Estamos desenvolvendo o **Fanta Halloween Photobooth** — app de fotocabine para evento/show.

Branch atual: `ios`

## O que já existe

- `v2/index-segmento.html` — versão web com segmentação em tempo real (TensorFlow.js + Body-Pix)
  - Remove fundo ao vivo via câmera
  - Substitui por gradiente Fanta laranja
  - Contagem 3, 2, 1 e captura foto
- `v2/index-simples.html` — versão sem segmentação (fallback offline)
- `v2/serve.py` — servidor local Python (porta 8000)
- `android/` — versão Android já funcionando (UVCAndroid 1.0.13, câmera USB)

## Decisão tomada

**PWA no iPad Safari** — sem custo, sem App Store, sem assinar.

Para o evento: notebook serve o app na rede WiFi local → iPad abre pelo IP no Safari.

## O que falta fazer (próxima sessão)

1. **Adaptar `index-segmento.html` para iOS Safari**
   - Verificar `playsInline` na tag `<video>`
   - Câmera frontal no iPad (facingMode)
   - Layout landscape/portrait para iPad
   - Botão "Adicionar à tela inicial" (manifesto PWA)

2. **Criar `manifest.json`** para PWA real
   - Ícone, nome, orientação, fullscreen

3. **Testar na rede local**
   - Notebook: `python serve.py` na pasta `v2/`
   - iPad Safari: `http://IP-DO-NOTEBOOK:8000/index-segmento.html`
   - iOS Safari exige HTTPS para câmera em rede local — pode precisar de certificado self-signed ou usar ngrok

4. **Ajustar performance para iPad**
   - Testar Body-Pix no Safari (WebGL)
   - Ajustar resolução se necessário

## Nota importante — câmera em rede local no iOS

iOS Safari **bloqueia câmera em HTTP** (não-localhost). Para testar na rede:

**Opção A — ngrok (mais fácil):**
```bash
ngrok http 8000
# Abre a URL https gerada no iPad
```

**Opção B — certificado self-signed:**
```bash
# Gerar certificado
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 365 -nodes
# Servir com HTTPS
python -c "
import ssl, http.server
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('cert.pem', 'key.pem')
server = http.server.HTTPServer(('0.0.0.0', 8443), http.server.SimpleHTTPRequestHandler)
server.socket = ctx.wrap_socket(server.socket)
server.serve_forever()
"
# iPad acessa: https://IP-DO-MAC:8443/index-segmento.html
# (aceitar aviso de certificado no iPad)
```

**Opção C — localhost via cabo USB (melhor para dev):**
- Conectar iPad no Mac via cabo
- No terminal do Mac: `iproxy 8000 8000` (via libimobiledevice)
- iPad acessa: `http://localhost:8000` pelo Safari → câmera funciona!

## Estrutura de arquivos relevantes

```
v2/
├── index-segmento.html   ← arquivo principal a adaptar
├── index-simples.html    ← fallback sem segmentação
├── serve.py              ← servidor local
assets/
├── backgrounds/          ← imagens de fundo
├── frames/               ← molduras
└── overlays/             ← personagem Ghostface
```

## Como retomar

Abra esta pasta no Mac, abra o Claude Code e diga:

> "Continuar desenvolvimento iOS do Fanta Photobooth. Ver IOS-CONTINUACAO.md"
