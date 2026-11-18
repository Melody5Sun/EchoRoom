"""Capture bounded reason counters and runtime identity without user/room metric labels."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
output = Path(args.output).resolve()
if not output.is_relative_to((root / 'load-tests/results').resolve()) or output.exists():
    raise SystemExit('Choose a new file inside ignored results')

def docker(*args):
    return subprocess.run(['docker', *args], text=True, encoding='utf-8', capture_output=True, timeout=15, check=True).stdout.strip()

report = {'utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'nodes': {}}
for index in (1, 2):
    node = f'echoroom-live-service-{index}'
    metrics = docker('exec', node, 'wget', '-qO-', 'http://localhost:8083/actuator/prometheus')
    counts = {reason: float(value) for reason, value in re.findall(
        r'^echoroom_realtime_admission_rejected_total\{reason="([a-z_]+)"\} ([0-9.eE+-]+)$', metrics, re.MULTILINE)}
    def gauge(name):
        match = re.search(r'^' + re.escape(name) + r' ([0-9.eE+-]+)$', metrics, re.MULTILINE)
        return float(match[1]) if match else 0
    report['nodes'][node] = {
        'rejected': counts,
        'registered': gauge('echoroom_realtime_admission_registered_total'),
        'connections': gauge('echoroom_realtime_send_connections'),
        'startedAt': docker('inspect', '--format', '{{.State.StartedAt}}', node),
        'jarSha256': docker('exec', node, 'sha256sum', '/app/app.jar').split()[0],
    }
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({node: {'rejected': info['rejected'], 'connections': info['connections']}
    for node, info in report['nodes'].items()}))
