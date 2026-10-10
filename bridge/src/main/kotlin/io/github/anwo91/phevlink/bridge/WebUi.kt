package io.github.anwo91.phevlink.bridge

/** The bridge's built-in control page. Works from any browser on your network or VPN. */
object WebUi {
    val HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PHEV Link Bridge</title>
<style>
:root{--bg:#f4f6f7;--card:#fff;--ink:#14212b;--muted:#61707c;--accent:#0f8a7a;--accent-ink:#fff;--warn:#c4581c;--bad:#b3261e;--line:#dde3e7}
@media (prefers-color-scheme:dark){:root{--bg:#0d1417;--card:#162126;--ink:#e6eef1;--muted:#93a4ad;--accent:#3fd0b8;--accent-ink:#06211c;--warn:#ffb070;--bad:#ff8a80;--line:#25343b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:16px}
header{display:flex;align-items:center;justify-content:space-between;margin:4px 0 14px}
h1{font-size:19px;margin:0}.chip{font-size:13px;padding:4px 10px;border-radius:99px;background:var(--line)}
.chip.ok{background:var(--accent);color:var(--accent-ink)}.chip.bad{background:var(--warn);color:#000}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin-bottom:12px}
.big{font-size:44px;font-weight:700;line-height:1}.muted{color:var(--muted);font-size:13px}
.row{display:flex;gap:8px;flex-wrap:wrap}.row>*{flex:1}
button{font:inherit;border:0;border-radius:12px;padding:12px 10px;background:var(--line);color:var(--ink);cursor:pointer}
button.primary{background:var(--accent);color:var(--accent-ink);font-weight:600}button.sel{outline:2px solid var(--accent)}
button:disabled{opacity:.5}.bar{height:10px;border-radius:99px;background:var(--line);overflow:hidden;margin:10px 0}
.bar>div{height:100%;background:var(--accent);width:0}
dl{display:grid;grid-template-columns:auto 1fr;gap:4px 12px;margin:0}dt{color:var(--muted)}dd{margin:0;text-align:right}
#toast{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);background:var(--ink);color:var(--bg);padding:10px 16px;border-radius:12px;display:none;max-width:90vw}
pre{white-space:pre-wrap;font-size:12px;max-height:280px;overflow:auto;margin:0}
input{font:inherit;padding:10px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--ink);width:100%}
</style>
</head>
<body><main>
<header><h1>PHEV Link</h1><span id="link" class="chip">…</span></header>

<section id="login" class="card" style="display:none">
  <p>Enter the bridge token (printed when the bridge first started, also in its config file).</p>
  <input id="tok" placeholder="token" autocomplete="off"><p></p>
  <button class="primary" onclick="saveToken()">Continue</button>
</section>

<div id="app" style="display:none">
<section class="card">
  <div class="row" style="align-items:flex-end">
    <div><div class="big" id="bat">–</div><div class="muted" id="chg">–</div></div>
    <div style="text-align:right" class="muted" id="upd">–</div>
  </div>
  <div class="bar"><div id="batbar"></div></div>
</section>

<section class="card">
  <div class="row" style="margin-bottom:8px"><strong id="clim">Climate</strong></div>
  <div class="row" id="modes">
    <button data-m="HEAT">Heat</button><button data-m="COOL">Cool</button><button data-m="WINDSCREEN">Defrost</button>
  </div><p></p>
  <div class="row" id="durs"><button data-d="10">10 min</button><button data-d="20">20 min</button><button data-d="30">30 min</button></div><p></p>
  <div class="row"><button class="primary" onclick="climate()">Start</button><button onclick="cmd({action:'climate_off'})">Stop</button></div>
</section>

<section class="card">
  <div class="row">
    <button onclick="cmd({action:'locate',seconds:5})">Flash lights</button>
    <button onclick="cmd({action:'refresh'})">Refresh</button>
  </div><p></p>
  <div class="row">
    <button onclick="cmd({action:'headlights',on:!state.car.headlights})" id="hl">Headlights</button>
    <button onclick="cmd({action:'parking_lights',on:!state.car.parkingLights})" id="pl">Parking lights</button>
  </div>
</section>

<section class="card"><dl id="facts"></dl></section>
<section class="card"><div class="row"><button onclick="post('connect')">Connect now</button><button onclick="post('disconnect')">Disconnect</button><button onclick="showLog()">Log</button></div><pre id="log"></pre></section>
</div>
<div id="toast"></div>
</main>
<script>
let token = localStorage.getItem('phevToken') || new URLSearchParams(location.search).get('token') || '';
let state = {car:{}, link:{}}, mode = 'HEAT', dur = 20, es;
const ${'$'} = id => document.getElementById(id);
function saveToken(){ token = ${'$'}('tok').value.trim(); localStorage.setItem('phevToken', token); start(); }
function toast(t){ const el=${'$'}('toast'); el.textContent=t; el.style.display='block'; clearTimeout(el._t); el._t=setTimeout(()=>el.style.display='none',3500); }
function hdr(){ return {'Authorization':'Bearer '+token,'Content-Type':'application/json'}; }
async function cmd(c){ toast('Sending…'); try{ const r=await fetch('api/command',{method:'POST',headers:hdr(),body:JSON.stringify(c)}); const j=await r.json(); toast(j.ok?j.message:'Failed: '+j.error);}catch(e){toast('Bridge unreachable');} }
async function post(p){ await fetch('api/'+p,{method:'POST',headers:hdr()}); toast(p==='connect'?'Connecting…':'Disconnected'); }
async function showLog(){ const r=await fetch('api/log?n=200',{headers:hdr()}); ${'$'}('log').textContent=await r.text(); }
function climate(){ cmd({action:'climate',mode:mode,minutes:dur}); }
document.querySelectorAll('#modes button').forEach(b=>b.onclick=()=>{mode=b.dataset.m;sel();});
document.querySelectorAll('#durs button').forEach(b=>b.onclick=()=>{dur=+b.dataset.d;sel();});
function sel(){ document.querySelectorAll('#modes button').forEach(b=>b.classList.toggle('sel',b.dataset.m===mode)); document.querySelectorAll('#durs button').forEach(b=>b.classList.toggle('sel',+b.dataset.d===dur)); }
function ago(ms){ if(!ms) return 'never'; const s=Math.round((Date.now()-ms)/1000); if(s<60) return 'just now'; if(s<3600) return Math.round(s/60)+' min ago'; if(s<86400) return Math.round(s/3600)+' h ago'; return Math.round(s/86400)+' d ago'; }
function render(){
  const c=state.car||{}, l=state.link||{};
  const ok=l.phase==='connected'; const lk=${'$'}('link');
  lk.textContent= ok?'Connected': l.phase==='retrying'?'Retrying':l.phase==='idle'?'Idle (interval mode)':l.phase||'…';
  lk.className='chip '+(ok?'ok':l.phase==='retrying'?'bad':'');
  ${'$'}('bat').textContent = c.batteryPercent!=null? c.batteryPercent+'%':'–';
  ${'$'}('batbar').style.width=(c.batteryPercent||0)+'%';
  ${'$'}('chg').textContent = c.charging? 'Charging'+(c.chargeMinutesRemaining?' · '+c.chargeMinutesRemaining+' min left':'') : c.plug==='PLUGGED'?'Plugged in':'Not plugged in';
  ${'$'}('upd').textContent='Updated '+ago(c.updatedAtMs);
  const cl=c.climate||{}; ${'$'}('clim').textContent = cl.state==='RUNNING'? 'Climate running · '+(cl.mode||'')+' '+(cl.durationMinutes||'')+' min' : cl.state==='CANCELLED'?'Climate stopped by car (door/battery)':'Climate off';
  ${'$'}('hl').textContent='Headlights '+(c.headlights?'off':'on'); ${'$'}('pl').textContent='Parking lights '+(c.parkingLights?'off':'on');
  const d=c.doors||{}, open=Object.keys(d).filter(k=>d[k]);
  const facts=[['Locked',c.locked==null?'–':c.locked?'Yes':'No'],['Open',open.length?open.join(', '):'nothing'],['Ignition',(c.ignition||'').toLowerCase()],['VIN',c.vin||'–'],['Phones registered',c.registrations??'–'],['Bridge mode',(state.bridge||{}).mode||'']];
  if(l.error) facts.push(['Last error',l.error]);
  ${'$'}('facts').innerHTML=facts.map(f=>'<dt>'+f[0]+'</dt><dd>'+String(f[1]).replace(/</g,'&lt;')+'</dd>').join('');
}
function start(){
  if(!token){ ${'$'}('login').style.display='block'; return; }
  ${'$'}('login').style.display='none'; ${'$'}('app').style.display='block'; sel();
  if(es) es.close();
  es=new EventSource('api/events?token='+encodeURIComponent(token));
  es.addEventListener('status',e=>{state=JSON.parse(e.data);render();});
  es.onerror=()=>{ fetch('api/status',{headers:hdr()}).then(r=>{ if(r.status===401){ localStorage.removeItem('phevToken'); token=''; es.close(); start(); toast('Wrong token'); } }).catch(()=>{}); };
  setInterval(render,30000);
}
start();
</script>
</body></html>
""".trimIndent()
}
