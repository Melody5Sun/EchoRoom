"""Run inside a Linux diagnostic container sharing the target JVM PID namespace.

Protocol reference: OpenJDK jdk17u attachListener_linux.cpp / attachListener.cpp.
This client deliberately excludes arbitrary commands, agents and heap dumps.
"""
import argparse
import os
from pathlib import Path
import signal
import socket
import stat
import sys
import time

parser = argparse.ArgumentParser()
parser.add_argument('command', choices=['GC.heap_info', 'GC.run', 'GC.class_histogram'])
args = parser.parse_args()
target = Path('/proc/1')
if target.joinpath('comm').read_text().strip() != 'java':
    raise SystemExit('PID 1 must be the target JVM')
uid_line = next(line for line in target.joinpath('status').read_text().splitlines() if line.startswith('Uid:'))
target_uid = int(uid_line.split()[2])
if os.geteuid() != target_uid:
    raise SystemExit('Diagnostic client must use the JVM effective UID')
directory = target / 'root/tmp'
endpoint = directory / '.java_pid1'
marker = directory / '.attach_pid1'
created = False
try:
    if not endpoint.exists():
        fd = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        created = True
        os.kill(1, signal.SIGQUIT)
        deadline = time.monotonic() + 10
        while not endpoint.exists():
            if time.monotonic() >= deadline:
                raise TimeoutError('JVM attach listener did not start')
            time.sleep(0.1)
    info = endpoint.stat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != target_uid:
        raise RuntimeError('Unexpected attach endpoint owner or type')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(30)
        client.connect(str(endpoint))
        client.sendall(b'\0'.join(part.encode() for part in ['1', 'jcmd', args.command, '', '']) + b'\0')
        chunks = []
        size = 0
        while True:
            chunk = client.recv(65536)
            if not chunk:
                break
            size += len(chunk)
            if size > 16 * 1024 * 1024:
                raise RuntimeError('Diagnostic response exceeds limit')
            chunks.append(chunk)
    status, _, output = b''.join(chunks).partition(b'\n')
    sys.stdout.write(output.decode('utf-8', errors='replace'))
    if status != b'0':
        raise SystemExit('JVM diagnostic command failed: ' + status.decode(errors='replace'))
finally:
    if created:
        marker.unlink(missing_ok=True)
