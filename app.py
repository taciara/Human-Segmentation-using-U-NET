import threading
import time
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from flask import Flask, Response, jsonify, render_template, request, send_from_directory, url_for
from mediapipe.tasks.python.vision import ImageSegmenter, ImageSegmenterOptions, RunningMode

ROOT = Path(__file__).resolve().parent
BG_DIR = ROOT / "assets" / "backgrounds"
FRAME_DIR = ROOT / "assets" / "frames"
PHOTOS_DIR = ROOT / "photos"
MODEL_PATH = ROOT / "models" / "selfie_segmenter.tflite"
WIDTH, HEIGHT = 1280, 720

PHOTOS_DIR.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)

_lock = threading.Lock()
_state = {
    "background": "estudio.png",
    "frame": "fanta_halloween.png",
    "latest_jpeg": None,
    "latest_bgr": None,
    "incoming": None,
    "last_photo": None,
}


def list_png(folder: Path):
    if not folder.exists():
        return []
    return sorted(p.name for p in folder.glob("*.png"))


def load_bg(name: str):
    path = BG_DIR / name
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        img = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
        img[:] = (90, 40, 120)
    return cv2.resize(img, (WIDTH, HEIGHT))


def load_frame(name: str):
    path = FRAME_DIR / name
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    return cv2.resize(img, (WIDTH, HEIGHT), interpolation=cv2.INTER_AREA)


def overlay_rgba(base_bgr, overlay_bgra):
    if overlay_bgra is None or overlay_bgra.shape[2] < 4:
        return base_bgr
    alpha = overlay_bgra[:, :, 3:4].astype(np.float32) / 255.0
    color = overlay_bgra[:, :, :3].astype(np.float32)
    out = base_bgr.astype(np.float32) * (1.0 - alpha) + color * alpha
    return out.astype(np.uint8)


def refine_person_mask(person):
    """Corta um pouco do halo, mas deixa a borda suave (não serrilhada)."""
    person = np.clip(person.astype(np.float32), 0.0, 1.0)
    person = np.clip((person - 0.12) / 0.78, 0.0, 1.0)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    hard = (person > 0.42).astype(np.uint8)
    hard = cv2.erode(hard, kernel, iterations=1)
    soft = cv2.GaussianBlur(hard.astype(np.float32), (17, 17), 0)
    return np.clip(soft, 0.0, 1.0)


def defringe(bgr, mask):
    """Tira a franja clara nas bordas (resto da parede/luz do quarto)."""
    m = np.squeeze(mask).astype(np.float32)
    edge = ((m > 0.04) & (m < 0.88)).astype(np.float32)[:, :, None]
    if float(edge.max()) == 0:
        return bgr
    inward = cv2.erode((m > 0.75).astype(np.uint8), np.ones((7, 7), np.uint8))
    fill = cv2.GaussianBlur(bgr, (9, 9), 0).astype(np.float32)
    interior = cv2.bitwise_and(bgr, bgr, mask=inward)
    interior_blur = cv2.GaussianBlur(interior, (9, 9), 0).astype(np.float32)
    use_interior = (inward > 0)[:, :, None].astype(np.float32)
    replacement = interior_blur * use_interior + fill * (1.0 - use_interior)
    out = bgr.astype(np.float32) * (1.0 - 0.38 * edge) + replacement * (0.38 * edge)
    return np.clip(out, 0, 255).astype(np.uint8)


def person_mask_from_category(category, size):
    mask = np.squeeze(category).astype(np.float32)
    if mask.max() > 1.0:
        mask = mask / 255.0
    # 0 = pessoa, 1 = fundo
    person = 1.0 - np.clip(mask, 0.0, 1.0)
    if person.shape[0] != size[1] or person.shape[1] != size[0]:
        person = cv2.resize(person, size, interpolation=cv2.INTER_LINEAR)
    return refine_person_mask(person)


def compose(person_bgr, mask, background, frame_rgba):
    m = np.squeeze(mask).astype(np.float32)
    if m.ndim != 2:
        m = m.reshape(person_bgr.shape[0], person_bgr.shape[1])
    person_bgr = defringe(person_bgr, m)
    alpha = np.power(np.clip(m, 0.0, 1.0), 1.08)[:, :, None]
    scene = background.astype(np.float32) * (1.0 - alpha) + person_bgr.astype(np.float32) * alpha
    scene = np.clip(scene, 0, 255).astype(np.uint8)
    return overlay_rgba(scene, frame_rgba)


class CameraBooth:
    def __init__(self):
        options = ImageSegmenterOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(MODEL_PATH)),
            running_mode=RunningMode.VIDEO,
            output_category_mask=True,
            output_confidence_masks=False,
        )
        self.segmenter = ImageSegmenter.create_from_options(options)
        self._ts = 0
        self._bg_cache = {}
        self._frame_cache = {}
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _assets(self, bg_name, frame_name):
        if bg_name not in self._bg_cache:
            self._bg_cache[bg_name] = load_bg(bg_name)
        if frame_name not in self._frame_cache:
            self._frame_cache[frame_name] = load_frame(frame_name)
        return self._bg_cache[bg_name], self._frame_cache[frame_name]

    def _loop(self):
        while self.running:
            with _lock:
                frame = _state.get("incoming")
                _state["incoming"] = None
            if frame is None:
                time.sleep(0.02)
                continue

            frame = cv2.resize(frame, (WIDTH, HEIGHT))
            rgb = np.ascontiguousarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            self._ts += 33
            try:
                result = self.segmenter.segment_for_video(mp_image, self._ts)
                category = np.copy(result.category_mask.numpy_view())
                mask = person_mask_from_category(category, (WIDTH, HEIGHT))
            except Exception:
                time.sleep(0.02)
                continue

            with _lock:
                bg_name = _state["background"]
                frame_name = _state["frame"]
            background, moldura = self._assets(bg_name, frame_name)
            composed = compose(frame, mask, background, moldura)

            ok_jpg, buf = cv2.imencode(".jpg", composed, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
            if ok_jpg:
                with _lock:
                    _state["latest_jpeg"] = buf.tobytes()
                    _state["latest_bgr"] = composed

    def stop(self):
        self.running = False
        self.segmenter.close()


booth = None


def get_booth():
    global booth
    if booth is None:
        booth = CameraBooth()
    return booth


def frame_labels():
    return {
        "dourada.png": "Dourada",
        "rosa.png": "Rosa",
        "preta.png": "Preta",
        "fanta_halloween.png": "Fanta Halloween",
        "Fanta_panic.png": "Fanta Panic",
    }


def background_labels():
    return {
        "estudio.png": "Estudio",
        "praia.png": "Praia",
        "noite.png": "Noite",
        "panic.png": "Pânico",
    }


@app.before_request
def _boot_camera():
    get_booth()


@app.route("/")
def home():
    backgrounds = list_png(BG_DIR)
    frames = list_png(FRAME_DIR)
    return render_template(
        "index.html",
        backgrounds=backgrounds,
        frames=frames,
        background_labels=background_labels(),
        frame_labels=frame_labels(),
        current_bg=_state["background"],
        current_frame=_state["frame"],
    )


@app.route("/video")
def video():
    def gen():
        while True:
            with _lock:
                payload = _state.get("latest_jpeg")
            if payload is None:
                time.sleep(0.02)
                continue
            yield (
                b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + payload + b"\r\n"
            )
            time.sleep(0.03)

    return Response(gen(), mimetype="multipart/x-mixed-replace; boundary=frame")


@app.post("/frame")
def upload_frame():
    data = request.get_data()
    if not data:
        return jsonify(ok=False), 400
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return jsonify(ok=False), 400
    with _lock:
        _state["incoming"] = img
    return jsonify(ok=True)


@app.post("/config")
def config():
    data = request.get_json(force=True, silent=True) or {}
    with _lock:
        if data.get("background") in list_png(BG_DIR):
            _state["background"] = data["background"]
        if data.get("frame") in list_png(FRAME_DIR):
            _state["frame"] = data["frame"]
        return jsonify(background=_state["background"], frame=_state["frame"])


@app.post("/capture")
def capture():
    with _lock:
        image = _state.get("latest_bgr")
        if image is None:
            return jsonify(ok=False, error="Sem imagem da webcam"), 503
        name = time.strftime("foto_%Y%m%d_%H%M%S.jpg")
        path = PHOTOS_DIR / name
        cv2.imwrite(str(path), image)
        _state["last_photo"] = name
    return jsonify(ok=True, file=name, url=url_for("photo_file", name=name))


@app.get("/photos/<name>")
def photo_file(name):
    return send_from_directory(PHOTOS_DIR, name)


if __name__ == "__main__":
    get_booth()
    try:
        print("Photobooth: http://127.0.0.1:5000")
        print("Túnel: https://filtro.seuprojeto.online")
        app.run(host="127.0.0.1", port=5000, debug=False, threaded=True)
    finally:
        if booth is not None:
            booth.stop()
