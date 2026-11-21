"""鬼抓人平衡模擬（粗略）：用實際蓋出來的大樓（/ghosthunt export 匯出的 JSON）讓電腦逃生者和電腦鬼照規則玩很多局。

用法：python tools/simulate.py <layouts 資料夾> [每種設定幾局]
限制：AI 很簡單，不會心理戰、不用門／障礙物／蹲伏破洞／手電筒；只適合看大方向（誰太強、節奏太長或太短）。
"""
import json
import math
import random
import statistics
import sys
from collections import deque
from multiprocessing import Pool
from pathlib import Path

DT = 0.5                       # 每一步 0.5 秒
SPEED = 5.6                    # 疾跑速度（格／秒）
INF = 10 ** 9

# ---- 規則數值（跟 config.yml 預設一樣） ----
R = dict(
    release=30, ghost_bonus={3: 0.03, 4: 0.05, 5: 0.07}, capture_cd=3, fatigue=1.5, fatigue_speed=0.9,
    dash=1.5, dash_bonus=0.5, dash_cd=45, rescue={3: 4, 4: 5, 5: 6}, camp_radius=12, camp_time=10, camp_rescue=2,
    camp_recover=10, rescue_boost=3, rescue_bonus=0.4, protect=3, max_captures=2,
    search=2, pry=5, power_on=5, power_repair=6, swipe=3, restart=3, sabotage=4, cut=3, coop=1.5, noise=16,
    blackout=(300, 600), grace=90, reveal=90, move_card=4, decoys=3, drop_radius=15, countdown=25,
    spawn={3: dict(smoke=3, speed=3, flash=2, scanner=2, barricade=2), 4: dict(smoke=4, speed=4, flash=2, scanner=3, barricade=3),
           5: dict(smoke=5, speed=5, flash=3, scanner=3, barricade=4)},
    smoke_blind=3, speed_bonus=0.4, speed_time=5,
    nm_first=60, nm_dur=90, nm_cd=150, nm_trail=12,
    glow=900, limit=1020,
    vis_dark=14, vis_lit=24, vis_fog=6, hit_chance=0.7,
    # 模擬真人的不完美
    panic=0.15,          # 逃跑時走錯方向的機率
    react=0.5,           # 開始做事前的反應時間（秒）
    pick_spread=3,       # 選箱子時從最近的幾個裡隨機挑
    discover=10,         # 經過電力室多近才會發現拉桿
    # 實驗用
    container_mult=1.0,  # 容器數量倍率（多出來的放在靠牆、靠家具的格子）
    cards=2,             # 全場門禁卡張數
    cards_per_gate=1,    # 一扇大門要刷幾張卡才開始倒數
)


class Layout:
    def __init__(self, data):
        self.S = data['size']
        self.F = data['floors']
        self.tiles = data['tiles']
        S, F = self.S, self.F
        self.walk = [[[self.tiles[f][z][x] == '.' for x in range(S)] for z in range(S)] for f in range(F)]
        # 節點：真實格子 id = f*S*S + z*S + x；樓梯中間的虛擬節點接在後面
        self.n_real = F * S * S
        adj = [[] for _ in range(self.n_real)]
        for f in range(F):
            for z in range(S):
                for x in range(S):
                    if not self.walk[f][z][x]:
                        continue
                    i = self.nid(f, x, z)
                    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, nz = x + dx, z + dz
                        if 0 <= nx < S and 0 <= nz < S and self.walk[f][nz][nx]:
                            adj[i].append(self.nid(f, nx, nz))
        # 樓梯：5 個虛擬節點串起來（爬 5 階）
        for (a, b) in data['stairs']:
            fa, xa, za = a[0]
            fb, xb, zb = b[0]
            prev = self.nid(fa, xa, za)
            if not self.walk[fa][za][xa] or not self.walk[fb][zb][xb]:
                continue
            for _ in range(5):
                v = len(adj)
                adj.append([prev])
                adj[prev].append(v)
                prev = v
            top = self.nid(fb, xb, zb)
            adj[prev].append(top)
            adj[top].append(prev)
        self.adj = adj
        self.N = len(adj)
        self.containers = [self.nid(*p) for p in data['containers']]
        if R['container_mult'] > 1:
            # 多放一些容器：靠牆或靠家具、走得到的格子，固定種子讓每次一樣
            rr = random.Random(data['seed'])
            want = int(len(self.containers) * R['container_mult'])
            cand = []
            for f in range(self.F):
                for z in range(1, self.S - 1):
                    for x in range(1, self.S - 1):
                        if self.walk[f][z][x] and any(self.tiles[f][z + dz][x + dx] in '#,'
                                                      for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                            cand.append(self.nid(f, x, z))
            rr.shuffle(cand)
            have = set(self.containers)
            for c in cand:
                if len(self.containers) >= want:
                    break
                if c not in have and all(self.euclid(c, o) > 3 for o in self.containers[-40:]):
                    self.containers.append(c)
                    have.add(c)
        self.power = [self.nid(*p) for p in data['power']]
        # 出口在場地外面 2 格（座標可能是負的或超出 S），不能換成格子編號，直接用座標當 key
        self.gates = [(self.nid(*g['reader'][0]), ('exit',) + tuple(g['exit'][0])) for g in data['gates']]
        self.jails = [self.nid(*p) for p in data['jails']]
        self.real_nodes = [i for i in range(self.n_real) if self.adj[i]]
        # 匯出時鬼房的門還封著（裡面算走不到），鬼從鬼房外最近的格子出發
        g = self.nid(*data['ghost'][0])
        self.ghost_room = g
        self.ghost_start = min((i for i in self.real_nodes if self.floor(i) == self.floor(g)), key=lambda i: self.euclid(i, g))
        # 固定目標的距離表（站到目標旁邊一格就算到）
        self.fields = {}
        for c in self.containers + self.power + [g[0] for g in self.gates]:
            self.fields[c] = self.bfs(self.adjacent_walk(c))
        for g in self.gates:
            # 出口在大樓外面（匯出的地圖沒有），走到大門開口內側（離出口 3.6 格內）就算逃出
            self.fields[g[1]] = self.bfs(self.near_xyz(*g[1][1:], 3.6))
        self.jail_zone = {}
        for j in self.jails:
            zone = self.near(j, 4.6)
            self.jail_zone[j] = set(zone)
            self.fields[j] = self.bfs(zone)

    def nid(self, f, x, z):
        return f * self.S * self.S + z * self.S + x

    def fxz(self, i):
        if i >= self.n_real:
            return None
        f, r = divmod(i, self.S * self.S)
        z, x = divmod(r, self.S)
        return f, x, z

    def adjacent_walk(self, i):
        f, x, z = self.fxz(i)
        out = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (0, 0)):
            nx, nz = x + dx, z + dz
            if 0 <= nx < self.S and 0 <= nz < self.S and self.walk[f][nz][nx]:
                out.append(self.nid(f, nx, nz))
        return out

    def near(self, i, r):
        return self.near_xyz(*self.fxz(i), r)

    def near_xyz(self, f, x, z, r):
        out = []
        for dz in range(-int(r) - 1, int(r) + 2):
            for dx in range(-int(r) - 1, int(r) + 2):
                nx, nz = x + dx, z + dz
                if dx * dx + dz * dz <= r * r and 0 <= nx < self.S and 0 <= nz < self.S and self.walk[f][nz][nx]:
                    out.append(self.nid(f, nx, nz))
        return out

    def bfs(self, sources, limit=INF):
        d = [INF] * self.N
        q = deque()
        for s in sources:
            if d[s] == INF:
                d[s] = 0
                q.append(s)
        adj = self.adj
        while q:
            u = q.popleft()
            du = d[u] + 1
            if du > limit:
                continue
            for v in adj[u]:
                if d[v] == INF:
                    d[v] = du
                    q.append(v)
        return d

    def floor(self, i):
        p = self.fxz(i)
        return p[0] if p else -1

    def euclid(self, a, b):
        pa, pb = self.fxz(a), self.fxz(b)
        if not pa or not pb or pa[0] != pb[0]:
            return INF
        return math.hypot(pa[1] - pb[1], pa[2] - pb[2])

    def los(self, a, b, maxd):
        """同一層、距離內、中間沒有擋視線（#）的東西。"""
        pa, pb = self.fxz(a), self.fxz(b)
        if not pa or not pb or pa[0] != pb[0]:
            return False
        f, x0, z0 = pa
        _, x1, z1 = pb
        d = math.hypot(x1 - x0, z1 - z0)
        if d > maxd:
            return False
        n = int(d * 2) + 1
        row = self.tiles[f]
        for k in range(1, n):
            t = k / n
            x = int(round(x0 + (x1 - x0) * t))
            z = int(round(z0 + (z1 - z0) * t))
            if row[z][x] == '#':
                return False
        return True


def step_toward(lay, field, node, rnd):
    """往距離表裡數字比較小的鄰居走一步。"""
    best, bd = node, field[node]
    nb = lay.adj[node][:]
    rnd.shuffle(nb)
    for v in nb:
        if field[v] < bd:
            best, bd = v, field[v]
    return best


def path_first_step(lay, gfield, start, target):
    """用從 start 出發的距離表，從 target 往回找，回傳第一步。"""
    if gfield[target] >= INF:
        return start
    cur = target
    while gfield[cur] > 1:
        nxt = None
        for v in lay.adj[cur]:
            if gfield[v] == gfield[cur] - 1:
                nxt = v
                break
        if nxt is None:
            return start
        cur = nxt
    return cur if gfield[cur] == 1 else start


class Agent:
    def __init__(self, node):
        self.node = node
        self.acc = 0.0


def simulate(lay, n_surv, nightmare, seed):
    rnd = random.Random(seed)
    t = 0.0
    stats = dict(captures=0, rescues=0, power_on=None, first_card=None, gate_open=[], escaped=0, eliminated=0,
                 first_capture=None, end=None, sabotages=0, cuts=0, moves=0, blackouts=0, smokes=0)
    # ---- 容器內容 ----
    conts = lay.containers[:]
    rnd.shuffle(conts)
    content = {}
    floors_used = set()
    cards = 0
    for c in conts:
        if cards >= R['cards']:
            break
        if lay.floor(c) not in floors_used or len(floors_used) >= lay.F:
            floors_used.add(lay.floor(c))
            content[c] = 'card'
            cards += 1
    loot = [k for k, v in R['spawn'][min(5, max(3, n_surv))].items() for _ in range(v)]
    rnd.shuffle(loot)
    free_c = [c for c in conts if c not in content]
    for c, it in zip(free_c, loot):
        content[c] = it
    locked = set([c for c in free_c if c in content][:R['decoys']])
    searched = set()
    lever = rnd.choice(lay.power)
    lever_known = False
    power_todo = set(lay.power)   # 還沒去看過的電源候選（逃生者不知道拉桿在哪一間電力室）
    power = False
    ever_on = False
    blackout_at = None
    blackouts = 0
    grace_until = 0
    gates = [dict(reader=g[0], exit=g[1], unlocked=False, opened=False, sabotaged=False, remaining=None, cards=0) for g in lay.gates]
    moves = {}
    next_reveal = R['release'] + R['reveal']
    # ---- 角色 ----
    walk_nodes = [i for i in lay.real_nodes if lay.euclid(i, lay.ghost_room) > 25 or lay.floor(i) != lay.floor(lay.ghost_room)]
    survivors = []
    for k in range(n_surv):
        a = Agent(rnd.choice(walk_nodes))
        a.status = 'free'
        a.captures = 0
        a.items = {'smoke': 0, 'speed': 0, 'scanner': 0}
        a.card = 0
        a.task = None           # (kind, target, done_time)
        a.protect = 0
        a.dash_ready = 0
        a.boost_until = 0
        a.boost = 0
        a.goal = None
        a.jail = None
        survivors.append(a)
    ghost = Agent(lay.ghost_start)
    ghost.fatigue = 0
    ghost.cap_ready = 0
    ghost.blind = 0
    ghost.busy = None
    ghost.last_seen = None
    ghost.noise = None
    ghost.wander = None
    ghost.cut_used = False
    bonus = R['ghost_bonus'][min(5, max(3, n_surv))]
    nm_ready = R['release'] + R['nm_first'] if nightmare else INF
    nm_until = -1
    rescue_prog = {j: 0.0 for j in lay.jails}
    camp = {j: 0.0 for j in lay.jails}
    camp_away = {j: 0.0 for j in lay.jails}

    def lights_out():
        return t < nm_until

    def vis_range():
        if lights_out():
            return R['vis_fog']
        return R['vis_lit'] if power else R['vis_dark']

    def make_noise(node):
        if t >= R['release'] and lay.euclid(node, ghost.node) <= R['noise']:
            ghost.noise = (node, t)

    def capture(s):
        nonlocal_stats = stats
        s.captures += 1
        nonlocal_stats['captures'] += 1
        if nonlocal_stats['first_capture'] is None:
            nonlocal_stats['first_capture'] = t
        s.task = None
        if s.card:
            drop_card(s.node, s.card)
            s.card = 0
        if s.captures >= R['max_captures']:
            s.status = 'out'
            nonlocal_stats['eliminated'] += 1
        else:
            j = max(lay.jails, key=lambda j: lay.euclid(j, ghost.node) if lay.floor(j) == lay.floor(ghost.node) else 999)
            s.status = 'jail'
            s.jail = j
            s.node = j

    def drop_card(node, n):
        cands = [c for c in lay.containers if content.get(c) != 'card' and lay.euclid(c, node) <= R['drop_radius']]
        if not cands:
            cands = [c for c in lay.containers if content.get(c) != 'card' and lay.floor(c) == lay.floor(node)]
        if not cands:
            cands = [c for c in lay.containers if content.get(c) != 'card']
        c = rnd.choice(cands)
        content[c] = 'card'
        searched.discard(c)

    def free_list():
        return [s for s in survivors if s.status == 'free']

    def move(agent, speed, choose):
        agent.acc += speed * DT
        while agent.acc >= 1:
            agent.acc -= 1
            nxt = choose(agent.node)
            if nxt == agent.node:
                agent.acc = 0
                break
            agent.node = nxt

    end_reason = None
    while True:
        t += DT
        if t >= R['limit']:
            end_reason = 'time'
            break
        frees = free_list()
        if not frees:
            end_reason = 'no_free'
            break
        released = t >= R['release']
        # ---- 夢魘 ----
        if nightmare and released and t >= nm_ready and not lights_out():
            nm_until = t + R['nm_dur']
            nm_ready = nm_until + R['nm_cd']
        # ---- 隨機斷電 ----
        if power and blackout_at is not None and blackouts < 1 and t >= blackout_at and t >= grace_until:
            power = False
            blackouts += 1
            stats['blackouts'] += 1
        # ---- 大門倒數 ----
        for g in gates:
            if g['remaining'] is not None:
                g['remaining'] -= DT
                if g['remaining'] <= 0:
                    g['remaining'] = None
                    g['opened'] = True
                    stats['gate_open'].append(round(t))
        # ---- 鬼的距離表（從鬼出發） ----
        gfield = lay.bfs([ghost.node], limit=70)
        # ---- 救援／守監獄 ----
        for j in lay.jails:
            near = lay.euclid(ghost.node, j) <= R['camp_radius'] and released
            if near:
                camp[j] += DT
                camp_away[j] = 0
            else:
                camp_away[j] += DT
                if camp_away[j] >= R['camp_recover']:
                    camp[j] = 0
            jailed = [s for s in survivors if s.status == 'jail' and s.jail == j]
            if not jailed:
                rescue_prog[j] = 0
                continue
            rescuers = [s for s in frees if s.node in lay.jail_zone[j]]
            if not rescuers:
                rescue_prog[j] = 0
                continue
            rescue_prog[j] += DT
            need = R['camp_rescue'] if camp[j] >= R['camp_time'] else R['rescue'][min(5, max(3, n_surv))]
            if rescue_prog[j] >= need:
                rescue_prog[j] = 0
                stats['rescues'] += 1
                for s in jailed:
                    s.status = 'free'
                    s.node = rescuers[0].node
                    s.protect = t + R['protect']
                    s.boost, s.boost_until = R['rescue_bonus'], t + R['rescue_boost']
        frees = free_list()

        # ---- 逃生者 ----
        claimed = set()
        for s in frees:
            gd = gfield[s.node]
            seen = released and lay.los(s.node, ghost.node, vis_range())
            close = released and gd <= 3
            danger = seen and gd <= 18 or close
            spd = SPEED * (1 + (s.boost if t < s.boost_until else 0))
            if danger:
                s.task = None
                if gd <= 4:
                    if s.items['smoke'] and t >= ghost.blind:
                        s.items['smoke'] -= 1
                        ghost.blind = t + R['smoke_blind']
                        ghost.busy = None
                        stats['smokes'] += 1
                    elif t >= s.dash_ready:
                        s.dash_ready = t + R['dash_cd']
                        s.boost, s.boost_until = R['dash_bonus'], t + R['dash']
                    elif s.items['speed'] and t >= s.boost_until:
                        s.items['speed'] -= 1
                        s.boost, s.boost_until = R['speed_bonus'], t + R['speed_time']
                spd = SPEED * (1 + (s.boost if t < s.boost_until else 0))

                def flee(node, s=s):
                    nb = lay.adj[node][:]
                    rnd.shuffle(nb)
                    if nb and rnd.random() < R['panic']:
                        return nb[0]
                    best, bd = node, gfield[node]
                    for v in nb:
                        if gfield[v] > bd:
                            best, bd = v, gfield[v]
                    return best
                move(s, spd, flee)
                continue
            # 正在做事
            if s.task:
                kind, target, done = s.task
                if kind == 'power':
                    rate = sum(1 for o in frees if o.task and o.task[0] == 'power') >= 2
                    done -= (R['coop'] - 1) * DT if rate else 0
                    s.task = (kind, target, done)
                if t >= done:
                    s.task = None
                    if kind == 'search':
                        c = content.pop(target, None)
                        searched.add(target)
                        locked.discard(target)
                        if c == 'card':
                            s.card += 1
                            if stats['first_card'] is None:
                                stats['first_card'] = round(t)
                        elif c in s.items:
                            s.items[c] += 1
                    elif kind == 'power':
                        if not power:
                            power = True
                            if not ever_on:
                                ever_on = True
                                stats['power_on'] = round(t)
                                blackout_at = t + rnd.uniform(*R['blackout'])
                            grace_until = t + R['grace']
                    elif kind == 'swipe':
                        g = gates[target]
                        if s.card and power:
                            s.card -= 1
                            g['cards'] += 1
                            if g['cards'] >= R['cards_per_gate']:
                                g['unlocked'] = True
                                g['remaining'] = R['countdown']
                    elif kind == 'restart':
                        gates[target]['remaining'] = R['countdown']
                else:
                    if int(t * 2) % 2 == 0:
                        make_noise(s.node)
                continue
            # 決定要做什麼
            goal = None
            opened = [g for g in gates if g['opened']]
            if opened:
                g = min(opened, key=lambda g: lay.fields[g['exit']][s.node])
                f = lay.fields[g['exit']]
                if f[s.node] == 0:
                    s.status = 'escaped'
                    stats['escaped'] += 1
                    if s.card:
                        drop_card(g['reader'], s.card)
                        s.card = 0
                    continue
                move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                continue
            jailed = [x for x in survivors if x.status == 'jail']
            if jailed:
                j = jailed[0].jail
                f = lay.fields[j]
                nearest = min(frees, key=lambda o: f[o.node])
                if nearest is s and lay.euclid(ghost.node, j) > 10:
                    if s.node not in lay.jail_zone[j]:
                        move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                    continue
            if not lever_known and lay.euclid(s.node, lever) <= R['discover']:
                lever_known = True
            if not power and lever_known:
                if False:
                    unchecked = [p for p in power_todo if p not in claimed] or list(power_todo)
                    p = min(unchecked, key=lambda p: lay.fields[p][s.node])
                    claimed.add(p)
                    f = lay.fields[p]
                    if f[s.node] <= 1:
                        if p == lever or lay.euclid(p, lever) <= 6:
                            lever_known = True
                        elif len(power_todo) > 1:
                            power_todo.discard(p)
                    move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                    if lay.euclid(s.node, lever) <= 6:
                        lever_known = True
                    continue
                f = lay.fields[lever]
                if f[s.node] <= 0:
                    s.task = ('power', lever, t + R['react'] + (R['power_repair'] if ever_on else R['power_on']))
                    make_noise(s.node)
                else:
                    move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                continue
            if s.card:
                cand = [i for i, g in enumerate(gates) if not g['opened'] and g['remaining'] is None and not g['unlocked']]
                if cand:
                    # 已經刷過卡的門優先（要湊滿張數），再挑近的
                    i = min(cand, key=lambda i: (-gates[i]['cards'], lay.fields[gates[i]['reader']][s.node]))
                    f = lay.fields[gates[i]['reader']]
                    if f[s.node] <= 0:
                        s.task = ('swipe' if not gates[i]['unlocked'] else 'restart', i, t + R['react'] + R['swipe'])
                        make_noise(s.node)
                    else:
                        move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                    continue
            sab = [i for i, g in enumerate(gates) if g['unlocked'] and not g['opened'] and g['remaining'] is None]
            if sab:
                i = sab[0]
                f = lay.fields[gates[i]['reader']]
                if f[s.node] <= 0:
                    s.task = ('restart', i, t + R['restart'])
                else:
                    move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))
                continue
            # 翻箱子
            todo = [c for c in lay.containers if c not in searched and c not in claimed]
            if not todo:
                todo = [c for c in lay.containers if content.get(c) == 'card'] or lay.containers
            if s.goal not in todo:
                todo.sort(key=lambda c: lay.fields[c][s.node])
                s.goal = rnd.choice(todo[:R['pick_spread']])
            c = s.goal
            claimed.add(c)
            f = lay.fields[c]
            if f[s.node] <= 0:
                s.task = ('search', c, t + R['react'] + (R['pry'] if c in locked else R['search']))
                make_noise(s.node)
            else:
                move(s, spd, lambda n, f=f: step_toward(lay, f, n, rnd))

        # ---- 鬼 ----
        if not released or t < ghost.blind:
            continue
        gspeed = SPEED * (R['fatigue_speed'] if t < ghost.fatigue else 1 + bonus)
        if ghost.busy:
            kind, target, done = ghost.busy
            if t >= done:
                ghost.busy = None
                if kind == 'sabotage':
                    g = gates[target]
                    if g['remaining'] is not None:
                        g['remaining'] = None
                        g['sabotaged'] = True
                        stats['sabotages'] += 1
                elif kind == 'cut':
                    if power:
                        power = False
                        ghost.cut_used = True
                        stats['cuts'] += 1
                elif kind == 'move':
                    srcs = [c for c, v in content.items() if v == 'card' and moves.get(c, 0) < 1]
                    if srcs:
                        src = min(srcs, key=lambda c: gfield[c] if gfield[c] < INF else 999)
                        dests = [c for c in lay.containers if c not in content]
                        if dests:
                            dst = max(dests, key=lambda c: min([lay.euclid(c, o.node) if lay.euclid(c, o.node) < INF else 80 for o in frees] or [0]))
                            del content[src]
                            content[dst] = 'card'
                            moves[dst] = moves.get(src, 0) + 1
                            locked.add(dst)
                            searched.discard(dst)
                            stats['moves'] += 1
            continue
        # 看得到的人（熄燈時 12 格內看得到軌跡；15 分鐘後全部發光）
        targets = []
        for s in frees:
            if lights_out() and lay.euclid(s.node, ghost.node) <= R['nm_trail']:
                targets.append(s)
            elif t >= R['glow'] or lay.los(ghost.node, s.node, vis_range()):
                targets.append(s)
        if targets:
            far = lay.bfs([ghost.node]) if t >= R['glow'] else gfield
            s = min(targets, key=lambda s: far[s.node])
            ghost.last_seen = (s.node, t)
            if far[s.node] <= 2 and lay.euclid(s.node, ghost.node) <= 3 and t >= ghost.fatigue and t >= ghost.cap_ready:
                ghost.fatigue = t + R['fatigue']
                if t >= s.protect and rnd.random() < R['hit_chance']:
                    ghost.cap_ready = t + R['capture_cd']
                    capture(s)
                continue
            # 從被追的人反過來算距離表，鬼一步一步往數字小的地方走（一步內可以走好幾格）
            toward = lay.bfs([s.node], limit=INF if t >= R['glow'] else 80)
            move(ghost, gspeed, lambda n, f=toward: step_toward(lay, f, n, rnd))
            continue
        # 搬卡
        if t >= next_reveal:
            next_reveal = t + R['reveal']
            if any(v == 'card' and moves.get(c, 0) < 1 for c, v in content.items()):
                ghost.busy = ('move', None, t + R['move_card'])
                continue
        # 弄壞大門（聽到警報）
        running = [i for i, g in enumerate(gates) if g['remaining'] is not None and not g['sabotaged']]
        if running:
            i = running[0]
            f = lay.fields[gates[i]['reader']]
            if f[ghost.node] <= 0:
                ghost.busy = ('sabotage', i, t + R['sabotage'])
            else:
                move(ghost, gspeed, lambda n, f=f: step_toward(lay, f, n, rnd))
            continue
        # 關電源（一局一次）
        if power and not ghost.cut_used and t >= grace_until and lay.fields[lever][ghost.node] < 30:
            f = lay.fields[lever]
            if f[ghost.node] <= 0:
                ghost.busy = ('cut', None, t + R['cut'])
            else:
                move(ghost, gspeed, lambda n, f=f: step_toward(lay, f, n, rnd))
            continue
        # 聽到聲音／剛剛看到的人
        mem = None
        if ghost.noise and t - ghost.noise[1] < 4:
            mem = ghost.noise[0]
        elif ghost.last_seen and t - ghost.last_seen[1] < 5:
            mem = ghost.last_seen[0]
        if mem is not None and gfield[mem] < INF and mem != ghost.node:
            toward = lay.bfs([mem], limit=80)
            move(ghost, gspeed, lambda n, f=toward: step_toward(lay, f, n, rnd))
            continue
        # 守監獄（有人被關時一半的機率在附近晃）
        jailed = [x for x in survivors if x.status == 'jail']
        if jailed and rnd.random() < 0.5:
            f = lay.fields[jailed[0].jail]
            if f[ghost.node] > 6:
                move(ghost, gspeed, lambda n, f=f: step_toward(lay, f, n, rnd))
                continue
        # 巡邏：隨便挑一個箱子走過去
        if ghost.wander is None or lay.fields[ghost.wander][ghost.node] <= 1:
            ghost.wander = rnd.choice(lay.containers)
        f = lay.fields[ghost.wander]
        move(ghost, gspeed, lambda n, f=f: step_toward(lay, f, n, rnd))

    stats['end'] = round(t)
    stats['reason'] = end_reason
    stats['jailed_end'] = sum(1 for s in survivors if s.status == 'jail')
    stats['free_end'] = sum(1 for s in survivors if s.status == 'free')
    return stats


_LAYOUTS = []


def _init(paths, overrides=None):
    global _LAYOUTS
    if overrides:
        for k, v in overrides.items():
            R[k] = {int(a): b for a, b in v.items()} if isinstance(v, dict) and all(a.isdigit() for a in v) else v
    _LAYOUTS = [Layout(json.loads(Path(p).read_text(encoding='utf-8'))) for p in paths]


def _run(args):
    li, n, nm, seed = args
    lay = _LAYOUTS[li]
    return (n, nm, simulate(lay, n, nm, seed))


def main():
    folder = Path(sys.argv[1])
    games = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    overrides = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None   # 例如 '{"search": 3, "countdown": 40}'
    paths = sorted(str(p) for p in folder.glob('*.json'))
    jobs = []
    k = 0
    for n in (3, 4, 5):
        for nm in (False, True):
            for g in range(games):
                jobs.append((g % len(paths), n, nm, 1000 + k))
                k += 1
    with Pool(initializer=_init, initargs=(paths, overrides)) as pool:
        results = pool.map(_run, jobs, chunksize=2)
    out = {}
    for n, nm, st in results:
        out.setdefault((n, nm), []).append(st)
    print(json.dumps({f'{n}_{"nightmare" if nm else "basic"}': v for (n, nm), v in out.items()}, ensure_ascii=False))


if __name__ == '__main__':
    main()
