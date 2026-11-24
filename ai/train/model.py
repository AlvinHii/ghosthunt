"""
高層策略網路（PPO 用）：每個區域用同一個小網路打分數（不管地圖有幾個區域、順序怎麼排都適用），
加一個 GRU 記住之前發生的事；特殊動作從記憶出分數。鬼和逃生者用同一種網路（輸入、動作數不同）。

輸入：obs、h（HID）、mask（True＝可以選）
輸出：logits（不能選的是 -1e9）、value、h'
"""
import torch
import torch.nn as nn

from ..sim.game import GLOBAL_FEATS, N_ACTIONS, REG_FEATS, RMAX

HID = 256
EMB = 64


class Net(nn.Module):
    def __init__(self, reg_feats=REG_FEATS, global_feats=GLOBAL_FEATS, n_actions=N_ACTIONS):
        super().__init__()
        self.rf, self.gf = reg_feats, global_feats
        self.reg = nn.Sequential(nn.Linear(reg_feats, EMB), nn.ReLU(), nn.Linear(EMB, EMB), nn.ReLU())
        self.ctx = nn.Sequential(nn.Linear(2 * EMB + global_feats, HID), nn.ReLU())
        self.gru = nn.GRUCell(HID, HID)
        self.score = nn.Sequential(nn.Linear(EMB + HID, 128), nn.ReLU(), nn.Linear(128, 1))
        self.special = nn.Linear(HID, n_actions - RMAX)
        self.value = nn.Sequential(nn.Linear(HID, 128), nn.ReLU(), nn.Linear(128, 1))

    def forward(self, obs, h, mask):
        b = obs.shape[0]
        reg = obs[:, :RMAX * self.rf].reshape(b, RMAX, self.rf)
        gl = obs[:, RMAX * self.rf:]
        valid = mask[:, :RMAX].float().unsqueeze(-1)
        e = self.reg(reg)
        # 區域有效：鬼的遮罩前 R 個一定是 True；逃生者不是自由狀態時全部 False（只能「繼續」），這時用全部區域做摘要
        anyv = valid.sum(1, keepdim=True) > 0
        valid = torch.where(anyv, valid, torch.ones_like(valid))
        e = e * valid
        mean = e.sum(1) / valid.sum(1).clamp(min=1.0)
        mx = (e - (1.0 - valid) * 1e4).max(1).values
        x = self.ctx(torch.cat([mean, mx, gl], -1))
        h2 = self.gru(x, h)
        s = self.score(torch.cat([e, h2.unsqueeze(1).expand(-1, RMAX, -1)], -1)).squeeze(-1)
        logits = torch.cat([s, self.special(h2)], -1)
        logits = logits.masked_fill(~mask, -1e9)
        return logits, self.value(h2).squeeze(-1), h2


class GhostNet(Net):
    def __init__(self):
        super().__init__()


class SurvivorNet(Net):
    def __init__(self):
        from ..sim.survivor import S_ACTIONS, S_GLOBAL, S_REG
        super().__init__(S_REG, S_GLOBAL, S_ACTIONS)


def load_expand(net, state):
    """載入舊版的權重，輸入特徵或特殊動作變多的時候：舊的欄位照抄，新的輸入欄位從 0 開始，新動作的偏差用舊動作的平均。
    （2026-11-24：觀察 12→15 個區域特徵、12→14 個全域特徵，加了「原地等」「跟著粒子找」兩個動作。）"""
    own = net.state_dict()
    for k, v in state.items():
        if k not in own:
            continue
        t = own[k]
        if t.shape == v.shape:
            own[k] = v.clone()
        elif k == 'reg.0.weight' or k == 'ctx.0.weight':
            t = t.clone()
            t[:, :v.shape[1]] = v
            t[:, v.shape[1]:] = 0
            own[k] = t
        elif k == 'special.weight':
            t = t.clone()
            t[:v.shape[0]] = v
            t[v.shape[0]:] = 0
            own[k] = t
        elif k == 'special.bias':
            t = t.clone()
            t[:v.shape[0]] = v
            t[v.shape[0]:] = v.mean()
            own[k] = t
        else:
            raise ValueError(f'{k}: {tuple(v.shape)} → {tuple(t.shape)} 不知道怎麼轉')
    net.load_state_dict(own)
