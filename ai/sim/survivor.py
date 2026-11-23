"""
逃生者由策略控制的版本（自我對弈第 3 步）：每個逃生者每 1 秒做一次高層決定，走路交給尋路。

- 只看得到自己視線內的鬼（看到要過反應時間才「知道」），隊友之間共享最後看到鬼的位置（等於語音），加上自己的心跳段數。
- 動作：去第 i 個區域、去開電源、翻最近的第 1／2／3 個木桶、去授權、去刷卡、從開著的大門逃、去救人／扶人、逃跑、躲暗格、繼續（不改計畫）。
- 獎勵（每個人，2026-11-24 參考 Claude／Gemini 的建議改過）：主線目標全隊 +0.3、救出／扶起隊友 +0.8、自己逃出 +2.5、
  每有一個隊友逃出其他人各 +0.8、被抓 −1、被淘汰再 −0.5、時間到還沒逃出 −1（躲到時間到不划算）。
- 讀條（翻箱、開電源、授權、刷卡、救人）進行中，只有「逃跑」「躲」會中斷；看到鬼或心跳段數變了立刻重新決定（不等 1 秒）。
"""
import math

import numpy as np

from .game import (DT, DOWN, ESCAPED, FREE, JAILED, OUT, RMAX, Game)

S_REG = 13
S_GLOBAL = 16
SOBS_DIM = RMAX * S_REG + S_GLOBAL
(SA_POWER, SA_BARREL1, SA_BARREL2, SA_BARREL3, SA_AUTH, SA_SWIPE, SA_ESCAPE, SA_RESCUE, SA_FLEE, SA_HIDE, SA_WAIT, SA_AUTO) = range(RMAX, RMAX + 12)
S_ACTIONS = RMAX + 12
S_NAMES = {SA_POWER: '開電源', SA_BARREL1: '木桶1', SA_BARREL2: '木桶2', SA_BARREL3: '木桶3', SA_AUTH: '授權', SA_SWIPE: '刷卡',
           SA_ESCAPE: '逃出', SA_RESCUE: '救人', SA_FLEE: '逃跑', SA_HIDE: '躲', SA_WAIT: '繼續', SA_AUTO: '照一般玩家'}
TICK = 4  # 每 4 步（1 秒）做一次決定


class SurvivorGame(Game):
    def __init__(self, *a, ghost_brain=None, **k):
        super().__init__(*a, **k)
        self.ghost_brain = ghost_brain
        self.srew = np.zeros(len(self.sv), np.float32)
        self.team_ghost = None      # (節點, 時間)：隊上最後確定看到鬼的位置
        for s in self.sv:
            s.hb = 0
            s.seen_since = -1.0
            s.react = self.rng.uniform(*self.real['react'])
            s.flee_until = -1.0

    # ---- 一個決定週期 ----

    def tick(self, actions):
        """每個逃生者一個動作（不是自由狀態的人照樣給，會被忽略），往前 1 秒，回傳每個人這段時間的獎勵。"""
        self.srew[:] = 0
        for s, a in zip(self.sv, actions):
            if s.state == FREE:
                self._apply(s, int(a))
        known0 = self.team_ghost
        for k in range(TICK):
            if self.done:
                break
            if self.need_decision and self.g_target is None and self.g_act is None and self.windup is None:
                self.decide(self.ghost_brain(self, self.mask()))
            self.step()
            # 事件：剛看到鬼、心跳段數變了 → 馬上回去重新決定
            event = self.team_ghost is not None and (known0 is None or self.team_ghost[1] > known0[1] + 2)
            for sv in self.free():
                st = self.heartbeat_stage(sv)
                if st != sv.hb:
                    sv.hb = st
                    event = True
            if event and k >= 1:
                break
        if self.done and self.t >= self.c['match']['time-limit']:
            for sv in self.sv:
                if sv.state in (FREE, JAILED, DOWN):
                    self.srew[sv.i] -= 1.0
        return self.srew.copy()

    # ---- 動作 ----

    def s_mask(self, s):
        m = np.zeros(S_ACTIONS, bool)
        m[SA_WAIT] = True
        if s.state != FREE:
            return m
        # 沒事做的時候不能「繼續」（不然會學成站著不動）
        m[SA_WAIT] = s.task is not None or s.act is not None or s.hidden is not None
        m[SA_AUTO] = True
        w = self.w
        m[:w.R] = True
        m[SA_POWER] = not self.power
        n = len(self._barrel_cands(s))
        m[SA_BARREL1], m[SA_BARREL2], m[SA_BARREL3] = n >= 1, n >= 2, n >= 3
        m[SA_AUTH] = self.power and any(c not in self.authorized for c in s.cards)
        m[SA_SWIPE] = self.power and any(c in self.authorized for c in s.cards) and any(g['counting'] < 0 for g in self.gates)
        m[SA_ESCAPE] = any(g['open'] for g in self.gates)
        m[SA_RESCUE] = any(o.state in (JAILED, DOWN) for o in self.sv) and (bool(w.jails) or any(o.state == DOWN for o in self.sv))
        m[SA_FLEE] = self._known_ghost() is not None  # 只能從看到過鬼的地方逃開（心跳不給方向）
        m[SA_HIDE] = self._hideout(s) is not None
        return m

    def _barrel_cands(self, s):
        claimed = {o.claim for o in self.sv if o is not s}
        c = [b for b in self.barrels if b not in self.searched and b not in claimed]
        c.sort(key=lambda b: self.d(s.pos, b))
        return c[:3]

    def _hideout(self, s):
        best, bd = None, 16.0
        for (hide, entrance) in self.w.hideouts:
            ei = self.w.node(*entrance)
            if ei < 0 or any(o.hidden and o.hidden[0] == hide for o in self.sv):
                continue
            d = self.d(s.pos, ei)
            if d < bd:
                best, bd = (hide, ei), d
        return best

    def _apply(self, s, a):
        if a == SA_WAIT:
            return
        if s.act and a not in (SA_FLEE, SA_HIDE):
            return  # 讀條中：只有逃跑、躲會中斷
        if a == SA_AUTO:
            # 照規則型逃生者（一般玩家）的做法：有危險就逃，沒有就做下一個目標
            if s.hidden:
                return
            if self._known_ghost() is not None and (self._sees_ghost(s) or self.heartbeat_stage(s) >= s.p['flee-stage']):
                s.act = None
                s.flee_until = self.t + 1.5
                s.task = ('flee', None, None)
            elif not s.act:
                s.task = self._choose_task(s)
            return
        w = self.w
        if s.hidden:
            if a == SA_HIDE:
                return
            s.pos = s.hidden[1]
            s.hidden = None
        if s.act:
            s.act = None
        s.claim = None
        task = None
        if a < RMAX:
            task = ('wander', int(w.reg_center[min(a, w.R - 1)]), None)
        elif a == SA_POWER:
            if self.known_lever:
                task = ('power', self.lever, None)
            else:
                c = [p for p in w.power_cands if int(w.reg[p]) not in self.visited_regions]
                task = ('look', min(c, key=lambda p: self.d(s.pos, p)), None) if c else ('power', self.lever, None)
        elif a in (SA_BARREL1, SA_BARREL2, SA_BARREL3):
            c = self._barrel_cands(s)
            k = a - SA_BARREL1
            if k < len(c):
                s.claim = c[k]
                task = ('search', c[k], None)
        elif a == SA_AUTH:
            card = next((c for c in s.cards if c not in self.authorized), None)
            known = [q for q in self.terminals if q in self.known_terms]
            if known:
                task = ('auth', min(known, key=lambda q: self.d(s.pos, q)), card)
            else:
                c = [q for q in w.term_cands if int(w.reg[q]) not in self.visited_regions]
                if c:
                    task = ('look', min(c, key=lambda q: self.d(s.pos, q)), None)
        elif a == SA_SWIPE:
            known = [k for k in self.known_gates if self.gates[k]['counting'] < 0 and not self.gates[k]['open']]
            if known:
                k = min(known, key=lambda k: self.d(s.pos, self.gates[k]['reader']))
                task = ('swipe', self.gates[k]['reader'], k)
            else:
                c = [g['reader'] for g in w.gate_cands if int(w.reg[g['reader']]) not in self.visited_regions]
                if c:
                    task = ('look', min(c, key=lambda q: self.d(s.pos, q)), None)
        elif a == SA_ESCAPE:
            g = min([g for g in self.gates if g['open']], key=lambda g: self.d(s.pos, g['exit']), default=None)
            if g:
                task = ('escape', g['exit'], g)
        elif a == SA_RESCUE:
            down = [o for o in self.sv if o.state == DOWN]
            if down:
                o = min(down, key=lambda o: self.d(s.pos, o.pos))
                task = ('revive', o.pos, o)
            elif w.jails:
                task = ('rescue', w.jails[0]['stand'], None)
        elif a == SA_FLEE:
            s.flee_until = self.t + 1.5
            task = ('flee', None, None)
        elif a == SA_HIDE:
            h = self._hideout(s)
            if h:
                task = ('hide', h[1], h[0])
        s.task = task

    # ---- 每一步 ----

    def _survivor(self, s):
        c = self.c
        if s.hidden:
            return
        # 看到鬼：過了反應時間才算知道（隊上共享）
        if self._sees_ghost(s):
            if s.seen_since < 0:
                s.seen_since = self.t
            if self.t - s.seen_since >= s.react:
                self.team_ghost = (self.g, self.t)
        else:
            s.seen_since = -1.0
        speed = 5.6 * (1.5 if self.t < s.dash_until or self.t < s.boost_until else 1.0)
        if s.act:
            kind, left, arg = s.act
            mult = 1.0
            if kind == 'power':
                helpers = sum(1 for o in self.free() if o is not s and o.act and o.act[0] == 'power' and self.eu(o.pos, s.pos) <= 3)
                mult = c['interaction']['team-power-multiplier'] if helpers else 1.0
            left -= DT * mult
            if kind == 'rescue' and self.camped():
                left = min(left, self.c['jail']['camp-rescue-time'])
            if kind != 'rescue' and int((left + DT * mult) / 1.0) != int(left / 1.0):
                self.noise_at(s.pos, c['interaction']['noise-radius'])
            if left > 0:
                s.act = (kind, left, arg)
                return
            s.act = None
            self._survivor_finish(s, kind, arg)
            return
        if not s.task:
            return
        kind, target, arg = s.task
        if kind == 'flee':
            if self.t >= s.flee_until:
                s.task = None
                return
            g = self._known_ghost()
            if g is None:
                s.task = None
                return
            if self.d(s.pos, self.g) <= 6 and self.t >= s.dash_ready:
                s.dash_until = self.t + c['survivor']['dash-duration']
                s.dash_ready = self.t + c['survivor']['dash-cooldown']
            s.budget += speed * DT
            s.running = True
            s.pos, s.budget = self.step_toward(s.pos, g, s.budget, away=True)
            self._run_noise(s)
            return
        if kind == 'revive' and arg.state == DOWN:
            target = arg.pos
        if s.pos == target or (kind == 'rescue' and self._near_jail(s)):
            self._discover(s)
            if kind == 'hide':
                s.hidden = (arg, target, 1e9)
                s.task = None
                return
            if kind in ('revive',):
                return  # 站在旁邊就會扶
            self._start_task(s, kind, target, arg)
            return
        s.running = True
        s.budget += speed * DT
        s.pos, s.budget = self.step_toward(s.pos, target, s.budget)
        self._run_noise(s)
        self._discover(s)

    # ---- 獎勵 ----

    def _objective(self, name):
        super()._objective(name)
        self.srew += 0.3  # 全隊都拿，不會搶著做

    _actor = None

    def _survivor_finish(self, s, kind, arg):
        before = self.stats['rescues']
        self._actor = s
        super()._survivor_finish(s, kind, arg)
        self._actor = None
        if self.stats['rescues'] > before:
            self.srew[s.i] += 0.8

    def _rescues(self):
        before = {o.i: o.state for o in self.sv}
        super()._rescues()
        for o in self.sv:
            if before[o.i] == DOWN and o.state == FREE:
                helpers = [h for h in self.free() if h is not o and self.eu(h.pos, o.pos) <= 2.5]
                if helpers:
                    self.srew[helpers[0].i] += 0.8

    def _capture(self, s):
        super()._capture(s)
        self.srew[s.i] -= 1.0
        if s.state == OUT:
            self.srew[s.i] -= 0.5

    def _start_task(self, s, kind, target, arg):
        was = s.state
        super()._start_task(s, kind, target, arg)
        if was == FREE and s.state == ESCAPED:
            for o in self.sv:
                self.srew[o.i] += 2.5 if o is s else 0.8

    # ---- 觀察 ----

    def s_obs(self, s):
        w = self.w
        o = np.zeros(SOBS_DIM, np.float32)
        if s.state != FREE or s.pos is None:
            return o
        R = w.R
        reg = o[:RMAX * S_REG].reshape(RMAX, S_REG)
        reg[w.reg[s.pos], 0] = 1
        for t in self.free():
            if t is not s:
                reg[w.reg[t.pos], 1] += 1 / 3
        kg = self.team_ghost
        if kg:
            reg[w.reg[kg[0]], 2] = math.exp(-(self.t - kg[1]) / 10)
        for r in self.visited_regions:
            reg[r, 3] = 1
        for b in self.barrels:
            if b not in self.searched:
                reg[w.reg[b], 4] += 0.2
        reg[:R, 5] = np.minimum(np.asarray(w.dist[s.pos][w.reg_center]).astype(np.float32) / 150, 1)
        reg[:R, 6] = w.reg_floor
        reg[:R, 7] = w.reg_dead_end
        reg[:R, 8] = np.minimum(w.reg_size / 400, 1)
        if self.known_lever:
            reg[w.reg[self.lever], 9] = 1
        else:
            for p in w.power_cands:
                if int(w.reg[p]) not in self.visited_regions:
                    reg[w.reg[p], 9] = 0.5
        for q in w.term_cands:
            r = w.reg[q]
            reg[r, 10] = max(reg[r, 10], 1.0 if q in self.known_terms else (0.5 if int(r) not in self.visited_regions else 0))
        for k, g in enumerate(self.gates):
            if k in self.known_gates:
                reg[w.reg[g['reader']], 11] = 3 if g['open'] else (2 if g['counting'] >= 0 else 1)
        for g in w.gate_cands:
            r = w.reg[g['reader']]
            if int(r) not in self.visited_regions and reg[r, 11] == 0:
                reg[r, 11] = 0.5
        # 隊友正要去哪裡（等於語音報位：不重複做同一件事）
        for t in self.free():
            if t is not s and t.task and t.task[1] is not None:
                reg[w.reg[t.task[1]], 12] += 1 / 3
        gl = o[RMAX * S_REG:]
        gl[0] = self.t / self.c['match']['time-limit']
        gl[1] = self.power
        gl[2] = sum(c not in self.authorized for c in s.cards)
        gl[3] = sum(c in self.authorized for c in s.cards)
        gl[4] = sum(len(t.cards) for t in self.free()) / 2
        gl[5] = self.heartbeat_stage(s) / 4
        sees = s.seen_since >= 0 and self.t - s.seen_since >= s.react
        gl[6] = sees
        if sees:
            fs, xs, zs = self.xyz(s.pos)
            fg, xg, zg = self.xyz(self.g)
            gl[7] = min(math.hypot(xg - xs, zg - zs) / 24, 1)
            gl[8], gl[9] = (xg - xs) / 24, (zg - zs) / 24
        gl[10] = self.t >= s.dash_ready
        gl[11] = s.hidden is not None
        gl[12] = sum(t.state in (JAILED, DOWN) for t in self.sv) / 3
        gl[13] = sum(t.state == ESCAPED for t in self.sv) / 3
        gl[14] = any(g['counting'] >= 0 for g in self.gates)
        gl[15] = s.act is not None
        return o
