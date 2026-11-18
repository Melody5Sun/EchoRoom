// Controlled negative case: authoritative profile dependency down, then restored.
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
if(fixture.baseUrl!=='http://localhost:8089')throw new Error('Local isolated rehearsal only');
const run='admission-user-'+randomUUID().slice(0,8), target='streamhub-user-service-1';
const report={run,passed:false};const sockets=[];
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function docker(...args){return (await execute('docker',args,{windowsHide:true,timeout:30000})).stdout.trim();}
async function ticket(){
  const response=await fetch(fixture.baseUrl+'/api/v1/auth/ws-ticket',{method:'POST',
    headers:{Authorization:`Bearer ${fixture.participants[0].accessToken}`,'Content-Type':'application/json'},body:'{}',signal:AbortSignal.timeout(5000)});
  const value=await response.json();if(!response.ok || !value.success)throw new Error('Ticket preparation failed');return value.data.ticket;
}
async function probe(value){
  const socket=new WebSocket(`${fixture.baseUrl.replace(/^http/,'ws')}/ws/chat?roomId=${fixture.roomId}&ticket=${encodeURIComponent(value)}`);
  sockets.push(socket);
  return new Promise(resolve=>{
    let connected=false,done=false;
    const finish=result=>{if(done)return;done=true;clearTimeout(timer);socket.terminate();resolve(result);};
    const timer=setTimeout(()=>finish({connected,timeout:true}),10000);
    socket.on('message',raw=>{if(JSON.parse(raw.toString()).type==='CONNECTED'){connected=true;finish({connected:true});}});
    socket.on('close',(code,reason)=>finish({connected,closeCode:code,closeReason:reason.toString().slice(0,200)}));
    socket.on('unexpected-response',(_req,res)=>finish({connected,upgradeStatus:res.statusCode}));
    socket.on('error',error=>finish({connected,socketError:true,errorCode:error.code||error.name}));
  });
}
async function snapshot(stage){
  const output=path.join(root,`load-tests/results/${run}-${stage}.json`);
  await execute('python',[path.join(root,'load-tests/realtime-admission-snapshot.py'),'--output',output],{windowsHide:true,timeout:45000});
  return JSON.parse(readFileSync(output,'utf8'));
}
async function runDrill(){
let stopped=false,restoreStarted=0;
try{
  if(!(await probe(await ticket())).connected)throw new Error('Healthy dependency must allow admission');
  const before=await snapshot('before');
  const tickets=await Promise.all([ticket(),ticket()]);
  stopped=true;await docker('stop','-t','5',target);
  report.outage=await Promise.all(tickets.map(probe));
  const after=await snapshot('during');
  const total=state=>Object.values(state.nodes).reduce((sum,node)=>sum+(node.rejected.user_unavailable||0),0);
  report.userUnavailableDelta=total(after)-total(before);
  if(report.outage.some(value=>value.connected || value.closeCode!==4001))throw new Error('Dependency outage did not produce the retryable application close');
  if(report.userUnavailableDelta!==2)throw new Error('Missing bounded dependency rejection counters');
}finally{
  sockets.forEach(socket=>socket.terminate());
  if(stopped){
    restoreStarted=Date.now();
    await docker('start',target);
    const deadline=Date.now()+90000;let healthy=false;
    while(Date.now()<deadline){if((await docker('inspect','--format','{{.State.Health.Status}}',target))==='healthy'){healthy=true;break;}await delay(1000);}
    if(!healthy)throw new Error('Profile service did not recover its health check');
  }
}
report.recoveryAttempts=[];
const recoveryDeadline=Date.now()+60000;
do{
  try{report.recovered=await probe(await ticket());}catch{report.recovered={connected:false,ticketUnavailable:true};}
  report.recoveryAttempts.push(report.recovered);
  if(report.recovered.connected)break;
  await delay(1000);
}while(Date.now()<recoveryDeadline);
if(!report.recovered.connected)throw new Error('Admission did not recover with the profile service');
report.observedRecoveryMs=Date.now()-restoreStarted;
report.passed=true;
}
try{await runDrill();}catch(error){report.error=error.message.split('\n')[0];process.exitCode=1;}
finally{
sockets.forEach(socket=>socket.terminate());
writeFileSync(path.join(root,'load-tests/results/admission-user-outage.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));
}
