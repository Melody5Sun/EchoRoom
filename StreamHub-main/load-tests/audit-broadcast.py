"""Reconcile --console-output logs from separate publisher/receiver k6 runs (AUDIT=1).
Usage: python load-tests/audit-broadcast.py publisher.log receiver.log
Run with synchronized clocks; timestamps select only receiver's connected interval.
"""
import json
import sys
import argparse
from bisect import bisect_right
from collections import defaultdict
from pathlib import Path
from ws_audit import continuous_windows

parser=argparse.ArgumentParser()
parser.add_argument('--legacy-open-intervals',action='store_true',help='Reproduce historical TCP-open audits; not business-ready intervals')
parser.add_argument('--minimum-hold-seconds',type=float,default=0)
parser.add_argument('--expected-receivers',type=int)
parser.add_argument('--expected-publishers',type=int)
parser.add_argument('--continuity-output',type=Path)
parser.add_argument('logs',nargs='+')
args=parser.parse_args()
if args.minimum_hold_seconds < 0:
    parser.error('Minimum hold must be nonnegative')
if args.minimum_hold_seconds and (not args.expected_receivers or not args.expected_publishers or not args.continuity_output):
    parser.error('Hold verification requires receiver/publisher counts and an output path')

def events(wanted):
    for path in args.logs:
        with open(path, encoding='utf-8') as stream:
            for line in stream:
                if not any(kind in line for kind in wanted):
                    continue
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


def receiver_key(e):
    return e['run'], e['room'], e['receiver']

if args.minimum_hold_seconds:
    lifecycle = list(events({'ready','close'}))
    runs = {event['run'] for event in lifecycle}
    if len(runs) != 1:
        raise SystemExit('Continuity verification requires one run')
    run = next(iter(runs))
    try:
        receiver_windows = continuous_windows(lifecycle,'receiver',run,args.expected_receivers)
        publisher_windows = continuous_windows(lifecycle,'publisher',run,args.expected_publishers)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    minimums = {role:min(end-start for start,end in windows)
                for role,windows in (('receiver',receiver_windows),('publisher',publisher_windows))}
    passed = min(minimums.values()) >= args.minimum_hold_seconds*1000
    args.continuity_output.write_text(json.dumps({'run':run,'requiredHoldSeconds':args.minimum_hold_seconds,
        'receiverCount':len(receiver_windows),'publisherCount':len(publisher_windows),
        'minimumHoldMs':minimums,'passed':passed},indent=2),encoding='utf-8')
    if not passed:
        raise SystemExit('Continuous business-ready connection was shorter than required')

accepted, closes = defaultdict(dict), defaultdict(list)
owners = {}
boundary='open' if args.legacy_open_intervals else 'ready'
if args.legacy_open_intervals:
    print('Legacy mode: TCP-open timestamps include time before application admission.',file=sys.stderr)
receivers = []
for e in events({'accepted',boundary,'close'}):
    if e['audit'] == 'accepted' and e['role'] == 'publisher':
        accepted[e['run'],e['room']][e['id']] = e['sentAt']
        owners[e['run'],e['id']] = e['room']
    elif e['role'] == 'receiver':
        if e['audit'] == boundary:
            receivers.append(e)
        elif e['audit'] == 'close':
            closes[receiver_key(e)].append(e['at'])
if not receivers or not accepted:
    raise SystemExit('Audit invalid: no business-ready receiver intervals or no publisher-confirmed accepted messages')

windows = defaultdict(list)
totals = []
for opened in receivers:
    key = receiver_key(opened)
    start = opened['at']
    closed = [at for at in closes[key] if at >= start]
    if not closed:
        raise SystemExit('Audit incomplete: receiver close marker missing')
    finish = min(closed)
    # Exclude the final 1s to let in-flight deliveries finish before the receiver closes.
    remaining = {identifier for identifier,sent in accepted[key[:2]].items() if start <= sent < finish-1000}
    windows[key].append((start,finish,remaining))
    totals.append((opened['receiver'],len(remaining),remaining))
for sessions in windows.values():
    sessions.sort(key=lambda window: window[0])
starts = {key:[window[0] for window in sessions] for key,sessions in windows.items()}
cross_room = defaultdict(int)
for e in events({'message'}):
    if e['role'] != 'receiver':
        continue
    key = receiver_key(e)
    owner = owners.get((e['run'],e['id']))
    if key not in starts:
        continue
    index = bisect_right(starts[key],e['at'])-1
    # Adjacent sessions can share a millisecond timestamp. Count only
    # sessions containing the receipt, including that boundary.
    for window_index in range(index,-1,-1):
        start,finish,remaining = windows[key][window_index]
        if e['at'] > finish:
            break
        if owner is not None and owner != e['room']:
            cross_room[key,window_index] += 1
        remaining.discard(e['id'])
missing = 0
for opened,(receiver,expected,remaining) in zip(receivers,totals):
    lost = len(remaining)
    missing += lost
    key = receiver_key(opened)
    interval_index = bisect_right(starts[key],opened['at'])-1
    print(json.dumps({'receiver':receiver,'room':opened['room'],'expected':expected,
                     'observed':expected-lost,'missing':lost,'crossRoom':cross_room[key,interval_index]}))
raise SystemExit(1 if missing or any(cross_room.values()) else 0)
