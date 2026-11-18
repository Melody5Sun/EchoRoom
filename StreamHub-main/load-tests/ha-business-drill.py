"""Fault only the isolated streamhub-ha project; verify real Gateway orders and ledger effects."""
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
fixture = json.loads((root/'load-tests/results/fixture.json').read_text(encoding='utf-8'))
base = fixture['baseUrl']
sender = fixture['participants'][-1]
orders = []
results = {}
compose = ['docker', 'compose', '-p', 'streamhub-ha', '-f', str(root/'docker-compose.infra-ha.yml'), '--profile', '*']

def docker(args, data=None):
    run = subprocess.run(compose+args, input=data, text=True, capture_output=True, timeout=60)
    if run.returncode:
        raise RuntimeError('Docker probe failed: '+run.stderr[-500:])
    return run.stdout

def admin(args):
    return docker(['exec','-T','namesrv-2','sh','mqadmin']+args)

def request(path, body=None, account=sender):
    req = urllib.request.Request(base+path, headers={'Authorization':'Bearer '+account['accessToken'], 'Content-Type':'application/json'},
                                 data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(req, timeout=10) as response:
        result=json.load(response)
    if not result.get('success'):
        raise RuntimeError('API rejected '+path+': '+str(result.get('code')))
    return result['data']

def sql(query):
    return docker(['exec','-T','mysql-2','sh','-ec',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N streamhub'],query).strip()

def place():
    order='ha-'+uuid.uuid4().hex
    request(f'/api/v1/live/rooms/{fixture["roomId"]}/gifts', {'clientOrderNo':order,'giftCode':fixture['giftCode'],'quantity':1})
    return order

def completed(order, timeout=90):
    started=time.monotonic()
    while time.monotonic()-started < timeout:
        result=request('/api/v1/gift-orders/'+order)
        if result['status']=='SUCCESS':
            orders.append(order)
            return round((time.monotonic()-started)*1000)
        if result['status']!='PENDING':
            raise RuntimeError('Order failed during drill: '+result['status'])
        time.sleep(.5)
    raise RuntimeError('Order did not complete before recovery deadline')

def broker_primary():
    value=admin(['clusterList','-n','namesrv-2:9876'])
    match=re.search(r'(?m)^StreamHubHA\s+streamhub-broker\s+0\s+(broker-[12]):10911',value)
    return match.group(1) if match else None

before_sender=request('/api/v1/wallet')['balance']
before_anchor=request('/api/v1/wallet',account=fixture['anchor'])['balance']
price=next(g['price'] for g in request('/api/v1/gifts') if g['code']==fixture['giftCode'])
completed(place())
primary=broker_primary()
if not primary:
    raise RuntimeError('No active primary in isolated MQ cluster')
start=time.monotonic()
try:
    docker(['stop',primary])
    deadline=time.monotonic()+60
    promoted=None
    while time.monotonic()<deadline:
        candidate=broker_primary()
        if candidate and candidate!=primary:
            promoted=candidate
            break
        time.sleep(1)
    if not promoted:
        raise RuntimeError('Controller did not elect a replacement Broker')
    pending=place()
    time.sleep(3)
    if request('/api/v1/gift-orders/'+pending)['status']!='PENDING':
        raise RuntimeError('Single-replica write was incorrectly completed')
    outbox=sql(f"SELECT status FROM mq_outbox WHERE event_key='gift:process:{pending}';")
    if outbox not in ('PENDING','SENDING'):
        raise RuntimeError('Unsafe acknowledgement marked Outbox '+outbox)
finally:
    docker(['start',primary])
recovery=completed(pending)
results['broker']={'oldPrimary':primary,'newPrimary':promoted,'outboxDuringFault':outbox,'pendingRecoveredMs':recovery,
                   'totalDrillMs':round((time.monotonic()-start)*1000)}

metadata=admin(['getControllerMetaData','-a','controller-1:9878'])
leader=re.search(r'#ControllerLeaderAddress\s+(controller-[123]):9878',metadata).group(1)
probe='controller-1' if leader!='controller-1' else 'controller-2'
try:
    docker(['stop',leader])
    deadline=time.monotonic()+45
    new_leader=None
    while time.monotonic()<deadline:
        try:
            metadata=admin(['getControllerMetaData','-a',probe+':9878'])
            found=re.search(r'#ControllerLeaderAddress\s+(controller-[123]):9878',metadata)
            if found and found.group(1)!=leader:
                new_leader=found.group(1)
                break
        except RuntimeError:
            pass
        time.sleep(1)
    if not new_leader:
        raise RuntimeError('Controller quorum did not elect a replacement leader')
    latency=completed(place())
    results['controller']={'oldLeader':leader,'newLeader':new_leader,'orderCompletionMs':latency}
finally:
    docker(['start',leader])

try:
    docker(['stop','namesrv-1'])
    route=admin(['topicRoute','-n','namesrv-2:9876','-t','STREAMHUB_GIFT_ORDER'])
    if 'streamhub-broker' not in route:
        raise RuntimeError('Surviving NameServer has no gift route')
    results['nameserver']={'survivingRouteVerified':True,'orderCompletionMs':completed(place())}
finally:
    docker(['start','namesrv-1'])

try:
    docker(['stop','nacos-1'])
    nodes=json.loads(docker(['exec','-T','nacos-2','curl','-fsS',
        'http://localhost:8848/nacos/v1/ns/instance/list?serviceName=streamhub-live&groupName=DEFAULT_GROUP']))
    healthy=[item for item in nodes.get('hosts',[]) if item.get('healthy')]
    if len(healthy)<2:
        raise RuntimeError('Surviving Nacos node did not retain both healthy Live instances')
    results['nacos']={'healthyLiveInstances':len(healthy),'orderCompletionMs':completed(place())}
finally:
    docker(['start','nacos-1'])

after_sender=request('/api/v1/wallet')['balance']
after_anchor=request('/api/v1/wallet',account=fixture['anchor'])['balance']
expected=len(orders)*price
if before_sender-after_sender!=expected or after_anchor-before_anchor!=expected:
    raise RuntimeError('Wallet delta does not match the exact successful drill orders')
quoted=','.join("'"+order+"'" for order in orders)
rows=int(sql(f'SELECT COUNT(*) FROM wallet_ledger WHERE reference_id IN ({quoted});'))
if rows!=2*len(orders):
    raise RuntimeError('Missing or repeated ledger entries')
results['business']={'successfulOrders':len(orders),'ledgerEntries':rows,'senderDebit':expected,'anchorCredit':expected,'duplicateEffects':False}
output=root/'load-tests/results/ha-business-drill.json'
output.write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps(results,indent=2))
