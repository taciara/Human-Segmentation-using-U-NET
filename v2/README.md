# Fanta Photobooth V2 - MediaPipe Web

🚀 **Nova arquitetura**: Processamento 100% no navegador, sem servidor pesado!

## Arquitetura

```
CÂMERA
  ↓
MediaPipe Selfie Segmentation (no browser!)
  ↓
Máscara de pessoa
  ↓
Canvas: Fundo + Pessoa + Moldura
  ↓
FOTO FINAL (JPEG)
  ↓
Upload para VPS (só a foto)
  ↓
VPS retorna URL/QR
```

## Configuração

### 1. URL da VPS
Edite `index.html` e procure por:
```javascript
fetch('https://SEU-DOMINIO/api/upload', {
```

Troque `SEU-DOMINIO` pela sua URL real (ex: `https://fanta-filtro.seuprojeto.online`)

### 2. Imagem de fundo
Coloque a imagem em:
```
v2/assets/backgrounds/fundo.jpg
```

A imagem será ajustada automaticamente pra 1920x1080.

### 3. Testar localmente

```bash
# Python 3
python -m http.server 8000

# Depois acesse: http://localhost:8000/v2/
```

Ou com Live Server (VS Code):
- Clique direito → "Open with Live Server"

## Fluxo de uso

1. **Tela inicial** → Botão "COMEÇAR"
2. **Câmera ativa** → MediaPipe processa em tempo real
3. **Contagem** → 3, 2, 1... FOTO!
4. **Resultado** → Botões: REFAZER ou COMPARTILHAR
5. **Upload** → Envia para sua VPS
6. **QR Code** → Gerado pela VPS (como hoje funciona)

## Performance

- ⚡ **Instantâneo** (sem delay de rede)
- 📱 **Funciona offline** (câmera + MediaPipe rodam localmente)
- 🎨 **Suave** (60 FPS em navegadores modernos)
- 📤 **Upload rápido** (só a foto final, não frames)

## O que foi removido

❌ Python (RVM no servidor)  
❌ Delay de rede  
❌ Processamento no servidor  
❌ API complexa  

## O que vai vir depois

✅ Moldura/overlay customizada  
✅ Filtros adicionais (Face Mesh, Pose)  
✅ Efeitos em tempo real  
✅ Versão APK com Capacitor  

## Erros comuns

**"Erro ao acessar câmera"**
- Certifique-se que está em HTTPS ou localhost
- Dê permissão ao navegador

**"MediaPipe não carrega"**
- Verifique conexão com internet (primeira vez faz download)
- Abra DevTools (F12) → Console para ver erro exato

**"Foto fica toda preta"**
- Imagem de fundo não carregou (verifique `assets/backgrounds/fundo.jpg`)
- Tente colocar uma imagem PNG simples primeiro

## Próximos passos

1. Teste com a imagem de fundo real
2. Valide o upload para sua VPS
3. Verifique se o QR Code é gerado corretamente
4. Depois refatoramos pra APK com Capacitor

---

**Status**: V2 em desenvolvimento 🚧
