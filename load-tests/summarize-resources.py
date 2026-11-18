"""Summarize measured resource samples; never infer capacity from these statistics alone."""
import argparse
import datetime as dt
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('samples')
parser.add_argument('--start', help='Inclusive UTC ISO timestamp')
parser.add_argument('--end', help='Inclusive UTC ISO timestamp')
args = parser.parse_args()

def instant(value):
    stamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if stamp.tzinfo is None:
        raise ValueError('Explicit timezone required')
    return stamp

start = instant(args.start) if args.start else None
end = instant(args.end) if args.end else None
if start and end and start > end:
    raise SystemExit('Invalid time window')
samples = [json.loads(line) for line in Path(args.samples).read_text(encoding='utf-8-sig').splitlines() if line.strip()]
samples = [row for row in samples if (not start or instant(row['utc']) >= start) and (not end or instant(row['utc']) <= end)]
good = [row for row in samples if not row.get('error') and row.get('metrics')]
if not good:
    raise SystemExit('No successful samples in this window')

def values(row, name, **labels):
    return [float(item['value'][1]) for item in row['metrics'] if item['metric']['__name__'] == name
        and all(item['metric'].get(key) == value for key, value in labels.items())]

def series(name, reducer=max, **labels):
    return [reducer(found) for row in good if (found := values(row, name, **labels))]

def stats(found, scale=1):
    if not found:
        return None
    return {key: round(value / scale, 3) for key, value in {
        'first': found[0], 'last': found[-1], 'min': min(found), 'max': max(found)}.items()}

old = [sum(float(item['value'][1]) for item in row['metrics'] if item['metric']['__name__'] == 'jvm_memory_used_bytes'
    and item['metric'].get('area') == 'heap' and any(term in item['metric'].get('id', '').lower()
    for term in ('old', 'tenured'))) for row in good]
lag = []
for row in good:
    groups = {}
    for item in row['metrics']:
        if item['metric']['__name__'] == 'streamhub_mq_consumer_lag':
            group = item['metric']['consumer_group']
            groups[group] = max(groups.get(group, 0), float(item['value'][1]))
    if groups:
        lag.append(sum(groups.values()))
waits = series('mysql_global_status_innodb_row_lock_waits')
print(json.dumps({
    'samples': len(samples), 'samplingErrors': len(samples) - len(good),
    'firstUtc': good[0]['utc'], 'lastUtc': good[-1]['utc'],
    'hostAvailableGiB': stats([row['hostAvailableBytes'] for row in good if 'hostAvailableBytes' in row], 2**30),
    'vmAvailableGiB': stats(series('node_memory_MemAvailable_bytes'), 2**30),
    'heapMiB': stats(series('jvm_memory_used_bytes', sum, area='heap'), 2**20),
    'oldGenMiB': stats(old, 2**20),
    'hikariPending': stats(series('hikaricp_connections_pending', sum)),
    'hikariActive': stats(series('hikaricp_connections_active', sum)),
    'sendBytes': stats(series('streamhub_realtime_send_bytes', sum)),
    # Both Live nodes expose the shared database's global count; do not sum them.
    'outboxPending': stats(series('streamhub_mq_outbox_pending')),
    'outboxOldestAgeSeconds': stats(series('streamhub_mq_outbox_oldest_age_seconds')),
    'consumerLag': stats(lag),
    'applicationTargetsUp': stats(series('up', sum, job='streamhub')),
    'mysqlThreadsRunning': stats(series('mysql_global_status_threads_running')),
    'mysqlRowLockWaitsDelta': waits[-1] - waits[0] if waits and waits[-1] >= waits[0] else None,
    'maximumSingleJvmCpuFraction': max(series('process_cpu_usage'), default=None),
}, indent=2))
