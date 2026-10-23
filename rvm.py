"""Robust Video Matting (PeterL1n) — recorte com alpha e primeiro plano limpo."""
from __future__ import annotations

import torch
import numpy as np

DOWNSAMPLE = 0.28
torch.set_num_threads(1)
torch.set_num_interop_threads(1)


def load_rvm():
    model = torch.hub.load(
        "PeterL1n/RobustVideoMatting",
        "mobilenetv3",
        pretrained=True,
        trust_repo=True,
    )
    model.eval()
    return model


class RVMMatting:
    def __init__(self):
        self.device = torch.device("cpu")
        self.model = load_rvm().to(self.device)

    def reset(self):
        return [None, None, None, None]

    @torch.inference_mode()
    def matting(self, frame_bgr: np.ndarray, rec=None, downsample=None):
        if rec is None:
            rec = [None, None, None, None]
        if downsample is None:
            downsample = DOWNSAMPLE
        rgb = np.ascontiguousarray(frame_bgr[:, :, ::-1])
        src = torch.from_numpy(rgb).float().permute(2, 0, 1).unsqueeze(0) / 255.0
        src = src.to(self.device)
        fgr, pha, *rec = self.model(src, *rec, float(downsample))
        fgr_np = fgr[0].permute(1, 2, 0).cpu().numpy()
        pha_np = pha[0, 0].cpu().numpy()
        return fgr_np, pha_np, rec
