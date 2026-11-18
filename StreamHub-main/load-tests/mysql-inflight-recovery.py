"""Verify an order whose wallet transaction is blocked when the local MySQL primary stops."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
results = (root / 'load-tests/results').resolve()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fixture', required=True, type=Path)
args = parser.parse_args()
fixture_path = args.fixture.resolve()
if not fixture_path.is_relative_to(results):
    parser.error('Use an isolated fixture in ignored results')
fixture = json.loads(fixture_path.read_text(encoding='utf-8-sig'))
base = fixture['baseUrl']
if base not in ('http://localhost:8089','http://127.0.0.1:8089'):
    parser.error('Only the local streamhub-ha rehearsal can be faulted')
sender = fixture['participants'][0]
anchor = fixture['anchor']
room = int(fixture['roomId'])
run = 'mysql-inflight-'+uuid.uuid4().hex[:10]
order = run+'-gift'
report = {'run':run,'order':order,'passed':False}
output = results / (run+'.json')
compose = ['docker','compose','-p','streamhub-ha','-f',str(root/'docker-compose.infra-ha.yml')]
mysql = ['sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B --unbuffered streamhub']


def sql(query):
    return subprocess.run(compose+['exec','-T','mysql-2',*mysql],input=query,text=True,
                          encoding='utf-8',capture_output=True,check=True,timeout=15).stdout.strip()


def api(path, body=None, account=sender):
    request = urllib.request.Request(base+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization':'Bearer '+account['accessToken'],'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=5) as response:
        value = json.load(response)
    if not value.get('success'):
        raise RuntimeError('Business API rejected the probe')
    return value['data']


def place():
    return api(f'/api/v1/live/rooms/{room}/gifts',
               {'clientOrderNo':order,'giftCode':fixture['giftCode'],'quantity':1})


def wait_for(probe, timeout, message):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        value = probe()
        if value:
            return value
        time.sleep(.25)
    raise RuntimeError(message)


before_sender = api('/api/v1/wallet')['balance']
before_anchor = api('/api/v1/wallet',account=anchor)['balance']
old = sql('SELECT @@hostname;')
if old not in ('mysql-1','mysql-2','mysql-3'):
    raise SystemExit('Unexpected writable primary')
probe_container = 'mysql-3' if old == 'mysql-2' else 'mysql-2'
holder = fault = None
started = time.monotonic()
try:
    with (results/(run+'-wallet-lock.log')).open('wb') as lock_log:
        holder = subprocess.Popen(compose+['exec','-T',probe_container,*mysql],
            stdin=subprocess.PIPE,stdout=lock_log,stderr=subprocess.STDOUT)
        marker = f'DO /*{run}*/ SLEEP(40)'
        holder.stdin.write(f"BEGIN; SELECT balance FROM wallet_account WHERE user_id={int(sender['userId'])} FOR UPDATE; {marker}; ROLLBACK;\n".encode())
        holder.stdin.close()
        wait_for(lambda: sql(f"SELECT COUNT(*) FROM information_schema.processlist WHERE INFO='{marker}';")=='1',
                 15,'Wallet lock was not acquired')
        if place()['status'] != 'PENDING':
            raise RuntimeError('Expected an accepted order pending wallet processing')
        locks = wait_for(lambda: sql(f"""SELECT COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
            JOIN performance_schema.threads t ON t.THREAD_ID=b.THREAD_ID
            WHERE b.OBJECT_SCHEMA='streamhub' AND b.OBJECT_NAME='wallet_account'
              AND t.PROCESSLIST_INFO='{marker}';""") != '0',
            15,'Consumer did not enter a wallet lock wait')
        report['walletLockWaitVerified'] = bool(locks)
        report['pendingAtFault'] = api('/api/v1/gift-orders/'+order)['status']
        if report['pendingAtFault'] != 'PENDING':
            raise RuntimeError('Order completed before the fault')
        with (results/(run+'-infra.log')).open('w',encoding='utf-8') as log:
            fault = subprocess.Popen(['pwsh','-NoProfile','-File',str(root/'infra/ha/mysql-failover.ps1')],
                stdout=log,stderr=subprocess.STDOUT)
            samples = []
            while fault.poll() is None:
                begin = time.monotonic()
                try:
                    state = api('/api/v1/gift-orders/'+order)['status']
                    status = 200
                except urllib.error.HTTPError as error:
                    status, state = error.code, None
                except (TimeoutError,urllib.error.URLError):
                    status, state = 0, None
                samples.append({'atMs':round((begin-started)*1000),'httpStatus':status,'state':state})
                if begin-started>180:
                    raise RuntimeError('MySQL drill exceeded its deadline')
                time.sleep(.5)
            if fault.returncode:
                raise RuntimeError('Infrastructure drill failed')
        report['apiSamples'] = samples
        if not any(item['httpStatus'] != 200 for item in samples):
            raise RuntimeError('The client did not observe the intended interruption')
        holder.wait(timeout=50)
        if holder.returncode == 0:
            raise RuntimeError('Wallet lock expired normally before the fault interrupted it')
        report['walletLockConnectionInterrupted'] = True
    wait_for(lambda: api('/api/v1/gift-orders/'+order)['status']=='SUCCESS',
             90,'In-flight order did not complete after recovery')
    # Replay the same identity after the uncertain interval.
    if place()['orderNo'] != order:
        raise RuntimeError('Replay changed order identity')
    wait_for(lambda: sql(f"SELECT status FROM mq_outbox WHERE event_key='gift:process:{order}';")=='SENT',
             20,'Outbox did not reach SENT')
    count,unique,debit,credit = map(int,sql(f"""SELECT COUNT(*),COUNT(DISTINCT biz_no),
        COALESCE(SUM(IF(change_amount<0,-change_amount,0)),0),
        COALESCE(SUM(IF(change_amount>0,change_amount,0)),0)
        FROM wallet_ledger WHERE reference_id='{order}';""").split())
    amount = int(sql(f"SELECT total_amount FROM gift_order WHERE order_no='{order}' AND status='SUCCESS';"))
    sender_delta = before_sender-api('/api/v1/wallet')['balance']
    anchor_delta = api('/api/v1/wallet',account=anchor)['balance']-before_anchor
    if count != 2 or unique != 2 or not debit == credit == amount == sender_delta == anchor_delta:
        raise RuntimeError('Wallet and unique ledger effects did not reconcile')
    report.update(passed=True,originalPrimary=old,currentPrimary=sql('SELECT @@hostname;'),
                  uniqueLedgerEntries=unique,amount=amount,senderDebit=sender_delta,anchorCredit=anchor_delta,
                  replayPreservedIdentity=True,outbox='SENT',elapsedMs=round((time.monotonic()-started)*1000))
finally:
    # Infrastructure owns its restoration finally block. Wait for it before exiting.
    if fault is not None and fault.poll() is None:
        fault.wait(timeout=150)
    if holder is not None and holder.poll() is None:
        holder.wait(timeout=50)
    output.write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({key:value for key,value in report.items() if key!='apiSamples'}))
