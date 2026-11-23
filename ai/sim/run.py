"""
規則型鬼 vs 規則型逃生者，跑很多局，印統計和速度。

  python -m ai.sim.run --map mall2 --games 1000 --difficulty normal --survivors 3 --workers 12 --replays 3
"""
import argparse
import json
import multiprocessing as mp
import os
import sys
import time
from collections import Counter

import numpy as np

from .config import load
from .game import play
from .world import World

_W = {}


def _init(map_name):
    _W['w'] = World(map_name)


def _one(args):
    seed, diff, n, sets = args
    cfg = load(diff)
    for k, v in sets.items():
        cfg['ai'][k] = v
    t = time.time()
    g = play(_W['w'], cfg, seed=seed, survivors=n)
    r = g.result()
    r['wall'] = time.time() - t
    r['steps'] = int(g.t / 0.25)
    return r


def summarize(results, n):
    k = len(results)
    out = {}
    esc = [r['escaped'] for r in results]
    out['局數'] = k
    out['有人逃出的局'] = f"{sum(e > 0 for e in esc) / k:.0%}"
    out['逃出人數（平均）'] = f"{np.mean(esc):.2f} / {n}"
    out['全滅的局（沒人逃出、場上沒人）'] = f"{sum(r['escaped'] == 0 and r['time'] < 1019 for r in results) / k:.0%}"
    out['時間到的局'] = f"{sum(r['time'] >= 1019 for r in results) / k:.0%}"
    out['一局（中位數）'] = f"{np.median([r['time'] for r in results]) / 60:.1f} 分"
    out['抓／救／淘汰（平均）'] = f"{np.mean([r['captures'] for r in results]):.1f}／{np.mean([r['rescues'] for r in results]):.1f}／{np.mean([r['eliminated'] for r in results]):.1f}"
    out['揮空（平均）'] = f"{np.mean([r['whiffs'] for r in results]):.1f}"
    out['鬼關掉電源的局'] = f"{sum(r['power_cut'] for r in results) / k:.0%}"
    for name in ['開電源', '第一張卡', '授權', '刷卡', '大門打開']:
        ts = [r['objectives'][name] for r in results if name in r['objectives']]
        out[f'{name}（完成率、中位數時間）'] = f"{len(ts) / k:.0%}、{np.median(ts) / 60:.1f} 分" if ts else '0%'
    steps = sum(r['steps'] for r in results)
    wall = sum(r['wall'] for r in results)
    out['速度（每秒模擬步，單核）'] = f"{steps / wall:,.0f} 步／秒（每步 0.25 秒、{n + 1} 個角色 → 每秒 {steps * (n + 1) / wall:,.0f} 個角色步）"
    return out


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--map', default='mall2')
    ap.add_argument('--games', type=int, default=200)
    ap.add_argument('--difficulty', default='normal')
    ap.add_argument('--survivors', type=int, default=3)
    ap.add_argument('--workers', type=int, default=max(1, os.cpu_count() - 4))
    ap.add_argument('--replays', type=int, default=0)
    ap.add_argument('--out', default=None)
    ap.add_argument('--set', action='append', default=[], help='覆寫 ai-ghost 的值，例如 --set capture-mode=jail')
    a = ap.parse_args()
    World(a.map)  # 先建好距離快取
    t = time.time()
    sets = {}
    for kv in a.set:
        k, v = kv.split('=', 1)
        try:
            v = float(v)
        except ValueError:
            pass
        sets[k] = v
    jobs = [(s, a.difficulty, a.survivors, sets) for s in range(a.games)]
    with mp.Pool(a.workers, initializer=_init, initargs=(a.map,)) as pool:
        results = pool.map(_one, jobs, chunksize=4)
    s = summarize(results, a.survivors)
    print(f'== {a.map}｜{a.difficulty}{" " + str(sets) if sets else ""}｜逃生者 {a.survivors} 人｜規則型鬼 vs 規則型逃生者 ==')
    for k, v in s.items():
        print(f'{k}：{v}')
    print(f'總共 {time.time() - t:.1f} 秒（{a.workers} 個行程）')
    if a.out:
        os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
        with open(a.out, 'w', encoding='utf-8') as f:
            json.dump({'summary': s, 'results': results}, f, ensure_ascii=False, indent=1)
    if a.replays:
        from ..tools.replay import write_replay
        w = World(a.map)
        for k in range(a.replays):
            g = play(w, load(a.difficulty), seed=k, survivors=a.survivors, record=True)
            path = write_replay(w, g, os.path.join('ai', 'replays', f'{a.map}_rule_{k}.html'))
            print('重播：', path, g.result()['time'], '秒', '逃出', g.result()['escaped'])


if __name__ == '__main__':
    main()
