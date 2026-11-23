"""
把訓練好的鬼策略匯出成 ONNX，並比對 PyTorch 和 ONNX Runtime 的輸出（差異要在 1e-4 以內）。

  python -m ai.export.onnx_export --run ghost_v1 [--ckpt ckpt_00100.pt] [--out ai/models/ghost.onnx]

輸入：obs[1, OBS_DIM] float32、h[1, HID] float32、mask[1, N_ACTIONS] bool
輸出：logits[1, N_ACTIONS]、value[1]、h_next[1, HID]
"""
import argparse
import os
import sys

import numpy as np
import torch

from ..sim.game import N_ACTIONS, OBS_DIM
from ..sim.world import CACHE
from ..train.model import HID, GhostNet


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', default='ghost_v1')
    ap.add_argument('--ckpt', default='latest.pt')
    ap.add_argument('--out', default=None)
    a = ap.parse_args()
    src = os.path.join(CACHE, 'runs', a.run, a.ckpt)
    ck = torch.load(src, map_location='cpu')
    net = GhostNet()
    net.load_state_dict(ck['net'])
    net.eval()
    out = a.out or os.path.join('ai', 'models', f'{a.run}.onnx')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    obs = torch.zeros(1, OBS_DIM)
    h = torch.zeros(1, HID)
    mask = torch.ones(1, N_ACTIONS, dtype=torch.bool)
    torch.onnx.export(net, (obs, h, mask), out, input_names=['obs', 'h', 'mask'], output_names=['logits', 'value', 'h_next'],
                      dynamic_axes={'obs': {0: 'b'}, 'h': {0: 'b'}, 'mask': {0: 'b'}, 'logits': {0: 'b'}, 'value': {0: 'b'}, 'h_next': {0: 'b'}},
                      opset_version=17, dynamo=False)
    import onnxruntime as ort
    sess = ort.InferenceSession(out, providers=['CPUExecutionProvider'])
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(200):
        o = rng.random((1, OBS_DIM), dtype=np.float32)
        hh = rng.standard_normal((1, HID)).astype(np.float32) * 0.3
        m = rng.random((1, N_ACTIONS)) < 0.8
        m[0, 0] = True
        with torch.no_grad():
            lt, vt, ht = net(torch.from_numpy(o), torch.from_numpy(hh), torch.from_numpy(m))
        lo, vo, ho = sess.run(None, {'obs': o, 'h': hh, 'mask': m})
        ok = m[0]
        worst = max(worst, float(np.abs(lt.numpy()[0][ok] - lo[0][ok]).max()), float(np.abs(ht.numpy() - ho).max()))
    import time
    t = time.perf_counter()
    for _ in range(1000):
        sess.run(None, {'obs': o, 'h': hh, 'mask': m})
    ms = (time.perf_counter() - t)
    print(f'匯出：{out}（{os.path.getsize(out) / 1024:.0f} KB），PyTorch 和 ONNX 最大差異 {worst:.2e}，每次推論 {ms:.3f} 毫秒（CPU）')


if __name__ == '__main__':
    main()
