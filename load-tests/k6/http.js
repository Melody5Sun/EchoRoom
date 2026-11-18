import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
const outcome = new Counter('business_outcomes');
const acceptedRatio = new Rate('business_accepted_ratio');
const replays = new Counter('idempotent_replays');
const errors = new Rate('unexpected_errors');
const acceptedLatency = new Trend('successful_api_duration', true);
const completion = new Trend('order_completion', true);
const pending = new Counter('orders_unfinished');
const failed = new Counter('orders_failed');
const ambiguousAccepted = new Counter('orders_ambiguous_accepted');
const unconfirmed = new Counter('orders_unconfirmed');
if (__ENV.ACCESS_TOKENS && __ENV.ACCESS_TOKENS_FILE) throw new Error('Choose one credential source');
const tokens = new SharedArray('http-participant-tokens',()=>{
  if (!__ENV.ACCESS_TOKENS_FILE) return (__ENV.ACCESS_TOKENS || '').split(',').filter(Boolean);
  const fixture=JSON.parse(open(__ENV.ACCESS_TOKENS_FILE));
  const accounts=Array.isArray(fixture)?fixture:fixture.participants;
  if(!Array.isArray(accounts))throw new Error('Credential dataset must be an array or contain participants');
  const deadline=Date.now()+Number(__ENV.MIN_TOKEN_TTL_SECONDS || 0)*1000;
  return accounts.map(account=>{
    if(typeof account==='string') {
      if(!account.trim())throw new Error('Empty participant credential');
      return account;
    }
    if(account.expiresAt) {
      const expires=Date.parse(account.expiresAt);
      if(!Number.isFinite(expires))throw new Error('Invalid credential expiry metadata');
      if(expires<deadline)throw new Error('Participant credentials expire before this phase can finish');
    }
    if(typeof account.accessToken!=='string' || !account.accessToken)throw new Error('Invalid participant record');
    return account.accessToken;
  });
});
if (!tokens.length) throw new Error('A real participant credential dataset is required');
const rate = Number(__ENV.RATE || 10);
export const options = {
  summaryTrendStats: ['avg','min','med','max','p(90)','p(95)','p(99)'],
  scenarios: { business: { executor:'constant-arrival-rate', rate, timeUnit:'1s', duration:__ENV.DURATION || '10m', gracefulStop:'35s', preAllocatedVUs:Number(__ENV.PREALLOCATED_VUS || 50), maxVUs:Number(__ENV.MAX_VUS || 200) } },
  thresholds: { business_accepted_ratio:[`rate>${__ENV.MIN_ACCEPTED_RATIO || '0.9'}`],
    unexpected_errors:['rate<0.01',{threshold:'rate<0.05',abortOnFail:true,delayAbortEval:'30s'}],
    successful_api_duration:['p(95)<500','p(99)<1000'], order_completion:['p(95)<5000'], orders_unfinished:['count==0'], orders_failed:['count==0'], orders_unconfirmed:['count==0'], idempotent_replays:['count==0'], dropped_iterations:['count==0'] },
};
export default function () {
  pending.add(0);
  failed.add(0); replays.add(0);
  ambiguousAccepted.add(0); unconfirmed.add(0);
  const kind = __ENV.KIND || 'seckill';
  const index=exec.scenario.iterationInTest;
  if (__ENV.MIXED_AUDIT==='1' && index===0) console.log(JSON.stringify({
    audit:'http_window', run:__ENV.RUN_ID, start:exec.scenario.startTime,
    end:exec.scenario.startTime+Number(__ENV.MIXED_SECONDS)*1000,
  }));
  if (kind==='seckill' && index>=tokens.length) exec.test.abort('Seckill requires a fresh participant token for every iteration');
  const token = tokens[index%tokens.length];
  const headers = {Authorization:`Bearer ${token}`, 'Content-Type':'application/json'};
  const order = `${__ENV.RUN_ID || 'run'}-${__VU}-${__ITER}`;
  const root = __ENV.BASE_URL || 'http://localhost:8089';
  const path = kind === 'gift' ? `/api/v1/live/rooms/${__ENV.ROOM_ID}/gifts` : `/api/v1/activities/${__ENV.ACTIVITY_ID}/seckill`;
  const body = kind === 'gift' ? {clientOrderNo:order,giftCode:__ENV.GIFT_CODE,quantity:1} : {clientOrderNo:order};
  const begin = Date.now();
  const res = http.post(root+path,JSON.stringify(body),{headers,tags:{operation:kind},timeout:'5s'});
  let data; try { data=res.json(); } catch (_) { /* classified below */ }
  const success = res.status===200 && data && data.success && data.data && typeof data.data.orderNo==='string';
  const business = [400,409].includes(res.status) && data && data.success===false;
  outcome.add(1,{outcome:success?'accepted':res.status===429?'limited':business?'business_rejected':'system_error',http_status:String(res.status)});
  acceptedRatio.add(!!success);
  errors.add(!success && res.status!==429 && !business);
  const ambiguous=!success && (res.status===0 || res.status>=500 || (res.status>=200 && res.status<300));
  if (!success && !ambiguous) return;
  if (success) acceptedLatency.add(res.timings.duration);
  const orderNo=success ? data.data.orderNo : order;
  if (orderNo!==order) { replays.add(1); return; }
  const orderPath=kind==='gift' ? '/api/v1/gift-orders/' : '/api/v1/activity-orders/';
  let located=!!success;
  let finalStatus=success ? data.data.status : 'UNKNOWN';
  const deadline=begin+30000;
  while ((finalStatus==='PENDING' || finalStatus==='UNKNOWN') && Date.now()<deadline) {
    sleep(Math.min(0.5,Math.max(0,deadline-Date.now())/1000));
    const remaining=deadline-Date.now();
    if (remaining<=0) break;
    const poll=http.get(root+orderPath+encodeURIComponent(orderNo),{headers,tags:{operation:'order_poll'},timeout:`${Math.max(1,Math.min(5000,remaining))}ms`});
    if (poll.status===200) {
      try {
        const body=poll.json();
        if(body.success && body.data?.orderNo===orderNo){located=true;finalStatus=body.data.status;}
      } catch (_) { /* retry within the fixed deadline */ }
    }
  }
  if (ambiguous) {
    if(located)ambiguousAccepted.add(1);
    const apiCode=typeof data?.code==='string' && /^[A-Z0-9_-]{1,40}$/.test(data.code) ? data.code : null;
    const inventoryRecovery=apiCode==='COMMON-503' &&
      ['活动库存恢复中','活动库存已恢复，请重试'].includes(data?.message);
    console.warn(JSON.stringify({audit:'ambiguous_order_resolution',orderNo,httpStatus:res.status,apiCode,inventoryRecovery,located,finalStatus}));
  }
  if (!located || finalStatus==='UNKNOWN' || !finalStatus) unconfirmed.add(1);
  else if (finalStatus==='PENDING') pending.add(1);
  else if (finalStatus==='SUCCESS') completion.add(Date.now()-begin);
  else failed.add(1,{status:finalStatus});
}
