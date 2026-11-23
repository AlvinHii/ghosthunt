"""
地圖 → 模擬器用的世界（每層一張格子圖，1 格 = 1 方塊）。

讀插件附的固定地圖（src/main/resources/pve-maps/<名字>.txt，格式見 docs/PvE地圖格式.md），算出：
- 走得到的格子（含樓梯、地下通道）和它們之間的距離（全部兩兩之間的走路距離，存成快取）
- 擋視線的格子（牆、門、2 格以上的家具；矮欄杆、1 格高的家具看得穿）
- 區域（房間）：被門、開口、樓梯切開的連通塊，跟插件的「區域圖」同一個想法
- 興趣點：電源候選 E、終端機候選 Q、大門候選 G、監獄 J、地下通道、暗格、樓梯
"""
import hashlib
import os
import sys
from collections import deque

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import pve_map_check as pmc  # noqa: E402

CACHE = os.path.join(os.environ.get('LOCALAPPDATA', os.path.expanduser('~')), 'ghosthunt-ai')

BLOCK = set('#%+FRNMAZC')            # 擋路（C 可推倒的櫃子、F 配電盤、R 無線電、N 講台、M 螢幕、A 廣播台、Z 配電箱）
OPAQUE = set('#%DWhHFMZAC')          # 擋視線（門平常是關的；h／H 上面是牆）
CLOSED = set('TU')                   # 鐵捲門、上鎖的門：預設關著（支線還沒做）
ARROWS = {'>': (1, 0), '<': (-1, 0), '^': (0, -1), 'v': (0, 1), 'V': (0, 1)}
DIRS4 = ((1, 0), (-1, 0), (0, 1), (0, -1))
SPLIT = set('DWOIhHGTU')             # 分區域的格子（門口）


class World:
    def __init__(self, name):
        self.name = name
        path = os.path.join(ROOT, 'src', 'main', 'resources', 'pve-maps', name + '.txt')
        meta, floors, furn, pal = pmc.parse(path)
        self.meta = meta
        self.m = pmc.Map(floors)
        self.F, self.W, self.H = self.m.F, self.m.W, self.m.H
        self.furn_h = {}
        for k, v in furn.items():
            self.furn_h[k] = len([s for s in v.split('|')[0].split(',') if s.strip()])
        self.barrel_count = int(meta.get('barrels', 30))
        self._build()

    # ---- 格子 ----

    def ch(self, f, x, z):
        return self.m.at(f, x, z)

    def _walk_char(self, f, x, z):
        c = self.ch(f, x, z)
        if c == ' ' or c in BLOCK or c in CLOSED or c == 'J':
            return False
        if c.islower() and c not in 'hv':
            return False  # 家具
        if c == '=':
            return False  # 樓梯洞（樓梯頂另外處理）
        return True

    def _build(self):
        F, W, H = self.F, self.W, self.H
        walk = np.zeros((F, H, W), bool)
        opaque = np.zeros((F, H, W), bool)
        for f in range(F):
            for z in range(H):
                for x in range(W):
                    c = self.ch(f, x, z)
                    walk[f, z, x] = self._walk_char(f, x, z)
                    if c in OPAQUE or c == ' ':
                        opaque[f, z, x] = True
                    elif c.islower() and c not in 'hv' and self.furn_h.get(c, 1) >= 2:
                        opaque[f, z, x] = True
        # 蹲伏小洞、暗格：鬼過不去（模擬器的路一律照鬼的走法算，逃生者另外可以鑽）
        self.holes = [(f, x, z) for f in range(F) for z in range(H) for x in range(W) if self.ch(f, x, z) == 'h']
        self.hideouts = []
        for f in range(F):
            for z in range(H):
                for x in range(W):
                    if self.ch(f, x, z) != 'H':
                        continue
                    walk[f, z, x] = False
                    for dx, dz in DIRS4:
                        if walk[f, z + dz, x + dx] if 0 <= z + dz < H and 0 <= x + dx < W else False:
                            self.hideouts.append(((f, x, z), (f, x + dx, z + dz)))  # (暗格, 入口外那格)
                            break
        for (f, x, z) in self.holes:
            walk[f, z, x] = False
        # 樓梯：1F 的箭頭，最後一格（往上的那一端）其實是 2F 的高度
        links = []
        self.stairs = []
        for z in range(H):
            for x in range(W):
                c = self.ch(0, x, z)
                if c not in ARROWS:
                    continue
                dx, dz = ARROWS[c]
                if self.ch(0, x + dx, z + dz) == c:
                    continue
                if F < 2:
                    continue
                walk[0, z, x] = False
                walk[1, z, x] = True
                links.append(((0, x - dx, z - dz), (1, x, z), 1.0))
                self.stairs.append(((0, x - dx, z - dz), (1, x, z)))
        # 地下通道：同數字一對（逃生者自由進出；鬼要先撬開，距離另外加）
        ends = {}
        for z in range(H):
            for x in range(W):
                c = self.ch(0, x, z)
                if c.isdigit() and c != '0':
                    ends.setdefault(c, []).append((0, x, z))
        self.tunnels = []
        for k, v in sorted(ends.items()):
            if len(v) == 2:
                a, b = v
                d = abs(a[1] - b[1]) + abs(a[2] - b[2])
                links.append((a, b, d * 1.3 + 6))
                self.tunnels.append((a, b))
        self.walk, self.opaque = walk, opaque
        # 節點編號
        idx = -np.ones((F, H, W), np.int32)
        nodes = np.argwhere(walk)
        for i, (f, z, x) in enumerate(nodes):
            idx[f, z, x] = i
        self.idx = idx
        self.nodes = nodes  # [i] = (f, z, x)
        self.N = len(nodes)
        self.links = links
        # 相鄰（4 方向，同一層）
        nb = [[] for _ in range(self.N)]
        for i, (f, z, x) in enumerate(nodes):
            for dx, dz in DIRS4:
                xx, zz = x + dx, z + dz
                if 0 <= xx < W and 0 <= zz < H and idx[f, zz, xx] >= 0:
                    nb[i].append(int(idx[f, zz, xx]))
        for a, b, w in links:
            ia, ib = self.node(*a), self.node(*b)
            if ia >= 0 and ib >= 0:
                nb[ia].append(ib)
                nb[ib].append(ia)
        self.nb = [np.array(n, np.int32) for n in nb]
        self._regions()
        self._pois()
        self._dist()

    def node(self, f, x, z):
        if 0 <= f < self.F and 0 <= x < self.W and 0 <= z < self.H:
            return int(self.idx[f, z, x])
        return -1

    def pos(self, i):
        f, z, x = self.nodes[i]
        return int(f), int(x), int(z)

    # ---- 區域 ----

    def _regions(self):
        """被門口（D W O I h H G T U）和樓梯切開的連通塊。門口格子歸到旁邊最大的區域。"""
        reg = -np.ones(self.N, np.int32)
        rid = 0
        sizes = []
        for s in range(self.N):
            if reg[s] >= 0:
                continue
            f, x, z = self.pos(s)
            if self.ch(f, x, z) in SPLIT:
                continue
            q = deque([s])
            reg[s] = rid
            n = 0
            while q:
                i = q.popleft()
                n += 1
                fi, xi, zi = self.pos(i)
                for dx, dz in DIRS4:
                    j = self.node(fi, xi + dx, zi + dz)
                    if j >= 0 and reg[j] < 0 and self.ch(fi, xi + dx, zi + dz) not in SPLIT:
                        reg[j] = rid
                        q.append(j)
            sizes.append(n)
            rid += 1
        # 門口和樓梯頂：歸到相鄰的區域
        for _ in range(3):
            for i in range(self.N):
                if reg[i] >= 0:
                    continue
                for j in self.nb[i]:
                    if reg[j] >= 0:
                        reg[i] = reg[j]
                        break
        reg[reg < 0] = 0
        self.reg = reg
        self.R = rid
        # 區域的中心（離重心最近、屬於該區域的格子）、樓層、大小、相鄰
        self.reg_center = np.zeros(self.R, np.int32)
        self.reg_floor = np.zeros(self.R, np.int32)
        self.reg_size = np.array(sizes + [0] * (self.R - len(sizes)), np.int32)
        for r in range(self.R):
            members = np.where(reg == r)[0]
            if len(members) == 0:
                continue
            pts = self.nodes[members]
            cz, cx = pts[:, 1].mean(), pts[:, 2].mean()
            k = np.argmin((pts[:, 1] - cz) ** 2 + (pts[:, 2] - cx) ** 2)
            self.reg_center[r] = members[k]
            self.reg_floor[r] = pts[k, 0]
        adj = set()
        for i in range(self.N):
            for j in self.nb[i]:
                a, b = int(reg[i]), int(reg[j])
                if a != b:
                    adj.add((min(a, b), max(a, b)))
        self.reg_adj = [[] for _ in range(self.R)]
        for a, b in adj:
            self.reg_adj[a].append(b)
            self.reg_adj[b].append(a)
        self.reg_dead_end = np.array([len(a) <= 1 for a in self.reg_adj])

    # ---- 興趣點 ----

    def _pois(self):
        F, W, H = self.F, self.W, self.H
        self.power_cands, self.term_cands, self.spawns = [], [], []
        gate_tiles = []
        self.jails = []
        jt = []
        for f in range(F):
            for z in range(H):
                for x in range(W):
                    c = self.ch(f, x, z)
                    if c == 'E' and self.node(f, x, z) >= 0:
                        self.power_cands.append(self.node(f, x, z))
                    elif c == 'Q' and self.node(f, x, z) >= 0:
                        self.term_cands.append(self.node(f, x, z))
                    elif c == 'S' and self.node(f, x, z) >= 0:
                        self.spawns.append(self.node(f, x, z))
                    elif c == 'G':
                        gate_tiles.append((f, x, z))
                    elif c == 'J':
                        jt.append((f, x, z))
        # 監獄：連在一起的 J 一間；救人站在外面 4.6 格內
        seen = set()
        for t in jt:
            if t in seen:
                continue
            comp, q = [], deque([t])
            seen.add(t)
            while q:
                c = q.popleft()
                comp.append(c)
                for dx, dz in DIRS4:
                    n = (c[0], c[1] + dx, c[2] + dz)
                    if n in jt and n not in seen:
                        seen.add(n)
                        q.append(n)
            f = comp[0][0]
            cx = sum(c[1] for c in comp) / len(comp)
            cz = sum(c[2] for c in comp) / len(comp)
            # 外面站得到、最靠近中心的格子
            best, bd = -1, 1e9
            for (ff, x, z) in [(f, int(cx) + dx, int(cz) + dz) for dx in range(-5, 6) for dz in range(-5, 6)]:
                i = self.node(ff, x, z)
                if i >= 0:
                    d = (x - cx) ** 2 + (z - cz) ** 2
                    if d < bd:
                        best, bd = i, d
            self.jails.append({'floor': f, 'cx': cx + 0.5, 'cz': cz + 0.5, 'stand': best})
        # 大門候選：連續 3 格一扇；讀卡機在中間那格往內 2 格
        self.gate_cands = []
        gs = set(gate_tiles)
        used = set()
        for t in gate_tiles:
            if t in used:
                continue
            f, x, z = t
            for dx, dz in ((1, 0), (0, 1)):
                trio = [(f, x + k * dx, z + k * dz) for k in range(3)]
                if all(p in gs for p in trio):
                    mid = trio[1]
                    # 往內：哪一邊是場地內
                    inward = None
                    for sx, sz in ((dz, dx), (-dz, -dx)):
                        if self.node(f, mid[1] + 2 * sx, mid[2] + 2 * sz) >= 0:
                            inward = (sx, sz)
                    if inward is None:
                        continue
                    reader = self.node(f, mid[1] + 2 * inward[0], mid[2] + 2 * inward[1])
                    exit_ = self.node(*mid)
                    self.gate_cands.append({'floor': f, 'exit': exit_, 'reader': reader, 'tiles': trio})
                    used.update(trio)
                    break

    # ---- 距離（全部兩兩之間，存成快取）----

    def _dist(self):
        os.makedirs(CACHE, exist_ok=True)
        h = hashlib.sha1(open(os.path.join(ROOT, 'src', 'main', 'resources', 'pve-maps', self.name + '.txt'), 'rb').read()).hexdigest()[:10]
        path = os.path.join(CACHE, f'{self.name}_{h}_dist.npy')
        if not os.path.exists(path):
            from scipy.sparse import csr_matrix
            from scipy.sparse.csgraph import dijkstra
            rows, cols, w = [], [], []
            link_w = {}
            for a, b, ww in self.links:
                ia, ib = self.node(*a), self.node(*b)
                link_w[(ia, ib)] = link_w[(ib, ia)] = ww
            for i in range(self.N):
                for j in self.nb[i]:
                    rows.append(i)
                    cols.append(int(j))
                    w.append(link_w.get((i, int(j)), 1.0))
            g = csr_matrix((w, (rows, cols)), shape=(self.N, self.N))
            out = np.lib.format.open_memmap(path + '.tmp', mode='w+', dtype=np.float16, shape=(self.N, self.N))
            step = 512
            for s in range(0, self.N, step):
                d = dijkstra(g, directed=False, indices=np.arange(s, min(self.N, s + step)))
                d[np.isinf(d)] = 60000
                out[s:s + step] = d.astype(np.float16)
            out.flush()
            del out
            os.replace(path + '.tmp', path)
        self.dist = np.load(path, mmap_mode='r')

    # ---- 視線 ----

    def los(self, a, b):
        """a、b 兩個節點同一層、中間沒有擋視線的格子（Bresenham）。"""
        fa, xa, za = self.pos(a)
        fb, xb, zb = self.pos(b)
        if fa != fb:
            return False
        dx, dz = abs(xb - xa), abs(zb - za)
        sx, sz = (1 if xb > xa else -1), (1 if zb > za else -1)
        err = dx - dz
        x, z = xa, za
        op = self.opaque[fa]
        while (x, z) != (xb, zb):
            e2 = 2 * err
            if e2 > -dz:
                err -= dz
                x += sx
            if e2 < dx:
                err += dx
                z += sz
            if (x, z) != (xb, zb) and op[z, x]:
                return False
        return True

    def summary(self):
        return (f'{self.name}：{self.F} 層 {self.W}×{self.H}，走得到 {self.N} 格，區域 {self.R} 個（死路 {int(self.reg_dead_end.sum())}），'
                f'電源候選 {len(self.power_cands)}、終端機候選 {len(self.term_cands)}、大門候選 {len(self.gate_cands)}、'
                f'監獄 {len(self.jails)}、地下通道 {len(self.tunnels)}、暗格 {len(self.hideouts)}、樓梯 {len(self.stairs)}')


if __name__ == '__main__':
    import time
    sys.stdout.reconfigure(encoding='utf-8')
    for n in sys.argv[1:] or ['mall2', 'hospital']:
        t = time.time()
        w = World(n)
        print(w.summary(), f'（{time.time() - t:.1f} 秒）')
        # 連通檢查：從第一個區域中心走得到多少格
        d = np.asarray(w.dist[w.reg_center[0]])
        print('  從區域 0 走得到：', int((d < 60000).sum()), '/', w.N)
