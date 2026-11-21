"""把別的 AI 畫的地圖（markdown，```text 區塊）轉成 pve-maps 格式。
用法：python tools/convert_ai_maps.py <廢棄水力發電廠.md> <午夜濃霧濱海度假村.md> <輸出資料夾>
下面 __main__ 裡的 edits 是這兩張圖手動修的地方（座標是原圖的，補空白之前）；之後的新圖照這個樣子加一段。
轉換規則：
- B（木桶）→ 地面（木桶每局隨機）；v（家具）→ x（v 是往下的樓梯箭頭）
- 監獄：拘留室的牆換成地面，讓 J 外面一圈自動蓋鐵欄杆、外面站得到人
- 大門 G 加寬成 3 格（兩邊是牆才加）
- 四周各補 1 格空白（暗格挖在最外圈的牆時，躲藏空間後面要補牆）
- 暗格 H：躲藏空間另外三邊不是牆的補成牆
"""
import re, sys

sys.stdout.reconfigure(encoding='utf-8')


def blocks(path):
    txt = open(path, encoding='utf-8').read()
    out = re.findall(r'```text\n(.*?)```', txt, re.S)
    grids = []
    for b in out:
        rows = b.split('\n')
        if rows and rows[-1] == '':
            rows.pop()
        w = max(len(r) for r in rows)
        grids.append([list(r.ljust(w)) for r in rows])
    return grids


WALL = set('#%')
FLOORS = set('.,;')


def neighbors_floor(g, x, y):
    cnt = {'.': 0, ',': 0}
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            yy, xx = y + dy, x + dx
            if 0 <= yy < len(g) and 0 <= xx < len(g[0]) and g[yy][xx] in cnt:
                cnt[g[yy][xx]] += 1
    return '.' if cnt['.'] >= cnt[','] else ','


def convert(grids, edits, log):
    for (f, x, y, c) in edits:
        log.append(f'{f + 1}F ({x},{y}) {grids[f][y][x]!r} → {c!r}')
        grids[f][y][x] = c
    for g in grids:
        for y, row in enumerate(g):
            for x, c in enumerate(row):
                if c == 'B':
                    row[x] = neighbors_floor(g, x, y)
                elif c == 'v':
                    row[x] = 'x'
    # 推倒櫃子：旁邊 2 格內最近的門 D 改成開口 O（推倒後擋住它）
    for f, g in enumerate(grids):
        H, W = len(g), len(g[0])
        for y in range(H):
            for x in range(W):
                if g[y][x] != 'C':
                    continue
                best = None
                for yy in range(max(0, y - 2), min(H, y + 3)):
                    for xx in range(max(0, x - 2), min(W, x + 3)):
                        if g[yy][xx] in 'DO':
                            d = (xx - x) ** 2 + (yy - y) ** 2
                            if best is None or d < best[0]:
                                best = (d, xx, yy)
                if best is None:
                    log.append(f'{f + 1}F 櫃子 C ({x},{y}) 2 格內沒有門，要手動看')
                elif g[best[2]][best[1]] == 'D':
                    g[best[2]][best[1]] = 'O'
                    log.append(f'{f + 1}F 櫃子 C ({x},{y}) 旁邊的門 ({best[1]},{best[2]}) 改成開口 O')
    # 大門加寬
    for f, g in enumerate(grids):
        H, W = len(g), len(g[0])
        gs = [(x, y) for y in range(H) for x in range(W) if g[y][x] == 'G']
        for x, y in gs:
            horiz = y == 0 or y == H - 1 or (0 < y < H - 1 and (g[y - 1][x] == ' ' or g[y + 1][x] == ' '))
            for d in (-1, 1):
                xx, yy = (x + d, y) if horiz else (x, y + d)
                near_marker = any(0 <= xx + a < W and 0 <= yy + b < H and g[yy + b][xx + a] in 'EQ' for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if 0 <= xx < W and 0 <= yy < H and g[yy][xx] in WALL and not near_marker:
                    g[yy][xx] = 'G'
                    log.append(f'{f + 1}F 大門 ({x},{y}) 加寬到 ({xx},{yy})')
    # 四周補空白
    out = []
    for g in grids:
        W = len(g[0])
        ng = [[' '] * (W + 2)] + [[' '] + r + [' '] for r in g] + [[' '] * (W + 2)]
        out.append(ng)
    # 暗格的躲藏空間補牆
    for f, g in enumerate(out):
        H, W = len(g), len(g[0])
        for y in range(H):
            for x in range(W):
                if g[y][x] != 'H':
                    continue
                walk = [(dx, dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if g[y + dy][x + dx] not in WALL | {' ', 'H'}]
                if len(walk) != 1:
                    log.append(f'{f + 1}F 暗格 ({x - 1},{y - 1}) 旁邊有 {len(walk)} 格地面，要手動看')
                    continue
                dx, dy = walk[0]
                px, py = x - dx, y - dy
                back = g[py][px]
                if back not in WALL:
                    log.append(f'{f + 1}F 暗格 ({x - 1},{y - 1}) 後面不是牆（{back!r}），要手動看')
                    continue
                for ex, ey in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = px + ex, py + ey
                    if (nx, ny) == (x, y):
                        continue
                    if g[ny][nx] not in WALL:
                        log.append(f'{f + 1}F 暗格 ({x - 1},{y - 1}) 的躲藏空間旁 ({nx - 1},{ny - 1}) {g[ny][nx]!r} 補成 {back!r}')
                        g[ny][nx] = back
    return out


def write(path, meta, grids, furniture, palette, sounds=''):
    with open(path, 'w', encoding='utf-8', newline='\n') as fo:
        for k, v in meta.items():
            fo.write(f'{k}: {v}\n')
        for i, g in enumerate(grids):
            fo.write(f'\n[{i + 1}F]\n')
            for r in g:
                fo.write(''.join(r).rstrip() + '\n')
        fo.write('\n[furniture]\n' + furniture.strip() + '\n\n[palette]\n' + palette.strip() + '\n')
        if sounds.strip():
            fo.write('\n[sounds]\n' + sounds.strip() + '\n')


# 音效（資源包 resourcepack/assets/ghosthunt/sounds/hydro/，使用者提供的水力發電廠音效包）。座標是轉換後（補空白之後）的格子座標
HYDRO_SOUNDS = """
loop = 30                                         # 環境音都是 30 秒，每 30 秒重播
global = hydro.global 0.35                        # 全場底層
zone = hydro.dam 0.8 1F 13,2 44,8                 # 北邊大壩進水口廣場（含監獄）
zone = hydro.turbine 0.8 1F 2,27 13,36 37,10 47,16   # 西南主渦輪室、東北備用發電機房
zone = hydro.turbine 0.45 2F 6,18 18,25           # 2F 渦輪高層觀測室（隔一層，小聲）
zone = hydro.substation 0.7 1F 22,9 35,17 20,18 37,26 23,27 40,36   # 中央高壓變電廣場（含鬼房）
tunnel = hydro.tunnel 0.9                         # 地下檢修通道
random = hydro.creak 45 100 0.5                   # 遠處金屬異響
random = hydro.valve 70 140 0.6                   # 壓力閥洩壓
power-on = hydro.relay 1.0                        # 開電源／修好電源
power-off = hydro.relay 1.0                       # 停電、鬼關電源
"""


HYDRO_FURN = """
a = chiseled_tuff_bricks, observer                       # 配電盤
b = iron_block, iron_block                                # 鐵製檔案櫃
c = gray_concrete, gray_concrete, gray_concrete           # 混凝土支撐柱
d = dark_oak_planks                                       # 工業辦公桌
g = waxed_oxidized_copper, waxed_oxidized_copper_grate, waxed_oxidized_cut_copper   # 大型渦輪發電機
k = cyan_terracotta, cyan_terracotta, cyan_terracotta     # 維修貨櫃
l = deepslate_brick_wall, iron_bars, waxed_copper_bulb | light   # 工業高壓路燈（跟著電源亮暗）
p = waxed_copper_block, waxed_exposed_copper, waxed_copper_block # 粗輸水管線
r = stripped_spruce_log, spruce_planks                    # 大型電纜捲盤
t = deepslate_tiles, tuff_bricks, deepslate_tiles         # 高壓變壓器
u = yellow_terracotta, yellow_terracotta                  # 廢棄工程車
x = polished_andesite                                     # 閘門閥門座（原圖的 v）
"""
HYDRO_PAL = """
wall = stone_bricks
outer-wall = cracked_stone_bricks
ceiling = smooth_stone
hedge = tuff_bricks
hedge-base = tuff
fence = dark_oak_fence
outdoor = gravel, polished_andesite, grass_block
deck = tuff_bricks
path = polished_andesite
room-floors = prismarine_bricks, polished_diorite, waxed_oxidized_cut_copper, yellow_terracotta, red_nether_bricks, polished_granite, gray_glazed_terracotta, light_blue_concrete, brown_terracotta
door = spruce
"""
RESORT_FURN = """
b = stripped_oak_log, stripped_oak_log, hay_block         # 海灘茅草涼亭
c = jungle_log, jungle_log, jungle_leaves                 # 熱帶椰子樹
d = spruce_planks                                         # 接待櫃檯／酒吧吧台
f = prismarine_bricks, dark_prismarine                    # 景觀石雕噴泉
g = white_concrete, white_wool                            # 度假村高爾夫球車
l = bamboo_block, bamboo_fence, waxed_copper_bulb | light # 庭園竹節路燈（跟著電源亮暗）
m = light_blue_concrete, light_blue_concrete              # 飲料販賣機／製冰機
p = dark_prismarine                                       # 溫泉造景池
r = mangrove_planks, mangrove_fence                       # 衝浪板展示架
s = birch_planks                                          # 度假木頭躺椅
t = bamboo_mosaic, bamboo_planks, hay_block               # 茅草毛巾儲物亭
u = quartz_bricks, quartz_bricks, smooth_quartz           # 戶外更衣沖澡亭
"""
RESORT_PAL = """
wall = smooth_sandstone
outer-wall = sandstone
ceiling = stripped_birch_wood
hedge = moss_block
hedge-base = moss_block
fence = bamboo_fence
outdoor = grass_block, smooth_sandstone, sand
deck = oak_planks
path = smooth_sandstone
room-floors = terracotta, cyan_terracotta, stripped_jungle_wood, light_blue_terracotta, cracked_deepslate_bricks
door = jungle
"""

if __name__ == '__main__':
    hydro_md, resort_md, outdir = sys.argv[1:4]
    log = []
    g = blocks(hydro_md)
    edits = [(0, 17, y, ',') for y in range(1, 5)] + [(0, x, 5, ',') for x in range(13, 17)]
    # 雙開門旁邊的櫃子移到單門旁邊（雙開門留著卡門用）
    edits += [(0, 17, 21, '.'), (0, 16, 24, 'C'), (0, 38, 21, '.'), (0, 42, 24, 'C'), (0, 41, 29, '.'), (0, 47, 35, 'C')]
    edits += [(0, 16, 40, 'c')]  # 切斷西南大門那排 21 格的直線
    out = convert(g, edits, log)
    write(f'{outdir}/hydro_plant.txt', {
        'name': '廢棄水力發電廠',
        'type': 'mixed',
        'description': '北窄南寬的階梯梯形：北邊大壩廣場和拘留室，中間東西兩棟 2 層廠房夾著變電廣場（鬼的高壓隔離籠），南邊是被貨櫃切碎的維修堆置場。2F 有繞圈的鋼構天橋。',
    }, out, HYDRO_FURN, HYDRO_PAL, HYDRO_SOUNDS)
    print('== hydro_plant'); print('\n'.join(log)); log.clear()
    g = blocks(resort_md)
    edits = [(0, 9, y, ',') for y in range(1, 5)] + [(0, x, 5, ',') for x in range(5, 9)]
    edits += [(0, 8, 20, '.'), (0, 6, 23, 'C'), (0, 47, 21, '.'), (0, 51, 23, 'C')]
    edits += [(0, 50, 33, 'c')]  # 東小屋南邊開口往南 25 格的直線
    out = convert(g, edits, log)
    write(f'{outdir}/seaside_resort.txt', {
        'name': '午夜濃霧濱海度假村',
        'type': 'outdoor',
        'description': '幾乎全是戶外的海灣度假村：樹籬、涼亭、椰子樹切出的庭園迷宮，4 間度假小屋，中間是廢棄玻璃花房（鬼房），2F 環狀木棧天橋繞著鬼房一圈。',
    }, out, RESORT_FURN, RESORT_PAL)
    print('== seaside_resort'); print('\n'.join(log))
