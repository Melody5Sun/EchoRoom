"""Capture post-GC class counts for the eight local rehearsal JVMs, sequentially."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
args = parser.parse_args()
query = urllib.parse.urlencode({'query': 'sum(echoroom_realtime_send_connections)'})
with urllib.request.urlopen('http://localhost:9090/api/v1/query?' + query, timeout=5) as response:
    active = json.load(response)['data']['result']
if not active or any(float(item['value'][1]) != 0 for item in active):
    raise SystemExit('Require available metrics and zero active WebSocket connections before forced GC')
root = Path(__file__).resolve().parent.parent
output = Path(args.output).resolve()
results_root = (root / 'load-tests/results').resolve()
if not output.is_relative_to(results_root):
    raise SystemExit('Diagnostic output must stay in ignored load-tests/results')
nodes = ['auth-service-1', 'user-service-1', 'live-service-1', 'live-service-2',
         'gateway-service-1', 'gateway-service-2', 'realtime-gateway-1', 'realtime-gateway-2']
report = {'utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'nodes': {}}

def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr[-1000:])
    return result.stdout

def heap(text):
    generations = re.findall(r'(def new|tenured) generation\s+total (\d+)K, used (\d+)K', text)
    if len(generations) != 2:
        raise RuntimeError('Expected Serial GC generation counters; inspect raw output for this collector')
    return {'heapUsedKiB': sum(int(row[2]) for row in generations),
            'oldUsedKiB': next(int(row[2]) for row in generations if row[0] == 'tenured')}

try:
    for suffix in nodes:
        node = 'echoroom-' + suffix
        uid = run(['docker', 'exec', node, 'id', '-u']).strip()
        gid = run(['docker', 'exec', node, 'id', '-g']).strip()
        started = run(['docker', 'inspect', '--format', '{{.State.StartedAt}}', node]).strip()
        base = ['docker', 'run', '--rm', '--network', 'none', '--pid=container:' + node,
                '--user', uid + ':' + gid, '--cap-add', 'SYS_PTRACE', '--mount',
                f'type=bind,source={root / "scripts/jvm-attach.py"},target=/diagnostic.py,readonly',
                'python:3.12-slim', 'python', '/diagnostic.py']
        before = run(base + ['GC.heap_info'])
        gc = run(base + ['GC.run'])
        histogram = run(base + ['GC.class_histogram'])
        after = run(base + ['GC.heap_info'])
        if run(['docker', 'inspect', '--format', '{{.State.StartedAt}}', node]).strip() != started:
            raise RuntimeError('JVM restarted during diagnosis; sample is invalid')
        classes = {name: int(count) for count, name in re.findall(
            r'^\s*\d+:\s+(\d+)\s+\d+\s+(\S+)', histogram, re.MULTILINE)}
        report['nodes'][node] = {'before': heap(before), 'after': heap(after),
                                 'startedAt': started,
                                 'sessionObjects': {name: classes.get(name, 0) for name in [
                                     'com.echoroom.live.RoomSessionRegistry$SessionState',
                                     'com.echoroom.live.SessionSender$Outbound']},
                                 'beforeText': before, 'afterText': after, 'gc': gc,
                                 'histogram': histogram}
        print(json.dumps({'node': node, 'before': heap(before), 'after': heap(after)}), flush=True)
finally:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
