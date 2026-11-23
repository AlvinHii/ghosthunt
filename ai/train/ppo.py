"""
訓練鬼的高層策略（第 2 步：逃生者固定用規則型，只訓練鬼）。PPO＋GRU，可以從中斷的地方繼續。

  python -m ai.train.ppo --run ghost_v1 --hours 6
  （輸出在 %LOCALAPPDATA%/ghosthunt-ai/runs/<run>/：latest.pt、ckpt_*.pt、log.csv、eval.csv）

獎勵（照計畫，先不加引導分數）：抓到一人 +1、淘汰 +0.5、全部抓完 +3、逃生者每完成一個主線目標 −0.3、每逃出一人 −3。
"""
import argparse
import csv
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from ..sim.game import N_ACTIONS
from ..sim.world import CACHE
from .envs import VecEnv
from .model import HID, GhostNet


def summarize(res):
    if not res:
        return {}
    k = len(res)
    out = {
        'games': k,
        'any_escape': sum(r['escaped'] > 0 for r in res) / k,
        'escaped_per_survivor': sum(r['escaped'] for r in res) / sum(r['n'] for r in res),
        'captures': float(np.mean([r['captures'] for r in res])),
        'wipe': sum(r['escaped'] == 0 for r in res) / k,
        'minutes': float(np.median([r['time'] for r in res])) / 60,
        'power_cut': sum(r['power_cut'] for r in res) / k,
    }
    for m in sorted({r['map'] for r in res}):
        rr = [r for r in res if r['map'] == m]
        out[f'{m}_escaped_per_survivor'] = sum(r['escaped'] for r in rr) / sum(r['n'] for r in rr)
    return out


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', default='ghost_v1')
    ap.add_argument('--maps', default='mall2,hospital')
    ap.add_argument('--difficulty', default='normal')
    ap.add_argument('--workers', type=int, default=12)
    ap.add_argument('--envs', type=int, default=8)
    ap.add_argument('--steps', type=int, default=128)
    ap.add_argument('--hours', type=float, default=6)
    ap.add_argument('--lr', type=float, default=2.5e-4)
    ap.add_argument('--gamma', type=float, default=0.99)
    ap.add_argument('--lam', type=float, default=0.95)
    ap.add_argument('--clip', type=float, default=0.2)
    ap.add_argument('--ent', type=float, default=0.01)
    ap.add_argument('--epochs', type=int, default=3)
    ap.add_argument('--minibatches', type=int, default=4)
    ap.add_argument('--eval-every', type=int, default=25)
    ap.add_argument('--eval-games', type=int, default=240)
    a = ap.parse_args()

    dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    out = os.path.join(CACHE, 'runs', a.run)
    os.makedirs(out, exist_ok=True)
    maps = a.maps.split(',')
    venv = VecEnv(maps, a.workers, a.envs, seed=hash(a.run) % 1000, difficulty=a.difficulty)
    E, T = venv.n, a.steps
    net = GhostNet().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, eps=1e-5)
    update, decisions = 0, 0
    latest = os.path.join(out, 'latest.pt')
    if os.path.exists(latest):
        ck = torch.load(latest, map_location=dev)
        net.load_state_dict(ck['net'])
        opt.load_state_dict(ck['opt'])
        update, decisions = ck['update'], ck['decisions']
        print(f'從第 {update} 次更新繼續（{decisions:,} 個決定）')
    with open(os.path.join(out, 'args.json'), 'w', encoding='utf-8') as f:
        json.dump(vars(a), f, ensure_ascii=False, indent=1)

    # 對照：規則型大腦（同一批種子）
    base_path = os.path.join(out, 'baseline.json')
    if not os.path.exists(base_path):
        t = time.time()
        base = summarize(venv.evaluate(None, a.eval_games, use_rule=True))
        with open(base_path, 'w', encoding='utf-8') as f:
            json.dump(base, f, ensure_ascii=False, indent=1)
        print(f'規則型大腦（對照，{time.time() - t:.0f} 秒）：{base}')
    base = json.load(open(base_path, encoding='utf-8'))

    obs, mask = venv.reset()
    h = torch.zeros(E, HID, device=dev)
    ep_rew = np.zeros(E)
    recent = []
    t_start = time.time()
    logf = open(os.path.join(out, 'log.csv'), 'a', newline='', encoding='utf-8')
    log = csv.writer(logf)
    evalf = open(os.path.join(out, 'eval.csv'), 'a', newline='', encoding='utf-8')
    evlog = csv.writer(evalf)
    while time.time() - t_start < a.hours * 3600:
        # ---- 收集 ----
        b_obs = torch.zeros(T, E, obs.shape[1], device=dev)
        b_mask = torch.zeros(T, E, N_ACTIONS, dtype=torch.bool, device=dev)
        b_act = torch.zeros(T, E, dtype=torch.long, device=dev)
        b_logp = torch.zeros(T, E, device=dev)
        b_val = torch.zeros(T, E, device=dev)
        b_rew = torch.zeros(T, E, device=dev)
        b_done = torch.zeros(T, E, device=dev)
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
            obs, mask, rew, done, infos = venv.step(act.cpu().numpy())
            b_rew[t] = torch.from_numpy(rew).to(dev)
            b_done[t] = torch.from_numpy(done.astype(np.float32)).to(dev)
            ep_rew += rew
            for k in np.where(done)[0]:
                recent.append(dict(infos[k], reward=float(ep_rew[k])))
                ep_rew[k] = 0
            h = h * (1 - b_done[t]).unsqueeze(1)
        decisions += T * E
        t_collect = time.time() - t0
        with torch.no_grad():
            _, last_v, _ = net(torch.from_numpy(obs).to(dev), h, torch.from_numpy(mask).to(dev))
        # ---- GAE ----
        adv = torch.zeros(T, E, device=dev)
        gae = torch.zeros(E, device=dev)
        for t in reversed(range(T)):
            nv = last_v if t == T - 1 else b_val[t + 1]
            nonterm = 1 - b_done[t]
            delta = b_rew[t] + a.gamma * nv * nonterm - b_val[t]
            gae = delta + a.gamma * a.lam * nonterm * gae
            adv[t] = gae
        ret = adv + b_val
        # ---- 更新（循環網路：整段序列重算，依環境分小批）----
        idx = np.arange(E)
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
                logp = torch.stack(logps)
                ent = torch.stack(ents).mean()
                val = torch.stack(vals)
                A = adv[:, mb]
                A = (A - A.mean()) / (A.std() + 1e-8)
                ratio = torch.exp(logp - b_logp[:, mb])
                pg = -torch.min(ratio * A, torch.clamp(ratio, 1 - a.clip, 1 + a.clip) * A).mean()
                vl = F.mse_loss(val, ret[:, mb])
                loss = pg + 0.5 * vl - a.ent * ent
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(net.parameters(), 0.5)
                opt.step()
                stats.append((pg.item(), vl.item(), ent.item(), (ratio - 1).abs().mean().item()))
        update += 1
        pg, vl, ent, rc = np.mean(stats, axis=0)
        last = recent[-400:]
        s = summarize(last)
        msg = (f'更新 {update}｜決定 {decisions:,}｜{(time.time() - t_start) / 60:.0f} 分｜收集 {t_collect:.1f}s'
               f'｜每局獎勵 {np.mean([r["reward"] for r in last]) if last else 0:+.2f}'
               f'｜逃出率 {s.get("escaped_per_survivor", 0):.2f}（對照 {base["escaped_per_survivor"]:.2f}）'
               f'｜抓 {s.get("captures", 0):.1f}｜熵 {ent:.2f}')
        print(msg, flush=True)
        log.writerow([update, decisions, round(time.time() - t_start), pg, vl, ent, rc,
                      np.mean([r['reward'] for r in last]) if last else 0, s.get('escaped_per_survivor', 0), s.get('captures', 0)])
        logf.flush()
        recent = recent[-2000:]
        ck = {'net': net.state_dict(), 'opt': opt.state_dict(), 'update': update, 'decisions': decisions}
        torch.save(ck, latest + '.tmp')
        os.replace(latest + '.tmp', latest)
        if update % a.eval_every == 0:
            state = {k: v.cpu() for k, v in net.state_dict().items()}
            ev = summarize(venv.evaluate(state, a.eval_games))
            evlog.writerow([update, decisions, round(time.time() - t_start)] + [round(ev.get(k, 0), 4) for k in
                           ['any_escape', 'escaped_per_survivor', 'captures', 'wipe', 'minutes', 'power_cut', 'mall2_escaped_per_survivor', 'hospital_escaped_per_survivor']])
            evalf.flush()
            torch.save({'net': state, 'update': update, 'decisions': decisions, 'eval': ev}, os.path.join(out, f'ckpt_{update:05d}.pt'))
            print(f'  評估（{a.eval_games} 局，鬼選最高分）：{ev}', flush=True)
    venv.close()
    print('時間到，結束。')


if __name__ == '__main__':
    main()
