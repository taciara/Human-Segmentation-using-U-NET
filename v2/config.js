// ⚙️ CONFIGURAÇÃO V2

export const config = {
    // URL da sua VPS para upload
    API_URL: 'https://SEU-DOMINIO/api/upload',

    // Resolução da câmera (ideal)
    CAMERA_WIDTH: 1920,
    CAMERA_HEIGHT: 1080,

    // Canvas resolution (pode ser reduzido para performance)
    CANVAS_WIDTH: 1920,
    CANVAS_HEIGHT: 1080,

    // Qualidade JPEG (0-1)
    JPEG_QUALITY: 0.95,

    // Imagem de fundo
    BACKGROUND_IMAGE: 'assets/backgrounds/fundo.jpg',

    // MediaPipe config
    MEDIAPIPE: {
        MODEL_URL: 'https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float32/latest/selfie_segmenter.tflite',
        WASM_URL: 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm'
    }
};

// ✏️ COMO USAR:
//
// 1. Troque 'SEU-DOMINIO' pela sua URL real:
//    API_URL: 'https://fanta-filtro.seuprojeto.online/api/upload'
//
// 2. Se quiser reduzir o processamento pra mais performance:
//    CANVAS_WIDTH: 1280
//    CANVAS_HEIGHT: 720
//
// 3. A imagem de fundo deve estar em:
//    v2/assets/backgrounds/fundo.jpg
//
