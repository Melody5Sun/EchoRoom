"""Combine the isolated MySQL failover drill with real API and durable business facts."""
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
fixture = json.loads((root / 'load-tests/results/fixture.json').read_text(encoding='utf-8'))
base = fixture['baseUrl']
if base not in ('http://localhost:8089', 'http://127.0.0.1:8089'):
    raise SystemExit('Only the isolated local HA environment is supported')
sender = fixture['participants'][-2]
room = int(fixture['roomId'])
run = 'mysql-business-' + uuid.uuid4().hex[:10]

def api(route, body=None, account=sender):
    request = urllib.request.Request(base + route,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + account['accessToken'], 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        result = json.load(response)
    if not result.get('success'):
        raise RuntimeError('API rejected a business probe')
    return result['data']

def sql(query):
    result = subprocess.run(['docker', 'compose', '-p', 'echoroom-ha', '-f',
        str(root / 'docker-compose.infra-ha.yml'), 'exec', '-T', 'mysql-2', 'sh', '-ec',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B echoroom'],
        input=query, text=True, capture_output=True, timeout=20, check=True)
    return result.stdout.strip()

def place(suffix):
    order = run + '-' + suffix
    api(f'/api/v1/live/rooms/{room}/gifts', {'clientOrderNo': order,
        'giftCode': fixture['giftCode'], 'quantity': 1})
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        state = api('/api/v1/gift-orders/' + order)['status']
        if state == 'SUCCESS':
            return order
        if state != 'PENDING':
            raise RuntimeError('Business probe reached unexpected state: ' + state)
        time.sleep(.5)
    raise RuntimeError('Business probe did not complete')

def facts(order):
    return sql(f"""SELECT order_no,status,total_amount FROM gift_order WHERE order_no='{order}';
        SELECT biz_no,user_id,change_amount FROM wallet_ledger WHERE reference_id='{order}' ORDER BY biz_no;
        SELECT event_key,status FROM mq_outbox WHERE event_key='gift:process:{order}';
        SELECT id,stock,reserved_count FROM activity WHERE room_id={room} ORDER BY id;""")

before_sender = api('/api/v1/wallet')['balance']
before_anchor = api('/api/v1/wallet', account=fixture['anchor'])['balance']
first = place('before')
# Outbox marking and business consumption can finish in either order.
def drain(order):
    deadline = time.monotonic() + 15
    while sql(f"SELECT status FROM mq_outbox WHERE event_key='gift:process:{order}';") != 'SENT':
        if time.monotonic() > deadline:
            raise RuntimeError('Outbox did not drain')
        time.sleep(.5)
drain(first)
snapshot = facts(first)
log_path = root / 'load-tests/results/mysql-business-infra.log'
samples = []
with log_path.open('w', encoding='utf-8') as log:
    process = subprocess.Popen(['pwsh', '-NoProfile', '-File', str(root / 'infra/ha/mysql-failover.ps1')],
        stdout=log, stderr=subprocess.STDOUT)
    started = time.monotonic()
    try:
        while process.poll() is None:
            begin = time.monotonic()
            status = 200
            try:
                if api('/api/v1/gift-orders/' + first)['status'] != 'SUCCESS':
                    raise RuntimeError('Committed order changed state')
            except urllib.error.HTTPError as error:
                if error.code < 500:
                    raise RuntimeError('Committed-order probe returned unexpected HTTP ' + str(error.code)) from error
                status = error.code
            except (urllib.error.URLError, TimeoutError):
                status = 0
            samples.append({'atMs': round((begin - started) * 1000), 'status': status,
                'elapsedMs': round((time.monotonic() - begin) * 1000)})
            if time.monotonic() - started > 160:
                raise RuntimeError('Infrastructure drill exceeded its bounded deadline')
            time.sleep(.5)
        if process.returncode:
            raise RuntimeError('Infrastructure MySQL drill failed; inspect its private log')
    finally:
        # Give the infrastructure script time to execute its node-recovery finally block.
        if process.poll() is None:
            process.wait(timeout=150)

if facts(first) != snapshot:
    raise RuntimeError('Pre-fault order, ledger, Outbox or inventory facts changed')
second = place('after')
drain(second)
after_sender = api('/api/v1/wallet')['balance']
after_anchor = api('/api/v1/wallet', account=fixture['anchor'])['balance']
count, unique, amount = map(int, sql(f"""SELECT COUNT(*),COUNT(DISTINCT biz_no),SUM(ABS(change_amount))
    FROM wallet_ledger WHERE reference_id IN ('{first}','{second}');""").split())
debit = before_sender - after_sender
credit = after_anchor - before_anchor
order_amount = int(sql(f"SELECT SUM(total_amount) FROM gift_order WHERE order_no IN ('{first}','{second}');"))
if count != 4 or unique != 4 or debit != credit or debit != order_amount or amount != 2 * debit:
    raise RuntimeError('Exactly-once wallet effects did not reconcile')
infra_text = log_path.read_text(encoding='utf-8')
decoder = json.JSONDecoder()
infra = None
for index, char in enumerate(infra_text):
    if char == '{':
        try:
            candidate, _ = decoder.raw_decode(infra_text[index:])
            if isinstance(candidate, dict) and candidate.get('dataVerified'):
                infra = candidate
        except json.JSONDecodeError:
            pass
if not infra:
    raise RuntimeError('Missing verified infrastructure result')
failures = [sample for sample in samples if sample['status'] != 200]
first_failure = failures[0]['atMs'] if failures else None
recovery = next((sample['atMs'] - first_failure for sample in samples
    if first_failure is not None and sample['atMs'] > first_failure and sample['status'] == 200), None)
report = {'run': run, 'infrastructure': infra, 'apiSamples': samples,
    'apiObservedRecoveryMs': recovery, 'unexpectedProbeStatuses': sorted({x['status'] for x in failures}),
    'preFaultFactsPreserved': True, 'successfulOrders': 2, 'uniqueLedgerEntries': 4,
    'senderDebit': debit, 'anchorCredit': credit, 'passed': True}
(root / 'load-tests/results/mysql-business-recovery.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({key: value for key, value in report.items() if key != 'apiSamples'}))
