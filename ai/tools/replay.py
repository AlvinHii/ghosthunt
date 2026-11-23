"""把一局的紀錄畫成可以播放的 HTML（兩層樓並排；紅＝鬼、藍＝逃生者、黃框＝拿卡、灰＝被關、×＝淘汰）。"""
import json
import os

import numpy as np

TEMPLATE = r"""<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__</title>
<style>
:root{--bg:#111;--fg:#ddd;--muted:#888}
body{margin:0;background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif}
header{padding:8px 16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
canvas{display:block;margin:0 16px;max-width:calc(100% - 32px);image-rendering:pixelated}
#log{padding:8px 16px;color:var(--muted);max-height:30vh;overflow:auto;white-space:pre}
input[type=range]{flex:1;min-width:200px}
</style></head><body>
<header><button id="play">▶ 播放</button><input id="t" type="range" min="0" value="0"><span id="info"></span></header>
<canvas id="c"></canvas><div id="log"></div>
<script>
const D=__DATA__;const S=4;const cv=document.getElementById('c'),ctx=cv.getContext('2d');
const F=D.floors.length,W=D.W,H=D.H;cv.width=(W*F+F-1)*S;cv.height=H*S;
const bg=document.createElement('canvas');bg.width=cv.width;bg.height=cv.height;const b=bg.getContext('2d');
const col={'#':'#555','%':'#3a5a3a','+':'#776','.':'#262626',',':'#1d2a1d',';':'#2a2a22',' ':'#000'};
for(let f=0;f<F;f++)for(let z=0;z<H;z++){const row=D.floors[f][z]||'';for(let x=0;x<W;x++){const ch=row[x]||' ';
let c=col[ch];if(!c){c=/[a-z]/.test(ch)&&ch!=='h'&&ch!=='v'?'#3b3020':(ch==='D'||ch==='W'?'#6b4a2a':(ch==='J'?'#446':'#262626'))}
b.fillStyle=c;b.fillRect((f*(W+1)+x)*S,z*S,S,S)}}
for(const p of D.pois){b.fillStyle=p.c;b.fillRect((p.f*(W+1)+p.x)*S-1,p.z*S-1,S+2,S+2)}
const T=document.getElementById('t');T.max=D.frames.length-1;
function px(p){return [(p[0]*(W+1)+p[1]+0.5)*S,(p[2]+0.5)*S]}
function draw(i){const fr=D.frames[i];ctx.drawImage(bg,0,0);
 if(fr.goal&&fr.goal[1]){const [x,y]=px(fr.goal[1]);ctx.strokeStyle='#f80';ctx.strokeRect(x-6,y-6,12,12)}
 fr.s.forEach((s,k)=>{if(!s[0])return;const [x,y]=px(s[0]);ctx.fillStyle=s[1]==0?(s[2]?'#58a':'#4af'):'#666';
  ctx.beginPath();ctx.arc(x,y,S*1.2,0,7);ctx.fill();if(s[3]){ctx.strokeStyle='#ff0';ctx.lineWidth=2;ctx.stroke()}
  ctx.fillStyle='#fff';ctx.font='10px sans-serif';ctx.fillText(k+(s[4]?' '+s[4]:''),x+6,y-4)});
 (fr.imps||[]).forEach(p=>{const [x,y]=px(p);ctx.fillStyle='#c6f';ctx.fillRect(x-3,y-3,6,6)});
 if(fr.g){const [x,y]=px(fr.g);ctx.fillStyle='#f33';ctx.beginPath();ctx.arc(x,y,S*1.5,0,7);ctx.fill()}
 document.getElementById('info').textContent=`${fr.t.toFixed(1)} 秒｜電源 ${fr.power?'開':'關'}｜大門 ${fr.gates.map(g=>g[1]?'開':(g[0]>=0?'倒數'+g[0].toFixed(0):'鎖')).join(' ')}｜鬼${fr.gt!==null?' 追 '+fr.gt:''}`;
 document.getElementById('log').textContent=D.events.filter(e=>e[0]<=fr.t).slice(-12).map(e=>e[0].toFixed(1)+'  '+e[1]).join('\n')}
T.oninput=()=>draw(+T.value);let timer=null;
document.getElementById('play').onclick=()=>{if(timer){clearInterval(timer);timer=null;return}
 timer=setInterval(()=>{if(+T.value>=T.max){clearInterval(timer);timer=null;return}T.value=+T.value+1;draw(+T.value)},50)};
draw(0);
</script></body></html>"""


def write_replay(world, game, path, title=None):
    w = world
    pois = []

    def add(i, c):
        f, x, z = w.pos(int(i))
        pois.append({'f': f, 'x': x, 'z': z, 'c': c})

    add(game.lever, '#ff0')
    for q in game.terminals:
        add(q, '#0ff')
    for g in game.gates:
        add(g['reader'], '#0f0')
    for b in game.barrels:
        add(b, '#a64' if b not in game.barrel_card else '#fa0')
    data = {
        'floors': [list(r) if False else r for r in [w.m.g[f] for f in range(w.F)]],
        'W': w.W, 'H': w.H, 'pois': pois, 'frames': game.record,
        'events': game.events,
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    html = TEMPLATE.replace('__DATA__', json.dumps(data, ensure_ascii=False, default=lambda o: o.item() if isinstance(o, np.generic) else str(o)))
    html = html.replace('__TITLE__', title or os.path.basename(path))
    with open(path, 'w', encoding='utf-8') as f:
        f.write(html)
    return path
