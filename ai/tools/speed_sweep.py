"""
找 AI 鬼的移動速度：不同 base-speed × 難度 × 對手（規則型逃生者、沒見過的個性、逃生者 AI），鬼用規則型大腦。

  python -m ai.tools.speed_sweep --speeds 0.40,0.45,0.49,0.55 --games 400 --surv-run surv_v2
"""
import argparse
import json
import multiprocessing as mp
import os
import sys

import numpy as np

MAPS = ['mall2', 'hospital']
_W = {}


def _init():
    os.environ['OMP_NUM_THREADS'] = '1'
    from ..sim.world import World
    _W['worlds'] = {m: World(m) for m in MAPS}


def _job(args):
    from ..sim.config import load
    from ..train.envs import _evaluate
    speed, diff, personas, surv, seed, games = args
    cfg = load(diff)
    cfg['ai']['base-speed'] = speed
    return _evaluate(_W['worlds'], cfg, MAPS, None, games, seed, True, [2, 3], personas, surv)


def summary(res):
    esc = np.array([r['escaped'] / r['n'] for r in res])
    return {
        'per': float(esc.mean()),
        'any': float(np.mean([r['escaped'] > 0 for r in res])),
        'wipe': float(np.mean([r['escaped'] == 0 for r in res])),
        'min': float(np.median([r['time'] for r in res]) / 60),
        'caps': float(np.mean([r['captures'] for r in res])),
        'mall2': float(np.mean([r['escaped'] / r['n'] for r in res if r['map'] == 'mall2'])),
        'hospital': float(np.mean([r['escaped'] / r['n'] for r in res if r['map'] == 'hospital'])),
        'n2': float(np.mean([r['escaped'] / r['n'] for r in res if r['n'] == 2])),
        'n3': float(np.mean([r['escaped'] / r['n'] for r in res if r['n'] == 3])),
    }


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    import torch
    from ..sim.world import CACHE
    ap = argparse.ArgumentParser()
    ap.add_argument('--speeds', default='0.40,0.43,0.45,0.47,0.49,0.52,0.55')
    ap.add_argument('--difficulties', default='normal,hard')
    ap.add_argument('--games', type=int, default=400)
    ap.add_argument('--surv-run', default='surv_v2')
    ap.add_argument('--workers', type=int, default=15)
    ap.add_argument('--out', default=os.path.join('ai', 'runs', 'speed_sweep.json'))
    a = ap.parse_args()
    surv = None
    if a.surv_run:
        ck = torch.load(os.path.join(CACHE, 'runs', a.surv_run, 'latest.pt'), map_location='cpu')
        surv = {k: v.cpu() for k, v in ck['net'].items()}
    sets = [('train', '一般玩家（規則型）'), ('heldout', '謹慎玩家（沒見過）')] + ([('nn', f'高手（{a.surv_run}）')] if surv else [])
    out = {}
    chunk = 20
    with mp.get_context('spawn').Pool(a.workers, initializer=_init) as pool:
        for diff in a.difficulties.split(','):
            for sp in [float(x) for x in a.speeds.split(',')]:
                for key, name in sets:
                    jobs = [(sp, diff, key, surv if key == 'nn' else None, 50_000_000 + i, min(chunk, a.games - i)) for i in range(0, a.games, chunk)]
                    res = []
                    for r in pool.map(_job, jobs):
                        res += r
                    s = summary(res)
                    out[f'{diff}|{sp}|{key}'] = s
                    print(f'{diff} 速度 {sp:.2f} {name}：每人逃出 {s["per"]:.3f}（mall2 {s["mall2"]:.3f}、hospital {s["hospital"]:.3f}；2 人 {s["n2"]:.3f}、3 人 {s["n3"]:.3f}）'
                          f'｜有人逃出 {s["any"]:.0%}｜全滅 {s["wipe"]:.0%}｜一局 {s["min"]:.1f} 分｜抓 {s["caps"]:.1f}', flush=True)
                    json.dump(out, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
