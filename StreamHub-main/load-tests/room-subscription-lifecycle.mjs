// Run from the repository root against an unused room in an isolated fixture.
import {readFile, writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {resolve, sep} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';

const root=process.cwd();
const WebSocket=createRequire(resolve(root,'frontend/package.json'))('ws');
const execute=promisify(execFile);
const fixturePath=resolve(process.argv[2] || '');
const privateRoot=resolve(root,'load-tests/results')+sep;
if (!fixturePath.startsWith(privateRoot)) throw new Error('Use a private local fixture');
const fixture=JSON.parse(await readFile(fixturePath,'utf8'));
if (!['http://localhost:8089','http://127.0.0.1:8089'].includes(fixture.baseUrl)) throw new Error('Only the isolated local rehearsal is supported');
const token=fixture.participants[0].accessToken;
const run='room-lifecycle-'+randomUUID().slice(0,8);
const room=fixture.roomId;
const channel=`live:room:events:{${room}}`;
const sockets=new Set();
const report={run,roomId:room,passed:false};

async function docker(args) {
  return (await execute('docker',args,{timeout:15000,encoding:'utf8'})).stdout.trim();
}
const lines=(await docker(['exec','streamhub-ha-sentinel-1-1','redis-cli','-p','26379','--raw','SENTINEL','get-master-addr-by-name','streamhub'])).split(/\r?\n/);
const [primary,port]=lines;
const allowed=new Set(['redis-1','redis-2','redis-3']);
for(let i=1;i<=3;i++) allowed.add(await docker(['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',`streamhub-ha-redis-${i}-1`]));
if (!allowed.has(primary) || port!=='6379') throw new Error('Unexpected Sentinel primary');
async function redis(args) {
  return docker(['exec','streamhub-ha-sentinel-1-1','redis-cli','-h',primary,'-p',port,'--raw',...args]);
}
async function subscribers() {
  return Number((await redis(['PUBSUB','NUMSUB',channel])).split(/\r?\n/).at(-1));
}
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(probe,ms) {
  const end=Date.now()+ms;
  while(Date.now()<end) { if(await probe()) return; await wait(200); }
  throw new Error('Subscription lifecycle condition timed out');
}
async function connect() {
  const response=await fetch(fixture.baseUrl+'/api/v1/auth/ws-ticket',{
    method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:'{}',
    signal:AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error('Ticket HTTP '+response.status);
  const {data}=await response.json();
  const socket=new WebSocket(`${fixture.baseUrl.replace(/^http/,'ws')}/ws/chat?roomId=${room}&ticket=${encodeURIComponent(data.ticket)}`);
  sockets.add(socket);
  const messages=[];
  let ready=false, error;
  socket.on('message',raw=>{
    const event=JSON.parse(raw.toString());
    messages.push(event);
    if(event.type==='CONNECTED')ready=true;
  });
  socket.on('error',value=>{error=value;});
  socket.on('close',()=>sockets.delete(socket));
  await until(async()=>{if(error)throw error;return ready;},10000);
  return {socket,messages};
}

try {
  if (await subscribers()!==0) throw new Error('Room already has an observer');
  const first=await connect();
  await until(async()=>await subscribers()===1,5000);
  report.subscribersWithOneObserver=await subscribers();
  if (Number((await redis(['PUBSUB','NUMSUB','live:room:events'])).split(/\r?\n/).at(-1))!==0) {
    throw new Error('Unexpected legacy global subscription');
  }
  first.socket.close();
  await until(async()=>first.socket.readyState===WebSocket.CLOSED,5000);
  await wait(1000);
  const second=await connect();
  await wait(31000);
  if(await subscribers()!==1) throw new Error('Rejoin did not cancel the pending unsubscribe');
  const id=run+'-probe';
  second.socket.send(JSON.stringify({type:'CHAT',clientMessageId:id,content:run}));
  await until(async()=>second.messages.some(e=>e.type==='CHAT_ACK' && e.clientMessageId===id)
    && second.messages.some(e=>e.type==='CHAT' && e.content===run),10000);
  report.rejoinedBeforeUnsubscribe=true;
  report.localReceipts=second.messages.filter(e=>e.type==='CHAT' && e.content===run).length;
  if(report.localReceipts!==1)throw new Error('Probe delivery was duplicated');
  second.socket.close();
  await until(async()=>await subscribers()===0,45000);
  report.subscribersAfterLastLeave=await subscribers();
  const presence=await redis(['ZCARD',`live:room:{${room}}:online`]);
  if(Number(presence)!==0)throw new Error('Presence remained after all observers left');
  report.presenceAfterLastLeave=0;
  report.passed=true;
} finally {
  for(const socket of sockets) socket.terminate();
  await writeFile(resolve(root,'load-tests/results',run+'.json'),JSON.stringify(report,null,2));
}
console.log(JSON.stringify(report));
