# 🧪 Guia de Teste Rápido

## Pré-requisitos
- [ ] Navegador moderno (Chrome, Edge, Firefox)
- [ ] Webcam conectada
- [ ] Imagem de fundo em `assets/backgrounds/fundo.jpg` (1920x1080 ou proporcional)

## 1️⃣ Setup Inicial

### Opção A: Python (Recomendado)
```bash
cd v2
python serve.py
```

Depois abra: **http://localhost:8000**

### Opção B: VS Code Live Server
1. Clique direito em `index.html`
2. "Open with Live Server"

### Opção C: Sem servidor (menos recomendado)
- Abra `file:///D:/projetos/Human-Segmentation-using-U-NET/v2/index.html` no navegador
- **Nota**: Câmera pode não funcionar

## 2️⃣ Teste da Câmera

1. **Página abre** → Vê "🎃 FANTA Photobooth Halloween"
2. **Clica COMEÇAR**
3. **Permite câmera** → "Allow" quando o navegador pedir
4. **Vê a si mesmo** no canvas com fundo aplicado

### Se não funcionar:
- Abra DevTools: `F12` → **Console**
- Procure por erros em vermelho
- Copie a mensagem de erro

## 3️⃣ Teste do MediaPipe

1. **Status deve dizer**: "MediaPipe OK" (ou "Pronto!")
2. **Fundo deve aparecer** na câmera ao vivo
3. **Pessoa deve estar recortada** contra o fundo

### Se MediaPipe falhar:
- Primeira vez faz download (pode demorar)
- Verifique internet
- Veja Console (F12) para erro

## 4️⃣ Teste da Captura

1. **Clique em 📸 (câmera)**
2. **Contagem**: Vê "3", "2", "1"
3. **Resultado**: Vê a foto com fundo
4. **Botões**: "REFAZER" ou "COMPARTILHAR"

## 5️⃣ Teste do Upload

### IMPORTANTE: Editar antes de testar!

Em `index.html`, procure por:
```javascript
fetch('https://SEU-DOMINIO/api/upload', {
```

Troque `SEU-DOMINIO` por:
- Local: `http://localhost:5000` (seu Flask)
- Produção: `https://seu-dominio.com.br`

### Depois teste:
1. Tire uma foto
2. Clique "COMPARTILHAR"
3. **Esperado**: QR Code aparece ou link é mostrado
4. **Erro?**: Veja Console (F12) → Network

## 6️⃣ Performance

Abra DevTools: `F12` → **Performance**

1. Clique ⏹️ (Record)
2. Tire uma foto
3. Clique ⏹️ (Stop)
4. Veja o gráfico

**Esperado**:
- ✅ 60 FPS durante câmera
- ✅ Sem travos ao capturar
- ✅ Upload < 2 segundos

## Checklist de Validação

- [ ] Câmera funciona
- [ ] MediaPipe carrega
- [ ] Fundo aparece
- [ ] Pessoa é recortada
- [ ] Captura funciona
- [ ] Upload envia corretamente
- [ ] Performance é suave (60 FPS)
- [ ] Responsive (testa em celular se possível)

## Problemas Comuns

| Problema | Solução |
|----------|---------|
| "Câmera não tem permissão" | Deu permissão errada. Recarregue página. |
| "MediaPipe não carrega" | Internet OK? Espere 10s (primeiro download). |
| "Fundo fica preto" | `fundo.jpg` não existe ou não é acessível. |
| "Upload falha" | URL errada em `fetch('https://SEU-DOMINIO...')` |
| "Câmera fica lenta" | Reduz resolução em `config.js` |

## Próximos Passos Após Validação

1. ✅ Tudo funciona? Integra com VPS real
2. ✅ Ótimo? Adiciona moldura/overlay
3. ✅ Perfeito? Refactora pra APK com Capacitor

---

**Dúvida?** Abra DevTools (F12) → Console e copia o erro completo.
