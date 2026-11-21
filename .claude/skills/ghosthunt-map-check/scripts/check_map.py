"""鬼抓人 ASCII 地圖檢查。

用法：python3 -I check_map.py <地圖.md> [--low dvps] [--max-line 20]
  --low       1 格高、看得穿的家具字母（看該圖的家具表），預設 "dpsv"
  --max-line  1F 直線視野上限，預設 20
座標 (x, y)：x 從左數第幾欄、y 從上數第幾列，都從 0 開始。
"""
import argparse, re, sys
from collections import Counter, deque

N4 = ((0, 1), (0, -1), (1, 0), (-1, 0))
WALK = set(",.JKSEQ12><=DWOIGhB")
DOORS = set("DWOI")
KNOWN = set("#%+,.DWOGQEBChH123456789><^vV=KIJS ")


def load(path):
    global TEXT
    text = open(path, encoding="utf-8").read()
    TEXT = text
    grids = {}
    for f in ("1F", "2F"):
        m = re.search(r"## " + f + r"\s*\n+```text\n(.*?)```", text, re.S)
        if not m:
            sys.exit(f"找不到 ## {f} 底下的 ```text 程式碼區塊")
        grids[f] = m.group(1).split("\n")[:-1]
    return grids


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--low", default="dpsv")
    ap.add_argument("--max-line", type=int, default=20)
    a = ap.parse_args()
    raw = load(a.path)
    problems, ok = [], []

    rows = {f: len(g) for f, g in raw.items()}
    widths = {f: sorted(set(len(r) for r in g)) for f, g in raw.items()}
    W = max(max(w) for w in widths.values())
    if len(set(rows.values())) != 1:
        problems.append(f"兩層列數不同：{rows}")
    for f, w in widths.items():
        if len(w) != 1:
            problems.append(f"{f} 每列寬度不一致：{w}（已補空白繼續檢查）")
    G = {f: [r.ljust(W) for r in g] for f, g in raw.items()}
    ok.append(f"尺寸：{rows['1F']} 列 × {W} 欄")

    def at(g, c, r):
        return g[r][c] if 0 <= r < len(g) and 0 <= c < W else " "

    def pos(g, ch):
        return [(c, r) for r in range(len(g)) for c in range(W) if g[r][c] == ch]

    def nb(g, c, r):
        return [at(g, c + dc, r + dr) for dc, dr in N4]

    def bfs(g, start, passable):
        d = {start: 0}; q = deque([start])
        while q:
            c, r = q.popleft()
            for dc, dr in N4:
                p = (c + dc, r + dr)
                if p not in d and at(g, *p) in passable:
                    d[p] = d[(c, r)] + 1; q.append(p)
        return d

    low = set(a.low)
    stairs = set("><^vV")
    if re.search(r"\|\s*`v`\s*\|", TEXT):
        problems.append("家具表把小寫 `v` 當家具，但專案把 `v` 讀成往下的樓梯：家具要換字母（這次先當家具檢查）")
        stairs.discard("v"); low |= {"v"}
    # 樓梯四個方向、地洞 1～9 都算可走（`v` 被當家具時除外）
    WALK.update(stairs | set("3456789"))
    # 符號與數量
    for f, g in G.items():
        cnt = Counter("".join(g))
        unknown = sorted(ch for ch in cnt if ch not in KNOWN and not ch.islower())
        if unknown:
            problems.append(f"{f} 有圖例外的符號：{unknown}")
        ok.append(f"{f} 數量：" + "、".join(f"{k}={cnt[k]}" for k in "GQEBChHKJS><^vV=" if cnt[k]))
    g1, g2 = G["1F"], G["2F"]

    # 大門：3 格寬、往內 2 格是空地；2F 大門要有陽台＋梯子
    for f, g in G.items():
        seen = set()
        for c, r in pos(g, "G"):
            if (c, r) in seen: continue
            run = [(c, r)]; seen.add((c, r))
            for dc, dr in ((1, 0), (0, 1)):
                k = 1
                while at(g, c + dc * k, r + dr * k) == "G":
                    run.append((c + dc * k, r + dr * k)); seen.add(run[-1]); k += 1
                if len(run) > 1: break
            if len(run) != 3:
                problems.append(f"{f} 大門 {run} 不是 3 格寬")
                continue
            # 跟遊戲的讀取器一樣：只看中間那格，外面是場地外（空白），往內 2 格放讀卡機要是空地
            mc, mr = run[1]
            horiz = run[1][1] == run[0][1]
            dirs = [(0, 1), (0, -1)] if horiz else [(1, 0), (-1, 0)]
            out = [d for d in dirs if at(g, mc + d[0], mr + d[1]) == " "]
            if not out:
                problems.append(f"{f} 大門 {run} 不在最外圈（外面不是場地外）")
                continue
            ox, oy = out[0]
            reader = at(g, mc - 2 * ox, mr - 2 * oy)
            if reader not in ",.JKSEQ123456789":
                problems.append(f"{f} 大門 {run} 中間往內 2 格 ({mc - 2 * ox},{mr - 2 * oy}) 要是空地（放讀卡機），現在是 {reader!r}")
            if f == "2F":
                # 遊戲會在 2F 大門外面蓋陽台＋梯子；梯子落地的那一塊（往外 1～6 格、左右 2 格）在 1F 要是場地外，不然不放梯子
                lx, ly = (1, 0) if horiz else (0, 1)
                below = [(mc + ox * d + lx * l, mr + oy * d + ly * l) for d in range(1, 7) for l in range(-2, 3)]
                inside = [p for p in below if at(g1, *p) != " "]
                if inside:
                    problems.append(f"2F 大門 {run} 外面陽台底下的 1F 有東西 {inside[:4]}，遊戲不會放梯子（只有陽台）")
                else:
                    ok.append(f"2F 大門 {run}：外面會蓋陽台＋梯子")

    # 連通
    S = pos(g1, "S")
    if not S:
        problems.append("1F 沒有出生點 S")
    else:
        reach = bfs(g1, S[0], WALK - {"B"})
        walk = [p for p in ((c, r) for r in range(len(g1)) for c in range(W)) if at(g1, *p) in WALK - {"B"}]
        miss = [p for p in walk if p not in reach]
        (problems if miss else ok).append(f"1F 從 S 走不到的格子：{miss}" if miss else "1F 從 S 走得到每一格")

    for f, g in G.items():
        # 封閉
        leak = [(c, r) for r in range(len(g)) for c in range(W)
                if g[r][c] in WALK and g[r][c] != "G" and " " in nb(g, c, r)]
        if leak:
            problems.append(f"{f} 可走格碰到地圖外：{leak}")
        # 牆缺口
        gap = sorted({(c, r) for r in range(len(g)) for c in range(W)
                      if g[r][c] == "." and "," in nb(g, c, r)})
        if gap:
            problems.append(f"{f} 室內直接接到戶外（牆缺口）：{gap}")
        # 門
        for ch in DOORS:
            for c, r in pos(g, ch):
                ns, we = (at(g, c, r - 1), at(g, c, r + 1)), (at(g, c - 1, r), at(g, c + 1, r))
                wall = set("#% ")
                if ch == "W":  # 雙開門：另一半所在的軸是門框，通道在另一個軸
                    axis = we if "W" in ns else ns
                else:
                    axis = ns if not (set(ns) & wall) else we
                bad = [x for x in axis if x.islower()]
                if bad:
                    problems.append(f"{f} 門 {ch}@({c},{r}) 前後是家具 {bad}（死門）")
                if set(axis) & wall:
                    problems.append(f"{f} 門 {ch}@({c},{r}) 找不到前後都能走的方向")
        # 小洞、暗格
        for c, r in pos(g, "h"):
            n = nb(g, c, r)
            if not ((n[0] in WALK and n[1] in WALK) or (n[2] in WALK and n[3] in WALK)):
                problems.append(f"{f} 蹲伏小洞 h@({c},{r}) 兩側不是都能走：{n}")
        for c, r in pos(g, "H"):
            n = nb(g, c, r)
            if sum(x in "#%" for x in n) != 3:
                problems.append(f"{f} 暗格 H@({c},{r}) 不是三面牆：{n}")
                continue
            i = [k for k, x in enumerate(n) if x not in "#%"][0]
            dc, dr = N4[i]
            bc, br = c - dc, r - dr  # 入口的對面：要挖空的那格
            back_nb = [at(g, bc + x, br + y) for x, y in N4 if (bc + x, br + y) != (c, r)]
            if not all(x in "#%" for x in back_nb):
                problems.append(f"{f} 暗格 H@({c},{r}) 後面那格 ({bc},{br}) 挖空後，另外三邊不是都牆：{back_nb}")
            if any(at(g, bc + x, br + y) == " " for x, y in N4) or not (0 < bc < W - 1 and 0 < br < len(g) - 1):
                problems.append(f"{f} 暗格 H@({c},{r}) 後面那格 ({bc},{br}) 在最外圈")
        # 櫃子：2 格內要有開口 O
        for c, r in pos(g, "C"):
            if not any(at(g, c + x, r + y) == "O" for x in range(-2, 3) for y in range(-2, 3)):
                problems.append(f"{f} 櫃子 C@({c},{r}) 2 格內沒有開口 O（放在門旁邊沒用）")
        # 監獄：不能畫成有門的房間
        jd = sorted({(c + x, r + y) for c, r in pos(g, "J") for x, y in N4 if at(g, c + x, r + y) in "DW"})
        if jd:
            problems.append(f"{f} 監獄旁邊有門 {jd}（監獄不能畫成有門的房間）")

    # 樓梯
    for ch in stairs:
        for c, r in pos(g1, ch):
            if at(g2, c, r) != "=":
                problems.append(f"1F 樓梯 ({c},{r}) 上方 2F 不是 '='")
    holes = pos(g2, "=")
    edge = {(c + dc, r + dr) for c, r in holes for dc, dr in N4} - set(holes)
    open_edge = sorted(p for p in edge if at(g2, *p) in ",.")
    if open_edge:
        ok.append(f"2F 樓梯洞口旁的可走格（除了上樓那一端，其他都要是 '+'）：{open_edge}")

    # 地洞
    for d in "123456789":
        p = pos(g1, d) + pos(g2, d)
        if not p:
            continue
        if len(p) != 2:
            problems.append(f"地洞 {d} 不是剛好 2 端：{p}")
            continue
        (c1, r1), (c2, r2) = p
        walk = bfs(g1, p[0], WALK - {"h", "B"}).get(p[1])
        ok.append(f"地洞 {d}：直線 {((c1-c2)**2+(r1-r2)**2)**0.5:.1f} 格、從地面走 {walk} 格")

    # 距離
    def center(ps):
        return (sum(c for c, r in ps) / len(ps), sum(r for c, r in ps) / len(ps))
    if pos(g1, "K") and pos(g1, "J") and S:
        k, j, s = center(pos(g1, "K")), center(pos(g1, "J")), center(S)
        dist = lambda x, y: ((x[0]-y[0])**2 + (x[1]-y[1])**2) ** 0.5
        ok.append(f"鬼房到監獄 {dist(k, j):.1f} 格、到出生點 {dist(k, s):.1f} 格")

    # 直線視野
    see = set(",.JKSE12=B") | stairs | low
    for f, g in G.items():
        long = []
        for r in range(len(g)):
            c = 0
            while c < W:
                if g[r][c] in see:
                    s0 = c
                    while c < W and g[r][c] in see: c += 1
                    if c - s0 > a.max_line: long.append(f"y={r} x={s0}~{c-1}（{c-s0} 格）")
                else: c += 1
        for c in range(W):
            r = 0
            while r < len(g):
                if g[r][c] in see:
                    s0 = r
                    while r < len(g) and g[r][c] in see: r += 1
                    if r - s0 > a.max_line: long.append(f"x={c} y={s0}~{r-1}（{r-s0} 格）")
                else: r += 1
        if long:
            (problems if f == "1F" else ok).append(f"{f} 超過 {a.max_line} 格的直線" + ("" if f == "1F" else "（2F 若是刻意的高視野可以例外）") + "：" + "；".join(long))
        elif f == "1F":
            ok.append(f"1F 沒有超過 {a.max_line} 格的直線")

    print("座標 (x, y)：x 從左數第幾欄、y 從上數第幾列，從 0 開始\n")
    print("== 對的／參考 ==")
    for x in ok: print("  -", x)
    print("\n== 有問題的 ==")
    for x in problems or ["（沒有）"]: print("  -", x)


if __name__ == "__main__":
    main()
