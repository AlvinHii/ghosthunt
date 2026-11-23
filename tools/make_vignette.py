"""產生 BetterHud 用的心跳暗角（2026-11-23）：betterhud/ 資料夾，整包放進 plugins/BetterHud/。

4 段心跳 × 3 張（每下最暗 peak、中間 mid、平常 base），純黑、中間透明、四周越來越暗。
images 用 sequence 動畫照心跳速度循環；layouts 用 has_permission:ghosthunt.heart.N 決定顯示哪一段
（插件依心跳段數給玩家這個權限）。
用法：python tools/make_vignette.py
"""
import os
import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'betterhud')
W, H = 480, 270              # 16:9；在遊戲裡再用 layout 的 scale 調到蓋滿畫面
STRENGTH = [0.5, 0.65, 0.8, 0.92]   # 4 段的最大不透明度（最暗那一下）
BPM = [60, 75, 100, 130]
FRAMES = {'peak': 1.0, 'mid': 0.85, 'base': 0.7}


def vignette(alpha_max):
    y, x = np.mgrid[0:H, 0:W]
    nx = (x - W / 2) / (W / 2)
    ny = (y - H / 2) / (H / 2)
    r = np.sqrt(nx ** 2 * 1.0 + ny ** 2 * 1.15)        # 橢圓
    a = np.clip((r - 0.45) / 0.75, 0, 1) ** 1.6       # 中間 45% 完全透明，外面漸暗
    img = np.zeros((H, W, 4), dtype=np.uint8)
    img[..., 0] = 8                                    # 幾乎純黑，帶一點點紅
    img[..., 3] = (a * alpha_max * 255).astype(np.uint8)
    return Image.fromarray(img, 'RGBA')


def main():
    assets = os.path.join(OUT, 'assets', 'ghosthunt')
    for d in [assets, os.path.join(OUT, 'images'), os.path.join(OUT, 'layouts'), os.path.join(OUT, 'huds')]:
        os.makedirs(d, exist_ok=True)
    img_yml, lay_yml = [], []
    hud_yml = ['ghosthunt_heart:', '  layouts:']
    for s in range(4):
        for name, k in FRAMES.items():
            vignette(STRENGTH[s] * k).save(os.path.join(assets, f'heart{s + 1}_{name}.png'))
        beat = round(1200 / BPM[s])                    # 一下幾 tick
        peak, mid = (1, 2) if beat < 10 else (2, 3)
        base = beat - peak - mid
        img_yml += [f'ghosthunt_heart_{s + 1}:', '  type: sequence', '  files:',
                    f'    - ghosthunt/heart{s + 1}_peak.png:{peak}',
                    f'    - ghosthunt/heart{s + 1}_mid.png:{mid}',
                    f'    - ghosthunt/heart{s + 1}_base.png:{base}', '']
        lay_yml += [f'ghosthunt_heart_{s + 1}:', '  images:', '    1:', f'      name: ghosthunt_heart_{s + 1}',
                    '      x: -240', '      y: -135', '  conditions:', '    1:',
                    f'      first: has_permission:ghosthunt.heart.{s + 1}', '      second: true', '      operation: "=="', '']
        hud_yml += [f'    {s + 1}:', f'      name: ghosthunt_heart_{s + 1}', '      gui:', '        x: 50', '        y: 50',
                    '      pixel:', '        x: 0', '        y: 0']
    header = '# 鬼抓人：心跳暗角（tools/make_vignette.py 產生的，不要手改）\n'
    open(os.path.join(OUT, 'images', 'ghosthunt.yml'), 'w', encoding='utf-8').write(header + '\n'.join(img_yml))
    open(os.path.join(OUT, 'layouts', 'ghosthunt.yml'), 'w', encoding='utf-8').write(header + '\n'.join(lay_yml))
    open(os.path.join(OUT, 'huds', 'ghosthunt.yml'), 'w', encoding='utf-8').write(header + '\n'.join(hud_yml) + '\n')
    print('ok', OUT)


if __name__ == '__main__':
    main()
