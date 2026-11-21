"""Local fault probe: delay Outbox insertion after ingress, reconcile an uncertain HTTP result."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
fixture = json.loads((root / 'load-tests/results/fixture.json').read_text())
if fixture['baseUrl'] not in ('http://localhost:8089', 'http://127.0.0.1:8089'):
    raise SystemExit('Only the isolated local HA environment is supported')
account = fixture['participants'][0]
anchor = fixture['anchor']
run = 'ambiguous-' + uuid.uuid4().hex[:10]
results = root / 'load-tests/results'
command = ['docker', 'compose', '-p', 'echoroom-ha', '-f', str(root / 'docker-compose.infra-ha.yml'),
    'exec', '-T', 'mysql-2', 'sh', '-ec',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 --unbuffered -N -B echoroom']

def sql(query):
    return subprocess.run(command, input=query, text=True, capture_output=True, check=True, timeout=25).stdout.strip()

def balance(user):
    req = urllib.request.Request(fixture['baseUrl'] + '/api/v1/wallet',
        headers={'Authorization': 'Bearer ' + user['accessToken']})
    with urllib.request.urlopen(req, timeout=10) as response:
        return int(json.load(response)['data']['balance'])

if sql("SELECT COUNT(*) FROM mq_outbox WHERE status<>'SENT';") != '0':
    raise SystemExit('Drain Outbox before this deliberately blocking probe')
before = [balance(account), balance(anchor)]
lock = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    stderr=subprocess.PIPE, text=True)
try:
    # The empty PENDING range blocks new inserts, without modifying business rows.
    lock.stdin.write("START TRANSACTION;\nSELECT id FROM mq_outbox FORCE INDEX(idx_mq_outbox_dispatch) WHERE status='PENDING' FOR UPDATE;\nSELECT 'LOCK_ACQUIRED';\nSELECT SLEEP(8);\nCOMMIT;\n")
    lock.stdin.close()
    if lock.stdout.readline().strip() != 'LOCK_ACQUIRED':
        raise RuntimeError('Did not acquire the isolated pending-index gap')
    env = os.environ.copy()
    env.update(ACCESS_TOKENS=account['accessToken'], BASE_URL=fixture['baseUrl'],
        ROOM_ID=str(fixture['roomId']), GIFT_CODE=fixture['giftCode'], KIND='gift',
        RATE='1', DURATION='1s', PREALLOCATED_VUS='2', MAX_VUS='2', RUN_ID=run)
    summary = results / (run + '-summary.json')
    with (results / (run + '.log')).open('w', encoding='utf-8') as log:
        load = subprocess.run([str(root / '.tools/k6/k6-v0.53.0-windows-amd64/k6.exe'),
            'run', '--address', '127.0.0.1:0', '--summary-export', str(summary),
            str(root / 'load-tests/k6/http.js')], env=env, stdout=log, stderr=log, timeout=45)
    lock.wait(timeout=15)
    if lock.returncode:
        raise RuntimeError('Lock transaction failed: ' + lock.stderr.read()[-200:])
    metrics = json.loads(summary.read_text())['metrics']
    assert load.returncode != 0, 'HTTP error must still fail the capacity gate'
    attempts = metrics['iterations']['count']
    assert attempts >= 1
    assert metrics['business_accepted_ratio']['passes'] == 0
    assert metrics['unexpected_errors']['passes'] == attempts
    assert metrics['orders_ambiguous_accepted']['count'] == attempts
    assert metrics['orders_unconfirmed']['count'] == 0
    assert metrics['orders_unfinished']['count'] == 0
    assert metrics['orders_failed']['count'] == 0
    row = sql(f"SELECT COUNT(*),SUM(status='SUCCESS'),SUM(total_amount) FROM gift_order WHERE order_no LIKE '{run}-%';")
    count, success, amount = map(int, row.split())
    ledger = sql(f"SELECT COUNT(*),COUNT(DISTINCT biz_no) FROM wallet_ledger WHERE reference_id LIKE '{run}-%';")
    assert count == success == attempts and list(map(int, ledger.split())) == [2 * count, 2 * count]
    after = [balance(account), balance(anchor)]
    assert before[0] - after[0] == after[1] - before[1] == amount
    report = {'run': run, 'httpConfirmed': 0, 'recoveredSuccess': count, 'ledgerEntries': 2 * count,
        'walletDebitAndCredit': amount, 'capacityGateRejected': True, 'passed': True}
    (results / 'ambiguous-gift.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))
finally:
    if lock.poll() is None:
        # The bounded transaction releases itself; avoid killing a database session mid-probe.
        lock.wait(timeout=15)
