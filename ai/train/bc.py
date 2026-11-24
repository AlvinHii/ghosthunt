"""
模仿學習（behavior cloning）：先讓網路學會規則型大腦（RuleBrain）的決定，再交給 PPO 改進。
模擬器改了動作、觀察以後，從舊模型接著練會先變很弱（2026-11-24 ghost_v5 試過），所以先模仿規則型當起點。

  python -m ai.train.bc --run ghost_bc --games 2500 --workers 5
  python -m ai.train.ppo --run ghost_v6 --init ghost_bc ...

價值頭也一起學（每個決定之後的折扣報酬），PPO 一開始的優勢估計才不會亂。
"""
import argparse
import multiprocessing as mp
import os
import random
import sys
import time

import numpy as np

MAPS = ['mall2', 'hospital']
_W = {}


def _init(difficulty):
    os.environ['OMP_NUM_THREADS'] = '1'
    from ..sim.config import load
    from ..sim.world import World
    _W['worlds'] = {m: World(m) for m in MAPS}
    _W['cfg'] = load(difficulty)


def _collect(args):
    from ..sim.game import RuleBrain
    from .envs import advance, make_game
    seed, games, gamma = args
    out = []
    for k in range(games):
        s = seed + k
        m = MAPS[s % 2]
        n = 2 + (s // 2) % 2
        g = make_game(_W['worlds'][m], _W['cfg'], s, n, 'train')
        brain = RuleBrain(random.Random(s + 13))
        advance(g, 0)
        obs, masks, acts, rews = [], [], [], []
        while not g.done:
            mk = g.mask()
            a = brain(g, mk)
            obs.append(g.obs().astype(np.float16))
            masks.append(mk)
            acts.append(a)
            g.decide(a)
            rews.append(advance(g))
        ret = np.zeros(len(rews), np.float32)
        acc = 0.0
        for i in reversed(range(len(rews))):
            acc = rews[i] + gamma * acc
            ret[i] = acc
        out.append((np.stack(obs), np.stack(masks), np.array(acts, np.int64), ret))
    return out


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    import torch
    import torch.nn.functional as F
    from ..sim.world import CACHE
    from .model import HID, GhostNet
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', default='ghost_bc')
    ap.add_argument('--games', type=int, default=2500)
    ap.add_argument('--workers', type=int, default=5)
    ap.add_argument('--difficulty', default='normal')
    ap.add_argument('--gamma', type=float, default=0.997)
    ap.add_argument('--epochs', type=int, default=6)
    ap.add_argument('--chunk', type=int, default=64)
    ap.add_argument('--batch', type=int, default=128)
    ap.add_argument('--lr', type=float, default=5e-4)
    ap.add_argument('--smooth', type=float, default=0.0, help='標籤平滑：起點不要那麼確定（PPO 比較會去試別的做法）')
    ap.add_argument('--save-data', default=None, help='把收集到的局存起來（下次 --load-data 不用重跑）')
    ap.add_argument('--load-data', default=None)
    a = ap.parse_args()
    t0 = time.time()
    per = 25
    jobs = [(70_000_000 + i, min(per, a.games - i), a.gamma) for i in range(0, a.games, per)]
    seqs = []
    if a.load_data:
        import pickle
        seqs = pickle.load(open(a.load_data, 'rb'))
    else:
        with mp.get_context('spawn').Pool(a.workers, initializer=_init, initargs=(a.difficulty,)) as pool:
            for r in pool.imap_unordered(_collect, jobs):
                seqs += r
    if a.save_data:
        import pickle
        pickle.dump(seqs, open(a.save_data, 'wb'))
    n_dec = sum(len(s[2]) for s in seqs)
    print(f'收集 {len(seqs)} 局、{n_dec:,} 個決定（{time.time() - t0:.0f} 秒）', flush=True)

    # 切成固定長度的片段（GRU 從 0 開始；片段不是從頭的話，先跑前面 16 步暖機不算損失）
    WARM = 16
    pieces = []
    for o, m, act, ret in seqs:
        L = len(act)
        for st in range(0, L, a.chunk):
            w0 = max(0, st - WARM)
            pieces.append((o[w0:st + a.chunk], m[w0:st + a.chunk], act[w0:st + a.chunk], ret[w0:st + a.chunk], st - w0))
    dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    net = GhostNet().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr)
    rng = np.random.default_rng(0)
    split = int(len(pieces) * 0.95)
    order = rng.permutation(len(pieces))
    train_idx, val_idx = order[:split], order[split:]
    TL = a.chunk + WARM
    OD = seqs[0][0].shape[1]
    NA = seqs[0][1].shape[1]

    def batch(ids):
        B = len(ids)
        O = np.zeros((TL, B, OD), np.float32)
        M = np.ones((TL, B, NA), bool)
        A = np.zeros((TL, B), np.int64)
        R = np.zeros((TL, B), np.float32)
        V = np.zeros((TL, B), np.float32)   # 算損失的步
        for j, i in enumerate(ids):
            o, m, act, ret, w = pieces[i]
            L = len(act)
            O[:L, j], M[:L, j], A[:L, j], R[:L, j] = o, m, act, ret
            V[w:L, j] = 1
        f = lambda x: torch.from_numpy(x).to(dev)
        return f(O), f(M), f(A), f(R), f(V)

    def run(ids, train):
        O, M, A, R, V = batch(ids)
        h = torch.zeros(len(ids), HID, device=dev)
        ce, vl, acc = 0.0, 0.0, 0.0
        for t in range(TL):
            lg, v, h = net(O[t], h, M[t])
            lp = F.log_softmax(lg, -1)
            nll = -lp.gather(1, A[t].unsqueeze(1)).squeeze(1)
            if a.smooth > 0:
                # 只平滑到能選的動作上
                mk = M[t].float()
                uni = -(lp.masked_fill(~M[t], 0) * mk).sum(-1) / mk.sum(-1).clamp(min=1)
                nll = (1 - a.smooth) * nll + a.smooth * uni
            ce = ce + (nll * V[t]).sum()
            vl = vl + (((v - R[t]) ** 2) * V[t]).sum()
            acc = acc + ((lg.argmax(-1) == A[t]).float() * V[t]).sum()
        n = V.sum().clamp(min=1)
        return ce / n, vl / n, acc / n

    for ep in range(a.epochs):
        rng.shuffle(train_idx)
        net.train()
        tl = []
        for k in range(0, len(train_idx), a.batch):
            ce, vl, acc = run(train_idx[k:k + a.batch], True)
            loss = ce + 0.25 * vl
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            tl.append((ce.item(), vl.item(), acc.item()))
        net.eval()
        with torch.no_grad():
            vs = [tuple(x.item() for x in run(val_idx[k:k + a.batch], False)) for k in range(0, len(val_idx), a.batch)]
        tr, va = np.mean(tl, 0), np.mean(vs, 0)
        print(f'第 {ep + 1} 輪｜訓練 交叉熵 {tr[0]:.3f} 價值 {tr[1]:.3f} 準確率 {tr[2]:.1%}｜驗證 交叉熵 {va[0]:.3f} 價值 {va[1]:.3f} 準確率 {va[2]:.1%}（{time.time() - t0:.0f} 秒）', flush=True)
    out = os.path.join(CACHE, 'runs', a.run)
    os.makedirs(out, exist_ok=True)
    torch.save({'net': net.state_dict(), 'update': 0, 'decisions': 0}, os.path.join(out, 'latest.pt'))
    print('存好了：', os.path.join(out, 'latest.pt'))


if __name__ == '__main__':
    main()
