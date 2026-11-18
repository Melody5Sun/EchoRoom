// Real Chromium playback using synthetic FFmpeg input; no mocked media or API responses.
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, openSync, closeSync } from 'node:fs';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const {chromium}=createRequire(path.join(root,'frontend/package.json'))('playwright');
const fixture=JSON.parse(readFileSync(path.join(root,'load-tests/results/fixture.json'),'utf8'));
const compose=['compose','-p','echoroom-ha','-f',path.join(root,'docker-compose.infra-ha.yml'),'--profile','*'];
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const publishers=[];
function docker(args) {
  return execFileSync('docker',[...compose,...args],{windowsHide:true,stdio:'ignore',timeout:60000});
}
function publish(node) {
  const log=openSync(path.join(root,`load-tests/results/media-source-${node}.log`),'w');
  const child=spawn(process.env.FFMPEG_PATH || 'ffmpeg',['-nostdin','-loglevel','error','-re','-f','lavfi','-i','testsrc=size=320x180:rate=10',
    '-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-c:v','libx264','-threads','1','-preset','ultrafast','-tune','zerolatency',
    '-pix_fmt','yuv420p','-g','20','-c:a','aac','-b:a','32k','-t','180','-f','flv',`rtmp://localhost:${19350+node}/live/${fixture.roomId}`],
    {windowsHide:true,stdio:['ignore','ignore',log]});
  closeSync(log);publishers.push(child);return child;
}
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH || undefined,
  args:['--autoplay-policy=no-user-gesture-required']});
let stopped=false;
let page;
const report={realBrowser:true,syntheticSource:true,responses:[]};
try {
  publish(1);await delay(2000);
  page=await browser.newPage();
  page.on('response', response=>{
    const url=new URL(response.url());
    if(url.pathname.startsWith('/rtc/') || url.pathname.endsWith('.flv'))report.responses.push({path:url.pathname,status:response.status(),at:Date.now()});
  });
  // A live FLV request may never become network-idle.
  await page.goto(`${fixture.baseUrl}/live/${fixture.roomId}`,{waitUntil:'domcontentloaded'});
  await page.waitForSelector('video',{timeout:20000});
  await page.waitForFunction(()=>{const v=document.querySelector('video');return v?.readyState>=2 && v.getVideoPlaybackQuality().totalVideoFrames>10;},null,{timeout:45000});
  const first=await page.locator('video').evaluate(v=>({frames:v.getVideoPlaybackQuality().totalVideoFrames,time:v.currentTime,
    transport:v.srcObject?'webrtc':v.currentSrc.startsWith('blob:')?'flv':'unknown'}));
  const firstMode=first.transport;
  report.first=first;report.firstMode=firstMode;
  stopped=true;docker(['stop','srs-1']);
  await delay(6000);
  const pausedFrames=await page.locator('video').evaluate(v=>v.getVideoPlaybackQuality().totalVideoFrames);
  report.pausedFrames=pausedFrames;
  const recoveryStart=Date.now();publish(2);
  // Reattaching a media source can reset frame counters. Require fresh playback
  // progress after the alternate publisher starts, rather than a lifetime total.
  await page.evaluate(()=>{window.__mediaRecoverySample=null;});
  await page.waitForFunction(()=>{
    const v=document.querySelector('video');
    if(!v || v.readyState<2 || v.paused){window.__mediaRecoverySample=null;return false;}
    const frames=v.getVideoPlaybackQuality().totalVideoFrames;
    const sample=window.__mediaRecoverySample;
    if(!sample || frames<sample.frames || v.currentTime<sample.time){
      window.__mediaRecoverySample={frames,time:v.currentTime};return false;
    }
    return frames>sample.frames+10 && v.currentTime>sample.time+1;
  },null,{timeout:60000});
  const second=await page.locator('video').evaluate(v=>({frames:v.getVideoPlaybackQuality().totalVideoFrames,time:v.currentTime,
    transport:v.srcObject?'webrtc':v.currentSrc.startsWith('blob:')?'flv':'unknown'}));
  const secondMode=second.transport;
  Object.assign(report,{second,secondMode,recoveryMs:Date.now()-recoveryStart,passed:true});
  writeFileSync(path.join(root,'load-tests/results/media-failover.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
} catch(error) {
  report.passed=false;report.error=error.message.split('\n')[0];
  if(page) {
    report.lastPlayerText=await page.locator('.video-frame').innerText().catch(()=>null);
    report.lastVideo=await page.locator('video').evaluate(v=>({readyState:v.readyState,time:v.currentTime,frames:v.getVideoPlaybackQuality().totalVideoFrames,paused:v.paused,src:v.currentSrc})).catch(()=>null);
  }
  writeFileSync(path.join(root,'load-tests/results/media-failover.json'),JSON.stringify(report,null,2));
  throw error;
} finally {
  for(const publisher of publishers)if(publisher.exitCode===null)publisher.kill();
  try { if(stopped)docker(['start','srs-1']); }
  finally { await browser.close(); }
}
