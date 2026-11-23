"""
一局《鬼抓人》PvE 的簡化模擬（1 隻鬼 vs 2～3 個逃生者），每步 0.25 秒。

分兩層，跟插件的 AI 鬼一樣：
- 反射層（不訓練）：看到人就追、進攻擊距離就揮拳（前搖、揮空、疲勞照 config）、追丟先去最後看到的位置。
- 高層決策（可以訓練）：沒看到人時「接下來去哪裡、做什麼」。規則型大腦（RuleBrain）或外部策略（強化學習）。

逃生者是規則型 AI，刻意加上真人的限制：反應延遲、只知道看得到／聽得到的事（加上心跳）、偶爾選錯路。
數值全部讀 src/main/resources/config.yml。沒有模擬的東西寫在 ai/README.md。
"""
import math
import random

import numpy as np

from .config import by_survivors

DT = 0.25
RMAX = 64                 # 觀察的區域數上限（mall2 44、hospital 55）
REG_FEATS = 12
GLOBAL_FEATS = 12
OBS_DIM = RMAX * REG_FEATS + GLOBAL_FEATS
A_NOISE, A_POWER, A_SABOTAGE, A_JAIL = RMAX, RMAX + 1, RMAX + 2, RMAX + 3
N_ACTIONS = RMAX + 4
ACTION_NAMES = {A_NOISE: '去聲音', A_POWER: '關電源', A_SABOTAGE: '弄壞大門', A_JAIL: '守監獄'}

FREE, JAILED, ESCAPED, OUT, DOWN = 0, 1, 2, 3, 4   # DOWN：困難模式的倒地（capture-mode: downed）

# 逃生者的「真人程度」（模擬器的電腦逃生者比真人有效率；之後用真人紀錄校正）
REALISM = {
    'walk-chance': 0.4,     # 沒有危險時用走的（不是跑）的機率（每次換任務擲一次）
    'hesitate-chance': 0.04,  # 每 0.5 秒停下來看看四周的機率
    'hesitate-time': (1.0, 3.0),
    'mistake-chance': 0.01,  # 走錯路（去隨便一個相鄰區域）的機率
    'react': (0.3, 0.8),    # 察覺危險的反應時間（秒）
    'flee-stage': 3,        # 心跳到第幾段（1 遠～4 貼臉）就開始逃
    'rescue': 1.0,          # 願意去救人／扶人的機率（每局每人擲一次）
    'hide': 1.0,            # 逃跑時附近有暗格就躲的機率（每局每人擲一次）
}

# 規則型逃生者的個性：訓練鬼時每個人隨機一種（避免鬼只會剋一種機器人）；heldout 只在評估用（訓練沒見過）
PERSONAS = {
    'standard': {},
    'rusher': {'walk-chance': 0.1, 'flee-stage': 4, 'rescue': 0.6, 'hesitate-chance': 0.02},
    'stealth': {'walk-chance': 0.8, 'flee-stage': 2, 'hide': 1.0, 'rescue': 0.7},
    'rescuer': {'rescue': 1.0, 'flee-stage': 3, 'walk-chance': 0.3},
    'selfish': {'rescue': 0.0, 'flee-stage': 3, 'hide': 0.5},
}
HELDOUT = {
    'careful': {'walk-chance': 0.6, 'flee-stage': 2, 'rescue': 0.9, 'hesitate-chance': 0.08, 'react': (0.5, 1.0), 'hide': 0.3},
}


class Survivor:
    def __init__(self, i, pos):
        self.i = i
        self.pos = pos
        self.state = FREE
        self.captures = 0
        self.cards = []            # 卡的編號
        self.task = None           # (種類, 目標節點, 參數)
        self.act = None            # 正在做的互動：(種類, 剩餘秒, 參數)
        self.budget = 0.0
        self.flee_until = -1
        self.threat_seen_at = -1   # 第一次察覺危險的時間（反應延遲用）
        self.dash_ready = 0
        self.dash_until = -1
        self.boost_until = -1
        self.protect_until = -1
        self.hidden = None         # 躲在暗格：(暗格, 入口節點, 出來的時間)
        self.claim = None          # 搶著翻的木桶
        self.running = False


class Game:
    def __init__(self, world, cfg, seed=0, survivors=3, record=False, realism=None, personas='standard'):
        self.real = dict(REALISM, **(realism or {}))
        self.w = world
        self.c = cfg
        self.ai = cfg['ai']
        self.rng = random.Random(seed)
        self.t = 0.0
        self.n = survivors
        self.record = [] if record else None
        self.events = []
        w = world
        # ---- 每局隨機：電源、保全室、大門、木桶、門禁卡 ----
        self.lever = self.rng.choice(w.power_cands)
        terms = list(w.term_cands)
        self.rng.shuffle(terms)
        self.terminals = [terms[0]]
        other = [q for q in terms if w.reg[q] != w.reg[terms[0]] and w.reg_floor[w.reg[q]] != w.reg_floor[w.reg[terms[0]]]] \
            or [q for q in terms if w.reg[q] != w.reg[terms[0]]]
        if other:
            self.terminals.append(other[0])
        gates = list(w.gate_cands)
        self.rng.shuffle(gates)
        self.gates = []
        for g in gates:
            if all(g['floor'] != h['floor'] or w.dist[g['reader'], h['reader']] >= cfg['gate']['min-distance'] for h in self.gates):
                if not self.gates or g['floor'] != self.gates[0]['floor'] or len({x['floor'] for x in gates}) == 1:
                    self.gates.append(dict(g, counting=-1.0, open=False, sabotaged=False, unlocked=False))
            if len(self.gates) == 2:
                break
        self.barrels = self._place_barrels()
        self.barrel_card = {}
        floors = {}
        for b in self.barrels:
            floors.setdefault(w.pos(b)[0], []).append(b)
        fl = list(floors)
        self.rng.shuffle(fl)
        for k in range(cfg['keycard']['count']):
            f = fl[k % len(fl)]
            b = self.rng.choice([x for x in floors[f] if x not in self.barrel_card])
            self.barrel_card[b] = k + 1
        self.searched = set()
        self.authorized = set()
        # ---- 狀態 ----
        self.power = False
        self.power_on_at = -1.0
        self.power_cut = False
        self.blackout_at = None
        self.blackouts = 0
        self.objectives = {}       # 名稱 → 完成時間
        self.known_lever = False
        self.known_terms = set()
        self.known_gates = set()
        self.visited_regions = set()
        # ---- 出生：逃生者隨機分散，鬼在離所有人最遠的地方 ----
        spread = cfg['pve'].get('spawn-spread', 15)
        cands = [i for i in range(w.N) if w.ch(*w.pos(i)) in '.,;' and w.dist[w.reg_center[0], i] < 60000]
        pts = []
        while len(pts) < survivors:
            p = self.rng.choice(cands)
            if all(w.dist[p, q] >= spread for q in pts) or self.rng.random() < 0.02:
                pts.append(p)
        self.sv = [Survivor(i, p) for i, p in enumerate(pts)]
        table = {'standard': {'standard': {}}, 'train': PERSONAS, 'heldout': HELDOUT}[personas] if isinstance(personas, str) else personas
        for s in self.sv:
            name = self.rng.choice(sorted(table))
            s.persona = name
            s.p = dict(self.real, **table[name])
            s.will_rescue = self.rng.random() < s.p['rescue']
            s.will_hide = self.rng.random() < s.p['hide']
            s.last_pos = s.pos
        # 鑽漏洞檢查用的統計
        self.m = {'jailed_t': 0.0, 'near_jail_t': 0.0, 'down_t': 0.0, 'near_down_t': 0.0, 'hide_t': 0.0, 'free_t': 0.0,
                  'idle_t': 0.0, 'doublekill': 0}
        self.contrib = [0] * survivors
        self.camp_t = 0.0
        self.camp_away = 0.0
        # 隊上最後確定看到鬼的位置（等於語音報位）。逃生者只能從這裡判斷鬼在哪個方向：
        # 心跳只告訴你多近、不告訴你方向（2026-11-24 修：之前逃跑直接用鬼的真實位置，等於透視）
        self.team_ghost = None
        # 守監獄的額度：每次有人被關，鬼最多在監獄附近待 guard-cap 秒（跟插件現在的 AI 一樣的設計限制）
        self.guard_used = 0.0
        self.jail_regions = set()
        for j in world.jails:
            for r in range(world.R):
                f, x, z = world.pos(int(world.reg_center[r]))
                if f == j['floor'] and math.hypot(x + 0.5 - j['cx'], z + 0.5 - j['cz']) <= 15:
                    self.jail_regions.add(r)
        self.rescue_events = []
        self.ghost_regions = set()
        far = np.min(np.stack([np.asarray(w.dist[p]) for p in pts]), axis=0)
        far[far >= 60000] = -1
        self.g = far.argmax()
        self.g_face = (0.0, 1.0)
        self.g_budget = 0.0
        bonus = by_survivors(cfg['ghost']['speed-bonus-by-survivors'], survivors)
        self.g_speed = 5.6 * (1 + bonus)
        self.g_goal = None         # (動作, 目標節點, 結束條件參數)
        self.g_goal_since = 0.0
        self.g_act = None          # (種類, 剩餘秒)
        self.g_target = None       # 正在追的逃生者
        self.g_last_seen = None    # (節點, 時間)
        self.windup = None         # (目標, 揮出去的時間)
        self.fatigue_until = -1.0
        self.capture_ready = 0.0
        self.last_contact = 0.0
        self.last_sense = -999.0
        self.noise = None          # (節點, 時間, 強度)
        self.noise_handled = True
        self.reg_visit = np.zeros(w.R) - 60
        self.reg_noise = np.zeros(w.R) - 999
        self.belief = w.reg_size.astype(np.float64) / w.reg_size.sum() * survivors
        self.need_decision = True
        self.reward = 0.0
        self.done = False
        self.stats = {'captures': 0, 'rescues': 0, 'escaped': 0, 'eliminated': 0, 'whiffs': 0, 'decisions': 0}
        big = w.N > self.ai.get('big-map-nodes', 8000)
        self.sense_quiet = self.ai['sense-quiet'] * (self.ai.get('big-map-sense-scale', 0.67) if big else 1)
        # 小鬼（不抓人：碰到人打斷做事、大笑讓大鬼聽到）；困難 4 隻（ai-ghost.presets.hard.imp-count）
        imp = cfg.get('imp', {})
        self.imps = []
        if imp.get('enabled', True):
            for k in range(int(self.ai.get('imp-count', imp.get('count', 3)))):
                self.imps.append({'pos': None, 'out_at': imp.get('start-after', 150) + imp.get('stagger', 40) * k,
                                  'ready': 0.0, 'target': None, 'budget': 0.0})
        self.stats['imp_touches'] = 0

    # =================================================================
    # 公用
    # =================================================================

    def _place_barrels(self):
        w = self.w
        cands = []
        for i in range(w.N):
            f, x, z = w.pos(i)
            if w.ch(f, x, z) != '.':
                continue
            if any(w.ch(f, x + dx, z + dz) == '#' for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                cands.append(i)
        self.rng.shuffle(cands)
        out = []
        for c in cands:
            if all(w.dist[c, o] >= 6 for o in out):
                out.append(c)
            if len(out) >= w.barrel_count:
                break
        return out

    def xyz(self, i):
        f, x, z = self.w.pos(i)
        return f, x + 0.5, z + 0.5

    def eu(self, a, b):
        fa, xa, za = self.w.pos(a)
        fb, xb, zb = self.w.pos(b)
        if fa != fb:
            return 999.0
        return math.hypot(xa - xb, za - zb)

    def d(self, a, b):
        return float(self.w.dist[a, b])

    def free(self):
        return [s for s in self.sv if s.state == FREE]

    def log(self, msg):
        self.events.append((round(self.t, 1), msg))

    def step_toward(self, pos, target, budget, away=False):
        """沿著距離表往 target 走（away=True 是往遠離 target 走），走 budget 格；回傳 (新位置, 剩下的 budget)。"""
        w = self.w
        row = w.dist[target]
        while budget >= 1.0:
            nb = w.nb[pos]
            if len(nb) == 0:
                break
            vals = row[nb].astype(np.float32)
            if away:
                # 逃跑：往離 target 更遠的格子走，避開死路區域；沒有更遠的就停（被逼到角落）
                score = vals.copy()
                for k, j in enumerate(nb):
                    if w.reg_dead_end[w.reg[j]] and w.reg[j] != w.reg[pos]:
                        score[k] -= 30
                k = int(np.argmax(score))
                if vals[k] <= row[pos]:
                    break
            else:
                k = int(np.argmin(vals))
                if vals[k] >= row[pos]:
                    break
            nxt = int(nb[k])
            fa, xa, za = w.pos(pos)
            fb, xb, zb = w.pos(nxt)
            cost = 1.0 if fa == fb and abs(xa - xb) + abs(za - zb) == 1 else max(1.0, abs(float(w.dist[pos, nxt])))
            if cost > budget:
                break
            budget -= cost
            pos = nxt
        return pos, budget

    def noise_at(self, node, radius, strength=1.0):
        """做事、跑步的聲音：同一層 radius 格內鬼聽得到（位置有誤差）。"""
        if self.eu(node, self.g) <= radius:
            err = self.ai.get('noise-error', 3)
            f, x, z = self.w.pos(node)
            for _ in range(6):
                j = self.w.node(f, x + self.rng.randint(-err, err), z + self.rng.randint(-err, err))
                if j >= 0:
                    node = j
                    break
            self.noise = (node, self.t, strength)
            self.noise_handled = False
            self.reg_noise[self.w.reg[node]] = self.t
            self.belief[self.w.reg[node]] += 0.5 * strength
            self.need_decision = True

    def heartbeat_stage(self, s):
        d = self.d(s.pos, self.g)
        for k, lim in enumerate(reversed(self.c['heartbeat']['stage-distances'])):
            if d <= lim:
                return 4 - k
        return 0

    # =================================================================
    # 一步
    # =================================================================

    def step(self):
        """往前 0.25 秒。"""
        if self.done:
            return
        self.t += DT
        self._env()
        self._ghost_reflex()
        self._imps()
        for s in self.sv:
            if s.state == FREE:
                self._survivor(s)
        self._metrics()
        self._rescues()
        self._gates()
        self._end_check()
        if self.record is not None and int(self.t / DT) % 2 == 0:
            self.record.append(self.snapshot())

    def _env(self):
        # 隨機斷電（電源開了以後，一局最多一次）
        p = self.c['power']
        if self.power and self.blackouts < p['random-blackout-max']:
            if self.blackout_at is None:
                lo, hi = p['random-blackout-window']
                self.blackout_at = self.t + self.rng.uniform(lo, hi)
            elif self.t >= self.blackout_at:
                self.power = False
                self.blackouts += 1
                self.blackout_at = None
                self.log('隨機斷電')
        # 信念：每秒往相鄰區域擴散
        if abs(self.t - round(self.t)) < 1e-6:
            w = self.w
            m = 0.12
            spread = np.zeros_like(self.belief)
            for r in range(w.R):
                if w.reg_adj[r]:
                    share = self.belief[r] * m / len(w.reg_adj[r])
                    for q in w.reg_adj[r]:
                        spread[q] += share
                    spread[r] -= self.belief[r] * m
            self.belief = np.maximum(self.belief + spread, 0)
            nf = len(self.free())
            s = self.belief.sum()
            if s > 0 and nf > 0:
                self.belief *= nf / s
        # 15 分鐘起還沒逃出的人發光：鬼知道位置
        if self.t >= self.c['match']['glow-start'] and abs(self.t - round(self.t)) < 1e-6:
            for s in self.free():
                if not s.hidden:
                    self.noise_at(s.pos, 999, 2.0)

    # =================================================================
    # 鬼：反射層
    # =================================================================

    def _sight(self):
        return self.ai['sight-range'] if self.power else self.ai['sight-range-dark']

    def _ghost_sees(self, s):
        if s.state != FREE or s.hidden:
            return False
        d = self.eu(self.g, s.pos)
        if d > self._sight():
            return False
        if d > self.ai['near-aware']:
            fg, xg, zg = self.xyz(self.g)
            fs, xs, zs = self.xyz(s.pos)
            vx, vz = xs - xg, zs - zg
            n = math.hypot(vx, vz) or 1
            cos = (vx * self.g_face[0] + vz * self.g_face[1]) / n
            if cos < math.cos(math.radians(self.ai['fov'] / 2)):
                return False
        return self.w.los(self.g, s.pos)

    def _ghost_move(self, target, mult):
        speed = self.g_speed * mult
        if self.t < self.fatigue_until:
            speed *= 1 - self.c['ghost']['attack-fatigue-slow']
        self.g_budget += speed * DT
        old = self.g
        self.g, self.g_budget = self.step_toward(self.g, target, self.g_budget)
        if self.g != old:
            fo, xo, zo = self.w.pos(old)
            fn, xn, zn = self.w.pos(self.g)
            if fo == fn and (xn, zn) != (xo, zo):
                n = math.hypot(xn - xo, zn - zo)
                self.g_face = ((xn - xo) / n, (zn - zo) / n)
        else:
            self.g_budget = min(self.g_budget, 1.0)
        r = self.w.reg[self.g]
        self.reg_visit[r] = self.t

    def _ghost_reflex(self):
        ai = self.ai
        seen = [s for s in self.sv if self._ghost_sees(s)]
        r = self.w.reg[self.g]
        if seen:
            if self.g_target is None and self.t - self.last_contact > 5:
                self.log('鬼看到 逃生者%d' % seen[0].i)
            tgt = min(seen, key=lambda s: self.eu(self.g, s.pos))
            self.g_target = tgt
            self.g_last_seen = (tgt.pos, self.t)
            self.last_contact = self.t
            self.belief[self.w.reg[tgt.pos]] += 1.0
            self.g_act = None
            self.g_goal = None
        else:
            # 沒看到人：這一區的信念降低
            self.belief[r] *= 0.85
            if self.g_target is not None:
                self.g_target = None
                self.need_decision = True
        # 揮拳：前搖 → 判定
        if self.windup is not None:
            s, at = self.windup
            if self.t >= at:
                self.windup = None
                self.fatigue_until = self.t + self.c['ghost']['attack-fatigue']
                if s.state == FREE and not s.hidden and self.eu(self.g, s.pos) <= ai['attack-range'] and self.t >= s.protect_until:
                    self._capture(s)
                else:
                    self.stats['whiffs'] += 1
            return
        # 正在做事（關電源、弄壞大門、打暗格）
        if self.g_act is not None:
            kind, left, arg = self.g_act
            left -= DT
            if left > 0:
                self.g_act = (kind, left, arg)
                return
            self.g_act = None
            self._ghost_finish(kind, arg)
            self.need_decision = True
            return
        if self.g_target is not None:
            s = self.g_target
            if self.t >= self.fatigue_until and self.t >= self.capture_ready and self.eu(self.g, s.pos) <= ai['attack-range']:
                self.windup = (s, self.t + ai['reaction'])
                return
            self._ghost_move(s.pos, ai['chase-speed'])
            return
        # 追丟：先去最後看到的位置（search-time 內）
        if self.g_last_seen and self.t - self.g_last_seen[1] <= ai['search-time'] and self.g != self.g_last_seen[0]:
            self._ghost_move(self.g_last_seen[0], ai['search-speed'])
            return
        # 感應：太久沒接觸 → 知道最近的人在哪一區
        if self.t - self.last_contact >= self.sense_quiet and self.t - self.last_sense >= ai['sense-gap'] * (self.sense_quiet / ai['sense-quiet']):
            fr = [s for s in self.free() if not s.hidden]
            if fr:
                s = min(fr, key=lambda s: self.d(self.g, s.pos))
                self.last_sense = self.t
                rr = self.w.reg[s.pos]
                self.noise = (int(self.w.reg_center[rr]), self.t, 2.0)
                self.noise_handled = False
                self.belief[rr] += 1.5
                self.need_decision = True
        # 高層決策的目標
        if self.g_goal is None or self.need_decision or self.t - self.g_goal_since > 6:
            self.need_decision = True
            return  # 等外面決定（decide）
        a, target, _ = self.g_goal
        if self.g == target:
            self._ghost_arrive(a, target)
            return
        mult = ai['search-speed'] if a in (A_NOISE, A_SABOTAGE) else ai['patrol-speed']
        self._ghost_move(target, mult)

    def _ghost_arrive(self, a, target):
        ai = self.ai
        if a == A_POWER and self.power and not self.power_cut:
            self.g_act = ('power', self.c['ghost']['cut-power-time'], None)
            return
        if a == A_SABOTAGE:
            for g in self.gates:
                if g['reader'] == target and g['counting'] >= 0 and not g['sabotaged']:
                    self.g_act = ('sabotage', self.c['gate']['ghost-sabotage-time'], g)
                    return
        if a == A_NOISE:
            self.noise_handled = True
        if a == A_JAIL:
            self.g_act = ('wait', 6.0, None)
            return
        # 到了一個區域：查暗格（擲骰）
        r = self.w.reg[self.g]
        for (hide, entrance) in self.w.hideouts:
            ei = self.w.node(*entrance)
            if ei >= 0 and self.w.reg[ei] == r and self.rng.random() < ai['check-hideout-base'] * 0.5:
                if self.d(self.g, ei) < 12:
                    self.g_act = ('hideout', self.c['ghost']['widen-crawl-hole'], hide)
                    self.g_goal = (a, ei, None)
                    return
        self.need_decision = True

    def _imps(self):
        if not self.imps:
            return
        c = self.c.get('imp', {})
        w = self.w
        for im in self.imps:
            if im['pos'] is None:
                if self.t >= im['out_at']:
                    im['pos'] = int(self.rng.choice([int(w.reg_center[r]) for r in range(w.R) if w.reg_size[r] > 20]))
                continue
            fr = [x for x in self.free() if not x.hidden]
            if self.t >= im['ready'] and fr:
                tgt = min(fr, key=lambda x: self.d(im['pos'], x.pos))
                if self.d(im['pos'], tgt.pos) <= c.get('seek-range', 48):
                    im['target'] = tgt.pos
                    if self.eu(im['pos'], tgt.pos) <= c.get('touch-range', 1.3) + 0.5:
                        # 碰到：打斷做事、大笑（大鬼沿路 40 格內聽得到）、逃開
                        if tgt.act:
                            tgt.act = None
                        self.stats['imp_touches'] += 1
                        if self.d(tgt.pos, self.g) <= c.get('laugh-range', 40):
                            self.noise = (tgt.pos, self.t, 1.5)
                            self.noise_handled = False
                            self.reg_noise[w.reg[tgt.pos]] = self.t
                            self.belief[w.reg[tgt.pos]] += 1.0
                            self.need_decision = True
                        im['ready'] = self.t + c.get('touch-cooldown', 45)
                        im['target'] = int(w.reg_center[self.rng.randrange(w.R)])
            if im['target'] is None or im['pos'] == im['target']:
                im['target'] = int(w.reg_center[self.rng.randrange(w.R)])
            im['budget'] += c.get('speed', 3.2) * DT
            im['pos'], im['budget'] = self.step_toward(im['pos'], im['target'], im['budget'])

    def _ghost_finish(self, kind, arg):
        if kind == 'power' and self.power:
            self.power = False
            self.power_cut = True
            self.log('鬼關掉電源')
        elif kind == 'sabotage':
            g = arg
            g['counting'] = self.c['gate']['open-countdown']
            g['sabotaged'] = True
            self.log('鬼弄壞大門')
        elif kind == 'hideout':
            for s in self.sv:
                if s.hidden and s.hidden[0] == arg and s.state == FREE:
                    s.hidden = None
                    self._capture(s)
                    break

    def _capture(self, s):
        c = self.c
        s.captures += 1
        s.act = None
        s.task = None
        s.hidden = None
        self.stats['captures'] += 1
        self.capture_ready = self.t + c['ghost']['capture-cooldown']
        self.g_target = None
        self.g_last_seen = None
        self.last_contact = self.t
        self.reward += 1.0 if s.captures == 1 else 0.3
        # 救援雙殺：救人／被救後 10 秒內又被抓
        for ev in self.rescue_events:
            if not ev.get('dk') and self.t - ev['t'] <= 10 and s.i in ev['who']:
                ev['dk'] = True
                self.m['doublekill'] += 1
        # 卡藏進附近的木桶
        for card in s.cards:
            near = [b for b in self.barrels if self.eu(b, s.pos) <= c['jail']['dropped-card-radius'] and b not in self.barrel_card]
            b = self.rng.choice(near) if near else self.rng.choice([b for b in self.barrels if b not in self.barrel_card])
            self.barrel_card[b] = card
            self.searched.discard(b)
        s.cards = []
        if s.captures >= c['jail']['max-captures']:
            s.state = OUT
            s.pos = None
            self.stats['eliminated'] += 1
            self.reward += 0.5
            self.log('逃生者%d 被淘汰' % s.i)
        elif self.ai.get('capture-mode') == 'downed':
            s.state = DOWN
            s.bleed = float(self.ai.get('down-bleed', 60))
            s.revive = 0.0
            self.log('逃生者%d 倒地（第 %d 次）' % (s.i, s.captures))
        else:
            s.state = JAILED
            s.pos = None
            self.log('逃生者%d 被抓（第 %d 次）' % (s.i, s.captures))
        self.need_decision = True

    # =================================================================
    # 鬼：高層決策（外部呼叫）
    # =================================================================

    def mask(self):
        m = np.zeros(N_ACTIONS, bool)
        m[:self.w.R] = True
        m[A_NOISE] = self.noise is not None and not self.noise_handled and self.t - self.noise[1] <= 20
        m[A_POWER] = self.power and not self.power_cut and self.t - self.power_on_at >= self.ai['power-cut-delay']
        m[A_SABOTAGE] = any(g['counting'] >= 0 and not g['sabotaged'] for g in self.gates)
        m[A_JAIL] = (any(s.state == JAILED for s in self.sv) and bool(self.w.jails)) or any(s.state == DOWN for s in self.sv)
        if self.guard_used >= self.ai.get('guard-cap', 45) and any(s.state == JAILED for s in self.sv):
            m[A_JAIL] = any(s.state == DOWN for s in self.sv)
            for r in self.jail_regions:
                m[r] = False
            if not m[:self.w.R].any():
                m[:self.w.R] = True
        return m

    def decide(self, a):
        """套用一個高層動作。"""
        self.stats['decisions'] += 1
        w = self.w
        if a < RMAX:
            target = int(w.reg_center[min(a, w.R - 1)])
        elif a == A_NOISE:
            target = self.noise[0] if self.noise else self.g
        elif a == A_POWER:
            target = self.lever
        elif a == A_SABOTAGE:
            gs = [g for g in self.gates if g['counting'] >= 0 and not g['sabotaged']]
            target = min(gs, key=lambda g: self.d(self.g, g['reader']))['reader'] if gs else self.g
        else:
            down = [s for s in self.sv if s.state == DOWN]
            if down:
                target = min(down, key=lambda s: self.d(self.g, s.pos)).pos  # 守倒地的人（拿來當誘餌）
            else:
                target = w.jails[0]['stand'] if w.jails else self.g
        self.g_goal = (a, target, None)
        self.g_goal_since = self.t
        self.need_decision = False
        if self.g == target:
            self._ghost_arrive(a, target)

    def obs(self):
        w = self.w
        o = np.zeros(OBS_DIM, np.float32)
        R = w.R
        reg = o[:RMAX * REG_FEATS].reshape(RMAX, REG_FEATS)
        reg[:R, 0] = np.minimum(self.belief, 3) / 3
        reg[:R, 1] = np.exp(-(self.t - self.reg_noise) / 15)
        reg[:R, 2] = np.minimum((self.t - self.reg_visit) / 120, 1)
        gr = w.reg[self.g]
        reg[gr, 3] = 1
        reg[:R, 4] = np.minimum(np.asarray(w.dist[self.g][w.reg_center]).astype(np.float32) / 150, 1)
        reg[:R, 5] = w.reg_floor
        reg[:R, 6] = w.reg_dead_end
        reg[:R, 7] = np.minimum(w.reg_size / 400, 1)
        reg[w.reg[self.lever], 8] = 1
        for q in self.terminals:
            reg[w.reg[q], 9] = 1
        for g in self.gates:
            reg[w.reg[g['reader']], 10] = 1 if g['counting'] < 0 else 2
        for j in w.jails:
            if j['stand'] >= 0:
                reg[w.reg[j['stand']], 11] = 1
        gl = o[RMAX * REG_FEATS:]
        gl[0] = self.t / self.c['match']['time-limit']
        gl[1] = self.power
        gl[2] = len(self.free()) / 3
        gl[3] = sum(s.state in (JAILED, DOWN) for s in self.sv) / 3
        gl[4] = sum(s.state == ESCAPED for s in self.sv) / 3
        m = self.mask()
        gl[5:9] = m[RMAX:RMAX + 4]
        gl[9] = w.reg_floor[gr]
        gl[10] = min((self.t - self.last_contact) / 60, 1)
        gl[11] = len(self.authorized) / 2
        return o

    # =================================================================
    # 逃生者（規則型，像真人）
    # =================================================================

    def _known_ghost(self):
        if self.team_ghost and self.t - self.team_ghost[1] <= 20:
            return self.team_ghost[0]
        return None

    def _sees_ghost(self, s):
        d = self.eu(s.pos, self.g)
        rng = 24 if self.power else 12
        return d <= rng and self.w.los(s.pos, self.g)

    def _survivor(self, s):
        c = self.c
        w = self.w
        # 躲在暗格
        if s.hidden:
            if self.t >= s.hidden[2] and self.heartbeat_stage(s) <= 1:
                s.pos = s.hidden[1]
                s.hidden = None
            return
        threat = self._sees_ghost(s) or self.heartbeat_stage(s) >= s.p['flee-stage']
        if threat:
            if s.threat_seen_at < 0:
                s.threat_seen_at = self.t + self.rng.uniform(*s.p['react'])  # 反應延遲
            if self.t >= s.threat_seen_at:
                if self._sees_ghost(s):
                    self.team_ghost = (self.g, self.t)
                if self._known_ghost() is not None:
                    s.flee_until = self.t + 3
                    if s.act:
                        s.act = None
                else:
                    s.walk = True  # 心跳變快但不知道鬼在哪：放輕腳步，照原本的計畫走
        else:
            s.threat_seen_at = -1
        speed = 5.6
        if self.t < s.dash_until or self.t < s.boost_until:
            speed *= 1.5
        kg = self._known_ghost()
        if self.t < s.flee_until and kg is not None:
            # 逃跑：往離「隊上最後看到鬼的位置」遠的方向；心跳最快（鬼 6 格內）就衝刺；附近有暗格、鬼沒看到就躲
            if self.d(s.pos, self.g) <= 6 and self.t >= s.dash_ready:
                s.dash_until = self.t + c['survivor']['dash-duration']
                s.dash_ready = self.t + c['survivor']['dash-cooldown']
            if not self._ghost_sees(s) and s.will_hide:
                for (hide, entrance) in w.hideouts:
                    ei = w.node(*entrance)
                    if ei >= 0 and ei == s.pos and not any(o.hidden and o.hidden[0] == hide for o in self.sv):
                        s.hidden = (hide, ei, self.t + self.rng.uniform(12, 25))
                        return
                    if ei >= 0 and self.d(s.pos, ei) <= 5 and self.d(kg, ei) > self.d(s.pos, ei) + 4:
                        s.budget += speed * DT
                        s.pos, s.budget = self.step_toward(s.pos, ei, s.budget)
                        self._run_noise(s)
                        return
            s.budget += speed * DT
            s.running = True
            s.pos, s.budget = self.step_toward(s.pos, kg, s.budget, away=True)
            self._run_noise(s)
            return
        # 正在做事
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
        # 停下來看看四周
        if self.t < getattr(s, 'pause_until', -1):
            return
        # 決定任務（每 0.5 秒）
        if s.task is None or int(self.t / DT) % 2 == 0:
            if self.rng.random() < s.p['hesitate-chance']:
                s.pause_until = self.t + self.rng.uniform(*s.p['hesitate-time'])
                return
            old = s.task
            s.task = self._choose_task(s)
            if s.task is not None and (old is None or old[1] != s.task[1]):
                s.walk = self.rng.random() < s.p['walk-chance']
        if s.task is None:
            return
        kind, target, arg = s.task
        if s.pos == target or (kind == 'rescue' and self._near_jail(s)):
            self._discover(s)
            self._start_task(s, kind, target, arg)
            return
        near_ghost = self.heartbeat_stage(s) >= 1
        s.running = not near_ghost and not getattr(s, 'walk', False)
        sp = speed if s.running else 4.3
        s.budget += sp * DT
        s.pos, s.budget = self.step_toward(s.pos, target, s.budget)
        self._run_noise(s)
        self._discover(s)

    def _run_noise(self, s):
        if self.eu(s.pos, self.g) <= (self.ai['hear-sprint'] if s.running else self.ai['hear-walk']):
            self.noise = (s.pos, self.t, 0.5)
            self.noise_handled = False
            self.reg_noise[self.w.reg[s.pos]] = self.t

    def _discover(self, s):
        """走到附近才知道：哪個是真的電源、保全室、大門（門口告示牌）。"""
        r = self.w.reg[s.pos]
        self.visited_regions.add(int(r))
        if self.w.reg[self.lever] == r or self.eu(s.pos, self.lever) <= 6:
            self.known_lever = True
        for q in self.terminals:
            if self.w.reg[q] == r or self.eu(s.pos, q) <= 6:
                self.known_terms.add(q)
        for k, g in enumerate(self.gates):
            if self.w.reg[g['reader']] == r or self.eu(s.pos, g['reader']) <= 8:
                self.known_gates.add(k)

    def _near_jail(self, s):
        for j in self.w.jails:
            f, x, z = self.w.pos(s.pos)
            if f == j['floor'] and math.hypot(x + 0.5 - j['cx'], z + 0.5 - j['cz']) <= 4.6:
                return True
        return False

    def _choose_task(self, s):
        w = self.w
        rng = self.rng
        others = [o for o in self.free() if o is not s]
        # 大門開了：去那扇門
        for g in self.gates:
            if g['open']:
                return ('escape', g['exit'], g)
        # 扶倒地的隊友：最近的一個人去（倒地的位置就是目標，站旁邊就會扶）
        down = [o for o in self.sv if o.state == DOWN]
        # 去救人的路上心跳變快（鬼在附近，可能在守）：先放棄 15 秒，做別的事再回來（真人會這樣）
        if s.task and s.task[0] in ('rescue', 'revive') and self.heartbeat_stage(s) >= 2:
            s.rescue_block = self.t + 15
        can_rescue = s.will_rescue and self.t >= getattr(s, 'rescue_block', -1)
        if down and can_rescue:
            d0 = min(down, key=lambda o: self.d(s.pos, o.pos))
            if all(self.d(s.pos, d0.pos) <= self.d(o.pos, d0.pos) + 5 for o in others):
                return ('revive', d0.pos, d0)
        # 救人：最近的一個人去
        jailed = [o for o in self.sv if o.state == JAILED]
        if jailed and w.jails and can_rescue:
            stand = w.jails[0]['stand']
            if all(self.d(s.pos, stand) <= self.d(o.pos, stand) + 5 for o in others) or len(jailed) >= 2:
                return ('rescue', stand, None)
        # 偶爾犯錯：去隨便一個相鄰的區域
        if rng.random() < s.p['mistake-chance']:
            adj = w.reg_adj[w.reg[s.pos]]
            if adj:
                return ('wander', int(w.reg_center[rng.choice(adj)]), None)
        # 沒電：最近的人去開（不知道在哪就挨個找候選）
        if not self.power:
            closest = min([s] + others, key=lambda o: self.d(o.pos, self.lever if self.known_lever else o.pos))
            if closest is s or len(others) == 0 or (self.known_lever and self.d(s.pos, self.lever) < 25):
                if self.known_lever:
                    return ('power', self.lever, None)
                cands = [p for p in w.power_cands if int(w.reg[p]) not in self.visited_regions]
                if cands:
                    return ('look', min(cands, key=lambda p: self.d(s.pos, p)), None)
        # 有卡：授權 → 刷卡
        for card in s.cards:
            if card not in self.authorized:
                if not self.power:
                    break
                known = [q for q in self.terminals if q in self.known_terms]
                if known:
                    return ('auth', min(known, key=lambda q: self.d(s.pos, q)), card)
                cands = [q for q in w.term_cands if int(w.reg[q]) not in self.visited_regions]
                if cands:
                    return ('look', min(cands, key=lambda q: self.d(s.pos, q)), None)
            else:
                if not self.power:
                    break
                known = [k for k in self.known_gates if self.gates[k]['counting'] < 0 and not self.gates[k]['open']]
                if known:
                    k = min(known, key=lambda k: self.d(s.pos, self.gates[k]['reader']))
                    return ('swipe', self.gates[k]['reader'], k)
                cands = [g['reader'] for g in w.gate_cands if int(w.reg[g['reader']]) not in self.visited_regions]
                if cands:
                    return ('look', min(cands, key=lambda q: self.d(s.pos, q)), None)
        # 找卡：翻最近、別人沒在翻的木桶
        cards_left = len(self.barrel_card)
        if cards_left > 0 or not s.cards:
            claimed = {o.claim for o in others}
            cands = [b for b in self.barrels if b not in self.searched and b not in claimed]
            if cands:
                b = min(cands, key=lambda b: self.d(s.pos, b) + rng.uniform(0, 6))
                if s.claim in cands:
                    b = s.claim
                s.claim = b
                return ('search', b, None)
        # 沒事做：跟著拿卡的人、或去還沒去過的區域
        unvisited = [r for r in range(w.R) if r not in self.visited_regions and w.dist[s.pos, w.reg_center[r]] < 60000]
        if unvisited:
            r = min(unvisited, key=lambda r: self.d(s.pos, w.reg_center[r]))
            return ('wander', int(w.reg_center[r]), None)
        return None

    def _start_task(self, s, kind, target, arg):
        c = self.c['interaction']
        if kind == 'search':
            s.act = ('search', c['search-container'], target)
        elif kind == 'power' and not self.power:
            s.act = ('power', c['power-on'] if not self.power_cut and self.blackouts == 0 else c['power-repair'], None)
        elif kind == 'auth' and self.power:
            s.act = ('auth', c['authorize-card'], arg)
        elif kind == 'swipe' and self.power:
            s.act = ('swipe', c['swipe-card'], arg)
        elif kind == 'rescue':
            jailed = [o for o in self.sv if o.state == JAILED]
            if jailed:
                t = by_survivors(self.c['jail']['rescue-time-by-survivors'], self.n)
                if self.camped():
                    t = self.c['jail']['camp-rescue-time']
                s.act = ('rescue', t, None)
        elif kind == 'escape':
            s.state = ESCAPED
            s.pos = None
            self.stats['escaped'] += 1
            self.reward -= 3.0
            self.log('逃生者%d 逃出去了' % s.i)
        s.task = None

    def _survivor_finish(self, s, kind, arg):
        if kind == 'search':
            self.searched.add(arg)
            s.claim = None
            card = self.barrel_card.pop(arg, None)
            if card:
                s.cards.append(card)
                self.contrib[s.i] += 1
                self._objective('第一張卡')
                self.log('逃生者%d 找到門禁卡 #%d' % (s.i, card))
        elif kind == 'power':
            self.power = True
            self.power_on_at = self.t
            self.known_lever = True
            self.contrib[s.i] += 1
            self._objective('開電源')
            self.log('逃生者%d 開了電源' % s.i)
        elif kind == 'auth':
            self.authorized.add(arg)
            self.contrib[s.i] += 1
            self._objective('授權')
            self.log('逃生者%d 授權門禁卡 #%d' % (s.i, arg))
        elif kind == 'swipe':
            g = self.gates[arg]
            card = next((c for c in s.cards if c in self.authorized), None)
            if card is not None and g['counting'] < 0:
                s.cards.remove(card)
                g['counting'] = self.c['gate']['open-countdown']
                self.contrib[s.i] += 1
                self._objective('刷卡')
                self.log('逃生者%d 刷卡（大門 %dF）' % (s.i, g['floor'] + 1))
                self.need_decision = True
        elif kind == 'rescue':
            for o in self.sv:
                if o.state == JAILED:
                    o.state = FREE
                    o.pos = s.pos
                    o.protect_until = self.t + self.c['jail']['rescue-protection']
                    o.boost_until = self.t + self.c['jail']['rescue-speed-boost']
                    self.stats['rescues'] += 1
                    self.log('逃生者%d 救出逃生者%d' % (s.i, o.i))
                    self.rescue_events.append({'t': self.t, 'who': {s.i, o.i}, 'by': s.i})
            self.need_decision = True

    def _objective(self, name):
        if name not in self.objectives:
            self.objectives[name] = self.t
        self.reward -= 0.3

    def _rescues(self):
        """倒地：流血倒數（鬼在旁邊時暫停）、隊友站旁邊扶。"""
        ai = self.ai
        for s in self.sv:
            if s.state != DOWN:
                continue
            if self.eu(self.g, s.pos) > ai.get('down-camp-radius', 8):
                s.bleed -= DT
            helpers = [o for o in self.free() if self.eu(o.pos, s.pos) <= ai.get('revive-radius', 2.0)]
            s.revive = s.revive + DT if helpers else max(0.0, s.revive - 2 * DT)
            if s.revive >= ai.get('revive-time', 4):
                s.state = FREE
                s.protect_until = self.t + self.c['jail']['rescue-protection']
                s.boost_until = self.t + self.c['jail']['rescue-speed-boost']
                self.stats['rescues'] += 1
                self.log('逃生者%d 扶起逃生者%d' % (helpers[0].i, s.i))
                self.rescue_events.append({'t': self.t, 'who': {helpers[0].i, s.i}, 'by': helpers[0].i})
                self.need_decision = True
            elif s.bleed <= 0:
                s.state = OUT
                s.pos = None
                self.stats['eliminated'] += 1
                self.reward += 0.5
                self.log('逃生者%d 流血淘汰' % s.i)
                self.need_decision = True

    def _gates(self):
        for g in self.gates:
            if g['counting'] >= 0 and not g['open']:
                g['counting'] -= DT
                if g['counting'] <= 0:
                    g['open'] = True
                    self._objective('大門打開')
                    self.log('大門 %dF 打開' % (g['floor'] + 1))

    def camped(self):
        return self.camp_t >= self.c['jail']['camp-time']

    def _metrics(self):
        m = self.m
        self.ghost_regions.add(int(self.w.reg[self.g]))
        near = False
        if self.w.jails:
            j = self.w.jails[0]
            f, x, z = self.w.pos(self.g)
            near = f == j['floor'] and math.hypot(x + 0.5 - j['cx'], z + 0.5 - j['cz']) <= self.c['jail']['camp-radius']
        if near:
            self.camp_t += DT
            self.camp_away = 0.0
        else:
            self.camp_away += DT
            if self.camp_away >= self.c['jail']['camp-recover-time']:
                self.camp_t = 0.0
        if any(s.state == JAILED for s in self.sv) and self.w.jails:
            m['jailed_t'] += DT
            if near:
                m['near_jail_t'] += DT
                self.guard_used += DT
        else:
            self.guard_used = 0.0
        down = [s for s in self.sv if s.state == DOWN]
        if down:
            m['down_t'] += DT
            if min(self.eu(self.g, s.pos) for s in down) <= 8:
                m['near_down_t'] += DT
        for s in self.sv:
            if s.state != FREE:
                continue
            m['free_t'] += DT
            if s.hidden:
                m['hide_t'] += DT
            elif s.pos == s.last_pos and not s.act:
                m['idle_t'] += DT
            s.last_pos = s.pos

    def _end_check(self):
        if not self.free() or self.t >= self.c['match']['time-limit']:
            self.done = True
            if not any(s.state == ESCAPED for s in self.sv) and not self.free():
                self.reward += 3.0
            if self.free():
                self.reward -= 0.5

    # =================================================================
    # 重播
    # =================================================================

    def snapshot(self):
        def p(i):
            if i is None:
                return None
            f, x, z = self.w.pos(i)
            return [f, x, z]
        return {
            't': round(self.t, 2),
            'g': p(self.g), 'gt': None if self.g_target is None else self.g_target.i,
            'goal': None if self.g_goal is None else [int(self.g_goal[0]), p(self.g_goal[1])],
            's': [[p(s.pos) if s.pos is not None and not s.hidden else (p(s.hidden[1]) if s.hidden else None), s.state, bool(s.hidden), len(s.cards),
                   None if not s.task else s.task[0]] for s in self.sv],
            'power': self.power,
            'gates': [[g['counting'], g['open']] for g in self.gates],
            'imps': [p(im['pos']) for im in self.imps if im['pos'] is not None],
        }

    def result(self):
        return {
            'time': round(self.t, 1),
            'escaped': self.stats['escaped'],
            'eliminated': self.stats['eliminated'],
            'captures': self.stats['captures'],
            'rescues': self.stats['rescues'],
            'whiffs': self.stats['whiffs'],
            'decisions': self.stats['decisions'],
            'objectives': dict(self.objectives),
            'power_cut': self.power_cut,
            'imp_touches': self.stats['imp_touches'],
            # 鑽漏洞檢查
            'jail_guard': self.m['near_jail_t'] / self.m['jailed_t'] if self.m['jailed_t'] > 0 else None,
            'down_guard': self.m['near_down_t'] / self.m['down_t'] if self.m['down_t'] > 0 else None,
            'regions_per_min': len(self.ghost_regions) / max(1.0, self.t / 60),
            'hide_ratio': self.m['hide_t'] / max(1.0, self.m['free_t']),
            'idle_ratio': self.m['idle_t'] / max(1.0, self.m['free_t']),
            'contrib_top': max(self.contrib) / sum(self.contrib) if sum(self.contrib) >= 3 else None,
            'doublekill': self.m['doublekill'] / len(self.rescue_events) if self.rescue_events else None,
            'personas': [s.persona for s in self.sv],
        }


class RuleBrain:
    """規則型的高層決策（照插件的 AI 鬼簡化：聲音 → 關電源 → 弄壞大門 → 守監獄 → 依信念和多久沒去巡邏）。"""

    def __init__(self, rng=None, jail_chance=None):
        self.rng = rng or random.Random(0)
        self.jail_chance = jail_chance  # None：照 config 的 guard-jail-chance；「守監獄型」給 0.9

    def __call__(self, game, mask):
        g = game
        r = self.rng
        if mask[A_NOISE]:
            return A_NOISE
        if mask[A_SABOTAGE]:
            return A_SABOTAGE
        if mask[A_POWER] and g.t - g.power_on_at >= g.ai['power-cut-delay'] + 20:
            return A_POWER
        jc = self.jail_chance if self.jail_chance is not None else g.ai['guard-jail-chance'] * 0.3
        if mask[A_JAIL] and r.random() < jc:
            return A_JAIL
        w = g.w
        dist = np.asarray(w.dist[g.g][w.reg_center]).astype(np.float64)
        stale = np.minimum((g.t - g.reg_visit) / 60, 2)
        score = g.belief * 2 + stale - dist / 80
        score[w.reg[g.g]] -= 5
        score += np.array([r.random() * 0.5 for _ in range(w.R)])
        return int(np.argmax(score))


def play(world, cfg, brain=None, seed=0, survivors=3, record=False):
    """用規則型（或給的）大腦打完一局。brain(game, mask) → 動作。"""
    g = Game(world, cfg, seed=seed, survivors=survivors, record=record)
    brain = brain or RuleBrain(random.Random(seed + 1))
    while not g.done:
        if g.need_decision and g.g_target is None and g.g_act is None and g.windup is None:
            g.decide(brain(g, g.mask()))
        g.step()
    return g
