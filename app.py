import os
import re
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
OVERLAY_DIR = ROOT / "assets" / "overlays"
PHOTOS_DIR = ROOT / "photos"
FANTA_POLAROID_DIR = PHOTOS_DIR / "fanta_polaroid"
LAND_W, LAND_H = 720, 405
PORT_W, PORT_H = 480, 600
WIDTH, HEIGHT = LAND_W, LAND_H
COOKIE_SID = "booth_sid"
SESSION_TTL = 15 * 60
ACCESS_KEY = os.environ.get("BOOTH_KEY", "").strip()

PHOTO_NAME = re.compile(r"^foto_\d{8}_\d{6}(_\d+)?(_p)?\.jpg$")
LOGO_PATH = ROOT / "static" / "img" / "logo.png"

app = Flask(__name__)
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.jinja_env.auto_reload = True
SEO_TITLE = "Fanta Halloween | Photobooth do Pânico"
SEO_DESCRIPTION = "A abóbora pediu um gole e o pânico atendeu. Entre na Polaroid da Fanta Halloween, recorte o susto e leve o Ghostface nas redes."
SEO_OG_DESCRIPTION = "A abóbora pediu um gole e o pânico atendeu. Polaroid envelhecida, recorte em preto e branco e um susto laranja pra compartilhar."
SEO_TWITTER_DESCRIPTION = "A abóbora pediu um gole e o pânico atendeu. Tire sua foto na Polaroid da Fanta Halloween."

_lock = threading.Lock()
_sessions = {}
_defaults = {
    "background": "bg_foto.png",
    "frame": "",
}


def public_origin():
    host = (request.headers.get("X-Forwarded-Host") or request.host or "").split(",")[0].strip()
    proto = (request.headers.get("X-Forwarded-Proto") or request.scheme or "http").split(",")[0].strip()
    if host.endswith("seuprojeto.online"):
        proto = "https"
    return f"{proto}://{host}"


@app.context_processor
def inject_seo():
    origin = public_origin()
    return {
        "seo_title": SEO_TITLE,
        "seo_description": SEO_DESCRIPTION,
        "seo_og_description": SEO_OG_DESCRIPTION,
        "seo_twitter_description": SEO_TWITTER_DESCRIPTION,
        "og_image": origin + "/static/img/logo.png",
        "og_url": origin + request.path,
    }


def list_png(folder: Path):
    if not folder.exists():
        return []
    return sorted(p.name for p in folder.glob("*.png"))


def canvas_size(img):
    h, w = img.shape[:2]
    if h >= w:
        return PORT_W, PORT_H
    return LAND_W, LAND_H


def load_bg(name: str, tw=None, th=None):
    tw = LAND_W if tw is None else tw
    th = LAND_H if th is None else th
    path = BG_DIR / name
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        img = np.zeros((th, tw, 3), dtype=np.uint8)
        img[:] = (90, 40, 120)
    return cv2.resize(img, (tw, th))


def load_frame(name: str, tw=None, th=None):
    tw = LAND_W if tw is None else tw
    th = LAND_H if th is None else th
    path = FRAME_DIR / name
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    return cv2.resize(img, (tw, th), interpolation=cv2.INTER_AREA)


def letterbox(img, tw=WIDTH, th=HEIGHT):
    h, w = img.shape[:2]
    scale = min(tw / w, th / h)
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((th, tw, 3), dtype=np.uint8)
    y, x = (th - nh) // 2, (tw - nw) // 2
    canvas[y : y + nh, x : x + nw] = resized
    return canvas


def cover_crop(img, tw, th, zoom_out=1.0):
    if zoom_out > 1.0:
        h0, w0 = img.shape[:2]
        pad_x = int(w0 * (zoom_out - 1.0) / 2)
        pad_y = int(h0 * (zoom_out - 1.0) / 2)
        img = cv2.copyMakeBorder(img, pad_y, pad_y, pad_x, pad_x, cv2.BORDER_REPLICATE)
    h, w = img.shape[:2]
    scale = max(tw / max(1, w), th / max(1, h))
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    x = max(0, (nw - tw) // 2)
    y = max(0, (nh - th) // 2)
    return resized[y : y + th, x : x + tw]


def age_paper(card, pad, inner_w, inner_h):
    h, w = card.shape[:2]
    paper = card.astype(np.int16)
    rng = np.random.default_rng(7)
    paper += rng.integers(-16, 17, paper.shape, dtype=np.int16)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    bottom = np.clip((yy - (pad + inner_h - 8)) / max(1, h - pad - inner_h), 0, 1)
    edge = np.maximum.reduce(
        [
            np.clip(1 - xx / 28, 0, 1),
            np.clip(1 - (w - 1 - xx) / 28, 0, 1),
            np.clip(1 - yy / 22, 0, 1),
            bottom * 0.85,
        ]
    )[:, :, None]
    dirt = np.array([38, 48, 62], dtype=np.float32)
    paper = paper * (1 - edge * 0.22) + dirt * edge * 0.22
    inner = np.ones((h, w), dtype=bool)
    inner[pad : pad + inner_h, pad : pad + inner_w] = False
    out = card.copy()
    out[inner] = np.clip(paper, 0, 255).astype(np.uint8)[inner]
    return out


def make_polaroid(photo_bgr):
    pad = 36
    inner_w = 840
    inner_h = int(inner_w * 5 / 4)
    logo = cv2.imread(str(LOGO_PATH), cv2.IMREAD_UNCHANGED)
    lw = int(inner_w * 0.58)
    lh = 220
    if logo is not None:
        lh = max(1, int(logo.shape[0] * lw / max(1, logo.shape[1])))
    overlap = int(lh * 0.32)
    footer = lh - overlap + pad
    card_w = inner_w + pad * 2
    card_h = pad + inner_h + footer
    card = np.full((card_h, card_w, 3), (210, 223, 230), dtype=np.uint8)
    crop = cover_crop(photo_bgr, inner_w, inner_h)
    card[pad : pad + inner_h, pad : pad + inner_w] = crop
    card = age_paper(card, pad, inner_w, inner_h)
    if logo is not None:
        logo_r = cv2.resize(logo, (lw, lh), interpolation=cv2.INTER_AREA)
        canvas = np.zeros((card_h, card_w, 4), dtype=np.uint8)
        lx = (card_w - lw) // 2
        ly = pad + inner_h - overlap
        ly = max(0, min(card_h - lh, ly))
        canvas[ly : ly + lh, lx : lx + lw] = logo_r
        card = overlay_rgba(card, canvas)
    return card


def crop_if_baked_polaroid(bgr):
    """Tira moldura/logo se o JPEG já for a polaroid do app nativo."""
    h, w = bgr.shape[:2]
    if h < 20 or w < 20:
        return bgr
    if w / float(h) >= 0.74:
        return bgr
    pad = max(8, int(round(w * 36 / 912.0)))
    footer = max(24, int(round(h * 220 / 1306.0)))
    y1, y2 = pad, h - footer
    x1, x2 = pad, w - pad
    if y2 - y1 < 20 or x2 - x1 < 20:
        return bgr
    return bgr[y1:y2, x1:x2]


def polaroid_name(name: str) -> str:
    return name.replace(".jpg", "_p.jpg")


def resolve_photo(name: str):
    for folder in (FANTA_POLAROID_DIR, PHOTOS_DIR):
        path = folder / name
        if path.is_file():
            return path
    return None


def overlay_rgba(base_bgr, overlay_bgra):
    if overlay_bgra is None or overlay_bgra.shape[2] < 4:
        return base_bgr
    alpha = overlay_bgra[:, :, 3:4].astype(np.float32) / 255.0
    color = overlay_bgra[:, :, :3].astype(np.float32)
    out = base_bgr.astype(np.float32) * (1.0 - alpha) + color * alpha
    return out.astype(np.uint8)


def place_overlay(overlay_bgra, tw, th, x_shift=0.06):
    if overlay_bgra is None:
        return None
    canvas = np.zeros((th, tw, 4), dtype=np.uint8)
    ch, cw = overlay_bgra.shape[:2]
    scale = (th * 0.95) / max(1, ch)
    nw, nh = max(1, int(cw * scale)), max(1, int(ch * scale))
    resized = cv2.resize(overlay_bgra, (nw, nh), interpolation=cv2.INTER_AREA)
    x = int((tw - nw) / 2 + tw * x_shift)
    y = th - nh
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(tw, x + nw), min(th, y + nh)
    sx0, sy0 = x0 - x, y0 - y
    canvas[y0:y1, x0:x1] = resized[sy0 : sy0 + (y1 - y0), sx0 : sx0 + (x1 - x0)]
    return canvas


def refine_alpha(pha):
    pha = np.clip(pha.astype(np.float32), 0.0, 1.0)
    pha[pha < 0.05] = 0.0
    u8 = (pha * 255).astype(np.uint8)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    u8 = cv2.morphologyEx(u8, cv2.MORPH_CLOSE, kernel, iterations=1)
    u8 = cv2.GaussianBlur(u8, (5, 5), 0)
    pha = u8.astype(np.float32) / 255.0
    pha = np.clip((pha - 0.08) / 0.84, 0.0, 1.0)
    return pha


def compose_rvm(fgr_rgb, pha, background, frame_rgba, character=None):
    """Usa o primeiro plano já limpo do RVM (sem halo da parede)."""
    pha = refine_alpha(pha)
    if pha.shape[:2] != background.shape[:2]:
        pha = cv2.resize(pha, (background.shape[1], background.shape[0]), interpolation=cv2.INTER_LINEAR)
        fgr_rgb = cv2.resize(fgr_rgb, (background.shape[1], background.shape[0]), interpolation=cv2.INTER_LINEAR)
    scene_bg = background
    if character is not None:
        if character.shape[0] == background.shape[0] and character.shape[1] == background.shape[1]:
            scene_bg = overlay_rgba(scene_bg, character)
        else:
            scene_bg = overlay_rgba(scene_bg, place_overlay(character, background.shape[1], background.shape[0]))
    a = pha[:, :, None]
    fgr_bgr = np.clip(fgr_rgb[..., ::-1], 0.0, 1.0)
    gray = (
        0.114 * fgr_bgr[:, :, 0]
        + 0.587 * fgr_bgr[:, :, 1]
        + 0.299 * fgr_bgr[:, :, 2]
    )
    fgr_bgr = np.repeat(gray[:, :, None], 3, axis=2) * 255.0
    scene = fgr_bgr * a + scene_bg.astype(np.float32) * (1.0 - a)
    scene = np.clip(scene, 0, 255).astype(np.uint8)
    return overlay_rgba(scene, frame_rgba)


class CameraBooth:
    def __init__(self):
        self.rvm = RVMMatting()
        self.infer_lock = threading.RLock()
        self._busy = False
        self._bg_cache = {}
        self._frame_cache = {}
        self._character = cv2.imread(str(OVERLAY_DIR / "personagem.png"), cv2.IMREAD_UNCHANGED)
        self._character_placed = None
        self._scene_cache = {}

    def _assets(self, bg_name, frame_name, tw, th):
        bg_key = (bg_name, tw, th)
        fr_key = (frame_name, tw, th)
        if bg_key not in self._bg_cache:
            self._bg_cache[bg_key] = load_bg(bg_name, tw, th)
        if fr_key not in self._frame_cache:
            self._frame_cache[fr_key] = load_frame(frame_name, tw, th) if frame_name else None
        return self._bg_cache[bg_key], self._frame_cache[fr_key]

    def process(self, frame, rec, bg_name, frame_name):
        tw, th = canvas_size(frame)
        # zoom_out > 1 dá mais campo de visão: a câmera fica perto da pessoa no
        # totem físico, e o crop vertical/retrato deixava a pessoa grande demais.
        frame = cover_crop(frame, tw, th, zoom_out=1.35)
        try:
            with self.infer_lock:
                fgr, pha, rec = self.rvm.matting(frame, rec, downsample=0.28)
        except RuntimeError:
            rec = [None, None, None, None]
            with self.infer_lock:
                fgr, pha, rec = self.rvm.matting(frame, rec, downsample=0.28)
        background, moldura = self._assets(bg_name, frame_name, tw, th)
        h, w = frame.shape[:2]
        if background.shape[1] != w or background.shape[0] != h:
            background = cv2.resize(background, (w, h), interpolation=cv2.INTER_AREA)
        key = (bg_name, w, h)
        if key not in self._scene_cache:
            if self._character_placed is None or self._character_placed.shape[1] != w or self._character_placed.shape[0] != h:
                self._character_placed = place_overlay(self._character, w, h)
            self._scene_cache[key] = overlay_rgba(background, self._character_placed)
        scene_bg = self._scene_cache[key]
        if moldura is not None and (moldura.shape[1] != w or moldura.shape[0] != h):
            moldura = cv2.resize(moldura, (w, h), interpolation=cv2.INTER_AREA)
        composed = compose_rvm(fgr, pha, scene_bg, moldura, None)
        ok_jpg, buf = cv2.imencode(".jpg", composed, [int(cv2.IMWRITE_JPEG_QUALITY), 82])
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
    # Assets estáticos (fontes, imagens de fundo) não mudam entre deploys e são
    # pesados (~1MB no total) — sem cache, o WebView do APK rebaixava tudo a cada
    # reload da página, mesmo na mesma sessão, atrasando o carregamento inicial
    # (onPageFinished nunca disparava a tempo em Wi-Fi mais lenta de evento).
    if request.path.startswith("/static/"):
        resp.headers["Cache-Control"] = "public, max-age=86400"
    else:
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
    if request.endpoint in ("photo_file", "static", "share_page", "upload_polaroid"):
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
        cached = sess.get("last_jpeg")
    if not booth.infer_lock.acquire(blocking=False):
        if cached:
            return Response(cached, mimetype="image/jpeg")
        return jsonify(ok=False), 503
    booth._busy = True
    try:
        composed, jpeg, rec = booth.process(img, rec, bg_name, frame_name)
    except Exception:
        with _lock:
            rec = [None, None, None, None]
            _sessions[g.sid]["rec"] = rec
        composed, jpeg, rec = booth.process(img, rec, bg_name, frame_name)
    finally:
        booth._busy = False
        booth.infer_lock.release()
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


@app.post("/upload_polaroid")
def upload_polaroid():
    uploaded = request.files.get("photo")
    if uploaded is None:
        return jsonify(ok=False, error="Sem arquivo"), 400
    data = uploaded.read()
    if not data or len(data) > 12 * 1024 * 1024:
        return jsonify(ok=False, error="Arquivo inválido"), 400
    requested = (request.form.get("name") or "").strip()
    if requested and PHOTO_NAME.match(requested):
        name = requested
    else:
        stamp = time.strftime("%Y%m%d_%H%M%S")
        name = f"foto_{stamp}.jpg"
        n = 1
        dest = FANTA_POLAROID_DIR / name
        while dest.exists():
            name = f"foto_{stamp}_{n}.jpg"
            dest = FANTA_POLAROID_DIR / name
            n += 1
    FANTA_POLAROID_DIR.mkdir(parents=True, exist_ok=True)
    dest = FANTA_POLAROID_DIR / name
    dest.write_bytes(data)
    page_url = f"{public_origin()}/p/{name}"
    return jsonify(
        ok=True,
        file=name,
        url=url_for("photo_file", name=name),
        share_url=page_url,
    )


@app.post("/capture")
def capture():
    with _lock:
        sess = _sessions.get(g.sid)
        image = None if sess is None else sess.get("last_bgr")
        if image is not None:
            image = image.copy()
    if image is None:
        return jsonify(ok=False, error="Sem imagem da webcam"), 503
    h, w = image.shape[:2]
    if max(h, w) > 900:
        scale = 900 / float(max(h, w))
        image = cv2.resize(image, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    name = time.strftime("foto_%Y%m%d_%H%M%S.jpg")
    cv2.imwrite(str(PHOTOS_DIR / name), image)
    card = make_polaroid(image)
    cv2.imwrite(str(PHOTOS_DIR / polaroid_name(name)), card)
    page_url = f"{public_origin()}/p/{name}"
    return jsonify(
        ok=True,
        file=name,
        url=url_for("photo_file", name=name),
        share_url=page_url,
    )


@app.get("/p/<name>")
def share_page(name):
    if not PHOTO_NAME.match(name):
        return make_response("Foto não encontrada.", 404)
    src_path = resolve_photo(name)
    if src_path is None:
        return make_response("Foto não encontrada.", 404)
    src = cv2.imread(str(src_path))
    if src is None:
        return make_response("Foto não encontrada.", 404)
    from_tablet = src_path.parent.resolve() == FANTA_POLAROID_DIR.resolve()
    if from_tablet:
        card_name = name
    else:
        card = polaroid_name(name)
        card_path = resolve_photo(card)
        if card_path is None:
            cv2.imwrite(str(PHOTOS_DIR / card), make_polaroid(src))
            card_name = card
        else:
            card_name = card_path.name
    return render_template(
        "share.html",
        photo_url=url_for("photo_file", name=name),
        card_url=url_for("photo_file", name=card_name),
    )


@app.get("/photos/<name>")
def photo_file(name):
    if not PHOTO_NAME.match(name):
        return make_response("Arquivo inválido.", 404)
    path = resolve_photo(name)
    if path is None:
        return make_response("Arquivo inválido.", 404)
    if request.args.get("inner") == "1":
        img = cv2.imread(str(path))
        if img is None:
            return make_response("Arquivo inválido.", 404)
        inner = crop_if_baked_polaroid(img)
        ok, buf = cv2.imencode(".jpg", inner, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
        if not ok:
            return make_response("Arquivo inválido.", 404)
        return Response(buf.tobytes(), mimetype="image/jpeg")
    return send_from_directory(path.parent, path.name)


if __name__ == "__main__":
    get_booth()
    try:
        print("Photobooth: http://127.0.0.1:5000")
        print("Túnel: https://fanta-filtro.seuprojeto.online")
        app.run(host="127.0.0.1", port=5000, debug=False, threaded=True)
    finally:
        if booth is not None:
            booth.stop()
