"""
逃生者訓練用的平行模擬：每局 2～3 個逃生者（固定 3 個位置，沒用到的位置只能「繼續」），鬼從對手池抽：
規則型大腦，或訓練好的鬼（ghost 的存檔，鬼取最高分的動作）。
"""
import multiprocessing as mp
import random

import numpy as np

from ..sim.config import load
from ..sim.game import FREE, Game, RuleBrain
from ..sim.survivor import S_ACTIONS, SOBS_DIM, SA_WAIT, TICK, SurvivorGame
from .envs import advance

SLOTS = 3


class NNGhost:
    """訓練好的鬼（CPU、取最高分）。"""

    def __init__(self, state):
        import torch
        from .model import GhostNet, HID, load_expand
        torch.set_num_threads(1)
        self.torch = torch
        self.net = GhostNet()
        load_expand(self.net, state)
        self.net.eval()
        self.h = torch.zeros(1, HID)

    def __call__(self, game, mask):
        t = self.torch
        with t.no_grad():
            lg, _, self.h = self.net(t.from_numpy(game.obs()[None]), self.h, t.from_numpy(mask[None]))
        return int(lg.argmax(-1))


def make_ghost(spec, rng):
    if spec == 'rule':
        return RuleBrain(random.Random(rng.random()))
    if spec == 'rule_camper':
        return RuleBrain(random.Random(rng.random()), jail_chance=0.9)  # 能守監獄就守（還是受 45 秒額度限制）
    return NNGhost(spec)


def _slots(g):
    obs = np.zeros((SLOTS, SOBS_DIM), np.float32)
    mask = np.zeros((SLOTS, S_ACTIONS), bool)
    mask[:, SA_WAIT] = True
    valid = np.zeros(SLOTS, bool)
    for s in g.sv:
        obs[s.i] = g.s_obs(s)
        mask[s.i] = g.s_mask(s)
        valid[s.i] = True
    return obs, mask, valid


def _worker(conn, maps, n_envs, seed0, difficulty, survivors):
    import os
    os.environ['OMP_NUM_THREADS'] = '1'
    from ..sim.world import World
    worlds = {m: World(m) for m in maps}
    cfg = load(difficulty)
    rng = random.Random(seed0)
    counter = [seed0 * 100000]
    pool = ['rule']

    def pick_ghost():
        x = rng.random()
        if len(pool) == 1 or x < 0.25:
            return 'rule'
        if x < 0.45:
            return 'rule_camper'
        return pool[-1] if rng.random() < 0.5 else rng.choice(pool[1:])

    def new(k):
        counter[0] += 1
        m = maps[(counter[0] + k) % len(maps)]
        n = rng.choice(survivors)
        g = SurvivorGame(worlds[m], cfg, seed=counter[0], survivors=n, ghost_brain=make_ghost(pick_ghost(), rng))
        g.map_name = m
        return g

    envs = []
    while True:
        cmd, data = conn.recv()
        if cmd == 'pool':
            pool = ['rule'] + list(data)
            conn.send(True)
        elif cmd == 'reset':
            envs = [new(k) for k in range(n_envs)]
            out = [_slots(g) for g in envs]
            conn.send(out)
        elif cmd == 'step':
            res = []
            for k, acts in enumerate(data):
                g = envs[k]
                rew = np.zeros(SLOTS, np.float32)
                r = g.tick(acts[:len(g.sv)])
                rew[:len(r)] = r
                info = None
                if g.done:
                    info = dict(g.result(), map=g.map_name, n=g.n)
                    envs[k] = g = new(k)
                res.append((_slots(g), rew, info))
            conn.send(res)
        elif cmd == 'eval':
            conn.send(_evaluate(worlds, cfg, maps, *data, survivors=survivors))
        elif cmd == 'close':
            break


def _evaluate(worlds, cfg, maps, state, ghost_spec, games, seed, survivors):
    """固定種子：逃生者用策略（state，取最高分）或規則型（state=None），鬼用 ghost_spec。"""
    import torch
    from .model import HID, SurvivorNet
    torch.set_num_threads(1)
    net = None
    if state is not None:
        net = SurvivorNet()
        net.load_state_dict(state)
        net.eval()
    out = []
    for k in range(games):
        m = maps[k % len(maps)]
        n = survivors[(k // len(maps)) % len(survivors)]
        rng = random.Random(seed + k)
        ghost = make_ghost(ghost_spec, rng)
        if net is None:
            g = Game(worlds[m], cfg, seed=seed + k, survivors=n)
            advance(g, 0)
            while not g.done:
                g.decide(ghost(g, g.mask()))
                advance(g)
        else:
            g = SurvivorGame(worlds[m], cfg, seed=seed + k, survivors=n, ghost_brain=ghost)
            h = torch.zeros(SLOTS, HID)
            while not g.done:
                obs, mask, valid = _slots(g)
                with torch.no_grad():
                    lg, _, h = net(torch.from_numpy(obs), h, torch.from_numpy(mask))
                g.tick(lg.argmax(-1).numpy())
        out.append(dict(g.result(), map=m, n=n, seed=seed + k))
    return out


class SVecEnv:
    def __init__(self, maps, workers=12, envs_per_worker=8, seed=0, difficulty='normal', survivors=(2, 3)):
        ctx = mp.get_context('spawn')
        self.conns, self.procs = [], []
        for i in range(workers):
            a, b = ctx.Pipe()
            p = ctx.Process(target=_worker, args=(b, list(maps), envs_per_worker, seed + i + 1, difficulty, list(survivors)), daemon=True)
            p.start()
            self.conns.append(a)
            self.procs.append(p)
        self.per = envs_per_worker
        self.n = workers * envs_per_worker

    def set_pool(self, states):
        for c in self.conns:
            c.send(('pool', states))
        for c in self.conns:
            c.recv()

    def _cat(self, slots):
        obs = np.concatenate([s[0] for s in slots])
        mask = np.concatenate([s[1] for s in slots])
        valid = np.concatenate([s[2] for s in slots])
        return obs, mask, valid

    def reset(self):
        for c in self.conns:
            c.send(('reset', None))
        slots = []
        for c in self.conns:
            slots += c.recv()
        return self._cat(slots)

    def step(self, actions):
        acts = actions.reshape(self.n, SLOTS)
        for i, c in enumerate(self.conns):
            c.send(('step', acts[i * self.per:(i + 1) * self.per]))
        slots, rews, dones, infos = [], [], [], []
        for c in self.conns:
            for sl, r, info in c.recv():
                slots.append(sl)
                rews.append(r)
                dones.append([info is not None] * SLOTS)
                infos.append(info)
        obs, mask, valid = self._cat(slots)
        return obs, mask, valid, np.concatenate(rews), np.array(dones, bool).reshape(-1), infos

    def evaluate(self, state, ghost_spec, games, seed=20_000_000):
        per = max(1, games // len(self.conns))
        for i, c in enumerate(self.conns):
            c.send(('eval', (state, ghost_spec, per, seed + i * per)))
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
