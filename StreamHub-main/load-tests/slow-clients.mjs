// Genuine backpressure: pause the TCP reader, not just the message callback.
import { createRequire } from 'node:module';
let WebSocket;
try { WebSocket = createRequire(import.meta.url)('ws'); }
catch { WebSocket = createRequire(new URL('../frontend/package.json', import.meta.url))('ws'); }
const tokens=(process.env.ACCESS_TOKENS || '').split(',').filter(Boolean);
if (!tokens.length) throw new Error('ACCESS_TOKENS required');
const base=process.env.BASE_URL || 'http://localhost:8089';
const count=Number(process.env.SLOW_CONNECTIONS || 10);
const rooms=(process.env.ROOM_IDS || process.env.ROOM_ID || '1').split(',');
const attempts=new Map();
const openedIndices=new Set();
const counters={attempts:0,opened:0,closed:0,retries:0,forcedCloses:0};
const sockets=new Set(); let finished=false;
function retry(index) {
  if(finished)return;
  const attempt=(attempts.get(index)||0)+1;attempts.set(index,attempt);counters.retries++;
  const wait=Math.min(30000,1000*2**Math.min(attempt-1,5))*(0.8+Math.random()*0.4);
  setTimeout(()=>connect(index),wait);
}
async function connect(index) {
  if (finished) return;
  counters.attempts++;
  try {
    const result=await fetch(base+'/api/v1/auth/ws-ticket',{method:'POST',headers:{Authorization:`Bearer ${tokens[index%tokens.length]}`,'Content-Type':'application/json'},body:'{}',signal:AbortSignal.timeout(5000)});
    if (!result.ok) throw new Error(`ticket HTTP ${result.status}`);
    const {data}=await result.json();
    if (finished) return;
    const socket=new WebSocket(`${base.replace(/^http/,'ws')}/ws/chat?roomId=${rooms[index%rooms.length]}&ticket=${encodeURIComponent(data.ticket)}`);
    sockets.add(socket); let heartbeat,stable,lifetime;
    socket.on('open',()=>{
      counters.opened++;
      openedIndices.add(index);
      socket._socket.pause();
      stable=setTimeout(()=>attempts.delete(index),30000);
      // Optional client watchdog: exercise reconnect even when paused reads hide CLOSE.
      const maxLifetime=Number(process.env.SLOW_MAX_LIFETIME_MS || 0);
      if(maxLifetime>0)lifetime=setTimeout(()=>{counters.forcedCloses++;socket.terminate();},maxLifetime);
      heartbeat=setInterval(()=>{ if (socket.readyState===WebSocket.OPEN) socket.send(JSON.stringify({type:'HEARTBEAT'})); },20000);
    });
    socket.on('error',()=>socket.terminate());
    socket.on('close',()=>{counters.closed++;clearInterval(heartbeat);clearTimeout(stable);clearTimeout(lifetime);sockets.delete(socket);retry(index);});
  } catch (error) { console.error(error.message);retry(index); }
}
const connectInterval=Number(process.env.SLOW_CONNECT_INTERVAL_MS || 1000);
for(let i=0;i<count;i++)setTimeout(()=>connect(i),i*connectInterval);
setTimeout(()=>{finished=true;for(const socket of sockets)socket.terminate();console.error(JSON.stringify({slowClientSummary:true,distinctOpened:openedIndices.size,...counters}));},Number(process.env.STAGE_SECONDS || process.env.HOLD_SECONDS || 1800)*1000);
