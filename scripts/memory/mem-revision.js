// mem-revision.js — 记忆变更（revision）流程最小版
// 背景：审核（写不写）已有；revision（写错了怎么改/撤销）没有。
// 用法：
//   node mem-revision.js move <from_uri> <to_uri> <reason>
//   node mem-revision.js log [n]
const fs=require('fs'), path=require('path'), http=require('http');
const KEY=fs.readFileSync('/data/adb/dsh-tools/.ovkey','utf8').trim();
const HOME=process.env.DSH_HOME||'/data/user/0/cc.bestly.dsh/files/dshhome';
const LOG=path.join(HOME,'memory','revisions.jsonl');

function call(p,body){return new Promise((res,rej)=>{const d=JSON.stringify(body);
 const r=http.request({host:process.env.OV_HOST||'127.0.0.1',port:parseInt(process.env.OV_PORT||'1933',10),path:p,method:'POST',
  headers:{'X-API-Key':KEY,'Content-Type':'application/json','Content-Length':Buffer.byteLength(d)}},
 x=>{let b='';x.on('data',c=>b+=c);x.on('end',()=>res(b));});r.on('error',rej);r.write(d);r.end();});}
function append(rec){
  fs.mkdirSync(path.dirname(LOG),{recursive:true});
  fs.appendFileSync(LOG, JSON.stringify(rec)+'\n');
}
const cmd=process.argv[2];
(async()=>{
  if(cmd==='move'){
    const [from,to,reason]=process.argv.slice(3);
    if(!from||!to||!reason){console.error('usage: move <from_uri> <to_uri> <reason>');process.exit(2);}
    const out=await call('/api/v1/fs/mv',{from_uri:from,to_uri:to});
    let j=null;try{j=JSON.parse(out)}catch{}
    const ok=j&&j.status==='ok';
    append({ts:new Date().toISOString(),action:'move',from,to,reason,ok});
    // 同步 commit（只影响记忆根，避免波及别处）
    // commit 的 paths 必须精确到「变动所在的目录」，不能写整棵 memories
    // （2026-11-08 实测：写整个 memories 会把历史积压一起提交，一次 move 报 243 变化）
    const dirOf = u => u.replace(/\/[^/]+$/, '');
    const paths = [...new Set([dirOf(from), dirOf(to)].filter(Boolean))];
    let c='';
    if(ok) c=await call('/api/v1/snapshot/commit',{message:`revision: move (${reason.slice(0,40)})`,paths});
    console.log(JSON.stringify({moved:ok,commit:c.slice(0,120)},null,1));
  } else if(cmd==='log'){
    const n=parseInt(process.argv[3]||'20',10);
    const rows=fs.existsSync(LOG)?fs.readFileSync(LOG,'utf8').split('\n').filter(Boolean).slice(-n):[];
    rows.forEach(r=>{try{const j=JSON.parse(r);
      console.log(`${j.ts.slice(0,16)} ${j.ok?'ok ':'FAIL'} ${j.action} ${(j.from||'').split('/').pop()} → ${(j.to||'').split('/').pop()} | ${j.reason}`);}catch{}});
    if(!rows.length) console.log('（还没有 revision 记录）');
  } else {
    console.error('usage: move <from> <to> <reason> | log [n]');
    process.exit(2);
  }
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
