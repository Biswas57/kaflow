#!/usr/bin/env python3
"""Build and verify an isolated Compose project; missing engine is BLOCKED, not a pass."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
from release_support import client, command, get, json_lines, ports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('app/build/reports/container-smoke'))
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=args.output)).resolve()
    result = dict(run=str(run), passed=False, blocked=False, errors=[], phases=[],
                  claim='container replacement and SIGTERM; not physical power-loss proof')
    if not shutil.which('docker'):
        result.update(blocked=True, missing='Docker CLI, Compose plugin and reachable engine required',
                      next_command='python3 scripts/smoke_container.py')
        (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
        (run/'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result, indent=2)); return 2
    env = os.environ.copy(); hp, gp = ports()
    env.update(KAFLOW_HTTP_PORT=str(hp), KAFLOW_GRPC_PORT=str(gp))
    project = 'kf009-check-'+uuid.uuid4().hex[:12]
    compose = ['docker', 'compose', '-p', project, '-f', str(Path('compose.yaml').resolve())]
    result['isolated_project'] = project
    started = False; waiting = None; wait_log = None
    def docker(*arguments, name, timeout=60, expected=0):
        return command(['docker', *arguments], run/(name+'.log'), timeout=timeout, expected=expected, env=env)
    def dc(*arguments, name, timeout=60, expected=0):
        return command([*compose, *arguments], run/(name+'.log'), timeout=timeout, expected=expected, env=env)
    def ready():
        began = time.monotonic()
        while time.monotonic()-began < 90:
            try:
                if get(f'http://127.0.0.1:{hp}/actuator/health/readiness')[0] == 200:
                    identity = dc('ps', '-q', 'broker', name='health-container').strip()
                    health = docker('inspect', identity, '--format', '{{.State.Health.Status}}', name='health-status').strip()
                    if health == 'healthy': return
            except OSError:
                pass
            time.sleep(.2)
        raise RuntimeError('Container readiness exceeded 90s')
    try:
        try:
            result['compose_version'] = docker('compose', 'version', name='compose-version', timeout=10).strip()
            result['versions'] = json.loads(docker('version', '--format', '{{json .}}', name='versions', timeout=10))
            result['context'] = docker('context', 'show', name='context', timeout=10).strip()
            endpoint = docker('context', 'inspect', result['context'], '--format', '{{json .Endpoints.docker.Host}}', name='context-endpoint', timeout=10).strip()
            result['engine_endpoint'] = json.loads(endpoint)
            info = json.loads(docker('info', '--format', '{"OSType":{{json .OSType}},"Architecture":{{json .Architecture}},"OperatingSystem":{{json .OperatingSystem}},"Driver":{{json .Driver}},"ServerVersion":{{json .ServerVersion}}}', name='engine-info', timeout=10))
            result['engine'] = {k: info.get(k) for k in ('OSType', 'Architecture', 'OperatingSystem', 'Driver', 'ServerVersion')}
            result['execution_location'] = 'remote/context endpoint' if not result['engine_endpoint'].startswith(('unix://', 'npipe://')) else 'local engine socket; engine may run in a VM'
        except Exception as error:
            result.update(blocked=True, missing='Docker Compose or engine access: '+str(error), next_command='python3 scripts/smoke_container.py')
            return 2
        dc('config', name='compose-config')
        dc('build', name='source-image-build', timeout=600)
        result['image'] = json.loads(docker('image', 'inspect', 'kaflow:local', '--format', '{{json .Id}}', name='image-id'))
        started = True
        dc('up', '-d', name='up'); ready()
        container = dc('ps', '-q', 'broker', name='container-id').strip()
        assert docker('exec', container, 'id', '-u', name='runtime-uid').strip() == '10001'
        docker('exec', container, 'test', '-w', '/var/lib/kaflow', name='writable-volume')
        inspected = json.loads(docker('inspect', container, name='inspect-start'))[0]
        assert inspected['Config']['User'] == '10001:10001'
        assert all(binding['HostIp'] == '127.0.0.1' for bindings in inspected['NetworkSettings']['Ports'].values() if bindings for binding in bindings)
        assert not inspected['HostConfig']['Privileged']
        assert any(m['Type'] == 'volume' and m['Destination'] == '/var/lib/kaflow' for m in inspected['Mounts'])
        # Helpers share only this test project's named volume, never a host mount or user volume.
        dc('run', '--rm', '--no-deps', 'broker', name='second-owner-rejected', expected=1)
        dc('run', '--rm', '--no-deps', '--user', '10002:10002', 'broker', name='wrong-uid-rejected', expected=1)
        witness = []
        for phase in range(2):
            demo = json_lines(command(client(gp, 'demo'), run/f'demo-{phase}.log'))[-1]
            assert (demo['resumedA'], demo['resumedB']) == (phase*2, phase)
            witness += demo['receipts']; actual = {r['offset']: r for r in demo['inspectedRecords']}
            assert all(actual[r['offset']] == r for r in witness)
            result['phases'].append(demo)
            if phase == 0:
                dc('stop', name='stop-before-replacement'); dc('rm', '-f', name='remove-container')
                dc('up', '-d', name='replacement'); ready()
        (run/'acknowledgements.json').write_text(json.dumps(witness, indent=2)+'\n')
        wait_log = (run/'parked-client.log').open('w')
        waiting = subprocess.Popen(client(gp, 'consume', '--group', 'demo-a', '--format', 'bytes', '--wait-ms', '10000'),
                                   stdout=wait_log, stderr=subprocess.STDOUT)
        began = time.monotonic()
        while time.monotonic()-began < 5:
            status, gauge = get(f'http://127.0.0.1:{hp}/actuator/metrics/kaflow.wait.parked')
            if status == 200 and gauge['measurements'][0]['value'] == 1 and waiting.poll() is None: break
            time.sleep(.02)
        else: raise RuntimeError('Did not observe one outstanding parked client before SIGTERM')
        result['parked_before_shutdown'] = 1
        began = time.monotonic(); dc('stop', name='parked-sigterm', timeout=25)
        result['shutdown_seconds'] = round(time.monotonic()-began, 3)
        container = dc('ps', '-aq', 'broker', name='stopped-id').strip()
        state = json.loads(docker('inspect', container, '--format', '{{json .State}}', name='stopped-state'))
        assert state['ExitCode'] in (0, 143) and not state['OOMKilled']
        logs = dc('logs', '--no-color', name='broker-logs')
        assert 'Durable shutdown incomplete' not in logs and 'Durable shutdown failed' not in logs
        result['passed'] = True
    except Exception as error:
        result['errors'].append(f'{type(error).__name__}: {error}')
    finally:
        if waiting is not None:
            if waiting.poll() is None:
                waiting.terminate()
                try: waiting.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    waiting.kill(); waiting.wait(timeout=5); result['errors'].append('client cleanup exceeded 20s')
            wait_log.close()
        if started:
            try: dc('logs', '--no-color', name='final-container-logs')
            except Exception as error: result['errors'].append('log capture: '+str(error))
            try:
                # Destructive cleanup is confined to the freshly generated test project above.
                dc('down', '--volumes', '--remove-orphans', name='isolated-test-cleanup')
            except Exception as error: result['errors'].append('isolated cleanup: '+str(error))
        result['passed'] = result['passed'] and not result['errors'] and not result['blocked']
        (run/'result.json').write_text(json.dumps(result, indent=2)+'\n'); (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result, indent=2))
    return 2 if result['blocked'] else 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
