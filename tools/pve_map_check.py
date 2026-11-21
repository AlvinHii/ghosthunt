"""
PvE 固定地圖檢查（src/main/resources/pve-maps/*.txt 的格式，見 docs/PvE地圖格式.md）。

用法：python tools/pve_map_check.py <地圖檔> [--png 輸出.png]

檢查：格式（行寬、層數對齊）、符號、樓梯、地下通道配對、大門在外圈、保全室候選在室內、
監獄四周、暗格、全部走得到（含樓梯上下）、每區出口數、長直線視野、空曠區域、數量需求。
錯誤（ERROR）讀取器會拒絕或修正；警告（WARN）是設計上的建議。
"""
import sys
from collections import deque, Counter

sys.stdout.reconfigure(encoding='utf-8')

WALLS = set('#%+')
OPENINGS = set('DWOIhHG')
ARROWS = {'>': (1, 0), '<': (-1, 0), '^': (0, -1), 'v': (0, 1)}
MARKERS = set('SKJEQC123456789')
FLOORS = set('.,;') | MARKERS
FURN_RESERVED = set('h')


def is_furn(c):
    return c.islower() and c not in FURN_RESERVED


def parse(path):
    meta, floors, furn, pal = {}, [], {}, {}
    sec = None
    cur = None
    with open(path, encoding='utf-8') as f:
        for raw in f:
            line = raw.rstrip('\r\n')
            st = line.strip()
            if st.startswith('[') and st.endswith(']'):
                sec = st[1:-1].strip()
                if sec.upper().endswith('F') and sec[:-1].isdigit():
                    cur = []
                    floors.append(cur)
                    sec = 'map'
                continue
            if sec == 'map':
                if st == '' and not cur:
                    continue
                cur.append(line)
                continue
            if '#' in line and sec not in ('map',):
                line = line.split('#', 1)[0]
            st = line.strip()
            if not st:
                continue
            if sec is None:
                if ':' in st:
                    k, v = st.split(':', 1)
                    meta[k.strip()] = v.strip()
            elif sec == 'furniture':
                k, v = st.split('=', 1)
                furn[k.strip()] = v.strip()
            elif sec == 'palette':
                k, v = st.split('=', 1)
                pal[k.strip()] = v.strip()
    # 去掉每層結尾的空行
    for fl in floors:
        while fl and fl[-1].strip() == '':
            fl.pop()
    return meta, floors, furn, pal


class Map:
    def __init__(self, floors):
        self.H = max(len(f) for f in floors)
        self.W = max(max((len(r) for r in f), default=0) for f in floors)
        self.g = []
        for f in floors:
            rows = [r.ljust(self.W) for r in f] + [' ' * self.W] * (self.H - len(f))
            self.g.append(rows)
        self.F = len(floors)

    def at(self, f, x, z):
        if f < 0 or f >= self.F or x < 0 or z < 0 or x >= self.W or z >= self.H:
            return ' '
        return self.g[f][z][x]


def main():
    args = sys.argv[1:]
    png = None
    if '--png' in args:
        i = args.index('--png')
        png = args[i + 1]
        del args[i:i + 2]
    meta, floors, furn, pal = parse(args[0])
    errors, warns = [], []
    E = errors.append
    Wn = warns.append
    if not floors:
        print('ERROR 沒有任何一層')
        return 1
    for i, fl in enumerate(floors):
        lens = Counter(len(r) for r in fl if r.strip() and not r.endswith(' '))
        if len(lens) > 1 and max(lens) - min(lens) > 0 and len([1 for r in fl if len(r) == max(lens)]) < len(fl) // 2:
            Wn(f'{i + 1}F 每行長度不一樣：{dict(lens)}（讀取器會用空白補齊）')
    m = Map(floors)
    print(f'地圖：{meta.get("name", "?")}（{meta.get("type", "?")}），{m.W}×{m.H}，{m.F} 層')

    # ---- 符號 ----
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                if c == ' ' or c in WALLS or c in OPENINGS or c in ARROWS or c == '=' or c in FLOORS:
                    continue
                if is_furn(c):
                    if c not in furn:
                        E(f'{f + 1}F ({x},{z}) 家具 {c} 沒有定義')
                    continue
                E(f'{f + 1}F ({x},{z}) 不認識的符號 {c!r}')

    def walkable_char(c):
        return c in FLOORS or c in ARROWS

    def passable_char(c):  # 走路時過得去（門、開口算）
        return walkable_char(c) or c in 'DWOG' or c == '='

    # ---- 樓梯 ----
    stairs = []  # (f, tiles[(x,z,i)], dir)
    seen = set()
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                if c not in ARROWS or (f, x, z) in seen:
                    continue
                comp, q = [], deque([(x, z)])
                seen.add((f, x, z))
                while q:
                    a, b = q.popleft()
                    comp.append((a, b))
                    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        n = (a + dx, b + dz)
                        if m.at(f, *n) == c and (f, *n) not in seen:
                            seen.add((f, *n))
                            q.append(n)
                d = ARROWS[c]
                along = [a * d[0] + b * d[1] for a, b in comp]
                lo, hi = min(along), max(along)
                lanes = Counter((b if d[0] else a) for a, b in comp)
                if hi - lo + 1 != 5 or len(comp) != 10 or len(lanes) != 2:
                    E(f'{f + 1}F 樓梯 {c} 在 ({x},{z}) 形狀不對：要 5 格長 × 2 格寬，現在 {len(comp)} 格')
                    continue
                if f + 1 >= m.F:
                    E(f'{f + 1}F 樓梯 ({x},{z}) 上面沒有樓層')
                    continue
                tiles = [(a, b, (a * d[0] + b * d[1]) - lo) for a, b in comp]
                for a, b, i in tiles:
                    if m.at(f + 1, a, b) != '=':
                        E(f'{f + 2}F ({a},{b}) 應該是 =（樓梯開口）')
                # 底部平台、頂端平台
                for a, b, i in tiles:
                    if i == 0:
                        bx, bz = a - d[0], b - d[1]
                        if not walkable_char(m.at(f, bx, bz)):
                            E(f'{f + 1}F 樓梯底部 ({bx},{bz}) 要是地面，現在是 {m.at(f, bx, bz)!r}')
                    if i == 4:
                        tx, tz = a + d[0], b + d[1]
                        if not walkable_char(m.at(f + 1, tx, tz)):
                            E(f'{f + 2}F 樓梯頂端 ({tx},{tz}) 要是地面，現在是 {m.at(f + 1, tx, tz)!r}')
                stairs.append((f, tiles, d))
    for f in range(1, m.F):
        for z in range(m.H):
            for x in range(m.W):
                if m.at(f, x, z) == '=' and m.at(f - 1, x, z) not in ARROWS:
                    E(f'{f + 1}F ({x},{z}) 有 = 但下面不是樓梯')

    hole = set()  # (f, x, z) 樓梯開口（前 4 格），2F 不能站
    stair_top = set()
    for f, tiles, d in stairs:
        for a, b, i in tiles:
            if i < 4:
                hole.add((f + 1, a, b))
            else:
                stair_top.add((f + 1, a, b))

    # ---- 走路圖（含樓梯） ----
    def stand(f, x, z):
        c = m.at(f, x, z)
        if (f, x, z) in hole:
            return False
        if c == '=':
            return (f, x, z) in stair_top
        return walkable_char(c) or c in 'DWOG'

    def neighbors(node, allow_crawl=False, allow_doors=True):
        f, x, z = node
        out = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            if stand(f, nx, nz) and (allow_doors or m.at(f, nx, nz) not in 'DW'):
                out.append((f, nx, nz))
            elif allow_crawl and m.at(f, nx, nz) in 'hH':
                out.append((f, nx, nz))
        c = m.at(f, x, z)
        return out

    nodes = set()
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                if stand(f, x, z) and not is_furn(m.at(f, x, z)):
                    nodes.add((f, x, z))
    # 家具不能站
    def ok(n):
        return n in nodes

    adj = {n: [] for n in nodes}
    for n in nodes:
        for nb in neighbors(n):
            if ok(nb):
                adj[n].append(nb)
    # 樓梯：1F 樓梯格之間本來就相鄰；頂端那格接到 2F 的平台
    for f, tiles, d in stairs:
        for a, b, i in tiles:
            if i == 4:
                lo = (f, a, b)
                up = (f + 1, a + d[0], b + d[1])
                if lo in adj and up in adj:
                    adj[lo].append(up)
                    adj[up].append(lo)
    # 地下通道
    hatch = {}
    for z in range(m.H):
        for x in range(m.W):
            c = m.at(0, x, z)
            if c.isdigit():
                hatch.setdefault(c, []).append((0, x, z))
    for k, v in hatch.items():
        if len(v) != 2:
            E(f'地下通道 {k} 要剛好 2 個出入口，現在 {len(v)} 個')
            continue
        (fa, ax, az), (fb, bx, bz) = v
        dist = abs(ax - bx) + abs(az - bz)
        if dist < 20:
            Wn(f'地下通道 {k} 兩端只相距 {dist} 格（建議 ≥ 20）')
        adj[v[0]].append(v[1])
        adj[v[1]].append(v[0])
    for f in range(1, m.F):
        for z in range(m.H):
            for x in range(m.W):
                if m.at(f, x, z).isdigit():
                    E(f'{f + 1}F ({x},{z}) 地下通道出入口只能在 1F')

    # ---- 監獄、鬼房（不算在一般走得到的範圍） ----
    def region_of(ch):
        return {n for n in nodes if m.at(*n) == ch}

    jail = region_of('J')
    ghost = region_of('K')
    if not jail:
        E('沒有監獄 J')
    if not ghost:
        E('沒有鬼房 K')
    if len({n[0] for n in jail}) > 1:
        E('監獄 J 要在同一層')
    # J 外圍一圈會蓋鐵欄杆：要是地面；再外面一圈要站得到人
    ring, ring2 = set(), set()
    for f, x, z in jail:
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                n = (f, x + dx, z + dz)
                if n not in jail:
                    ring.add(n)
    for n in ring:
        c = m.at(*n)
        if not (walkable_char(c) and c not in MARKERS or c in '.,;'):
            E(f'{n[0] + 1}F 監獄 J 外圍 ({n[1]},{n[2]}) 要是地面（會放鐵欄杆），現在是 {c!r}')
    for f, x, z in ring:
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (f, x + dx, z + dz)
            if n not in ring and n not in jail:
                ring2.add(n)
    if jail and not any(n in nodes for n in ring2):
        E('監獄外面沒有地方站著救人')
    # 鬼房要有鐵欄杆門
    if ghost:
        has_bar = False
        for f, x, z in ghost:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if m.at(f, x + dx, z + dz) == 'I':
                    has_bar = True
        if not has_bar:
            E('鬼房 K 旁邊沒有鐵欄杆門 I')
        for f, x, z in ghost:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                c = m.at(f, x + dx, z + dz)
                if c not in 'KI#%' and not is_furn(c):
                    E(f'鬼房 K ({x},{z}) 旁邊 ({x + dx},{z + dz}) 是 {c!r}：鬼房要用牆和 I 圍起來')

    # ---- 連通 ----
    start = None
    for n in sorted(nodes):
        if m.at(*n) == 'S':
            start = n
            break
    if start is None:
        E('沒有出生點 S')
        start = next(iter(sorted(nodes)))
    excluded = jail | ring
    seen = {start}
    q = deque([start])
    while q:
        n = q.popleft()
        for nb in adj[n]:
            if nb not in seen and nb not in excluded and m.at(*nb) not in 'K':
                seen.add(nb)
                q.append(nb)
    # 鐵欄杆門 I 鬼房那側也要接回主要區域（鬼放出來後）
    unreach = [n for n in nodes if n not in seen and n not in excluded and m.at(*n) != 'K']
    if unreach:
        E(f'有 {len(unreach)} 格走不到，例如 ' + ', '.join(f'{f + 1}F({x},{z})' for f, x, z in sorted(unreach)[:6]))

    # ---- 門、開口 ----
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                if c not in 'DWOIhG':
                    continue
                ew = stand(f, x - 1, z) or m.at(f, x - 1, z) in 'K' or is_furn(m.at(f, x - 1, z)), stand(f, x + 1, z) or m.at(f, x + 1, z) == 'K'
                ns = stand(f, x, z - 1) or m.at(f, x, z - 1) == 'K', stand(f, x, z + 1) or m.at(f, x, z + 1) == 'K'
                if c == 'G':
                    out = [(dx, dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if m.at(f, x + dx, z + dz) == ' ']
                    inn = [(dx, dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if stand(f, x + dx, z + dz)]
                    if not out or not inn:
                        E(f'{f + 1}F 大門候選 G ({x},{z}) 要在最外圈（一邊是場地外、一邊是地面）')
                    else:
                        dx, dz = out[0]
                        rx, rz = x - 2 * dx, z - 2 * dz
                        if not stand(f, rx, rz) or is_furn(m.at(f, rx, rz)):
                            E(f'{f + 1}F 大門候選 G ({x},{z}) 往內 2 格 ({rx},{rz}) 要是空地（放讀卡機）')
                    continue
                if not (all(ew) or all(ns)):
                    E(f'{f + 1}F {c} ({x},{z}) 兩側要是地面（現在 東西 {ew} 南北 {ns}）')
    # 暗格
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                if m.at(f, x, z) != 'H':
                    continue
                fl = [(dx, dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if stand(f, x + dx, z + dz)]
                if len(fl) != 1:
                    E(f'{f + 1}F 暗格 H ({x},{z}) 旁邊要剛好 1 格地面（現在 {len(fl)}）')
                    continue
                dx, dz = fl[0]
                px, pz = x - dx, z - dz
                if m.at(f, px, pz) not in '#%':
                    E(f'{f + 1}F 暗格 H ({x},{z}) 後面 ({px},{pz}) 要是牆 # 或樹籬 %（會挖成躲藏空間）')
                    continue
                for ex, ez in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if (px + ex, pz + ez) == (x, z):
                        continue
                    if stand(f, px + ex, pz + ez):
                        E(f'{f + 1}F 暗格 H ({x},{z}) 的躲藏空間 ({px},{pz}) 旁邊有地面 ({px + ex},{pz + ez})，會被看穿')

    # ---- 區域（房間／戶外空地）：用牆、門、開口切開 ----
    regions = []
    rid = {}
    for n in sorted(nodes):
        if n in rid or m.at(*n) in 'DWOG':
            continue
        comp, q = [], deque([n])
        rid[n] = len(regions)
        while q:
            a = q.popleft()
            comp.append(a)
            f, x, z = a
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                b = (f, x + dx, z + dz)
                c = m.at(*b)
                if b in rid or c in 'DWOGhH' or c in WALLS or c == ' ':
                    continue
                if not (stand(*b) or is_furn(c) or c in ARROWS or c == '='):
                    continue
                rid[b] = len(regions)
                q.append(b)
        regions.append(comp)
    # 出口
    exits = Counter()
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                if c not in 'DWOhI':
                    continue
                sides = set()
                for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    b = (f, x + dx, z + dz)
                    if b in rid:
                        sides.add(rid[b])
                for r in sides:
                    exits[r] += 1
    hatch_regions = Counter(rid.get(v, -1) for vs in hatch.values() for v in vs)
    stair_regions = set()
    for f, tiles, d in stairs:
        for a, b, i in tiles:
            if (f, a, b) in rid:
                stair_regions.add(rid[(f, a, b)])
            if (f + 1, a, b) in rid:
                stair_regions.add(rid[(f + 1, a, b)])
    print('\n區域：')
    for i, comp in enumerate(regions):
        cs = Counter(m.at(*n) for n in comp)
        f = comp[0][0]
        if cs.get('J') or cs.get('K'):
            kind = '監獄' if cs.get('J') else '鬼房'
        else:
            kind = '室內' if cs.get('.', 0) >= cs.get(',', 0) + cs.get(';', 0) else '戶外'
        xs = [n[1] for n in comp]
        zs = [n[2] for n in comp]
        ex = exits[i] + hatch_regions.get(i, 0) + (1 if i in stair_regions else 0)
        tags = ''.join(sorted(set(c for c in cs if c in 'QESC123456789')))
        print(f'  #{i:2} {f + 1}F {kind} {len(comp):4} 格 x{min(xs)}-{max(xs)} z{min(zs)}-{max(zs)} 出口 {ex} {tags}')
        if kind in ('室內', '戶外') and ex <= 1 and len(comp) > 6:
            Wn(f'{f + 1}F 區域 #{i}（x{min(xs)}-{max(xs)} z{min(zs)}-{max(zs)}）只有 {ex} 個出口（死路）')
        if kind == '室內' and len(comp) > 200:
            Wn(f'{f + 1}F 室內區域 #{i} 有 {len(comp)} 格，偏大')

    # ---- 視野：看得穿的格子連成的直線 ----
    def see(c):
        return c in FLOORS or c in ARROWS or c in 'OG=+' or c == ' ' and False or (is_furn(c) and fh.get(c, 3) <= 1)

    fh = {}
    for k, v in furn.items():
        parts = [p.strip() for p in v.split('|')[0].split(',')]
        fh[k] = len(parts)
    longest = []
    for f in range(m.F):
        for z in range(m.H):
            run = 0
            for x in range(m.W + 1):
                if x < m.W and see(m.at(f, x, z)):
                    run += 1
                else:
                    if run > 20:
                        longest.append((run, f, f'z={z} x{x - run}-{x - 1}'))
                    run = 0
        for x in range(m.W):
            run = 0
            for z in range(m.H + 1):
                if z < m.H and see(m.at(f, x, z)):
                    run += 1
                else:
                    if run > 20:
                        longest.append((run, f, f'x={x} z{z - run}-{z - 1}'))
                    run = 0
    longest.sort(reverse=True)
    for run, f, where in longest[:8]:
        Wn(f'{f + 1}F 長直線視野 {run} 格：{where}')

    # ---- 空曠：沒有遮蔽（高 ≥2）的最大正方形 ----
    for f in range(m.F):
        best, at = 0, None
        dp = [[0] * (m.W + 1) for _ in range(m.H + 1)]
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                open_ = c in ',;' or c in MARKERS and c not in 'KJ' or c == '+' or (is_furn(c) and fh.get(c, 3) <= 1)
                if open_ and c != '.':
                    dp[z + 1][x + 1] = min(dp[z][x], dp[z][x + 1], dp[z + 1][x]) + 1
                    if dp[z + 1][x + 1] > best:
                        best, at = dp[z + 1][x + 1], (x, z)
        if best > 12:
            Wn(f'{f + 1}F 戶外有 {best}×{best} 的空地沒有遮蔽（右下角 {at}），建議 ≤ 12')
        elif best:
            print(f'{f + 1}F 戶外最大的無遮蔽空地 {best}×{best}')

    # ---- 數量 ----
    cnt = Counter()
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                cnt[(f, m.at(f, x, z))] += 1
    tot = Counter()
    for (f, c), v in cnt.items():
        tot[c] += v

    def runs(ch):
        """連在一起的同一種符號算一個（雙開門、2 格寬的開口、3 格寬的大門）。"""
        out = []
        s = set()
        for f in range(m.F):
            for z in range(m.H):
                for x in range(m.W):
                    if m.at(f, x, z) != ch or (f, x, z) in s:
                        continue
                    comp, q = [], deque([(f, x, z)])
                    s.add((f, x, z))
                    while q:
                        a = q.popleft()
                        comp.append(a)
                        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                            b = (a[0], a[1] + dx, a[2] + dz)
                            if m.at(*b) == ch and b not in s:
                                s.add(b)
                                q.append(b)
                    out.append(comp)
        return out

    gates = runs('G')
    qs = [n for n in nodes if m.at(*n) == 'Q']
    q_regions = {rid.get(n) for n in qs}
    print(f'\n大門候選 {len(gates)}（{", ".join(str(g[0][0] + 1) + "F" for g in gates)}）｜保全室候選 {len(q_regions)} 間｜電源候選 {tot["E"]}'
          f'｜地下通道 {len(hatch)} 對｜鑽洞 {tot["h"]}｜暗格 {tot["H"]}｜推倒櫃子 {tot["C"]}｜出生點 {tot["S"]}'
          f'｜雙開門 {len(runs("W"))}｜門 {tot["D"]}｜開口 {len(runs("O"))}')
    if len(gates) < 4:
        Wn(f'大門候選只有 {len(gates)} 個（建議 ≥ 4）')
    if len(q_regions) < 2:
        E(f'保全室候選只有 {len(q_regions)} 間（至少 2）')
    elif len(q_regions) < 4:
        Wn(f'保全室候選 {len(q_regions)} 間（建議 ≥ 4）')
    for n in qs:
        r = rid.get(n)
        comp = regions[r] if r is not None else []
        cs = Counter(m.at(*a) for a in comp)
        if cs.get('.', 0) < cs.get(',', 0) + cs.get(';', 0):
            E(f'{n[0] + 1}F 保全室候選 Q ({n[1]},{n[2]}) 要在室內')
        if not any(m.at(n[0], n[1] + dx, n[2] + dz) in '#%' for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            E(f'{n[0] + 1}F 保全室候選 Q ({n[1]},{n[2]}) 要靠牆')
    for f in range(m.F):
        for z in range(m.H):
            for x in range(m.W):
                if m.at(f, x, z) == 'E' and not any(m.at(f, x + dx, z + dz) in '#%' for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    E(f'{f + 1}F 電源候選 E ({x},{z}) 要靠牆')
                if m.at(f, x, z) == 'C':
                    near = any(m.at(f, x + dx, z + dz) == 'O' for dx in range(-2, 3) for dz in range(-2, 3))
                    if not near:
                        E(f'{f + 1}F 推倒櫃子 C ({x},{z}) 旁邊 2 格內要有開口 O（推倒後擋住那個門口）')
    if tot['E'] < 4:
        Wn(f'電源候選只有 {tot["E"]} 個（建議 ≥ 4）')
    if tot['S'] < 3:
        E(f'出生點 S 只有 {tot["S"]} 個（至少 3）')
    if m.F > 1:
        for f in range(m.F - 1):
            n = sum(1 for s in stairs if s[0] == f)
            if n < 2:
                Wn(f'{f + 1}F→{f + 2}F 只有 {n} 組樓梯（建議 2 組）')

    print()
    for w in warns:
        print('WARN ', w)
    for e in errors:
        print('ERROR', e)
    print(f'\n錯誤 {len(errors)}，警告 {len(warns)}')

    if png:
        render(m, png, furn)
    return 1 if errors else 0


COLORS = {
    '#': (90, 90, 95), '%': (40, 90, 40), '+': (140, 110, 70), '.': (225, 220, 205), ',': (120, 170, 90), ';': (175, 175, 170),
    'D': (160, 100, 40), 'W': (200, 130, 50), 'O': (240, 210, 80), 'I': (200, 200, 220), 'h': (255, 120, 200), 'H': (200, 0, 160),
    'G': (255, 60, 60), '=': (60, 60, 160), 'S': (60, 200, 255), 'K': (40, 20, 40), 'J': (120, 120, 160), 'E': (255, 255, 0),
    'Q': (0, 120, 255), 'C': (100, 60, 20), ' ': (20, 20, 25),
}


def render(m, path, furn):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print('（沒有 PIL，不畫圖）')
        return
    s = 10
    gap = 12
    img = Image.new('RGB', (m.W * s * m.F + gap * (m.F - 1), m.H * s + 18), (20, 20, 25))
    d = ImageDraw.Draw(img)
    for f in range(m.F):
        ox = f * (m.W * s + gap)
        d.text((ox + 2, m.H * s + 4), f'{f + 1}F', fill=(255, 255, 255))
        for z in range(m.H):
            for x in range(m.W):
                c = m.at(f, x, z)
                if c in ARROWS:
                    col = (90, 90, 200)
                elif c.isdigit():
                    col = (0, 200, 120)
                elif is_furn(c):
                    col = (110, 75, 45)
                else:
                    col = COLORS.get(c, (255, 0, 255))
                d.rectangle([ox + x * s, z * s, ox + x * s + s - 1, z * s + s - 1], fill=col)
                if c in ARROWS or c.isdigit() or c in 'GQESKJCHh':
                    d.text((ox + x * s + 2, z * s - 1), c, fill=(0, 0, 0) if c not in 'KH' else (255, 255, 255))
    img.save(path)
    print('畫好了：' + path)


if __name__ == '__main__':
    sys.exit(main())
