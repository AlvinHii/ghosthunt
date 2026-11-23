"""
鬼的高層策略網路（PPO 用）：每個區域用同一個小網路打分數（不管地圖有幾個區域、順序怎麼排都適用），
加一個 GRU 記住之前發生的事；特殊動作（去聲音、關電源、弄壞大門、守監獄）從記憶出分數。

輸入：obs（OBS_DIM）、h（HID）、mask（N_ACTIONS，True＝可以選）
輸出：logits（N_ACTIONS，不能選的是 -1e9）、value、h'
"""
import torch
import torch.nn as nn

from ..sim.game import GLOBAL_FEATS, N_ACTIONS, REG_FEATS, RMAX

HID = 256
EMB = 64


class GhostNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.reg = nn.Sequential(nn.Linear(REG_FEATS, EMB), nn.ReLU(), nn.Linear(EMB, EMB), nn.ReLU())
        self.ctx = nn.Sequential(nn.Linear(2 * EMB + GLOBAL_FEATS, HID), nn.ReLU())
        self.gru = nn.GRUCell(HID, HID)
        self.score = nn.Sequential(nn.Linear(EMB + HID, 128), nn.ReLU(), nn.Linear(128, 1))
        self.special = nn.Linear(HID, N_ACTIONS - RMAX)
        self.value = nn.Sequential(nn.Linear(HID, 128), nn.ReLU(), nn.Linear(128, 1))

    def forward(self, obs, h, mask):
        b = obs.shape[0]
        reg = obs[:, :RMAX * REG_FEATS].reshape(b, RMAX, REG_FEATS)
        gl = obs[:, RMAX * REG_FEATS:]
        valid = mask[:, :RMAX].float().unsqueeze(-1)
        e = self.reg(reg) * valid
        mean = e.sum(1) / valid.sum(1).clamp(min=1.0)
        mx = (e - (1.0 - valid) * 1e4).max(1).values
        x = self.ctx(torch.cat([mean, mx, gl], -1))
        h2 = self.gru(x, h)
        s = self.score(torch.cat([e, h2.unsqueeze(1).expand(-1, RMAX, -1)], -1)).squeeze(-1)
        logits = torch.cat([s, self.special(h2)], -1)
        logits = logits.masked_fill(~mask, -1e9)
        return logits, self.value(h2).squeeze(-1), h2
