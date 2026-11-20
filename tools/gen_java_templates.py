"""從 tools/room_plans.py 產生 src/main/java/com/ghosthunt/building/RoomTemplates.java。
改平面圖後執行：python tools/gen_java_templates.py
"""
import importlib.util
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('room_plans', root / 'tools' / 'room_plans.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

ok = True
for name, g in m.ROOMS.items():
    errs, _ = m.check(name, g)
    if errs:
        print(name, errs)
        ok = False
if not ok:
    sys.exit('平面圖檢查沒過，不產生 Java 檔')

# 平面圖名稱（英文部分）→ RoomType
TYPE = {
    'OPEN_OFFICE': 'OPEN_OFFICE', 'CUBICLES': 'CUBICLES', 'BOARDROOM': 'BOARDROOM', 'ARCHIVE': 'ARCHIVE',
    'STORAGE': 'STORAGE', 'LOUNGE': 'LOUNGE', 'ELECTRICAL': 'ELECTRICAL', 'SERVER': 'SERVER',
    'COPY_ROOM': 'COPY_ROOM', 'RESTROOM': 'TOILET', 'JAIL': 'JAIL', 'GHOST_ROOM': 'GHOST_ROOM',
}

lines = []
for name, g in m.ROOMS.items():
    t = TYPE[name.split(' ')[1]]
    rows = ',\n'.join(f'                    "{r}"' for r in g)
    lines.append(f'            Map.entry(RoomType.{t}, new String[]{{ // {name.split(" ")[0]}\n{rows}}})')

java = f'''package com.ghosthunt.building;

import java.util.Map;

/**
 * 房間內部 {m.N}×{m.N} 的平面圖（由 tools/gen_java_templates.py 從 tools/room_plans.py 產生，不要手動改）。
 * 圖例和設計說明見 docs/房間設計v2.md。每局隨機旋轉、鏡像。
 */
final class RoomTemplates {{

    static final int SIZE = {m.N};

    static final Map<RoomType, String[]> TEMPLATES = Map.ofEntries(
{(','+chr(10)).join(lines)});

    private RoomTemplates() {{}}

    /** 輸出座標 (r, c) 對應到原始平面圖的哪一格（跟 at 一樣的鏡像和旋轉），回傳 [列, 行]。 */
    static int[] source(int r, int c, int rot, boolean mirror) {{
        if (mirror) c = SIZE - 1 - c;
        for (int i = 0; i < rot; i++) {{
            int nr = c, nc = SIZE - 1 - r;
            r = nr;
            c = nc;
        }}
        return new int[]{{r, c}};
    }}

    /** 取平面圖上的字元，套用鏡像和旋轉（0～3 個 90 度）。超出範圍回傳空地。 */
    static char at(String[] t, int r, int c, int rot, boolean mirror) {{
        if (r < 0 || c < 0 || r >= SIZE || c >= SIZE) return '.';
        if (mirror) c = SIZE - 1 - c;
        for (int i = 0; i < rot; i++) {{
            int nr = c, nc = SIZE - 1 - r;
            r = nr;
            c = nc;
        }}
        return t[r].charAt(c);
    }}
}}
'''
out = root / 'src/main/java/com/ghosthunt/building/RoomTemplates.java'
out.write_text(java, encoding='utf-8')
print('written', out)
