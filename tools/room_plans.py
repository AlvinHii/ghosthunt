import sys
from collections import deque
sys.stdout.reconfigure(encoding='utf-8')

N = 18
# 走得過去：. 空地  E 電源開關候選（靠牆地面）  s 出生點候選  c 蹲伏破洞內側
WALK = set('.Escd')  # d = 廁所隔間的門（站得進去）
# 高度（擋視線看 >=2）
HEIGHT = {'U':1,'Y':3,'T':1,'C':1,'X':1,'K':1,'M':1,'F':1,'Q':1,'H':2,'S':2,'G':2,'V':2,'B':3,'P':3,'R':3,'W':3,'J':3}

ROOMS = {
"開放式辦公室 OPEN_OFFICE": [
"X................X",
"..................",
"..TT..TT..TT..TT..",
"..CC..CC..CC..CC..",
"..................",
"BB...HHH.ss.HHH.BB",
"....TTTT..TTTT....",
"....TTTT..TTTT....",
"..................",
"..................",
"....TTTT..TTTT....",
"....TTTT..TTTT....",
"BB...HHH....HHH.BB",
"..................",
"..CC..CC..CC..CC..",
"..TT..TT..TT..TT..",
"..................",
"X..E...........E.X",
],
"隔間辦公室 CUBICLES": [
"X................X",
"..................",
"..HH..HH..HH..HH..",
"..HT..TH..HT..TH..",
"..H....H..H...XH..",
"..HH..HH..HH..HH..",
"..................",
"..HH..HH..HH..HH..",
"..HT..TH..HT..TH..",
"..H....H..H....H..",
"..HH..HH..HH..HH..",
"..................",
"..HH..HH..HH..HH..",
"..HT..TH..HT..TH..",
"..HX...H..H....H..",
"..HH..HH..HH..HH..",
"..................",
"X..E..........E..X",
],
"大型會議室 BOARDROOM": [
"P.......  .......P".replace(' ','.'),
"..................",
"..WYYYYW..WYYYYW..",
"..W............W..",
"..W.CCCCCCCCCC.W..",
"..W.TTTTTTTTTT.W..",
"....TTTTTTTTTT....",
"..W.CCCCCCCCCC.W..",
"..W............W..",
"..W............W..",
"..W.X........X.W..",
"..WWWWW....WWWWW..",
"..................",
"..................",
"PP..SS......SS..PP",
"..................",
"..................",
"X..E..........E..X",
],
"檔案室 ARCHIVE": [
"S.......  .......S".replace(' ','.'),
"..................",
"..BBBBB....BBBBB..",
"..................",
"..BBBBB....BBBBB..",
"..................",
"..BBB..BBBB..BBB..",
"..............X...",
"..................",
"..................",
"...X..............",
"..BBB..BBBB..BBB..",
"..................",
"..BBBBB....BBBBB..",
"..................",
"..BBBBB....BBBBB..",
"..................",
"X..E..........E..X",
],
"儲藏室 STORAGE": [
"X.......  .......X".replace(' ','.'),
"..................",
"..RRRR.RRRRR.RRR..",
"..................",
"..RRRR.RRRRR.RRR..",
"..................",
".......SS...X.....",
"..SS...SS.....SS..",
"..................",
"..................",
"..SS.....SS...SS..",
".....X...SS.......",
"..................",
"..RRR.RRRRR.RRRR..",
"..................",
"..RRR.RRRRR.RRRR..",
"..................",
"X..E..........E..X",
],
"員工休息室 LOUNGE": [
"V.......  .......V".replace(' ','.'),
"..................",
"..FFF........FFF..",
"..F............F..",
"..................",
"......QQQQQQ......",
"......Q....Q......",
"......Q.TT.Q......",
"..........T.......",
".......T.TT.......",
"......Q....Q......",
"......QQQQQQ......",
"..................",
"..TT...SSSS...TT..",
"..TT...SSSS...TT..",
"..................",
"..................",
"X..E.KK....KK.E..X",
],
"電力室 ELECTRICAL": [
"G.......  .......G".replace(' ','.'),
"..................",
"..GG..GG..GG..GG..",
"..GG..GG..GG..GG..",
"..................",
"..................",
"..WWWWWW..WWWWWW..",
"..W....W..W....W..",
"..W.E..........W..",
"..W............W..",
"..W....W..W....W..",
"..WWWWWW..WWWWWW..",
"..................",
"..................",
"..GG..GG..GG..GG..",
"..GG..GG..GG..GG..",
"..................",
"X..............E.X",
],
"伺服器機房 SERVER": [
"X.......  .......X".replace(' ','.'),
"..................",
"..RR.RRRR..RRRR.R.",
"..RR.RRRR..RRRR.R.",
"..................",
"..................",
"..RRRRR.RR.RRRRR..",
"..................",
"..................",
"..................",
"..................",
"..RRRRR.RR.RRRRR..",
"..................",
"..................",
".R.RRRR..RRRR.RR..",
".R.RRRR..RRRR.RR..",
"..................",
"X..E..........E..X",
],
"影印室 COPY_ROOM": [
"S.......  .......S".replace(' ','.'),
"..................",
"..MM..MM..MM..MM..",
"..................",
"..................",
"..SSSS......SSSS..",
"..S..........X.S..",
"..S............S..",
"..................",
"..................",
"..S............S..",
"..S.X..........S..",
"..SSSS......SSSS..",
"..................",
"..................",
"..MM..TTTTTT..MM..",
"..................",
"X..E..........E..X",
],
"公共廁所 RESTROOM": [
"..HUHUHUHUHUHUH...",
"..H.H.H.H.H.H.H...",
"..HdHdHdHdHdHdH...",
"..................",
"..................",
"K................K",
"..................",
"K................K",
"....HHHHHHHHHH....",
"K................K",
"..................",
"K................K",
"..................",
"..................",
"X................X",
"..................",
"..................",
"..................",
],
"監獄 JAIL": [
"P.......  .......P".replace(' ','.'),
"..................",
"..................",
"...P..........P...",
"..................",
"..................",
"......JJJJJJ......",
"......J....J......",
"......J....J......",
"......J....J......",
"......J....J......",
"......JJJJJJ......",
"..................",
"..................",
"...P..........P...",
"..................",
"..................",
"X................X",
],
"鬼房 GHOST_ROOM": [
"..................",
"..................",
"..P............P..",
"..................",
"..................",
"..................",
"..................",
"..................",
"........ss........",
"..................",
"..................",
"..................",
"..................",
"..................",
"..................",
"..P............P..",
"..................",
"..................",
],
}

DOOR_COLS = (8, 9)          # 每面牆門口在內部的第 8、9 格（20×20 房間格的正中間）
ZONE = range(7, 11)         # 門口前面 4 格寬、2 格深要空著

def check(name, g):
    assert len(g) == N, (name, len(g))
    for i, r in enumerate(g):
        assert len(r) == N, (name, i, len(r), r)
    errs = []
    # 門口區：四面牆中間 4 格寬、2 格深
    zone = set()
    for k in ZONE:
        for d in range(2):
            zone |= {(d, k), (N-1-d, k), (k, d), (k, N-1-d)}
    one_door = name.startswith('公共廁所')  # 只有南面一個門，插件會旋轉讓它對齊實際的門
    if one_door:
        zone = {(N-1-d, k) for k in ZONE for d in range(2)}
    for (i, j) in zone:
        if g[i][j] not in WALK and not name.startswith('監獄') and not name.startswith('鬼房'):
            errs.append(f'門口區被擋住 ({i},{j})={g[i][j]}')
    # 從四個門口出發
    start = [(0, c) for c in DOOR_COLS] + [(N-1, c) for c in DOOR_COLS] + [(c, 0) for c in DOOR_COLS] + [(c, N-1) for c in DOOR_COLS]
    if one_door:
        start = [(N-1, c) for c in DOOR_COLS]
    seen = set(p for p in start if g[p[0]][p[1]] in WALK)
    q = deque(seen)
    while q:
        i, j = q.popleft()
        for di, dj in ((1,0),(-1,0),(0,1),(0,-1)):
            a, b = i+di, j+dj
            if 0 <= a < N and 0 <= b < N and (a, b) not in seen and g[a][b] in WALK:
                seen.add((a, b)); q.append((a, b))
    unreached = [(i, j) for i in range(N) for j in range(N) if g[i][j] in WALK and (i, j) not in seen]
    if name.startswith('監獄'):
        unreached = [p for p in unreached if not (7 <= p[0] <= 10 and 7 <= p[1] <= 10)]
    if unreached: errs.append(f'走不到 {unreached[:6]}{"…" if len(unreached) > 6 else ""}')
    for i in range(N):
        for j in range(N):
            if g[i][j] in 'X':
                if not any((i+di, j+dj) in seen for di, dj in ((1,0),(-1,0),(0,1),(0,-1))):
                    errs.append(f'容器旁邊站不到 ({i},{j})')
    walk = sum(1 for i in range(N) for j in range(N) if g[i][j] in WALK)
    tall = sum(1 for i in range(N) for j in range(N) if HEIGHT.get(g[i][j], 0) >= 2)
    low = sum(1 for i in range(N) for j in range(N) if HEIGHT.get(g[i][j], 0) == 1)
    cont = sum(r.count('X') for r in g)
    power = sum(r.count('E') for r in g)
    return errs, dict(walk=walk, tall=tall, low=low, containers=cont, power=power)

ok = True
for name, g in ROOMS.items():
    errs, st = check(name, g)
    print(name, st, 'OK' if not errs else errs)
    ok &= not errs
print('ALL OK' if ok else 'PROBLEMS')
