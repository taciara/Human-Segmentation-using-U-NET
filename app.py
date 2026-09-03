import os
import secrets
import threading
import time
from pathlib import Path

import cv2
import numpy as np
from flask import Flask, Response, g, jsonify, make_response, render_template, request, send_from_directory, url_for

from rvm import RVMMatting

ROOT = Path(__file__).resolve().parent
BG_DIR = ROOT / "assets" / "backgrounds"
FRAME_DIR = ROOT / "assets" / "frames"
PHOTOS_DIR = ROOT / "photos"
WIDTH, HEIGHT = 1280, 720
COOKIE_SID = "booth_sid"
SESSION_TTL = 15 * 60
ACCESS_KEY = os.environ.get("BOOTH_KEY", "").strip()

PHOTOS_DIR.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)

_lock = threading.Lock()
_sessions = {}
_defaults = {
    "background": "estudio.png",
    "frame": "fanta_halloween.png",
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


def letterbox(img, tw=1280, th=720):
    h, w = img.shape[:2]
    scale = min(tw / w, th / h)
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((th, tw, 3), dtype=np.uint8)
    y, x = (th - nh) // 2, (tw - nw) // 2
    canvas[y : y + nh, x : x + nw] = resized
    return canvas


def overlay_rgba(base_bgr, overlay_bgra):
    if overlay_bgra is None or overlay_bgra.shape[2] < 4:
        return base_bgr
    alpha = overlay_bgra[:, :, 3:4].astype(np.float32) / 255.0
    color = overlay_bgra[:, :, :3].astype(np.float32)
    out = base_bgr.astype(np.float32) * (1.0 - alpha) + color * alpha
    return out.astype(np.uint8)


def compose_rvm(fgr_rgb, pha, background, frame_rgba):
    """Usa o primeiro plano já limpo do RVM (sem halo da parede)."""
    pha = np.clip(pha.astype(np.float32), 0.0, 1.0)
    if pha.shape[:2] != background.shape[:2]:
        pha = cv2.resize(pha, (background.shape[1], background.shape[0]), interpolation=cv2.INTER_LINEAR)
        fgr_rgb = cv2.resize(fgr_rgb, (background.shape[1], background.shape[0]), interpolation=cv2.INTER_LINEAR)
    a = pha[:, :, None]
    fgr_bgr = np.clip(fgr_rgb[..., ::-1], 0.0, 1.0)
    gray = (
        0.114 * fgr_bgr[:, :, 0]
        + 0.587 * fgr_bgr[:, :, 1]
        + 0.299 * fgr_bgr[:, :, 2]
    )
    fgr_bgr = np.repeat(gray[:, :, None], 3, axis=2) * 255.0
    scene = fgr_bgr * a + background.astype(np.float32) * (1.0 - a)
    scene = np.clip(scene, 0, 255).astype(np.uint8)
    return overlay_rgba(scene, frame_rgba)


class CameraBooth:
    def __init__(self):
        self.rvm = RVMMatting()
        self.infer_lock = threading.Lock()
        self._bg_cache = {}
        self._frame_cache = {}

    def _assets(self, bg_name, frame_name):
        if bg_name not in self._bg_cache:
            self._bg_cache[bg_name] = load_bg(bg_name)
        if frame_name not in self._frame_cache:
            self._frame_cache[frame_name] = load_frame(frame_name)
        return self._bg_cache[bg_name], self._frame_cache[frame_name]

    def process(self, frame, rec, bg_name, frame_name):
        frame = letterbox(frame, 1280, 720)
        try:
            with self.infer_lock:
                fgr, pha, rec = self.rvm.matting(frame, rec, downsample=0.25)
        except RuntimeError:
            rec = [None, None, None, None]
            with self.infer_lock:
                fgr, pha, rec = self.rvm.matting(frame, rec, downsample=0.25)
        background, moldura = self._assets(bg_name, frame_name)
        h, w = frame.shape[:2]
        if background.shape[1] != w or background.shape[0] != h:
            background = cv2.resize(background, (w, h), interpolation=cv2.INTER_AREA)
        if moldura is not None and (moldura.shape[1] != w or moldura.shape[0] != h):
            moldura = cv2.resize(moldura, (w, h), interpolation=cv2.INTER_AREA)
        composed = compose_rvm(fgr, pha, background, moldura)
        ok_jpg, buf = cv2.imencode(".jpg", composed, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
        jpeg = buf.tobytes() if ok_jpg else None
        return composed, jpeg, rec

    def stop(self):
        pass


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


def _purge_sessions():
    now = time.time()
    dead = [sid for sid, s in _sessions.items() if now - s["seen"] > SESSION_TTL]
    for sid in dead:
        _sessions.pop(sid, None)


def _new_session():
    return {
        "rec": [None, None, None, None],
        "background": _defaults["background"],
        "frame": _defaults["frame"],
        "last_jpeg": None,
        "last_bgr": None,
        "seen": time.time(),
    }


def _bind_session():
    _purge_sessions()
    sid = request.cookies.get(COOKIE_SID)
    with _lock:
        if not sid or sid not in _sessions:
            sid = secrets.token_urlsafe(24)
            _sessions[sid] = _new_session()
        else:
            _sessions[sid]["seen"] = time.time()
        g.sid = sid
        g.session_is_new = request.cookies.get(COOKIE_SID) != sid
        return _sessions[sid]


def _authorized():
    if not ACCESS_KEY:
        return True
    if request.cookies.get("booth_ok") == ACCESS_KEY:
        return True
    return request.args.get("k") == ACCESS_KEY or request.headers.get("X-Booth-Key") == ACCESS_KEY


@app.after_request
def _privacy_headers(resp):
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, private, max-age=0"
    resp.headers["Expires"] = "0"
    resp.headers["Pragma"] = "no-cache"
    sid = getattr(g, "sid", None)
    if sid:
        secure = request.is_secure or request.headers.get("X-Forwarded-Proto") == "https"
        resp.set_cookie(
            COOKIE_SID,
            sid,
            httponly=True,
            samesite="Lax",
            secure=secure,
            max_age=SESSION_TTL,
        )
        if ACCESS_KEY and _authorized():
            resp.set_cookie(
                "booth_ok",
                ACCESS_KEY,
                httponly=True,
                samesite="Lax",
                secure=secure,
                max_age=SESSION_TTL,
            )
    return resp


@app.before_request
def _gate():
    if request.endpoint in ("photo_file", "static"):
        return None
    if not _authorized():
        return make_response("Link privado. Peça o endereço completo com chave de acesso.", 401)
    _bind_session()
    return None


@app.route("/")
def home():
    sess = _sessions[g.sid]
    return render_template(
        "index.html",
        backgrounds=list_png(BG_DIR),
        frames=list_png(FRAME_DIR),
        background_labels=background_labels(),
        frame_labels=frame_labels(),
        current_bg=sess["background"],
        current_frame=sess["frame"],
    )


@app.route("/video")
def video():
    return make_response("Stream compartilhado desativado por privacidade.", 404)


@app.post("/frame")
def upload_frame():
    data = request.get_data()
    if not data:
        return jsonify(ok=False), 400
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return jsonify(ok=False), 400
    booth = get_booth()
    with _lock:
        sess = _sessions[g.sid]
        rec = sess["rec"]
        bg_name = sess["background"]
        frame_name = sess["frame"]
    try:
        composed, jpeg, rec = booth.process(img, rec, bg_name, frame_name)
    except Exception:
        with _lock:
            rec = [None, None, None, None]
            _sessions[g.sid]["rec"] = rec
        composed, jpeg, rec = booth.process(img, rec, bg_name, frame_name)
    if jpeg is None:
        return jsonify(ok=False), 500
    with _lock:
        sess = _sessions[g.sid]
        sess["rec"] = rec
        sess["last_jpeg"] = jpeg
        sess["last_bgr"] = composed
        sess["seen"] = time.time()
    return Response(jpeg, mimetype="image/jpeg")


@app.post("/config")
def config():
    data = request.get_json(force=True, silent=True) or {}
    with _lock:
        sess = _sessions[g.sid]
        if data.get("background") in list_png(BG_DIR):
            sess["background"] = data["background"]
        if data.get("frame") in list_png(FRAME_DIR):
            sess["frame"] = data["frame"]
        return jsonify(background=sess["background"], frame=sess["frame"])


@app.post("/capture")
def capture():
    with _lock:
        sess = _sessions.get(g.sid)
        image = None if sess is None else sess.get("last_bgr")
        if image is None:
            return jsonify(ok=False, error="Sem imagem da webcam"), 503
        name = time.strftime("foto_%Y%m%d_%H%M%S.jpg")
        cv2.imwrite(str(PHOTOS_DIR / name), image)
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
