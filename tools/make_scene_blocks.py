"""
場景物件的 3D 模型改用「方塊展示實體（block display）」顯示（2026-11-23）。

做法：資源包把原版 redstone_wire（紅石線，地圖不用）的方塊狀態換成我們的模型，
每個模型 4 個方向（方塊狀態的 y 旋轉，跟熔爐 facing 一樣的規則），遊戲裡用 block display 顯示那個狀態。
紅石線不會真的放在世界上，只拿來借方塊狀態，所以不會有碰撞、遮擋、光線、更新的問題；
它是 cutout 渲染層，模型貼圖的透明像素會正常挖空。

這支程式產生：
  resourcepack/assets/ghosthunt/models/block/<風格>/<模型>.json   （從 item 模型複製，貼圖改指到 block/）
  resourcepack/assets/ghosthunt/textures/block/<風格>/*.png        （貼圖複製一份到方塊圖集）
  resourcepack/assets/minecraft/blockstates/redstone_wire.json     （multipart：只有我們用的狀態有模型）
  src/main/java/com/ghosthunt/game/SceneBlocks.java                 （模型名稱 → 方塊狀態）

模型正面一律當成朝北（-Z）；講台（note_stand*）、廣播台（pa_desk*）的正面朝南，產生時多轉 180 度。
改了模型包就重跑：python tools/make_scene_blocks.py
"""
import json
import os
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, 'resourcepack', 'assets')
STYLE = 'classic'
SRC_MODELS = os.path.join(PACK, 'ghosthunt', 'models', 'item', STYLE)
SRC_TEX = os.path.join(PACK, 'ghosthunt', 'textures', 'item', STYLE)
OUT_MODELS = os.path.join(PACK, 'ghosthunt', 'models', 'block', STYLE)
OUT_TEX = os.path.join(PACK, 'ghosthunt', 'textures', 'block', STYLE)
BLOCKSTATES = os.path.join(PACK, 'minecraft', 'blockstates', 'redstone_wire.json')
JAVA = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'ghosthunt', 'game', 'SceneBlocks.java')

# 拿在手上的道具不用做成方塊
HANDHELD = {'barricade_item', 'coin', 'flashlight', 'flashlight_on', 'fuse', 'keycard', 'keycard_ok', 'lights_out',
            'medkit', 'move_card', 'roof_key', 'scanner', 'smoke_bomb', 'speed_potion'}
FRONT_SOUTH = ('note_stand', 'pa_desk')  # 正面朝南的模型
SIDES = ['none', 'side', 'up']
FACINGS = ['north', 'east', 'south', 'west']
BASE_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def state(index):
    """第 index 個紅石線狀態（power 0～15 × 四邊 none/side/up）。"""
    power, rest = divmod(index, 81)
    assert power < 16, '狀態不夠用'
    e, rest = divmod(rest, 27)
    n, rest = divmod(rest, 9)
    s, w = divmod(rest, 3)
    return {'east': SIDES[e], 'north': SIDES[n], 'power': str(power), 'south': SIDES[s], 'west': SIDES[w]}


def main():
    models = sorted(f[:-5] for f in os.listdir(SRC_MODELS) if f.endswith('.json') and f[:-5] not in HANDHELD)
    os.makedirs(OUT_MODELS, exist_ok=True)
    os.makedirs(OUT_TEX, exist_ok=True)
    for f in os.listdir(SRC_TEX):
        if f.endswith('.png') or f.endswith('.mcmeta'):
            shutil.copyfile(os.path.join(SRC_TEX, f), os.path.join(OUT_TEX, f))
    item_prefix = f'ghosthunt:item/{STYLE}/'
    block_prefix = f'ghosthunt:block/{STYLE}/'
    for m in models:
        j = json.load(open(os.path.join(SRC_MODELS, m + '.json'), encoding='utf-8'))
        j.pop('display', None)
        j['ambientocclusion'] = False
        j['textures'] = {k: v.replace(item_prefix, block_prefix) for k, v in j.get('textures', {}).items()}
        with open(os.path.join(OUT_MODELS, m + '.json'), 'w', encoding='utf-8') as fo:
            json.dump(j, fo, ensure_ascii=False, separators=(',', ':'))

    parts = []
    for k, m in enumerate(models):
        extra = 180 if m.startswith(FRONT_SOUTH) else 0
        for r, facing in enumerate(FACINGS):
            st = state(k * 4 + r)
            apply = {'model': f'ghosthunt:block/{STYLE}/{m}'}
            y = (BASE_Y[facing] + extra) % 360
            if y:
                apply['y'] = y
            parts.append({'when': st, 'apply': apply})
    os.makedirs(os.path.dirname(BLOCKSTATES), exist_ok=True)
    with open(BLOCKSTATES, 'w', encoding='utf-8') as fo:
        json.dump({'multipart': parts}, fo, ensure_ascii=False, separators=(',', ':'))

    names = ',\n            '.join('"%s"' % m for m in models)
    java = f'''package com.ghosthunt.game;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * 場景模型 → 方塊狀態（tools/make_scene_blocks.py 產生的，不要手改）。
 * 資源包把紅石線（redstone_wire）的狀態換成我們的模型，用 block display 顯示；每個模型 4 個方向。
 */
public final class SceneBlocks {{

    private SceneBlocks() {{
    }}

    private static final String[] MODELS = {{
            {names}
    }};
    private static final String[] SIDES = {{"none", "side", "up"}};
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {{
        for (int i = 0; i < MODELS.length; i++) INDEX.put(MODELS[i], i);
    }}

    public static boolean has(String model) {{
        return INDEX.containsKey(model);
    }}

    /** 模型正面朝 front（東南西北）的方塊狀態；沒有這個模型就回傳 null。 */
    public static BlockData data(String model, BlockFace front) {{
        Integer k = INDEX.get(model);
        if (k == null) return null;
        int r = switch (front) {{
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        }};
        int i = k * 4 + r;
        int power = i / 81, rest = i % 81;
        String s = "minecraft:redstone_wire[east=" + SIDES[rest / 27] + ",north=" + SIDES[rest / 9 % 3]
                + ",power=" + power + ",south=" + SIDES[rest / 3 % 3] + ",west=" + SIDES[rest % 3] + "]";
        return Bukkit.createBlockData(s);
    }}
}}
'''
    with open(JAVA, 'w', encoding='utf-8', newline='\n') as fo:
        fo.write(java)
    print(f'{len(models)} 個模型、{len(parts)} 個方塊狀態')


if __name__ == '__main__':
    main()
