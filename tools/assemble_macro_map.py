"""
模組化藍圖（巨集圖＋模組）拼成一張完整地圖（格式見 docs/PvE大地圖_模組化prompt.md）。

用法：python tools/assemble_macro_map.py <藍圖.md> <輸出.txt> [--md 拼好的原圖.md] [--id 代號] [--barrels N]
  - 輸出 .txt 是 pve-maps 格式（[1F]/[2F]/[furniture]/[palette]）。家具用藍圖裡的 ```furniture 區塊（沒有才用下面 PRESETS），調色盤用 PRESETS。
  - --md 另外輸出一份「## 1F / ## 2F」的完整原圖，給 ghosthunt-map-check 檢查。

規則：
  - 每格巨集 15 格，相鄰模組共用中間那道牆（重疊 1 格）；模組大小 = 15 × 格數 + 1。
  - 重疊的格子兩邊畫的不一樣時：有一邊是開口（D W O h G）就用開口，其他照先放的；每一處都會列出來。
"""
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

OPEN = set('DWOhG')

# ---- 每張圖的家具、調色盤（家具只能是由下往上的方塊；朝向、展示框這些細節做不到）----
MALL_FURN = """
a = stripped_birch_wood, smooth_quartz, decorated_pot          # 島型展示櫃（頂上放展示的陶罐）
b = bookshelf, chiseled_bookshelf, bookshelf                   # 高書架
c = chiseled_quartz_block, quartz_pillar, quartz_pillar, quartz_pillar   # 大理石柱（接到天花板）
d = smooth_quartz                                              # 櫃檯／收銀台／辦公桌
f = spruce_planks, white_wool, spruce_planks                   # 服飾牆櫃
g = birch_fence, birch_pressure_plate                          # 圓餐桌
i = smoker, iron_block                                         # 後廚檯
j = chiseled_bookshelf, brown_carpet                           # 平台書桌
k = oak_planks, stripped_oak_wood, oak_planks                  # 倉儲木箱堆（原設計用木桶；木桶是遊戲的容器，改成木板）
l = polished_deepslate_wall, iron_chain, waxed_copper_bulb | light  # 立燈（跟著電源亮暗）
m = white_concrete, red_concrete                               # 自動販賣機
n = spruce_planks, red_shulker_box                             # 商品貨架
o = jukebox                                                    # 唱片機台
p = polished_deepslate, moss_block, pink_petals                # 綠籬花台
q = black_concrete, black_concrete, red_concrete               # 遊戲機台
r = black_concrete, white_concrete, white_concrete             # 電影銀幕
s = brown_wool                                                 # 座椅
t = stripped_spruce_log                                        # 長椅
u = quartz_block, quartz_block                                 # 冷藏櫃／冰箱
x = spruce_planks, black_concrete                              # 電視展示
y = polished_deepslate, black_concrete                         # 監視螢幕牆
z = composter                                                  # 垃圾桶
"""
MALL_PAL = """
wall = smooth_quartz
outer-wall = polished_deepslate
ceiling = smooth_stone
hedge = tuff_bricks
fence = dark_oak_fence
deck = smooth_stone
room-floors = pink_terracotta, light_gray_terracotta, gray_concrete, cracked_stone_bricks, polished_diorite, stripped_spruce_wood, yellow_terracotta, polished_andesite, brown_terracotta, orange_terracotta, quartz_bricks, smooth_sandstone, light_blue_concrete, terracotta, polished_granite, lime_terracotta, white_terracotta, tuff_bricks, mud_bricks, cyan_terracotta, diorite, white_concrete, red_nether_bricks, andesite, purple_terracotta, red_terracotta, stripped_oak_wood, magenta_terracotta, granite, green_terracotta, stripped_birch_wood, cherry_planks
door = dark_oak
"""
PRESETS = {'mall': (MALL_FURN, MALL_PAL)}

# 拼之前對模組改的格子：(樓層, 模組, x, y, 新字元)，模組內座標。用來切斷「幾個模組的開口排成一直線」造成的長直線視野。
EDITS = {
    'mall': [
        ('1F', 'RM_H', 8, 8, 'p'), ('1F', 'CORE', 9, 8, 'p'),   # 服務台東開口 → 中庭西開口那排 33 格
        ('1F', 'STR1', 9, 4, 'p'),                               # 西北手扶梯大廳 → 書店那排 29 格
        ('1F', 'STR3', 9, 4, 'p'),                               # 西南手扶梯大廳 → 超市那排 29 格
        ('1F', 'STR2', 12, 5, 'p'), ('2F', 'ST2U', 12, 5, 'p'),  # 東北手扶梯大廳 → 美食街那一欄 29 格（1F、2F）
        ('1F', 'RM_I', 12, 6, 'p'), ('2F', 'UP_D', 12, 6, 'p'),  # 同一欄再切一次
        ('1F', 'RM_A', 6, 8, 'p'),                               # 拘留廣場東開口 → 精品服飾店那排 28 格
    ],
    'mall_m': [
        ('1F', 'RM_H', 3, 7, 'p'), ('1F', 'RM_H', 3, 8, 'p'), ('1F', 'CORE', 9, 8, 'p'),  # 拘留廣場 → 服務台 → 中庭那排 48 格
        ('1F', 'STR1', 9, 4, 'p'),
        ('1F', 'STR2', 12, 5, 'p'), ('1F', 'RM_I', 12, 6, 'p'),
        # 2F 只有北邊一扇大門 → 組合只有 2 種；東北手扶梯迴廊外牆加一組（外面是陽台＋梯子，底下 1F 是場地外）
        ('2F', 'ST2U', 15, 6, 'G'), ('2F', 'ST2U', 15, 7, 'G'), ('2F', 'ST2U', 15, 8, 'G'),
    ],
}


def blocks_after(text, heading_re):
    """回傳 [(標題, code block 內容的每一行)]。"""
    out = []
    for m in re.finditer(heading_re + r'[^\n]*\n+```text\n(.*?)```', text, re.S):
        rows = m.group(m.lastindex).split('\n')
        if rows and rows[-1] == '':
            rows.pop()
        out.append((m.group(0).split('\n')[0], rows))
    return out


def parse(path):
    text = open(path, encoding='utf-8').read()
    macro = {}
    for f in ('1F', '2F'):
        m = re.search(r'## 巨集圖 ' + f + r'\s*\n+```text\n(.*?)```', text, re.S)
        if m:
            macro[f] = [r.split() for r in m.group(1).strip('\n').split('\n')]
    mods = {}
    for m in re.finditer(r'### (1F|2F) (\S+?)[（(\s]([^\n]*)\n+```text\n(.*?)```', text, re.S):
        rows = m.group(4).split('\n')
        if rows and rows[-1] == '':
            rows.pop()
        mods[(m.group(1), m.group(2))] = rows
        # 標題括號裡最後一段是房間名稱：「1F RM_E（16×16，室內，北側大型書店）」→ 北側大型書店
        title = m.group(3).rstrip('）)').split('，')[-1].strip()
        title = re.sub(r'^[0-9]F\s*', '', title)  # 「2F 影城放映監控室」：樓層告示牌上已經有
        TITLES[(m.group(1), m.group(2))] = title
    return macro, mods


TITLES = {}


def module_rects(macro, floor):
    """每個模組在拼好的圖上佔的長方形 (x1, z1, x2, z2, 代號)。"""
    grid = macro[floor]
    R, C = len(grid), len(grid[0])
    out, done = [], set()
    for r in range(R):
        for c in range(C):
            tok = grid[r][c]
            if tok == '----' or tok in done:
                continue
            done.add(tok)
            c1 = c
            while c1 + 1 < C and grid[r][c1 + 1] == tok:
                c1 += 1
            r1 = r
            while r1 + 1 < R and grid[r1 + 1][c] == tok:
                r1 += 1
            out.append((15 * c, 15 * r, 15 * (c1 + 1), 15 * (r1 + 1), tok))
    return out


def assemble(macro, mods, floor, log):
    grid = macro[floor]
    R, C = len(grid), len(grid[0])
    H, W = 15 * R + 1, 15 * C + 1
    out = [[' '] * W for _ in range(H)]
    owner = [[None] * W for _ in range(H)]
    done = set()
    for r in range(R):
        for c in range(C):
            tok = grid[r][c]
            if tok == '----' or tok in done:
                continue
            done.add(tok)
            # 這個模組佔的長方形
            c1 = c
            while c1 + 1 < C and grid[r][c1 + 1] == tok:
                c1 += 1
            r1 = r
            while r1 + 1 < R and grid[r1 + 1][c] == tok:
                r1 += 1
            rows = mods.get((floor, tok))
            if rows is None:
                log.append(f'錯誤：{floor} 巨集圖有 {tok}，但找不到模組')
                continue
            ew, eh = 15 * (c1 - c + 1) + 1, 15 * (r1 - r + 1) + 1
            if len(rows) != eh or any(len(x) != ew for x in rows):
                log.append(f'錯誤：{floor} {tok} 大小應該是 {ew}×{eh}，實際 {max(len(x) for x in rows)}×{len(rows)}')
            for y, row in enumerate(rows):
                for x, ch in enumerate(row):
                    gy, gx = 15 * r + y, 15 * c + x
                    if gy >= H or gx >= W:
                        continue
                    cur = out[gy][gx]
                    if cur == ' ' or owner[gy][gx] is None:
                        out[gy][gx], owner[gy][gx] = ch, tok
                    elif cur != ch:
                        pick = ch if ch in OPEN and cur not in OPEN else cur
                        log.append(f'{floor} ({gx},{gy}) {owner[gy][gx]} 畫 {cur!r}、{tok} 畫 {ch!r} → 用 {pick!r}')
                        out[gy][gx] = pick
    return [''.join(r) for r in out]


def main():
    args = sys.argv[1:]
    src, dst = args[0], args[1]
    opt = {args[i]: args[i + 1] for i in range(2, len(args) - 1, 2)}
    mid = opt.get('--id', 'mall')
    macro, mods = parse(src)
    for (fl, mod, x, y, ch) in EDITS.get(mid, []):
        rows = mods[(fl, mod)]
        old = rows[y][x]
        if old != '.' and not (ch == 'G' and old == '#'):
            print(f'錯誤：{fl} {mod} ({x},{y}) 是 {old!r}，不是空地，不改')
            continue
        rows[y] = rows[y][:x] + ch + rows[y][x + 1:]
        print(f'改：{fl} {mod} ({x},{y}) . → {ch}')
    text = open(src, encoding='utf-8').read()
    name = re.search(r'地圖顯示名稱\*\*：`([^`]+)`', text)
    log = []
    floors = [f for f in ('1F', '2F') if f in macro]
    grids = {f: assemble(macro, mods, f, log) for f in floors}
    furn, pal = PRESETS['mall']
    # 藍圖裡有「```furniture」區塊就用它（md 是家具的唯一來源）；沒有才用上面的預設
    fb = re.search(r'```furniture\r?\n(.*?)```', text, re.S)
    if fb:
        furn = fb.group(1)
        print('家具：用藍圖裡的 ```furniture 區塊')
    with open(dst, 'w', encoding='utf-8', newline='\n') as fo:
        fo.write(f'name: {name.group(1) if name else mid}\ntype: indoor\n')
        fo.write('description: 雙層購物中心：中央挑高中庭（鬼的保全隔離亭），四周一圈店鋪，2F 迴廊商街（由模組化藍圖拼成）。\n')
        if '--barrels' in opt:
            fo.write(f'barrels: {opt["--barrels"]}\n')
        for f in floors:
            fo.write(f'\n[{f}]\n')
            for row in grids[f]:
                fo.write(row.rstrip() + '\n')
        fo.write('\n[furniture]\n' + furn.strip() + '\n\n[palette]\n' + pal.strip() + '\n')
        # 房間名稱（門口的告示牌用）：每個模組的範圍＋藍圖標題裡的名稱
        fo.write('\n[rooms]\n')
        for f in floors:
            for (x1, z1, x2, z2, tok) in module_rects(macro, f):
                name = TITLES.get((f, tok))
                if name:
                    fo.write(f'{f} {x1},{z1} {x2},{z2} = {name}\n')
    if '--md' in opt:
        with open(opt['--md'], 'w', encoding='utf-8', newline='\n') as fo:
            fo.write(f'# {name.group(1) if name else mid}（拼好的原圖）\n\n| 字母 | 名稱 |\n| --- | --- |\n')
            for f in floors:
                fo.write(f'\n## {f}\n\n```text\n' + '\n'.join(grids[f]) + '\n```\n')
    print(f'{src} → {dst}：' + '、'.join(f'{f} {len(grids[f][0])}×{len(grids[f])}' for f in floors))
    print(f'模組 {len(mods)} 個；重疊不一致 {sum(1 for l in log if "→" in l)} 處；錯誤 {sum(1 for l in log if l.startswith("錯誤"))} 個')
    for l in log:
        print('  ' + l)


if __name__ == '__main__':
    main()
