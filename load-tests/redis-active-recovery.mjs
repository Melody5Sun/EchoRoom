// Run only after capacity accounting: fault the isolated Redis primary with real sockets open.
import {createRequire} from 'node:module';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'frontend/package.json'));
const WebSocket=require('ws');
const execute=promisify(execFile);
const fixture=JSON.parse(readFileSync(path.join(root,'load-tests/results/fixture.json'),'utf8'));
if(!['http://localhost:8089','http://127.0.0.1:8089'].includes(fixture.baseUrl))throw new Error('Local isolated HA only');
const room=fixture.roomId,account=fixture.participants[0];
const run='redis-active-'+randomUUID().slice(0,8);
const nodes=['streamhub-live-service-1','streamhub-live-service-2'];
const report={run,roomId:room,passed:false};
const clients=[];
let stopped,heartbeat;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function docker(args,timeout=15000){return (await execute('docker',args,{windowsHide:true,timeout,maxBuffer:4*1024*1024})).stdout.trim();}
async function redis(node,...args){return docker(['exec',node,'redis-cli','--raw',...args]);}
async function until(check,timeout=60000){
  const end=Date.now()+timeout;
  while(Date.now()<end){const value=await check();if(value)return value;await delay(250);}
  throw new Error('Recovery condition exceeded deadline');
}
async function api(route,body){
  const response=await fetch(fixture.baseUrl+route,{method:body===undefined?'GET':'POST',
    headers:{Authorization:`Bearer ${account.accessToken}`,'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  const result=await response.json();
  if(!response.ok || !result.success)throw new Error(`API probe rejected: ${response.status}`);
  return result.data;
}
async function counts(){
  return Promise.all(nodes.map(async node=>{
    const text=await docker(['exec',node,'wget','-qO-','http://localhost:8083/actuator/prometheus']);
    return Number(text.match(/^streamhub_realtime_send_connections ([0-9.eE+-]+)$/m)?.[1] ?? NaN);
  }));
}
async function connect(){
  const ticket=await api('/api/v1/auth/ws-ticket',{});
  const socket=new WebSocket(`${fixture.baseUrl.replace(/^http/,'ws')}/ws/chat?roomId=${room}&ticket=${encodeURIComponent(ticket.ticket)}`);
  const client={socket,ack:0,closed:false,messages:new Map(),accepted:new Map(),errors:[]};
  clients.push(client);
  await new Promise((resolve,reject)=>{
    const deadline=setTimeout(()=>{socket.terminate();reject(new Error('Ticket handshake timed out'));},15000);
    socket.on('message',raw=>{
      const event=JSON.parse(raw.toString());
      if(event.type==='CONNECTED'){clearTimeout(deadline);resolve();}
      if(event.type==='HEARTBEAT_ACK')client.ack=Date.now();
      if(event.type==='CHAT')client.messages.set(event.content,[...(client.messages.get(event.content)||[]),event.id]);
      if(event.type==='CHAT_ACK')client.accepted.set(event.clientMessageId,event.id);
      if(event.type==='ERROR')client.errors.push(event);
    });
    socket.on('error',()=>{clearTimeout(deadline);reject(new Error('Socket probe failed'));});
    socket.on('close',()=>{client.closed=true;});
  });
  return client;
}
function beat(client){if(client.socket.readyState===WebSocket.OPEN)client.socket.send(JSON.stringify({type:'HEARTBEAT'}));}
async function heartbeatProbe(pair){
  pair.forEach(client=>{client.ack=0;beat(client);});
  await until(()=>pair.every(client=>client.ack>0),8000);
}
function chat(client,id){client.socket.send(JSON.stringify({type:'CHAT',clientMessageId:id,content:id}));}
try{
  if((await counts()).some(value=>value!==0))throw new Error('Drain all other test connections first');
  const first=await connect();
  let second;
  for(let i=0;i<12;i++){
    const candidate=await connect();
    if((await counts()).every(value=>value===1)){second=candidate;break;}
    candidate.socket.close();
    await until(async()=> (await counts()).reduce((a,b)=>a+b,0)===1,10000);
  }
  if(!second)throw new Error('Could not place the two sockets on distinct Live instances');
  const pair=[first,second];
  const addressToNode=new Map();
  for(let i=1;i<=3;i++){
    const name=`streamhub-ha-redis-${i}-1`;
    const address=await docker(['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',name]);
    addressToNode.set(address,name);
    addressToNode.set(`redis-${i}`,name);
  }
  async function primary(){
    const value=await redis('streamhub-ha-sentinel-1-1','-p','26379','SENTINEL','get-master-addr-by-name','streamhub');
    const node=addressToNode.get(value.split(/\r?\n/)[0]);
    if(!node)throw new Error('Sentinel returned a node outside the isolated HA project');
    return node;
  }
  const old=await primary();report.oldPrimary=old;
  await heartbeatProbe(pair);
  // Ensure both replicas contain the pre-fault presence and credentials.
  const fence=execute('docker',['exec','-i',old,'redis-cli','--raw'],{windowsHide:true,timeout:15000});
  fence.child.stdin.end(`SET streamhub:drill:${run} ready EX 120\nWAIT 2 5000\n`);
  if((await fence).stdout.trim().split(/\r?\n/).at(-1)!=='2')throw new Error('Both replicas must be ready');
  const begin=Date.now();
  stopped=old;await docker(['stop','-t','5',old],30000);
  const outage=run+'-outage';chat(first,outage);
  const heartbeatBegin=Date.now();
  await heartbeatProbe(pair);
  await until(()=>first.errors.some(event=>event.code==='UNAVAILABLE'),5000);
  if(first.accepted.has(outage) || pair.some(client=>client.messages.has(outage)))throw new Error('Outage write was unexpectedly accepted');
  report.outageWriteRejected=true;
  pair.forEach(client=>{client.errors=[];});
  report.heartbeatWithOldPrimaryStopped=true;
  report.heartbeatBeforeSentinelPromotion=(await primary())===old;
  report.heartbeatProbeMs=Math.max(...pair.map(client=>client.ack))-heartbeatBegin;
  heartbeat=setInterval(()=>pair.forEach(beat),1000);
  const next=await until(async()=>{const value=await primary();return value!==old ? value : null;},45000);
  report.newPrimary=next;report.promotionObservedMs=Date.now()-begin;
  const channel=`live:room:events:{${room}}`;
  const userKey=`live:room:{${room}}:user:${account.userId}:connections`;
  await until(async()=>{
    const subscribers=(await redis(next,'PUBSUB','NUMSUB',channel)).split(/\r?\n/).at(-1);
    return Number(subscribers)===2 && Number(await redis(next,'ZCARD',userKey))===2;
  });
  report.activeSubscriptions=2;report.presenceConnections=2;
  report.presenceUsers=Number(await redis(next,'ZCARD',`live:room:{${room}}:online`));
  if(report.presenceUsers!==1)throw new Error('Two sockets of the same user must count as one viewer');
  await api('/api/v1/auth/ws-ticket',{});report.ticketAfterFailover=true;
  const probe=run+'-delivery';chat(first,probe);
  await until(()=>first.accepted.has(probe) && pair.every(client=>client.messages.has(probe)));
  const history=await api(`/api/v1/live/rooms/${room}/messages?afterId=${Math.max(0,Number(first.accepted.get(probe))-2)}&limit=100`);
  if(!history.some(message=>message.id===first.accepted.get(probe)))throw new Error('Broadcast probe missing from database history');
  if(history.some(message=>message.content===outage))throw new Error('Rejected outage write reached history');
  report.historyVerified=true;
  const burst=Array.from({length:20},(_,i)=>run+'-burst-'+i);
  const burstBegin=Date.now();
  burst.forEach((id,i)=>chat(pair[i%2],id));
  await until(()=>pair.reduce((sum,client)=>sum+client.accepted.size+client.errors.length,0)>=21,15000);
  const limited=pair.flatMap(client=>client.errors).filter(event=>event.code==='RATE_LIMITED' && event.retryAfterMs>0);
  if(!limited.length || pair.some(client=>client.errors.some(event=>event.code!=='RATE_LIMITED')))throw new Error('Post-failover limiter did not recover cleanly');
  const accepted=new Map(pair.flatMap(client=>[...client.accepted]));
  report.burstElapsedMs=Date.now()-burstBegin;
  report.sharedBucketUpperBound=Math.ceil(5+3*report.burstElapsedMs/1000);
  if(accepted.size-1>report.sharedBucketUpperBound)throw new Error('Default shared 3/s burst-5 user quota was exceeded');
  await until(()=>[...accepted.keys()].every(id=>pair.every(client=>client.messages.has(id))));
  if([...accepted].some(([id,messageId])=>pair.some(client=>client.messages.get(id).length!==1 || client.messages.get(id)[0]!==messageId)))throw new Error('Missing or duplicate cross-node broadcast');
  report.accepted=accepted.size;report.rateLimited=limited.length;report.duplicates=0;
  if(pair.some(client=>client.closed))throw new Error('Established connection was lost');
  report.originalSocketsPreserved=true;
  await docker(['start',old],30000);stopped=undefined;
  await until(async()=>{const info=await redis(old,'INFO','replication');return info.includes('role:slave') && info.includes('master_link_status:up');});
  report.oldPrimaryRejoined=true;
  const replicationKey=`streamhub:drill:${run}:after`;
  await redis(next,'SET',replicationKey,run,'EX','60');
  await until(async()=> (await redis(old,'GET',replicationKey))===run,15000);
  await redis(next,'DEL',replicationKey);
  report.postFailoverReplicationVerified=true;
  clearInterval(heartbeat);pair.forEach(client=>client.socket.close());
  await until(async()=> (await counts()).every(value=>value===0));
  await until(async()=>Number((await redis(next,'PUBSUB','NUMSUB',channel)).split(/\r?\n/).at(-1))===0,45000);
  if(Number(await redis(next,'ZCARD',userKey))!==0 || Number(await redis(next,'ZCARD',`live:room:{${room}}:online`))!==0)throw new Error('Presence did not clear');
  report.cleanupVerified=true;report.passed=true;
}catch(error){report.error=error.message.split('\n')[0];throw error;}
finally{
  clearInterval(heartbeat);clients.forEach(client=>client.socket.terminate());
  try{if(stopped)await docker(['start',stopped],30000);}
  finally{writeFileSync(path.join(root,'load-tests/results/redis-active-recovery.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));}
}
