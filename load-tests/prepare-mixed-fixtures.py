"""Create fresh local activities and pair funded senders with an isolated seckill room."""
import argparse
import datetime as dt
import json
from pathlib import Path
import secrets
import urllib.request

RESULTS = (Path(__file__).resolve().parent / 'results').resolve()


def private(path):
    path = path.resolve()
    if not path.is_relative_to(RESULTS):
        raise ValueError('Credentials must stay within ignored results')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--funded-fixture', required=True, type=Path)
    parser.add_argument('--seckill-fixture', required=True, type=Path)
    parser.add_argument('--gift-output', required=True, type=Path)
    parser.add_argument('--seckill-output', required=True, type=Path)
    parser.add_argument('--stock', required=True, type=int)
    parser.add_argument('--activities', type=int, default=2)
    args = parser.parse_args()
    funded, bulk = [json.loads(private(path).read_text(encoding='utf-8-sig'))
                    for path in (args.funded_fixture,args.seckill_fixture)]
    gift_out, buy_out = private(args.gift_output), private(args.seckill_output)
    if gift_out == buy_out or any(path.exists() or not path.parent.is_dir() for path in (gift_out,buy_out)):
        parser.error('Require two unused output files in existing ignored directories')
    base = bulk['baseUrl']
    if base != funded['baseUrl'] or base not in ('http://localhost:8089','http://127.0.0.1:8089'):
        parser.error('Fixture preparation writes only to the matching local rehearsal')
    if not 1 <= args.stock <= len(bulk['participants']) or not 2 <= args.activities <= 8:
        parser.error('Require stock within participant count and 2–8 activities')
    room = int(bulk['roomId'])
    anchor = bulk['anchor']
    sender_ids = {entry['userId'] for entry in funded['participants']}
    buyer_ids = {entry['userId'] for entry in bulk['participants']}
    if not sender_ids or sender_ids & (buyer_ids | {anchor['userId']}):
        parser.error('Use distinct funded senders, buyers and room owner')
    now = dt.datetime.now(dt.timezone.utc)
    run = 'mix-'+secrets.token_hex(5)

    def request(path, body, account):
        req = urllib.request.Request(base+path,data=json.dumps(body).encode(),
            headers={'Content-Type':'application/json','Authorization':'Bearer '+account['accessToken']})
        with urllib.request.urlopen(req,timeout=10) as response:
            data = json.load(response)
        if not data.get('success'):
            raise RuntimeError(f'Fixture write rejected at {path}: {data.get("code")}')
        return data['data']

    ids = []
    for index in range(args.activities):
        activity = request(f'/api/v1/live/rooms/{room}/activities',{
            'name':f'{run}-{index}','stock':args.stock,'unitPrice':1,
            'startsAt':(now-dt.timedelta(minutes=1)).isoformat(),
            'endsAt':(now+dt.timedelta(hours=4)).isoformat(),
        },anchor)
        activity_id = int(activity['id'])
        request(f'/api/v1/activities/{activity_id}/start',{},anchor)
        ids.append(activity_id)
        print(f'Started isolated activity {activity_id}',flush=True)
    gift = {'run':run,'baseUrl':base,'roomId':room,'giftCode':funded['giftCode'],
            'anchor':anchor,'participants':funded['participants']}
    buy = bulk | {'run':run,'activityId':ids[0],'activityIds':ids}
    for path,data in ((gift_out,gift),(buy_out,buy)):
        with path.open('x',encoding='utf-8') as stream:
            json.dump(data,stream)
    print(json.dumps({'roomId':room,'activityIds':ids}))


if __name__ == '__main__':
    main()
