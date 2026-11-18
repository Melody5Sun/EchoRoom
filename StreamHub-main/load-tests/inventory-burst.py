"""Bounded end-to-end inventory/idempotency check against unused local fixtures."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
fixture = json.loads((root / 'load-tests/results/fixture.json').read_text())
base = fixture['baseUrl']
if urllib.parse.urlparse(base).hostname not in ('localhost', '127.0.0.1'):
    raise SystemExit('This drill only permits local isolated fixtures')
activity = int(fixture['activityId'])
accounts = fixture['participants']

def sql(query):
    result = subprocess.run(['docker', 'compose', '-p', 'streamhub-ha', '-f',
        str(root / 'docker-compose.infra-ha.yml'), '--profile', '*', 'exec', '-T', 'mysql-2',
        'sh', '-ec', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B streamhub'],
        input=query, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('SQL verification failed: ' + result.stderr[-300:])
    return result.stdout.strip()

def request(path, account, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + account['accessToken'], 'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.code, json.load(response)

stock, reserved = map(int, sql(f'SELECT stock,reserved_count FROM activity WHERE id={activity};').split())
if reserved or int(sql(f'SELECT COUNT(*) FROM activity_order WHERE activity_id={activity};')):
    raise SystemExit('Require an unused activity; generate new fixtures first')
if stock < 1 or len(accounts) <= stock:
    raise SystemExit('Require more distinct participants than stock for the burst')
run = 'burst-' + uuid.uuid4().hex

def place(item):
    index, account = item
    order = f'{run}-{index}'
    status, result = request(f'/api/v1/activities/{activity}/seckill', account, {'clientOrderNo': order})
    return account, order, status, result

with ThreadPoolExecutor(max_workers=min(32, len(accounts))) as executor:
    results = list(executor.map(place, enumerate(accounts)))
winners = []
rejections = Counter()
for account, order, status, result in results:
    if status == 200 and result.get('success'):
        if result['data']['orderNo'] != order:
            raise RuntimeError('Unexpected preexisting order replay')
        winners.append((account, order))
    elif status in (400, 409) and result.get('success') is False:
        rejections[result.get('code', 'unknown')] += 1
    else:
        raise RuntimeError(f'Unexpected rejection status={status}, code={result.get("code")}')
if len(winners) != stock:
    raise RuntimeError(f'Expected {stock} accepted orders, got {len(winners)}')
deadline = time.monotonic() + 30
for account, order in winners:
    while True:
        status, result = request('/api/v1/activity-orders/' + order, account)
        final = result.get('data', {}).get('status') if status == 200 else None
        if final == 'SUCCESS':
            break
        if final in ('CLOSED', 'FAILED') or time.monotonic() >= deadline:
            raise RuntimeError('Accepted order did not reach SUCCESS')
        time.sleep(.5)
    status, replay = request(f'/api/v1/activities/{activity}/seckill', account, {'clientOrderNo': order})
    if status != 200 or not replay.get('success') or replay['data']['orderNo'] != order:
        raise RuntimeError('Same-order duplicate did not preserve idempotency')

reserved = int(sql(f'SELECT reserved_count FROM activity WHERE id={activity};'))
orders, successful, unique_users = map(int, sql(f"SELECT COUNT(*),SUM(status='SUCCESS'),COUNT(DISTINCT user_id) FROM activity_order WHERE activity_id={activity};").split())
if not reserved == orders == successful == unique_users == stock:
    raise RuntimeError('Stock, reservation, terminal orders and unique buyers disagree')
report = {'run': run, 'activity': activity, 'participants': len(accounts), 'stock': stock,
    'accepted': len(winners), 'businessRejected': sum(rejections.values()), 'rejectionCodes': dict(rejections),
    'duplicateRequests': len(winners), 'reserved': reserved, 'successOrders': successful,
    'uniqueBuyers': unique_users, 'oversold': False, 'passed': True}
(root / 'load-tests/results/inventory-burst.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
