"""Run independent HTTP, publisher, receiver and slow-client processes on one load host."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from ws_audit import continuous_windows

HERE = Path(__file__).resolve().parent
RESULTS = (HERE / 'results').resolve()


def audit_events(path, wanted):
    with path.open(encoding='utf-8') as lines:
        for line in lines:
            start, end = line.find('{'), line.rfind('}')
            if start < 0:
                continue
            try:
                event = json.loads(line[start:end + 1])
                if isinstance(event.get('msg'), str):
                    event = json.loads(event['msg'])
                if event.get('audit') in wanted:
                    yield event
            except (ValueError, AttributeError):
                continue


def fixture(path):
    path = path.resolve(strict=True)
    if not path.is_relative_to(RESULTS):
        raise ValueError('Fixtures must be in ignored results')
    raw = path.read_bytes()
    return path, json.loads(raw.decode('utf-8-sig')), hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gift-fixture', required=True, type=Path)
    parser.add_argument('--seckill-fixture', required=True, type=Path)
    parser.add_argument('--activity', required=True, type=int, nargs=2, metavar=('WARMUP', 'STEADY'))
    parser.add_argument('--gift-rate', type=int, default=5)
    parser.add_argument('--seckill-rate', type=int, default=5)
    parser.add_argument('--connections', type=int, default=100)
    parser.add_argument('--resources', required=True)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    gift_path, gift, gift_hash = fixture(args.gift_fixture)
    buy_path, buy, buy_hash = fixture(args.seckill_fixture)
    if args.connections < 10 or min(args.gift_rate, args.seckill_rate) < 1:
        parser.error('Require at least ten connections and positive transaction rates')
    if len(set(args.activity)) != 2 or not set(args.activity) <= set(buy['activityIds']):
        parser.error('Supply two distinct activities from the seckill fixture')
    if (gift['baseUrl'], gift['roomId']) != (buy['baseUrl'], buy['roomId']):
        parser.error('Use isolated gift and seckill fixtures for the same entry and room')
    k6, node = shutil.which('k6'), shutil.which('node')
    if not k6 or not node:
        parser.error('k6 and node must be on PATH')
    slow = pubs = args.connections // 10
    receivers = args.connections - slow - pubs
    if len(gift['participants']) < pubs:
        parser.error('Need one funded credential per publisher')
    # These workers share an IP and therefore the gateway's Ticket budget.
    receiver_rate, publisher_rate, slow_rate = 2, 0.5, 0.5
    ramp = max((receivers+1)//2, 2*pubs, 2*slow) + 2
    hold = 60 if args.smoke else 1800
    # Start HTTP first; its margin covers launch skew and the WS ramp.
    def timings(seconds):
        return seconds + ramp + 20, seconds + ramp + 8
    if len(buy['participants']) < args.seckill_rate * timings(hold)[0] + 1:
        parser.error('Not enough distinct seckill participants for the steady phase')
    run = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    manifest = {'run':run, 'smoke':args.smoke, 'resources':args.resources,
                'commit':subprocess.check_output(['git','rev-parse','HEAD'], cwd=HERE, text=True).strip(),
                'worktreeDirty':bool(subprocess.check_output(['git','status','--porcelain'], cwd=HERE)),
                'fixtures':{'gift':gift_hash,'seckill':buy_hash}, 'activities':args.activity,
                'rates':{'gift':args.gift_rate,'seckill':args.seckill_rate},
                'connectRates':{'receivers':receiver_rate,'publishers':publisher_rate,'slow':slow_rate},
                'connections':{'receivers':receivers,'publishers':pubs,'slow':slow},
                'phases':{}, 'loadChecksPassed':False, 'capacityApproved':False}
    path = RESULTS / f'{run}-mixed-manifest.json'
    def save(mode='w'):
        with path.open(mode, encoding='utf-8') as stream:
            json.dump(manifest, stream, indent=2)
    save('x')
    token_csv = ','.join(account['accessToken'] for account in gift['participants'])
    env = {k:v for k,v in os.environ.items()
           if k not in ('ACCESS_TOKENS','ACCESS_TOKENS_FILE','ROOM_IDS','WS_URL','SLOW_MAX_LIFETIME_MS')}
    env.update(BASE_URL=gift['baseUrl'], ROOM_ID=str(gift['roomId']))
    hidden = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    def phase(name, seconds, activity):
        http_seconds, ws_hold = timings(seconds)
        stage = ws_hold+ramp
        prefix = f'{run}-mixed'
        ws_run = f'{prefix}-ws-{name}'
        children, console = {}, {}
        report = manifest['phases'][name] = {}
        save()

        def start(role, script, overrides, summary=None):
            base = RESULTS / f'{prefix}-{role}-{name}'
            console[role] = base.with_suffix('.audit.log')
            command = ([node, str(HERE/script)] if role == 'slow' else
                       [k6,'run','--address','127.0.0.1:0','--log-format','json',
                        '--console-output',str(console[role]),'--summary-export',str(summary),
                        str(HERE/'k6'/script)])
            with base.with_suffix('.log').open('xb') as log:
                children[role] = subprocess.Popen(command, cwd=HERE, env=env | overrides,
                    stdout=log, stderr=subprocess.STDOUT, creationflags=hidden)

        try:
            for kind, rate, credentials in [('gift',args.gift_rate,gift_path),('seckill',args.seckill_rate,buy_path)]:
                tx_run = f'{prefix}-{kind}-{name}'
                start(kind,'http.js',{'KIND':kind,'RATE':str(rate),'ACCESS_TOKENS_FILE':credentials.as_posix(),
                    'RUN_ID':tx_run,'ACTIVITY_ID':str(activity),'GIFT_CODE':str(gift['giftCode']),
                    'DURATION':f'{http_seconds}s','MIN_TOKEN_TTL_SECONDS':str(http_seconds+45),
                    'MIXED_AUDIT':'1','MIXED_SECONDS':str(http_seconds),
                    'PREALLOCATED_VUS':'50','MAX_VUS':'200','MIN_ACCEPTED_RATIO':'0.9'},
                    RESULTS/f'{tx_run}-summary.json')
            # Start sockets only after both HTTP schedules are live.
            startup = time.monotonic()+20
            windows = []
            for role in ('gift','seckill'):
                while True:
                    if console[role].exists():
                        window = next(audit_events(console[role], {'http_window'}), None)
                        if window:
                            windows.append((window['start'],window['end']))
                            break
                    if children[role].poll() is not None or time.monotonic() >= startup:
                        raise ValueError(f'{role} failed to start its HTTP schedule')
                    time.sleep(.2)
            socket_env = {'ACCESS_TOKENS':token_csv,'HOLD_SECONDS':str(ws_hold),
                'RAMP_SECONDS':str(ramp),'STAGE_SECONDS':str(stage),'AUDIT':'1','RUN_ID':ws_run,
                'CHAT_INTERVAL_MS':'1000','HISTORY_SYNC':'1'}
            for role, count, rate in [('receiver',receivers,receiver_rate),('publisher',pubs,publisher_rate)]:
                start(role,'websocket.js',socket_env | {'ROLE':role,'VUS':str(count),'CONNECT_RATE':str(rate)},
                      RESULTS/f'{ws_run}-{role}-summary.json')
            start('slow','slow-clients.mjs',socket_env | {'SLOW_CONNECTIONS':str(slow),
                  'SLOW_CONNECT_INTERVAL_MS':str(int(1000/slow_rate))})
            deadline = time.monotonic()+max(stage,http_seconds)+60
            while any(child.poll() is None for child in children.values()):
                if time.monotonic() > deadline:
                    raise TimeoutError('Mixed phase did not terminate within its bound')
                time.sleep(.5)
            report['exitCodes'] = {role:child.returncode for role,child in children.items()}
            if any(report['exitCodes'].values()):
                raise ValueError('A mixed worker failed or violated a threshold')
        finally:
            for child in children.values():
                if child.poll() is None:
                    child.kill()
                    child.wait()
        slow_summary = next((entry for entry in audit_events(
            RESULTS/f'{prefix}-slow-{name}.log', {None}) if entry.get('slowClientSummary')), None)
        if not slow_summary or slow_summary['distinctOpened'] < slow:
            raise ValueError('Slow TCP workload did not establish its target')
        report['slowClients'] = slow_summary
        for role,count in [('receiver',receivers),('publisher',pubs)]:
            windows += continuous_windows(audit_events(console[role], {'ready','close'}), role, ws_run, count)
        report['overlapMs'] = min(end for _,end in windows)-max(start for start,_ in windows)
        if report['overlapMs'] < seconds*1000:
            raise ValueError('Measured business-ready overlap was too short')
        with (RESULTS/f'{ws_run}-broadcast.jsonl').open('xb') as audit:
            subprocess.run([sys.executable,str(HERE/'audit-broadcast.py'),
                str(console['publisher']),str(console['receiver'])], stdout=audit, check=True)
        report['broadcastAuditPassed'] = True
        save()

    print(f'Mixed run {run}', flush=True)
    try:
        phase('warmup',30 if args.smoke else 300,args.activity[0])
        phase('steady',hold,args.activity[1])
        manifest['loadChecksPassed'] = True
        print('Mixed workload checks passed; settlement and resource evidence are separate.', flush=True)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        manifest['error'] = str(error)
        raise SystemExit(f'Mixed workload checks failed: {error}') from None
    finally:
        save()


if __name__ == '__main__':
    main()
