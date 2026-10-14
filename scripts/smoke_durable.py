#!/usr/bin/env python3
"""Bounded opt-in packaged Append/restart smoke, using a separate Java test client.

Run after testClasses and bootJar. Data and acknowledgements remain under --output.
The client uses production classes/dependencies extracted from the executable JAR.
No public Fetch or storage administration endpoint is introduced.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from smoke_bootjar import executable_jars, listener_open


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--output', type=Path, default=Path('app/build/reports/durable-smoke'))
    args = parser.parse_args()
    jars = [args.jar] if args.jar else list(executable_jars())
    if len(jars) != 1 or not jars[0].is_file():
        parser.error('Expected one executable bootJar')
    tests = Path('app/build/classes/java/test').resolve()
    if not (tests / 'kaflow/storage/DurableSmokeClient.class').is_file():
        parser.error('Run :app:testClasses first')
    args.output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=args.output)).resolve()
    extracted = run / 'packaged'; extracted.mkdir()
    # Only extract known class/dependency entries; do not trust archive traversal paths.
    with zipfile.ZipFile(jars[0]) as jar:
        for entry in jar.infolist():
            if entry.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                destination = (extracted / entry.filename).resolve()
                if not destination.is_relative_to(extracted):
                    raise ValueError('Unsafe JAR entry')
                jar.extract(entry, extracted)
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    classpath = os.pathsep.join([str(tests), str(extracted/'BOOT-INF/classes'), str(extracted/'BOOT-INF/lib/*')])
    data = run/'data'; witness = run/'acknowledgements.bin'
    result = {'jar': str(jars[0]), 'run_directory': str(run), 'errors': [], 'phases': [],
              'claim': 'real network acknowledgement and process restart; not physical power-loss proof'}

    def fixture(*arguments):
        command = [java, '-cp', classpath, 'kaflow.storage.DurableSmokeClient', *map(str, arguments)]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
        (run / ('client-' + arguments[0] + '-' + str(len(result['phases'])) + '.log')).write_text(completed.stdout+completed.stderr)
        if completed.returncode:
            raise RuntimeError(f'Fixture {arguments[0]} exited {completed.returncode}: {completed.stderr[-1000:]}')
        return completed.stdout.strip()

    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        result['seed'] = fixture('seed', data)
        for phase in range(2):
            with socket.socket() as hs, socket.socket() as gs:
                hs.bind(('127.0.0.1', 0)); gs.bind(('127.0.0.1', 0))
                hp, gp = hs.getsockname()[1], gs.getsockname()[1]
            outcome = {'phase': phase, 'http_port': hp, 'grpc_port': gp}
            result['phases'].append(outcome)
            command = [java, '-jar', str(jars[0]), '--server.address=127.0.0.1', f'--server.port={hp}',
                       '--grpc.server.address=127.0.0.1', f'--grpc.server.port={gp}',
                       '--kaflow.durable.enabled=true', f'--kaflow.durable.directory={data}']
            with (run/f'application-{phase}.log').open('w') as log:
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                outcome['pid'] = process.pid
                try:
                    deadline = time.monotonic()+60
                    while time.monotonic() < deadline:
                        if process.poll() is not None:
                            raise RuntimeError(f'Packaged startup exited {process.returncode}')
                        try:
                            with http.open(f'http://127.0.0.1:{hp}/actuator/health', timeout=1) as response:
                                if response.status == 200 and json.load(response).get('status') == 'UP' and listener_open(gp):
                                    outcome['ready'] = True
                                    break
                        except (OSError, urllib.error.URLError):
                            pass
                        time.sleep(0.2)  # Startup polling, not a durability correctness mechanism.
                    else:
                        raise TimeoutError('Durable startup exceeded 60 seconds')
                    outcome['client'] = fixture('append', gp, witness, phase*2)
                finally:
                    start = time.monotonic()
                    # First restart is an abrupt process termination after the external client
                    # persisted actual network receipts. Second shutdown exercises normal cleanup.
                    outcome['termination'] = 'SIGKILL' if phase == 0 else 'SIGTERM'
                    if process.poll() is None:
                        process.kill() if phase == 0 else process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        outcome['shutdown_timed_out'] = True
                        process.kill(); process.wait(timeout=5)
                        result['errors'].append('Shutdown exceeded 20 seconds')
                    outcome['exit_code'] = process.returncode
                    outcome['shutdown_seconds'] = round(time.monotonic()-start, 3)
                    outcome['listeners_closed'] = not listener_open(hp) and not listener_open(gp)
                    allowed = (-9, 137) if phase == 0 else (0, -15, 143)
                    if process.returncode not in allowed or not outcome['listeners_closed']:
                        result['errors'].append('Unexpected shutdown result')
        result['inspection'] = fixture('inspect', data, witness)
    except (Exception, KeyboardInterrupt) as error:
        result['errors'].append(f'{type(error).__name__}: {error}')
    finally:
        shutil.rmtree(extracted)  # Only this invocation's extracted dependency scratch.
    result['passed'] = not result['errors'] and result.get('inspection') == 'VERIFIED 4 acknowledged records'
    (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
