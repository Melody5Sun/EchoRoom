"""Prepare isolated test accounts through real Gateway APIs; credentials stay in ignored results.
Usage: python load-tests/prepare-fixtures.py --base-url http://localhost:8089 --users 20
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://localhost:8089')
parser.add_argument('--users', type=int, default=20)
parser.add_argument('--output', default='load-tests/results/fixture.json')
parser.add_argument('--stock',type=int,default=5)
parser.add_argument('--activities',type=int,default=1)
parser.add_argument('--fund-amount',type=int,default=100_000_000)
parser.add_argument('--account-delay-ms',type=int,default=150)
parser.add_argument('--checkpoint')
parser.add_argument('--resume',action='store_true')
args = parser.parse_args()
if not 1<=args.users<=50000 or not 1<=args.activities<=32 or args.stock<1 or args.fund_amount<0 or args.account_delay_ms<0:
    parser.error('Invalid bounded fixture parameters')
root=Path(__file__).resolve().parent.parent
output=Path(args.output).resolve()
private_root=(root/'load-tests/results').resolve()
if not output.is_relative_to(private_root):
    parser.error('Credentials must stay inside ignored load-tests/results')
output.parent.mkdir(parents=True,exist_ok=True)
checkpoint=Path(args.checkpoint).resolve() if args.checkpoint else (output.with_suffix('.checkpoint.json') if args.users>100 else None)
if checkpoint and not checkpoint.is_relative_to(private_root):
    parser.error('Checkpoint must stay inside ignored results')
parameters={'users':args.users,'baseUrl':args.base_url,'stock':args.stock,'activities':args.activities,'fundAmount':args.fund_amount}
if args.resume:
    if not checkpoint or not checkpoint.exists():
        parser.error('Resume requires an existing checkpoint')
    saved=json.loads(checkpoint.read_text(encoding='utf-8'))
    if saved['parameters']!=parameters:
        parser.error('Checkpoint parameters do not match')
    run,password,accounts=saved['run'],saved['password'],saved['accounts']
    if saved.get('completed') and output.exists():
        if json.loads(output.read_text(encoding='utf-8'))['run']!=run:
            parser.error('Completed fixture was replaced')
        print(json.dumps({'fixture':str(output),'participants':len(accounts)-1,'alreadyPrepared':True}))
        raise SystemExit(0)
else:
    if checkpoint and checkpoint.exists():
        parser.error('Checkpoint exists; use --resume or a different output')
    run,password,accounts='load-'+secrets.token_hex(5),secrets.token_urlsafe(18),[]

def save_checkpoint(completed=False):
    if checkpoint:
        checkpoint.parent.mkdir(parents=True,exist_ok=True)
        temporary=checkpoint.with_suffix(checkpoint.suffix+'.tmp')
        temporary.write_text(json.dumps({'parameters':parameters,'run':run,'password':password,
            'accounts':accounts,'completed':completed},indent=2),encoding='utf-8')
        temporary.replace(checkpoint)

save_checkpoint()

def request(path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    for attempt in range(5):
        req = urllib.request.Request(args.base_url.rstrip('/') + path,
                                     data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                result = json.load(response)
            if not result.get('success'):
                raise RuntimeError(f'API rejected {path}: {result.get("code")}')
            return result['data']
        except urllib.error.HTTPError as error:
            if error.code in (429, 502, 503, 504) and attempt < 4:
                print(f'Retrying {path} after HTTP {error.code}', flush=True)
                time.sleep(max(1, int(error.headers.get('Retry-After', '1'))))
                continue
            details=json.load(error)
            if (path == '/api/v1/auth/register' and error.code == 400
                    and details.get('message') == '用户名已存在'):
                # A timed-out registration may have committed before the retry.
                # Login must prove ownership with this run's random password.
                print('Recovering prepared registration through login', flush=True)
                return request('/api/v1/auth/login', {
                    'username': body['username'], 'password': body['password']})
            raise RuntimeError(f'API HTTP {error.code}: {path}, code={details.get("code")}, message={details.get("message")}') from None
    raise RuntimeError('Retry limit exceeded')

try:
    for index in range(len(accounts),args.users + 1):
        if args.users<=100 or index%100==0:
            print(f'Preparing account {index+1}/{args.users+1}', flush=True)
        account = request('/api/v1/auth/register', {'username': f'{run}-{index}', 'nickname': f'load-{index}', 'password': password})
        accounts.append(account)
        if len(accounts)%200==0: save_checkpoint()
        time.sleep(args.account_delay_ms/1000)
finally:
    save_checkpoint()
anchor, participants = accounts[0], accounts[1:]
room = request('/api/v1/live/rooms', {'title': run, 'category': 'load-test'}, anchor['accessToken'])
request(f'/api/v1/live/rooms/{room["id"]}/start', {}, anchor['accessToken'])
gifts = request('/api/v1/gifts')
gift = min(gifts, key=lambda item: item['price'])
if args.fund_amount:
    for account in participants:
        request('/api/v1/wallet/recharge', {'bizNo': f'{run}-fund-{account["userId"]}', 'amount': args.fund_amount}, account['accessToken'])
now = dt.datetime.now(dt.timezone.utc)
activities=[]
for index in range(args.activities):
    activity = request(f'/api/v1/live/rooms/{room["id"]}/activities', {
        'name': run+'-'+str(index), 'stock': args.stock, 'unitPrice': 1,
        'startsAt': (now-dt.timedelta(minutes=1)).isoformat(),
        'endsAt': (now+dt.timedelta(hours=4)).isoformat(),
    }, anchor['accessToken'])
    request(f'/api/v1/activities/{activity["id"]}/start', {}, anchor['accessToken'])
    activities.append(activity['id'])
fixture = {'run': run, 'baseUrl': args.base_url, 'roomId': room['id'], 'activityId': activities[0], 'activityIds':activities,
           'giftCode': gift['code'], 'anchor': anchor, 'participants': participants}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(fixture, indent=2), encoding='utf-8')
save_checkpoint(completed=True)
print(json.dumps({'fixture': str(output), 'participants': len(participants), 'roomId': room['id'], 'activityIds':activities}))
