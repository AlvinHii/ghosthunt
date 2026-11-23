"""讀插件的 config.yml（數值只有這一份，插件和模擬器共用）。"""
import os

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONFIG = os.path.join(ROOT, 'src', 'main', 'resources', 'config.yml')


def load(difficulty='normal'):
    with open(CONFIG, encoding='utf-8') as f:
        c = yaml.safe_load(f)
    ai = dict(c['ai-ghost'])
    preset = (ai.get('presets') or {}).get(difficulty) or {}
    ai.update(preset)
    c['ai'] = ai
    c['difficulty'] = difficulty
    return c


def by_survivors(lst, n):
    """config 裡「依人數 3／4／5」的清單：2～3 人用第一個。"""
    i = max(0, min(len(lst) - 1, n - 3))
    return lst[i]
