"""Reject a capacity interval if its running artifacts, instances or limits changed."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('before', type=Path)
parser.add_argument('after', type=Path)
parser.add_argument('--minimum-targets', type=int, default=11)
args = parser.parse_args()
if args.minimum_targets < 1:
    parser.error('Minimum targets must be positive')
before, after = (json.loads(path.read_text(encoding='utf-8-sig')) for path in (args.before,args.after))
failures = []
for key in ('commit','compose','infraCompose','engine'):
    if key not in before or before[key] != after.get(key):
        failures.append(key)
if before.get('dirty',True) or after.get('dirty',True):
    failures.append('dirty worktree')
if not before.get('infraCompose') or not after.get('infraCompose'):
    failures.append('infrastructure not captured')

first, last = ({c['name']:c for c in value['containers']} for value in (before,after))
for name in sorted(first.keys() | last.keys()):
    if name not in first or name not in last:
        failures.append(name+': added or removed')
        continue
    for key in ('id','image','startedAt','restarts','runtimeJarSha256','nanoCpus','cpuQuota',
                'cpuPeriod','memory','parameters'):
        if key not in first[name] or first[name][key] != last[name].get(key):
            failures.append(f'{name}: {key}')
    if any(c.get('health') not in (None,'healthy') for c in (first[name],last[name])):
        failures.append(f'{name}: unhealthy endpoint')
def targets(value):
    return {(item['metric']['job'],item['metric']['instance'])
            for item in value['queries']['up']['data']['result'] if item['value'][1] == '1'}


before_targets, after_targets = targets(before), targets(after)
if min(len(before_targets),len(after_targets)) < args.minimum_targets:
    failures.append(f'fewer than {args.minimum_targets} live monitoring targets')
if before_targets != after_targets:
    failures.append('live monitoring targets changed')
print(json.dumps({'sameResourceEnvelope':not failures,'containersCompared':len(first.keys() & last.keys()),
                  'differences':failures,'capacityApproved':False},indent=2))
raise SystemExit(bool(failures))
