// Real ChatPanel + real Ticket, history and Redis room subscription chain.
import {createRequire} from 'node:module';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFile,execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {randomUUID,randomBytes} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'frontend/package.json'));
const {chromium}=require('playwright');
const WebSocket=require('ws');
const fixture=JSON.parse(readFileSync(path.join(root,'load-tests/results/fixture.json'),'utf8'));
const base=fixture.baseUrl;
const run='browser-'+randomUUID().slice(0,8);
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const docker=promisify(execFile);
const nodes=['streamhub-live-service-1','streamhub-live-service-2'];
const accepted=new Map(),direct=new Set(),pending=new Map();
const report={run,realBrowser:true,openedSockets:0,closedSockets:0};
let browser,publisher,target,timer,heartbeat,seq=0;
const output=path.join(root,'load-tests/results/browser-chat-failover.json');
async function waitFor(check,timeout=60000) {
  const end=Date.now()+timeout;
  while(Date.now()<end){if(await check())return;await delay(500);}
  throw new Error('Condition did not converge before deadline');
}
async function api(route,body,account=fixture.participants[0]) {
  const response=await fetch(base+route,{method:body===undefined?'GET':'POST',
    headers:{Authorization:`Bearer ${account.accessToken}`,'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  const result=await response.json();
  if(!response.ok || !result.success)throw new Error(`API rejected ${route}: ${response.status}`);
  return result.data;
}
function metrics(node) {
  const text=execFileSync('docker',['exec',node,'wget','-qO-','http://localhost:8083/actuator/prometheus'],
    {windowsHide:true,timeout:10000,encoding:'utf8'});
  const value=name=>Number(text.match(new RegExp(`^${name} ([0-9.eE+-]+)$`,'m'))?.[1] ?? NaN);
  return {connections:value('streamhub_realtime_send_connections'),subscriptions:value('streamhub_realtime_subscriptions')};
}
async function openPublisher(roomId) {
  const ticket=await api('/api/v1/auth/ws-ticket',{});
  const socket=new WebSocket(`${base.replace(/^http/,'ws')}/ws/chat?roomId=${roomId}&ticket=${encodeURIComponent(ticket.ticket)}`);
  await new Promise((resolve,reject)=>{
    const deadline=setTimeout(()=>{socket.terminate();reject(new Error('Publisher handshake timeout'));},10000);
    socket.on('message',raw=>{
      const event=JSON.parse(raw.toString());
      if(event.type==='CONNECTED'){clearTimeout(deadline);resolve();}
      if(event.type==='CHAT_ACK' && pending.has(event.clientMessageId)){
        accepted.set(pending.get(event.clientMessageId),event.id);pending.delete(event.clientMessageId);
      }
    });
    socket.on('error',()=>{clearTimeout(deadline);reject(new Error('Publisher socket failed'));});
  });
  return socket;
}
try {
  if(nodes.some(node=>metrics(node).connections!==0))throw new Error('Require no other test connections');
  const room=await api('/api/v1/live/rooms',{title:run,category:'load-test'},fixture.anchor);
  await api(`/api/v1/live/rooms/${room.id}/start`,{},fixture.anchor);report.roomId=room.id;
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH || undefined});
  const context=await browser.newContext();
  const signup=await context.request.post(base+'/api/v1/auth/register',{data:{username:run,
    nickname:'browser-drill',password:randomBytes(18).toString('base64url')}});
  if(!signup.ok())throw new Error('Browser registration failed');
  const page=await context.newPage();
  page.on('websocket',socket=>{
    if(!socket.url().includes('/ws/chat'))return;
    report.openedSockets++;
    socket.on('close',()=>{report.closedSockets++;});
    socket.on('framereceived',frame=>{
      try{const event=JSON.parse(String(frame.payload));
        if(event.type==='CHAT' && event.content.startsWith(run+':'))direct.add(event.content);
      }catch{/* Ignore non-chat frames. */}
    });
  });
  await page.goto(`${base}/live/${room.id}`,{waitUntil:'domcontentloaded'});
  const connected=page.locator('.chat-panel .panel-head').getByText('实时连接正常',{exact:true});
  await connected.waitFor({timeout:60000});
  const baseline=Object.fromEntries(nodes.map(node=>[node,metrics(node)]));
  target=nodes.find(node=>baseline[node].connections===1);
  if(!target || nodes.some(node=>baseline[node].connections>1))throw new Error('Ambiguous browser placement');
  for(let attempt=0;attempt<12;attempt++){
    publisher=await openPublisher(room.id);
    if(metrics(target).connections===1)break;
    publisher.close();publisher=null;
    await waitFor(()=>nodes.reduce((sum,node)=>sum+metrics(node).connections,0)===1,10000);
  }
  if(!publisher || metrics(target).connections!==1)throw new Error('Cannot place publisher on surviving node');
  heartbeat=setInterval(()=>publisher?.readyState===WebSocket.OPEN && publisher.send(JSON.stringify({type:'HEARTBEAT'})),20000);
  timer=setInterval(()=>{
    if(seq>=90 || publisher?.readyState!==WebSocket.OPEN)return;
    const id=`${run}-${++seq}`,content=`${run}:${seq}`;
    pending.set(id,content);publisher.send(JSON.stringify({type:'CHAT',clientMessageId:id,content}));
  },1000);
  await delay(12000);
  report.target=target;report.faultStarted=Date.now();
  await docker('docker',['restart','-t','5',target],{windowsHide:true,timeout:30000});
  await waitFor(()=>report.closedSockets>=1 && report.openedSockets>=2,90000);
  await connected.waitFor({timeout:60000});report.browserRecoveredMs=Date.now()-report.faultStarted;
  await waitFor(()=>seq===90 && accepted.size===90,150000);
  clearInterval(timer);timer=undefined;clearInterval(heartbeat);
  await waitFor(async()=>{
    const rendered=await page.locator('.chat-content').allTextContents();
    return [...accepted.keys()].every(content=>rendered.includes(content));
  },60000);
  const rendered=(await page.locator('.chat-content').allTextContents()).filter(content=>content.startsWith(run+':'));
  const recovered=[...accepted.keys()].filter(content=>!direct.has(content));
  if(!recovered.length)throw new Error('No history-only recovery exercised');
  if(rendered.length!==new Set(rendered).size)throw new Error('Duplicate rendered message');
  report.accepted=accepted.size;report.historyOnlyRecovered=recovered.length;report.duplicateRendered=0;
  publisher.close();publisher=null;
  await delay(35000);
  const after=Object.fromEntries(nodes.map(node=>[node,metrics(node)]));report.after=after;
  if(nodes.filter(node=>after[node].connections===0 && after[node].subscriptions===0).length!==1)
    throw new Error('No-audience node did not unsubscribe');
  const master=execFileSync('docker',['exec','streamhub-ha-sentinel-1-1','redis-cli','-p','26379','--raw',
    'SENTINEL','get-master-addr-by-name','streamhub'],{windowsHide:true,encoding:'utf8',timeout:10000}).trim().split(/\r?\n/)[0];
  const redisNode=[1,2,3].map(i=>`streamhub-ha-redis-${i}-1`).find(node=>
    execFileSync('docker',['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',node],
      {windowsHide:true,encoding:'utf8',timeout:10000}).trim()===master);
  if(!redisNode)throw new Error('Cannot identify rehearsal Redis master');
  const channel=`${process.env.REALTIME_CHANNEL_PREFIX || 'live:room:events'}:{${room.id}}`;
  const subscribers=execFileSync('docker',['exec',redisNode,'redis-cli','--raw','PUBSUB','NUMSUB',channel],
    {windowsHide:true,encoding:'utf8',timeout:10000}).trim().split(/\r?\n/);
  report.redisChannelSubscribers=Number(subscribers.at(-1));
  if(report.redisChannelSubscribers!==1)throw new Error('Room channel must have only the viewer node subscribed');
  await page.getByPlaceholder('说点什么',{exact:true}).fill(run+':ui-probe');
  await page.locator('.chat-input').getByRole('button',{name:'发送',exact:true}).click();
  await page.getByText(run+':ui-probe',{exact:true}).waitFor();
  report.uiSendAfterRecovery=true;report.passed=true;
  writeFileSync(output,JSON.stringify(report,null,2));console.log(JSON.stringify(report));
} catch(error) {
  report.passed=false;report.error=error.message.split('\n')[0];
  writeFileSync(output,JSON.stringify(report,null,2));throw error;
} finally {
  clearInterval(timer);clearInterval(heartbeat);publisher?.terminate();
  try{if(target)await docker('docker',['start',target],{windowsHide:true,timeout:30000});}
  finally{await browser?.close();}
}
