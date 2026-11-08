// mem-metabolism.js — 记忆代谢层（最小版）
// 记录每条记忆的「使用情况」：last_used / access_count / weight
// 数据存本地 JSON（不进 OpenViking、不进快照）——这是「代谢数据」，可丢可重建
// 用法：
//   node mem-metabolism.js touch <uri>     记录一次使用
//   node mem-metabolism.js list            按 weight 排序列出
//   node mem-metabolism.js stats           汇总
const fs=require('fs'), path=require('path');
const HOME=process.env.DSH_HOME||'/data/user/0/cc.bestly.dsh/files/dshhome';
const FILE=path.join(HOME,'memory','metabolism.json');
const HALF_LIFE_DAYS=30;

function load(){
  try { return JSON.parse(fs.readFileSync(FILE,'utf8')); } catch { return {}; }
}
function save(d){
  fs.mkdirSync(path.dirname(FILE),{recursive:true});
  fs.writeFileSync(FILE, JSON.stringify(d,null,1));
}
function weightOf(e, now=Date.now()){
  const days=(now-(e.last_used||now))/86400000;
  const decay=Math.pow(0.5, days/HALF_LIFE_DAYS);      // 30 天半衰
  const base=1+Math.log2(1+(e.access_count||0))*0.5;   // 访问越多越重（边际递减）
  return +(base*decay).toFixed(3);
}
function touch(uri){
  const d=load();
  const e=d[uri]||{first_seen:Date.now(),access_count:0};
  e.last_used=Date.now();
  e.access_count=(e.access_count||0)+1;
  e.weight=weightOf(e);
  d[uri]=e;
  save(d);
  return e;
}
const cmd=process.argv[2]||'stats';
if(cmd==='touch'){
  const uri=process.argv[3];
  if(!uri){console.error('usage: touch <uri>');process.exit(2);}
  console.log(JSON.stringify(touch(uri)));
} else if(cmd==='list'){
  const d=load(), now=Date.now();
  const rows=Object.entries(d).map(([u,e])=>({uri:u,count:e.access_count||0,
    last_used:new Date(e.last_used||0).toISOString().slice(0,16),weight:weightOf(e,now)}))
    .sort((a,b)=>b.weight-a.weight);
  console.log(JSON.stringify({entries:rows.length,rows},null,1));
} else {
  const d=load(), now=Date.now();
  const rows=Object.entries(d).map(([uri,e])=>({uri,count:e.access_count||0,
    last:new Date(e.last_used||0).toISOString().slice(0,16),weight:weightOf(e,now)}));
  const w=rows.map(r=>r.weight);
  const byCount=[...rows].sort((a,b)=>b.count-a.count);
  console.log(JSON.stringify({
    entries:rows.length,
    total_access:rows.reduce((s,r)=>s+r.count,0),
    avg_weight:rows.length?+(w.reduce((a,b)=>a+b,0)/rows.length).toFixed(3):0,
    max_weight:w.length?Math.max(...w):0,
    top10:byCount.slice(0,10),
    never_touched:rows.filter(r=>r.count===0).length,
    file:FILE,
    note:'weight 只用于统计，不参与检索排序（避免马太效应：越常搜越压过更相关的条目）'
  },null,1));
}
