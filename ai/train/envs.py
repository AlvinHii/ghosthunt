"""
平行跑很多局模擬（每個行程管好幾局），給 PPO 用。

每一步：外面給每局一個鬼的高層動作 → 模擬往前跑到鬼下一次要做決定 → 回傳新的觀察、獎勵、結束了沒。
一局結束就自動開新局（地圖在 mall2、hospital 之間輪流，逃生者 2～3 人）。
逃生者：規則型（隨機個性），或（輪流進化第 2 輪）一部分局用訓練好的逃生者 AI（surv_state、nn_frac）。
"""
import multiprocessing as mp
import random

import numpy as np

from ..sim.config import load
from ..sim.game import Game, RuleBrain

MIN_STEPS = 4   # 每個決定至少讓時間走 1 秒（不然一直選「待在原地」時間不會前進）


def ready(g):
    return g.need_decision and g.g_target is None and g.g_act is None and g.windup is None


class NNSurvivors:
    """訓練好的逃生者 AI 控制逃生者（CPU、取最高分）：每 1 秒、或剛看到鬼／心跳段數變了的時候做決定。"""

    def __init__(self, state):
        import torch
        from .model import HID, SurvivorNet
        torch.set_num_threads(1)
        self.torch = torch
        self.net = SurvivorNet()
        self.net.load_state_dict(state)
        self.net.eval()
        self.h = torch.zeros(3, HID)
        self.k = 0
        self.known = None
        self.hb = {}

    def __call__(self, g):
        from .s_envs import _slots
        event = g.team_ghost is not None and (self.known is None or g.team_ghost[1] > self.known[1] + 2)
        for sv in g.free():
            st = g.heartbeat_stage(sv)
            if self.hb.get(sv.i) != st:
                self.hb[sv.i] = st
                event = True
        if self.k % 4 == 0 or event:
            self.known = g.team_ghost
            obs, mask, valid = _slots(g)
            with self.torch.no_grad():
                lg, _, self.h = self.net(self.torch.from_numpy(obs), self.h, self.torch.from_numpy(mask))
            acts = lg.argmax(-1).numpy()
            for sv in g.sv:
                if sv.state == 0:
                    g._apply(sv, int(acts[sv.i]))
        self.k += 1


def advance(g, min_steps=MIN_STEPS):
    r0 = g.reward
    k = 0
    drive = getattr(g, 'driver', None)
    while not g.done:
        if k >= min_steps and ready(g):
            break
        if drive is not None:
            drive(g)
        g.step()
        k += 1
    return g.reward - r0


def make_game(world, cfg, seed, n, personas, surv_state=None):
    """personas='nn'：逃生者是訓練好的 AI；其他照規則型個性。"""
    if personas == 'nn':
        from ..sim.survivor import SurvivorGame
        g = SurvivorGame(world, cfg, seed=seed, survivors=n)
        g.driver = NNSurvivors(surv_state)
        return g
    return Game(world, cfg, seed=seed, survivors=n, personas=personas)


def _worker(conn, maps, n_envs, seed0, difficulty, survivors, surv_state=None, nn_frac=0.0):
    import os
    os.environ['OMP_NUM_THREADS'] = '1'
    from ..sim.world import World
    worlds = {m: World(m) for m in maps}
    cfg = load(difficulty)
    rng = random.Random(seed0)
    counter = [seed0 * 100000]

    def new(k):
        counter[0] += 1
        m = maps[(counter[0] + k) % len(maps)]
        n = rng.choice(survivors)
        use_nn = surv_state is not None and rng.random() < nn_frac
        g = make_game(worlds[m], cfg, counter[0], n, 'nn' if use_nn else 'train', surv_state)
        g.map_name = m
        advance(g, 0)
        return g

    envs = []
    while True:
        cmd, data = conn.recv()
        if cmd == 'reset':
            envs = [new(k) for k in range(n_envs)]
            conn.send(([g.obs() for g in envs], [g.mask() for g in envs]))
        elif cmd == 'step':
            obs, masks, rews, dones, infos = [], [], [], [], []
            for k, a in enumerate(data):
                g = envs[k]
                g.decide(int(a))
                r = advance(g)
                info = None
                if g.done:
                    info = dict(g.result(), map=g.map_name, n=g.n)
                    envs[k] = g = new(k)
                obs.append(g.obs())
                masks.append(g.mask())
                rews.append(r)
                dones.append(info is not None)
                infos.append(info)
            conn.send((obs, masks, rews, dones, infos))
        elif cmd == 'eval':
            state, games, seed, use_rule, personas = data
            conn.send(_evaluate(worlds, cfg, maps, state, games, seed, use_rule, survivors, personas, surv_state))
        elif cmd == 'close':
            break


def _evaluate(worlds, cfg, maps, state, games, seed, use_rule, survivors, personas='train', surv_state=None):
    """固定種子打很多局（鬼取最高分的動作），回傳每局結果。use_rule=True 用規則型大腦當對照。"""
    out = []
    net = None
    if not use_rule:
        import torch
        from .model import GhostNet, HID
        torch.set_num_threads(1)
        net = GhostNet()
        net.load_state_dict(state)
        net.eval()
    for k in range(games):
        m = maps[k % len(maps)]
        n = survivors[(k // len(maps)) % len(survivors)]
        g = make_game(worlds[m], cfg, seed + k, n, personas, surv_state)
        brain = RuleBrain(random.Random(seed + k + 7)) if use_rule else None
        h = None
        advance(g, 0)
        while not g.done:
            mask = g.mask()
            if use_rule:
                a = brain(g, mask)
            else:
                import torch
                with torch.no_grad():
                    if h is None:
                        h = torch.zeros(1, HID)
                    logits, _, h = net(torch.from_numpy(g.obs()[None]), h, torch.from_numpy(mask[None]))
                    a = int(logits.argmax(-1))
            g.decide(a)
            advance(g)
        out.append(dict(g.result(), map=m, n=n, seed=seed + k))
    return out


class VecEnv:
    def __init__(self, maps, workers=12, envs_per_worker=8, seed=0, difficulty='normal', survivors=(2, 3), surv_state=None, nn_frac=0.0):
        ctx = mp.get_context('spawn')
        self.conns = []
        self.procs = []
        for i in range(workers):
            a, b = ctx.Pipe()
            p = ctx.Process(target=_worker, args=(b, list(maps), envs_per_worker, seed + i + 1, difficulty, list(survivors), surv_state, nn_frac), daemon=True)
            p.start()
            self.conns.append(a)
            self.procs.append(p)
        self.per = envs_per_worker
        self.n = workers * envs_per_worker
        self.maps = list(maps)
        self.survivors = list(survivors)

    def reset(self):
        for c in self.conns:
            c.send(('reset', None))
        obs, masks = [], []
        for c in self.conns:
            o, m = c.recv()
            obs += o
            masks += m
        return np.stack(obs), np.stack(masks)

    def step(self, actions):
        for i, c in enumerate(self.conns):
            c.send(('step', actions[i * self.per:(i + 1) * self.per]))
        obs, masks, rews, dones, infos = [], [], [], [], []
        for c in self.conns:
            o, m, r, d, inf = c.recv()
            obs += o
            masks += m
            rews += r
            dones += d
            infos += inf
        return np.stack(obs), np.stack(masks), np.array(rews, np.float32), np.array(dones, bool), infos

    def evaluate(self, state, games, seed=10_000_000, use_rule=False, personas='train'):
        per = max(1, games // len(self.conns))
        for i, c in enumerate(self.conns):
            c.send(('eval', (state, per, seed + i * per, use_rule, personas)))
        out = []
        for c in self.conns:
            out += c.recv()
        return out

    def close(self):
        for c in self.conns:
            try:
                c.send(('close', None))
            except Exception:
                pass
        for p in self.procs:
            p.join(timeout=5)
