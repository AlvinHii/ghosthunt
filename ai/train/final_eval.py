"""
最終驗收：訓練中完全沒用過的新種子，策略 vs 規則型「同一批種子配對」比較，配對 bootstrap 算 95% 信賴區間。

  python -m ai.train.final_eval ghost --run ghost_v2 --games 1000
  python -m ai.train.final_eval survivor --run surv_v1 --ghost-run ghost_v2 --heldout-ghost-run ghost_v1 --games 1000

鬼：比每人逃出率（越低越好），逃生者分「訓練用的個性」和「沒見過的個性」兩組。
逃生者：比每人逃出率（越高越好），鬼分規則型、訓練的鬼、沒見過的鬼三組。
都會分地圖、分人數，並比較鑽漏洞的指標。
"""
import argparse
import json
import multiprocessing as mp
import os
import sys

import numpy as np

SEED0 = 90_000_000
MAPS = ['mall2', 'hospital']
_W = {}


def _init():
    os.environ['OMP_NUM_THREADS'] = '1'
    from ..sim.config import load
    from ..sim.world import World
    _W['worlds'] = {m: World(m) for m in MAPS}
    _W['cfg'] = load('normal')


def _ghost_job(args):
    from .envs import _evaluate
    state, seed, games, use_rule, personas = args
    return _evaluate(_W['worlds'], _W['cfg'], MAPS, state, games, seed, use_rule, [2, 3], personas)


def _surv_job(args):
    from .s_envs import _evaluate
    state, ghost, seed, games = args
    return _evaluate(_W['worlds'], _W['cfg'], MAPS, state, ghost, games, seed, [2, 3])


def run(pool, fn, make, games, chunk=20):
    jobs = [make(SEED0 + i, min(chunk, games - i)) for i in range(0, games, chunk)]
    out = []
    for r in pool.map(fn, jobs):
        out += r
    return sorted(out, key=lambda r: r['seed'])


def per_game(res):
    return np.array([r['escaped'] / r['n'] for r in res])


def boot(diff, n=4000, seed=0):
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(diff), (n, len(diff)))
    m = diff[idx].mean(1)
    return float(diff.mean()), float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def compare(pol, base):
    assert [r['seed'] for r in pol] == [r['seed'] for r in base]
    out = {}
    d = per_game(pol) - per_game(base)
    out['all'] = (per_game(pol).mean(), per_game(base).mean()) + boot(d)
    for key, f in [('mall2', lambda r: r['map'] == 'mall2'), ('hospital', lambda r: r['map'] == 'hospital'),
                   ('2 人', lambda r: r['n'] == 2), ('3 人', lambda r: r['n'] == 3)]:
        sel = np.array([f(r) for r in pol])
        out[key] = (per_game(pol)[sel].mean(), per_game(base)[sel].mean()) + boot(d[sel])
    ex = {}
    for k in ['jail_guard', 'down_guard', 'regions_per_min', 'hide_ratio', 'idle_ratio', 'contrib_top', 'doublekill', 'time', 'captures']:
        a = [r[k] for r in pol if r.get(k) is not None]
        b = [r[k] for r in base if r.get(k) is not None]
        if a and b:
            ex[k] = (float(np.mean(a)), float(np.mean(b)))
    out['exploit'] = ex
    return out


def table(title, cmp, better):
    lines = [f'**{title}**', '', '| | 策略 | 規則型 | 差（95% 信賴區間） |', '|---|---|---|---|']
    for k in ['all', 'mall2', 'hospital', '2 人', '3 人']:
        p, b, m, lo, hi = cmp[k]
        ok = (hi < 0) if better == 'lower' else (lo > 0)
        lines.append(f'| {"全部" if k == "all" else k} | {p:.3f} | {b:.3f} | {m:+.3f}（{lo:+.3f}～{hi:+.3f}）{" ✓" if ok else ""} |')
    names = {'jail_guard': '守監獄（有人被關時鬼在 12 格內的時間比例）', 'down_guard': '守倒地的人（8 格內的時間比例）',
             'regions_per_min': '鬼每分鐘走過的區域數', 'hide_ratio': '逃生者躲暗格的時間比例', 'idle_ratio': '逃生者原地不動的時間比例',
             'contrib_top': '主線目標由同一個人完成的比例', 'doublekill': '救援雙殺（救人後 10 秒內又被抓）', 'time': '一局（秒）', 'captures': '抓人次數'}
    lines += ['', '| 鑽漏洞指標 | 策略 | 規則型 |', '|---|---|---|']
    for k, (a, b) in cmp['exploit'].items():
        lines.append(f'| {names.get(k, k)} | {a:.2f} | {b:.2f} |')
    return '\n'.join(lines)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    import torch
    from ..sim.world import CACHE
    ap = argparse.ArgumentParser()
    ap.add_argument('who', choices=['ghost', 'survivor'])
    ap.add_argument('--run', required=True)
    ap.add_argument('--ckpt', default='latest.pt')
    ap.add_argument('--ghost-run', default='ghost_v2')
    ap.add_argument('--heldout-ghost-run', default='ghost_v1')
    ap.add_argument('--games', type=int, default=1000)
    ap.add_argument('--workers', type=int, default=12)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()

    def state_of(run, ckpt='latest.pt'):
        ck = torch.load(os.path.join(CACHE, 'runs', run, ckpt), map_location='cpu')
        return {k: v.cpu() for k, v in ck['net'].items()}

    state = state_of(a.run, a.ckpt)
    md = [f'## 最終驗收：{a.run}（{a.ckpt}，新種子 {a.games} 局，配對比較）', '']
    result = {}
    with mp.get_context('spawn').Pool(a.workers, initializer=_init) as pool:
        if a.who == 'ghost':
            for personas, title in [('train', '對訓練用的逃生者個性'), ('heldout', '對沒見過的逃生者個性')]:
                pol = run(pool, _ghost_job, lambda s, n: (state, s, n, False, personas), a.games)
                base = run(pool, _ghost_job, lambda s, n: (None, s, n, True, personas), a.games)
                cmp = compare(pol, base)
                result[personas] = cmp
                md += [table(f'鬼：{title}（每人逃出率，越低越好）', cmp, 'lower'), '']
        else:
            ghosts = [('rule', '規則型鬼', 'rule'), ('nn', f'訓練的鬼（{a.ghost_run}）', state_of(a.ghost_run)),
                      ('held', f'沒見過的鬼（{a.heldout_ghost_run}）', state_of(a.heldout_ghost_run))]
            for key, title, gh in ghosts:
                pol = run(pool, _surv_job, lambda s, n: (state, gh, s, n), a.games)
                base = run(pool, _surv_job, lambda s, n: (None, gh, s, n), a.games)
                cmp = compare(pol, base)
                result[key] = cmp
                md += [table(f'逃生者：對{title}（每人逃出率，越高越好）', cmp, 'higher'), '']
    text = '\n'.join(md)
    print(text)
    out = a.out or os.path.join('ai', 'runs', f'final_{a.run}.md')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8').write(text)
    json.dump(result, open(out.replace('.md', '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1, default=float)


if __name__ == '__main__':
    main()
