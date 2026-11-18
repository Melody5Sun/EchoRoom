"""Capture/verify isolated gift wallets, terminal orders and exactly-once ledger effects."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--baseline', required=True)
parser.add_argument('--fixture', default='load-tests/results/fixture.json')
parser.add_argument('--capture', action='store_true')
parser.add_argument('--run')
parser.add_argument('--audit-aborted-run', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
baseline_path = Path(args.baseline).resolve()
if not baseline_path.is_relative_to((root / 'load-tests/results').resolve()):
    raise SystemExit('Baseline must stay inside ignored results')
fixture_path = Path(args.fixture).resolve()
if not fixture_path.is_relative_to((root / 'load-tests/results').resolve()):
    raise SystemExit('Fixture must stay inside ignored results')
fixture = json.loads(fixture_path.read_text(encoding='utf-8-sig'))
if urllib.parse.urlparse(fixture['baseUrl']).hostname not in ('localhost', '127.0.0.1'):
    raise SystemExit('Only local isolated fixtures are permitted')

def wallet(account):
    request = urllib.request.Request(fixture['baseUrl'] + '/api/v1/wallet',
        headers={'Authorization': 'Bearer ' + account['accessToken']})
    with urllib.request.urlopen(request, timeout=10) as response:
        value = json.load(response)
    if not value.get('success'):
        raise RuntimeError('Wallet read failed')
    return int(value['data']['balance'])

def sql(query):
    result = subprocess.run(['docker', 'compose', '-p', 'streamhub-ha', '-f',
        str(root / 'docker-compose.infra-ha.yml'), '--profile', '*', 'exec', '-T', 'mysql-2',
        'sh', '-ec', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B streamhub'],
        input=query, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('SQL reconciliation failed: ' + result.stderr[-300:])
    return result.stdout.strip()

balances = {str(account['userId']): wallet(account) for account in [fixture['anchor'], *fixture['participants']]}
if args.capture:
    if baseline_path.exists():
        raise SystemExit('Refusing to overwrite baseline')
    baseline_path.parent.mkdir(parents=True, exist_ok=True)
    baseline_path.write_text(json.dumps({'utc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'fixtureRun': fixture['run'], 'roomId': fixture['roomId'], 'balances': balances}, indent=2), encoding='utf-8')
    print(json.dumps({'baselineCaptured': len(balances)}))
else:
    if not args.run or not re.fullmatch(r'\d{8}T\d{6}Z(?:-mixed-gift)?', args.run):
        raise SystemExit('Specify the exact UTC run id from the manifest')
    baseline = json.loads(baseline_path.read_text())
    if baseline['fixtureRun'] != fixture['run'] or baseline['roomId'] != fixture['roomId']:
        raise SystemExit('Fixture changed since baseline')
    room = int(fixture['roomId'])
    scope = f"g.room_id={room} AND g.order_no LIKE '{args.run}-%'"
    rows = sql(f'SELECT status,COUNT(*) FROM gift_order g WHERE {scope} GROUP BY status;')
    statuses = {status: int(count) for status, count in (line.split() for line in rows.splitlines())}
    successful = statuses.get('SUCCESS', 0)
    if not successful or any(count for status, count in statuses.items() if status != 'SUCCESS'):
        raise RuntimeError('Not all orders reached SUCCESS: ' + str(statuses))
    summaries = sorted((root / 'load-tests/results').glob(args.run + '-*-summary.json'))
    accepted = sum(json.loads(p.read_text())['metrics']['business_accepted_ratio']['passes'] for p in summaries)
    recovered = sum(json.loads(p.read_text())['metrics'].get('orders_ambiguous_accepted', {}).get('count', 0) for p in summaries)
    if (not summaries or accepted + recovered != successful) and not args.audit_aborted_run:
        raise RuntimeError('Accepted client orders and database terminal orders disagree')
    amount = int(sql(f'SELECT SUM(total_amount) FROM gift_order g WHERE {scope};'))
    ledger_count, debit, credit, unique = map(int, sql(f'''SELECT COUNT(*),
        COALESCE(SUM(IF(l.change_amount<0,-l.change_amount,0)),0),
        COALESCE(SUM(IF(l.change_amount>0,l.change_amount,0)),0),COUNT(DISTINCT l.biz_no)
        FROM wallet_ledger l JOIN gift_order g ON l.reference_id=g.order_no WHERE {scope};''').split())
    sender_debit = sum(baseline['balances'][str(a['userId'])] - balances[str(a['userId'])] for a in fixture['participants'])
    anchor_credit = balances[str(fixture['anchor']['userId'])] - baseline['balances'][str(fixture['anchor']['userId'])]
    if not ledger_count == unique == 2 * successful or not debit == credit == sender_debit == anchor_credit == amount:
        raise RuntimeError('Wallet deltas or ledger effects do not reconcile exactly')
    report = {'run': args.run, 'clientConfirmedInSummaries': accepted, 'recoveredServerAccepted': recovered, 'statuses': statuses,
        'ledgerEntries': ledger_count, 'uniqueLedgerBizNos': unique, 'amount': amount,
        'senderDebit': sender_debit, 'anchorCredit': anchor_credit, 'accountingReconciled': True,
        'capacityApproved': False, 'capacityReviewRequired': True, 'passed': not args.audit_aborted_run}
    suffix = '-aborted-settlement.json' if args.audit_aborted_run else '-settlement.json'
    (root / 'load-tests/results' / (args.run + suffix)).write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
