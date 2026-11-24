"""
訓練鬼的高層策略（第 2 步：逃生者固定用規則型，只訓練鬼）。PPO＋GRU，可以從中斷的地方繼續。

  python -m ai.train.ppo --run ghost_v1 --hours 6
  （輸出在 %LOCALAPPDATA%/ghosthunt-ai/runs/<run>/：latest.pt、ckpt_*.pt、log.csv、eval.csv）

獎勵（先不加引導分數）：同一個人第一次被抓 +1、之後再抓 +0.3（不刻意讓人被救再抓）、淘汰 +0.5、全部抓完 +3、
逃生者每完成一個主線目標 −0.3、每逃出一人 −3、時間到還有人自由 −0.5。逃生者是規則型，每個人隨機一種個性（PERSONAS）；
評估另外用訓練沒見過的個性（HELDOUT）。
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
from .model import HID, GhostNet, load_expand


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
    # 鑽漏洞檢查（平均；沒有發生的局不算）
    for key in ['jail_guard', 'down_guard', 'regions_per_min', 'hide_ratio', 'idle_ratio', 'contrib_top', 'doublekill']:
        v = [r[key] for r in res if r.get(key) is not None]
        if v:
            out[key] = float(np.mean(v))
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
    ap.add_argument('--workers', type=int, default=10)
    ap.add_argument('--envs', type=int, default=8)
    ap.add_argument('--steps', type=int, default=128)
    ap.add_argument('--hours', type=float, default=6)
    ap.add_argument('--lr', type=float, default=2.5e-4)
    ap.add_argument('--gamma', type=float, default=0.997)
    ap.add_argument('--lam', type=float, default=0.95)
    ap.add_argument('--clip', type=float, default=0.2)
    ap.add_argument('--ent', type=float, default=0.01)
    ap.add_argument('--epochs', type=int, default=3)
    ap.add_argument('--minibatches', type=int, default=4)
    ap.add_argument('--eval-every', type=int, default=25)
    ap.add_argument('--eval-games', type=int, default=240)
    ap.add_argument('--init', default=None, help='新的訓練從這個 run 的 latest.pt 開始')
    ap.add_argument('--surv-run', default=None, help='輪流進化：一部分局的逃生者用這個 run 訓練好的 AI')
    ap.add_argument('--nn-frac', type=float, default=0.5, help='用逃生者 AI 的局佔多少')
    a = ap.parse_args()

    if os.name == 'nt':
        # 訓練期間讓 Windows 不要睡眠（跟影片播放器一樣，不改設定；程式結束就恢復）
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)
    dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    out = os.path.join(CACHE, 'runs', a.run)
    os.makedirs(out, exist_ok=True)
    maps = a.maps.split(',')
    surv_state = None
    if a.surv_run:
        cks = torch.load(os.path.join(CACHE, 'runs', a.surv_run, 'latest.pt'), map_location='cpu')
        surv_state = {k: v.cpu() for k, v in cks['net'].items()}
        print(f'逃生者對手：規則型個性 {1 - a.nn_frac:.0%}＋{a.surv_run} 的 AI {a.nn_frac:.0%}', flush=True)
    venv = VecEnv(maps, a.workers, a.envs, seed=hash(a.run) % 1000, difficulty=a.difficulty, surv_state=surv_state, nn_frac=a.nn_frac if surv_state else 0.0)
    E, T = venv.n, a.steps
    net = GhostNet().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, eps=1e-5)
    update, decisions = 0, 0
    latest = os.path.join(out, 'latest.pt')
    if not os.path.exists(latest) and a.init:
        # 從別的訓練接著練（例如修了模擬器以後，不用從零開始）
        ck0 = torch.load(os.path.join(CACHE, 'runs', a.init, 'latest.pt'), map_location=dev)
        load_expand(net, ck0['net'])
        print(f'從 {a.init} 的模型開始', flush=True)
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
        base['heldout'] = summarize(venv.evaluate(None, a.eval_games, use_rule=True, personas='heldout'))
        if surv_state is not None:
            base['nn'] = summarize(venv.evaluate(None, a.eval_games, use_rule=True, personas='nn'))
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
            ev['heldout'] = summarize(venv.evaluate(state, a.eval_games, personas='heldout'))
            if surv_state is not None:
                ev['nn'] = summarize(venv.evaluate(state, a.eval_games, personas='nn'))
            evlog.writerow([update, decisions, round(time.time() - t_start)] + [round(ev.get(k, 0), 4) for k in
                           ['any_escape', 'escaped_per_survivor', 'captures', 'wipe', 'minutes', 'power_cut', 'mall2_escaped_per_survivor', 'hospital_escaped_per_survivor',
                            'jail_guard', 'regions_per_min']] + [round(ev['heldout'].get('escaped_per_survivor', 0), 4)])
            evalf.flush()
            torch.save({'net': state, 'update': update, 'decisions': decisions, 'eval': ev}, os.path.join(out, f'ckpt_{update:05d}.pt'))
            print(f'  評估（{a.eval_games} 局，鬼選最高分）：每人逃出 {ev["escaped_per_survivor"]:.3f}（規則型 {base["escaped_per_survivor"]:.3f}）'
                  f'｜沒見過的個性 {ev["heldout"].get("escaped_per_survivor", 0):.3f}（規則型 {base["heldout"].get("escaped_per_survivor", 0):.3f}）'
                  f'｜守監獄 {ev.get("jail_guard", 0):.2f}（{base.get("jail_guard", 0):.2f}）｜每分鐘區域 {ev.get("regions_per_min", 0):.1f}（{base.get("regions_per_min", 0):.1f}）'
                  f'｜一局 {ev["minutes"]:.1f} 分（{base["minutes"]:.1f}）'
                  + (f'｜對逃生者 AI {ev["nn"].get("escaped_per_survivor", 0):.3f}（規則型鬼 {base["nn"].get("escaped_per_survivor", 0):.3f}）' if 'nn' in ev else ''), flush=True)
    venv.close()
    print('時間到，結束。')


if __name__ == '__main__':
    main()
