"""
訓練逃生者（自我對弈第 3 步）：全隊共用一個網路，鬼從對手池抽（規則型、ghost 訓練的存檔）。PPO＋GRU，可以從中斷的地方繼續。

  python -m ai.train.s_ppo --run surv_v1 --ghost-run ghost_v1 --hours 4

獎勵見 ai/sim/survivor.py。評估：固定 240 局，對規則型鬼、對訓練好的鬼、對訓練沒見過的鬼（--heldout-ghost-run）各一組，
跟規則型逃生者比每人逃出率。
"""
import argparse
import csv
import glob
import json
import os
import sys
import time

import numpy as np
import torch

from ..sim.survivor import S_ACTIONS
from ..sim.world import CACHE
from .model import HID, SurvivorNet
from .ppo import summarize
from .s_envs import SLOTS, SVecEnv


def load_pool(ghost_run, k=4):
    d = os.path.join(CACHE, 'runs', ghost_run)
    files = sorted(glob.glob(os.path.join(d, 'ckpt_*.pt')))
    pick = files[-k:] if files and k > 0 else []
    if os.path.exists(os.path.join(d, 'latest.pt')):
        pick.append(os.path.join(d, 'latest.pt'))
    states = []
    for f in pick:
        ck = torch.load(f, map_location='cpu')
        states.append({kk: v.cpu() for kk, v in ck['net'].items()})
    return states, pick


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', default='surv_v1')
    ap.add_argument('--ghost-run', default='ghost_v2')
    ap.add_argument('--heldout-ghost-run', default='ghost_v1')
    ap.add_argument('--maps', default='mall2,hospital')
    ap.add_argument('--difficulty', default='normal')
    ap.add_argument('--workers', type=int, default=10)
    ap.add_argument('--envs', type=int, default=6)
    ap.add_argument('--steps', type=int, default=128)
    ap.add_argument('--hours', type=float, default=4)
    ap.add_argument('--lr', type=float, default=2.5e-4)
    ap.add_argument('--gamma', type=float, default=0.997)
    ap.add_argument('--lam', type=float, default=0.95)
    ap.add_argument('--clip', type=float, default=0.2)
    ap.add_argument('--ent', type=float, default=0.01)
    ap.add_argument('--epochs', type=int, default=3)
    ap.add_argument('--minibatches', type=int, default=4)
    ap.add_argument('--eval-every', type=int, default=20)
    ap.add_argument('--eval-games', type=int, default=240)
    a = ap.parse_args()
    if os.name == 'nt':
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)
    dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    out = os.path.join(CACHE, 'runs', a.run)
    os.makedirs(out, exist_ok=True)
    maps = a.maps.split(',')
    venv = SVecEnv(maps, a.workers, a.envs, seed=hash(a.run) % 1000 + 500, difficulty=a.difficulty)
    pool, files = load_pool(a.ghost_run)
    venv.set_pool(pool)
    print(f'對手池：規則型鬼＋{len(pool)} 個訓練的鬼（{[os.path.basename(f) for f in files]}）', flush=True)
    nn_ghost = pool[-1] if pool else 'rule'
    held, held_files = load_pool(a.heldout_ghost_run, k=0)
    held_ghost = held[-1] if held else 'rule'
    print(f'沒見過的鬼（只評估）：{[os.path.basename(f) for f in held_files]}', flush=True)
    N = venv.n * SLOTS
    T = a.steps
    net = SurvivorNet().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, eps=1e-5)
    update, decisions = 0, 0
    latest = os.path.join(out, 'latest.pt')
    if os.path.exists(latest):
        ck = torch.load(latest, map_location=dev)
        net.load_state_dict(ck['net'])
        opt.load_state_dict(ck['opt'])
        update, decisions = ck['update'], ck['decisions']
        print(f'從第 {update} 次更新繼續', flush=True)
    with open(os.path.join(out, 'args.json'), 'w', encoding='utf-8') as f:
        json.dump(vars(a), f, ensure_ascii=False, indent=1)
    base_path = os.path.join(out, 'baseline.json')
    if not os.path.exists(base_path):
        t = time.time()
        base = {'rule_ghost': summarize(venv.evaluate(None, 'rule', a.eval_games)),
                'nn_ghost': summarize(venv.evaluate(None, nn_ghost, a.eval_games)),
                'held_ghost': summarize(venv.evaluate(None, held_ghost, a.eval_games))}
        json.dump(base, open(base_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f'規則型逃生者（對照，{time.time() - t:.0f} 秒）：{base}', flush=True)
    base = json.load(open(base_path, encoding='utf-8'))

    obs, mask, valid = venv.reset()
    h = torch.zeros(N, HID, device=dev)
    recent = []
    ep_rew = np.zeros(N)
    t_start = time.time()
    logf = open(os.path.join(out, 'log.csv'), 'a', newline='', encoding='utf-8')
    log = csv.writer(logf)
    evalf = open(os.path.join(out, 'eval.csv'), 'a', newline='', encoding='utf-8')
    evlog = csv.writer(evalf)
    while time.time() - t_start < a.hours * 3600:
        b_obs = torch.zeros(T, N, obs.shape[1], device=dev)
        b_mask = torch.zeros(T, N, S_ACTIONS, dtype=torch.bool, device=dev)
        b_valid = torch.zeros(T, N, device=dev)
        b_act = torch.zeros(T, N, dtype=torch.long, device=dev)
        b_logp = torch.zeros(T, N, device=dev)
        b_val = torch.zeros(T, N, device=dev)
        b_rew = torch.zeros(T, N, device=dev)
        b_done = torch.zeros(T, N, device=dev)
        h0 = h.clone()
        t0 = time.time()
        for t in range(T):
            o = torch.from_numpy(obs).to(dev)
            mk = torch.from_numpy(mask).to(dev)
            with torch.no_grad():
                logits, v, h = net(o, h, mk)
                dist = torch.distributions.Categorical(logits=logits)
                act = dist.sample()
            b_obs[t], b_mask[t], b_act[t], b_logp[t], b_val[t] = o, mk, act, dist.log_prob(act), v
            b_valid[t] = torch.from_numpy(valid.astype(np.float32)).to(dev)
            obs, mask, valid, rew, done, infos = venv.step(act.cpu().numpy())
            b_rew[t] = torch.from_numpy(rew).to(dev)
            b_done[t] = torch.from_numpy(done.astype(np.float32)).to(dev)
            ep_rew += rew
            for e, info in enumerate(infos):
                if info is not None:
                    recent.append(dict(info, reward=float(ep_rew[e * SLOTS:(e + 1) * SLOTS].sum() / max(1, info['n']))))
                    ep_rew[e * SLOTS:(e + 1) * SLOTS] = 0
            h = h * (1 - b_done[t]).unsqueeze(1)
        decisions += int(b_valid.sum().item())
        t_collect = time.time() - t0
        with torch.no_grad():
            _, last_v, _ = net(torch.from_numpy(obs).to(dev), h, torch.from_numpy(mask).to(dev))
        adv = torch.zeros(T, N, device=dev)
        gae = torch.zeros(N, device=dev)
        for t in reversed(range(T)):
            nv = last_v if t == T - 1 else b_val[t + 1]
            nonterm = 1 - b_done[t]
            delta = b_rew[t] + a.gamma * nv * nonterm - b_val[t]
            gae = delta + a.gamma * a.lam * nonterm * gae
            adv[t] = gae
        ret = adv + b_val
        idx = np.arange(N)
        stats = []
        for _ in range(a.epochs):
            np.random.shuffle(idx)
            for mb in np.array_split(idx, a.minibatches):
                mb = torch.from_numpy(mb).to(dev)
                hh = h0[mb]
                logps, ents, vals = [], [], []
                for t in range(T):
                    lg, v, hh = net(b_obs[t, mb], hh, b_mask[t, mb])
                    d = torch.distributions.Categorical(logits=lg)
                    logps.append(d.log_prob(b_act[t, mb]))
                    ents.append(d.entropy())
                    vals.append(v)
                    hh = hh * (1 - b_done[t, mb]).unsqueeze(1)
                vm = b_valid[:, mb]
                nvm = vm.sum().clamp(min=1)
                logp = torch.stack(logps)
                ent = (torch.stack(ents) * vm).sum() / nvm
                val = torch.stack(vals)
                A = adv[:, mb]
                mean = (A * vm).sum() / nvm
                std = torch.sqrt((((A - mean) ** 2) * vm).sum() / nvm) + 1e-8
                A = (A - mean) / std
                ratio = torch.exp(logp - b_logp[:, mb])
                pg = -(torch.min(ratio * A, torch.clamp(ratio, 1 - a.clip, 1 + a.clip) * A) * vm).sum() / nvm
                vl = (((val - ret[:, mb]) ** 2) * vm).sum() / nvm
                loss = pg + 0.5 * vl - a.ent * ent
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(net.parameters(), 0.5)
                opt.step()
                stats.append((pg.item(), vl.item(), ent.item()))
        update += 1
        pg, vl, ent = np.mean(stats, axis=0)
        last = recent[-400:]
        s = summarize(last)
        print(f'更新 {update}｜決定 {decisions:,}｜{(time.time() - t_start) / 60:.0f} 分｜收集 {t_collect:.1f}s'
              f'｜每人獎勵 {np.mean([r["reward"] for r in last]) if last else 0:+.2f}'
              f'｜逃出率 {s.get("escaped_per_survivor", 0):.2f}｜抓 {s.get("captures", 0):.1f}｜熵 {ent:.2f}', flush=True)
        log.writerow([update, decisions, round(time.time() - t_start), pg, vl, ent,
                      np.mean([r['reward'] for r in last]) if last else 0, s.get('escaped_per_survivor', 0)])
        logf.flush()
        recent = recent[-2000:]
        ck = {'net': net.state_dict(), 'opt': opt.state_dict(), 'update': update, 'decisions': decisions}
        torch.save(ck, latest + '.tmp')
        os.replace(latest + '.tmp', latest)
        if update % a.eval_every == 0:
            state = {k: v.cpu() for k, v in net.state_dict().items()}
            ev_r = summarize(venv.evaluate(state, 'rule', a.eval_games))
            ev_n = summarize(venv.evaluate(state, nn_ghost, a.eval_games))
            ev_h = summarize(venv.evaluate(state, held_ghost, a.eval_games))
            evlog.writerow([update, decisions, round(time.time() - t_start), ev_r.get('escaped_per_survivor', 0), ev_n.get('escaped_per_survivor', 0),
                            ev_r.get('any_escape', 0), ev_n.get('any_escape', 0)])
            evalf.flush()
            torch.save({'net': state, 'update': update, 'eval_rule': ev_r, 'eval_nn': ev_n, 'eval_held': ev_h}, os.path.join(out, f'ckpt_{update:05d}.pt'))
            print(f'  評估 對規則型鬼：{ev_r.get("escaped_per_survivor", 0):.3f}（規則型逃生者 {base["rule_ghost"]["escaped_per_survivor"]:.3f}）'
                  f'｜對訓練的鬼：{ev_n.get("escaped_per_survivor", 0):.3f}（規則型逃生者 {base["nn_ghost"]["escaped_per_survivor"]:.3f}）'
                  f'｜對沒見過的鬼：{ev_h.get("escaped_per_survivor", 0):.3f}（規則型逃生者 {base["held_ghost"]["escaped_per_survivor"]:.3f}）'
                  f'｜躲 {ev_r.get("hide_ratio", 0):.2f}（{base["rule_ghost"].get("hide_ratio", 0):.2f}）｜發呆 {ev_r.get("idle_ratio", 0):.2f}（{base["rule_ghost"].get("idle_ratio", 0):.2f}）'
                  f'｜一人包辦 {ev_r.get("contrib_top", 0):.2f}（{base["rule_ghost"].get("contrib_top", 0):.2f}）｜救援雙殺 {ev_r.get("doublekill", 0):.2f}（{base["rule_ghost"].get("doublekill", 0):.2f}）', flush=True)
    venv.close()
    print('時間到，結束。')


if __name__ == '__main__':
    main()
