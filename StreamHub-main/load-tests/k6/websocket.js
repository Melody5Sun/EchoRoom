import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter, Rate } from 'k6/metrics';
const delay = new Trend('broadcast_delay', true);
const gaps = new Counter('attempt_sequence_gaps');
const received = new Counter('broadcast_received');
const chatAcceptance = new Rate('chat_acceptance');
const chatRejected = new Counter('chat_rejected');
const handshakes = new Rate('handshake_success');
const connectionFailures = new Counter('connection_failures');
const historySuccess = new Rate('history_sync_success');
const historyReads = new Counter('history_sync_reads');
const heartbeatSuccess = new Rate('heartbeat_success');
const tokens = (__ENV.ACCESS_TOKENS || '').split(',').filter(Boolean);
if (!tokens.length) throw new Error('ACCESS_TOKENS is required for real ticket authentication');
const hold=Number(__ENV.HOLD_SECONDS || 1800);
const connectRate=Number(__ENV.CONNECT_RATE || 6);
const ramp=Number(__ENV.RAMP_SECONDS || Math.ceil(Number(__ENV.VUS || 100)/connectRate));
const stageSeconds=hold+ramp;
const rooms=(__ENV.ROOM_IDS || __ENV.ROOM_ID || '1').split(',');
const historySync=__ENV.HISTORY_SYNC==='1';
const vus=Number(__ENV.VUS || 100);
let recentIds=[];
let historyIndex;
let connectionIndex;
export const options = {
  summaryTrendStats: ['avg','min','med','max','p(90)','p(95)','p(99)'],
  scenarios:{connections:{executor:'constant-vus',vus,duration:`${stageSeconds}s`,gracefulStop:'15s'},
    ...(historySync ? {history:{executor:'constant-vus',exec:'syncHistory',vus,duration:`${stageSeconds}s`,gracefulStop:'15s'}} : {})},
  thresholds:{...((__ENV.ROLE || 'receiver')==='publisher' ? {chat_acceptance:['rate>0.9']} : {}),
    ...(historySync ? {history_sync_success:['rate>0.99'],history_sync_reads:['count>0'],heartbeat_success:['rate>0.99']} : {}),
    handshake_success:['rate>0.99'],broadcast_delay:['p(95)<300'],broadcast_received:['count>0']},
};
export function syncHistory() {
  if (historyIndex === undefined) historyIndex=exec.scenario.iterationInTest;
  const index=historyIndex;
  const deadline=exec.scenario.startTime+stageSeconds*1000;
  if (exec.vu.iterationInScenario===0) sleep(index/connectRate+Math.random()*2);
  if (Date.now()>=deadline) return;
  const root=__ENV.BASE_URL || 'http://localhost:8089';
  const room=rooms[index%rooms.length];
  const headers={Authorization:`Bearer ${tokens[index%tokens.length]}`};
  let cursor=recentIds[0] || 0;
  for (let page=0;page<20 && Date.now()<deadline;page++) {
    const result=http.get(`${root}/api/v1/live/rooms/${room}/messages?afterId=${cursor}&limit=100`,{
      headers, timeout:'3s', tags:{operation:'chat_history',name:'chat_history'},
    });
    let body;
    try { body=result.json(); } catch (_) { /* counted as a failure */ }
    const ok=result.status===200 && Array.isArray(body?.data);
    historySuccess.add(ok); historyReads.add(1);
    if (!ok) {
      console.warn(`history sync failed status=${result.status}`);
      break;
    }
    if (!body.data.length) break;
    const ids=body.data.map(item=>item.id);
    recentIds=[...new Set(recentIds.concat(ids))].sort((a,b)=>a-b).slice(-100);
    const next=Math.max(...ids);
    if (body.data.length<100 || next<=cursor) break;
    cursor=next;
  }
  sleep(Math.min(30+Math.random()*10,Math.max(0,(deadline-Date.now())/1000)));
}
export default function () {
  if (connectionIndex === undefined) connectionIndex=historySync ? exec.scenario.iterationInTest : __VU-1;
  if (exec.vu.iterationInScenario===0) sleep(connectionIndex/connectRate);
  else sleep(1+Math.random()*4);
  const deadline=exec.scenario.startTime+stageSeconds*1000;
  if (Date.now()>=deadline) { sleep(5); return; }
  const root=__ENV.BASE_URL || 'http://localhost:8089';
  const token=tokens[connectionIndex%tokens.length];
  const ticket=http.post(root+'/api/v1/auth/ws-ticket','{}',{headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},timeout:'5s'});
  if (ticket.status!==200) {
    connectionFailures.add(1,{phase:'ticket',status:String(ticket.status)});
    console.warn(`ticket rejected status=${ticket.status}`);
    handshakes.add(false); return;
  }
  const room=rooms[connectionIndex%rooms.length];
  const base=__ENV.WS_URL || root.replace(/^http/,'ws');
  const role=__ENV.ROLE || 'receiver';
  gaps.add(0); received.add(0);
  const last={}; const pending={}; let confirmed=0; let sequence=0; let connected=false; let heartbeat=0; let closeCode=0;
  const res=ws.connect(`${base}/ws/chat?roomId=${room}&ticket=${encodeURIComponent(ticket.json().data.ticket)}`,{},socket=>{
    socket.on('open',()=>{
      if (__ENV.AUDIT==='1') console.log(JSON.stringify({audit:'open',run:__ENV.RUN_ID || 'ws',receiver:__VU,role,room,at:Date.now()}));
      socket.setInterval(()=>{socket.send(JSON.stringify({type:'HEARTBEAT'}));},20000);
      if (role!=='receiver') socket.setInterval(()=>{
        const clientMessageId=`${__ENV.RUN_ID || 'ws'}-${__VU}-${__ITER}-${++sequence}`;
        const sample={run:__ENV.RUN_ID || 'ws',publisher:`${__VU}-${__ITER}`,sequence,sentAt:Date.now()};
        pending[clientMessageId]=sample;
        socket.send(JSON.stringify({type:'CHAT',clientMessageId,content:JSON.stringify(sample)}));
      },Number(__ENV.CHAT_INTERVAL_MS || 10000));
    });
    socket.on('message',raw=>{
      const event=JSON.parse(raw);
      if (event.type==='CONNECTED' && !connected) {
        connected=true;
        if (__ENV.AUDIT==='1') console.log(JSON.stringify({audit:'ready',run:__ENV.RUN_ID || 'ws',receiver:__VU,role,room,at:Date.now()}));
      }
      if (event.type==='HEARTBEAT_ACK') heartbeat++;
      if (event.type==='ERROR') chatRejected.add(1,{code:event.code || 'unknown'});
      if (event.type==='CHAT_ACK' && pending[event.clientMessageId]) {
        const sample=pending[event.clientMessageId]; delete pending[event.clientMessageId]; confirmed++;
        if (__ENV.AUDIT==='1') console.log(JSON.stringify({audit:'accepted',run:sample.run,role,receiver:__VU,publisher:sample.publisher,sequence:sample.sequence,room,id:event.id,at:Date.now(),sentAt:sample.sentAt}));
      }
      if (event.type!=='CHAT') return;
      let sample; try { sample=JSON.parse(event.content); } catch (_) { return; }
      if (sample.run===(__ENV.RUN_ID || 'ws')) {
        delay.add(Math.max(0,Date.now()-sample.sentAt)); received.add(1);
        if (__ENV.AUDIT==='1') console.log(JSON.stringify({audit:'message',run:sample.run,role,receiver:__VU,publisher:sample.publisher,sequence:sample.sequence,room,id:event.id,at:Date.now(),sentAt:sample.sentAt}));
        const previous=last[sample.publisher];
        if (previous && sample.sequence>previous+1) gaps.add(sample.sequence-previous-1);
        last[sample.publisher]=Math.max(previous||0,sample.sequence);
      }
    });
    socket.on('close',code=>{closeCode=Number(code)||0;});
    socket.setTimeout(()=>socket.close(),Math.max(1000,deadline-Date.now()));
  });
  if (__ENV.AUDIT==='1') console.log(JSON.stringify({audit:'close',run:__ENV.RUN_ID || 'ws',role,receiver:__VU,room,at:Date.now()}));
  if (role==='publisher') {
    for (let n=0;n<sequence;n++) chatAcceptance.add(n<confirmed);
    if (sequence===0) chatAcceptance.add(false);
  }
  handshakes.add(!!res && res.status===101 && connected);
  if (!res || res.status!==101 || !connected) {
    connectionFailures.add(1,{phase:res?.status===101?'protocol':'upgrade',status:String(res?.status || 0)});
    console.warn(`websocket rejected status=${res?.status || 0} connected=${connected} closeCode=${closeCode}`);
  }
  check(heartbeat,{'heartbeat received':n=>n>0});
  if (historySync) heartbeatSuccess.add(heartbeat>0);
  console.log(JSON.stringify({run:__ENV.RUN_ID,receiver:__VU,room,published:sequence,lastSeen:last,heartbeat}));
  if (Date.now()>=deadline) sleep(5);
}
