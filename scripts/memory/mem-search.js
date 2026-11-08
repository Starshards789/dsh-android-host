// mem-search.js — 分层检索（阶段 3b）
//   node mem-search.js "关键词"          默认：只搜 episodic + semantic（找结论）
//   node mem-search.js "关键词" --deep   深挖：加上 日记/（找原文）
//   archive/ 永远不搜
const fs=require('fs'), http=require('http');
const KEY=fs.readFileSync('/data/adb/dsh-tools/.ovkey','utf8').trim();
const BASE='viking://user/zero-eight/memories';
const q=(process.argv[2]||'').trim();
const deep=process.argv.includes('--deep');
if(!q){console.error('usage: mem-search.js "关键词" [--deep]');process.exit(2);}
const target=deep?[`${BASE}/episodic`,`${BASE}/semantic`,`${BASE}/日记`]:[`${BASE}/episodic`,`${BASE}/semantic`];
// —— 代谢层：命中即记一次使用（本地 JSON，不进 OpenViking）——
const METAB=require('path').join(process.env.DSH_HOME||'/data/user/0/cc.bestly.dsh/files/dshhome','memory','metabolism.json');
function touchMetab(uri){
  try{
    let d={};
    try{ d=JSON.parse(require('fs').readFileSync(METAB,'utf8')); }catch{}
    const e=d[uri]||{first_seen:Date.now(),access_count:0};
    e.last_used=Date.now(); e.access_count=(e.access_count||0)+1; d[uri]=e;
    require('fs').mkdirSync(require('path').dirname(METAB),{recursive:true});
    require('fs').writeFileSync(METAB, JSON.stringify(d,null,1));
  }catch{}
}

function call(body){return new Promise((res,rej)=>{const d=JSON.stringify(body);
 const r=http.request({host:process.env.OV_HOST||'127.0.0.1',port:parseInt(process.env.OV_PORT||'1933',10),path:'/api/v1/search/find',method:'POST',
  headers:{'X-API-Key':KEY,'Content-Type':'application/json','Content-Length':Buffer.byteLength(d)}},
 x=>{let b='';x.on('data',c=>b+=c);x.on('end',()=>res(b));});r.on('error',rej);r.write(d);r.end();});}
(async()=>{
 const out=await call({query:q,target_uri:target,limit:8});
 let j;try{j=JSON.parse(out)}catch{console.error(out.slice(0,200));process.exit(1);}
 const mem=(j.result&&j.result.memories)||[];
 if(!mem.length){console.log('未命中：'+q+(deep?'（含 日记/）':'（已搜 episodic + semantic）'));return;}
 console.log(`命中 ${mem.length} 条（${deep?'深挖模式，含 日记/':'默认模式，只搜 episodic+semantic'}；archive 已排除）\n`);
 mem.forEach((m,i)=>{
  const layer=m.uri.includes('/episodic/')?'episodic':(m.uri.includes('/semantic/')?'semantic':'日记');
  const first=((m.abstract||'').split('\n').find(x=>x.trim() && !/^(---|kind:|status:|confidence:|source:|type:|ref:|created:)/.test(x.trim()))||'').slice(0,70);
  console.log(`${i+1}. [${layer}] ${m.uri.split('/').pop()}  score=${m.score.toFixed(4)}`);
  touchMetab(m.uri);
  console.log(`   ${first}`);
 });
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
