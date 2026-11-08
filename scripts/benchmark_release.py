#!/usr/bin/env python3
"""Repeat a fixed public-gRPC workload on fresh durable stores; correctness is separate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile
from release_support import Broker, client, command


def source_identity():
    if os.environ.get('KAFLOW_SOURCE_MANIFEST'):
        identity = json.loads(Path(os.environ['KAFLOW_SOURCE_MANIFEST']).read_text())
        for name, digest in identity['files'].items():
            if hashlib.sha256(Path(name).read_bytes()).hexdigest() != digest:
                raise RuntimeError('Candidate input differs from supplied manifest: '+name)
        return identity
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
    files = {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sorted(set(paths)) if p and Path(p).is_file()}
    encoded = json.dumps(files, sort_keys=True).encode()
    return dict(head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(), files=files,
                sha256=hashlib.sha256(encoded).hexdigest(), uncommitted=bool(subprocess.check_output(['git', 'status', '--porcelain'])))


def observation(args):
    try:
        value = subprocess.run(args, capture_output=True, text=True, timeout=5)
        return dict(command=args, exit=value.returncode, output=value.stdout.strip() or value.stderr.strip())
    except (OSError, subprocess.TimeoutExpired) as error:
        return dict(command=args, unavailable=type(error).__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['correctness', 'measured'], default='correctness')
    parser.add_argument('--config', type=Path, default=Path('benchmarks/release.json'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    if config['retentionBytes'] != 0 or config['repetitions'] < 3:
        parser.error('Measured baseline requires retention disabled and at least three repetitions')
    output = args.output or Path('app/build/reports/benchmarks')/args.mode
    output.mkdir(parents=True, exist_ok=True); run = Path(tempfile.mkdtemp(prefix='run-', dir=output)).resolve()
    identity = source_identity(); (run/'source-manifest.json').write_text(json.dumps(identity, indent=2)+'\n')
    environment = dict(platform=platform.platform(), architecture=platform.machine(), execution='native',
                       filesystem=observation(['df', '-h', str(run)]),
                       storage=observation(['diskutil', 'info', str(run)] if platform.system() == 'Darwin' else ['findmnt', '-T', str(run)]),
                       cpu=observation(['sysctl', '-n', 'machdep.cpu.brand_string'] if platform.system() == 'Darwin' else ['lscpu']),
                       memory=observation(['sysctl', '-n', 'hw.memsize'] if platform.system() == 'Darwin' else ['free', '-b']))
    (run/'environment.json').write_text(json.dumps(environment, indent=2)+'\n')
    result = dict(mode=args.mode, run=str(run), source_manifest_sha256=identity['sha256'], config=config,
                  trials=[], failures=[], expected_failure_checks=[], claim='closed-loop observed workloads, not production capacity or power-loss validation')
    repetitions = config['repetitions'] if args.mode == 'measured' else 1
    try:
        for workload in config['workloads']:
            for repetition in range(repetitions):
                name = f"{workload['scenario']}-{workload['concurrency']}-{repetition}"; trial = run/name; trial.mkdir()
                with Broker(trial/'broker', trial/'data', [('warm', config['partitions']), ('bench', config['partitions'])]) as broker:
                    invocation = client(broker.grpc, 'benchmark', '--scenario', workload['scenario'], '--concurrency', workload['concurrency'],
                                        '--mode', args.mode, '--http-url', broker.base, '--output', trial/'result.json',
                                        '--payload-bytes', config['payloadBytes'], '--key-bytes', config['keyBytes'], '--partitions', config['partitions'],
                                        '--warmup-ms', config['warmupMillis'] if args.mode == 'measured' else 0,
                                        '--duration-ms', config['durationMillis'] if args.mode == 'measured' else 10000)
                    command(invocation, trial/'client.log', timeout=240)
                    measured = json.loads((trial/'result.json').read_text())
                    assert measured['validated'] and measured['verification']['passed']
                    result['trials'].append(dict(name=name, result=measured))
                print(f'Completed {name}: verified', flush=True)
        if args.mode == 'correctness':
            for name, scenario, extra in [('setup-failure', 'long-poll', ['--http-url', 'http://127.0.0.1:1']),
                                          ('receipt-cap', 'append', ['--max-receipts', '1'])]:
                trial = run/name; trial.mkdir()
                with Broker(trial/'broker', trial/'data', [('warm', 4), ('bench', 4)]) as broker:
                    command(client(broker.grpc, 'benchmark', '--scenario', scenario, '--mode', 'correctness',
                                   '--output', trial/'result.json', *extra), trial/'client.log', timeout=60, expected=1)
                    failed = json.loads((trial/'result.json').read_text())
                    assert not failed['validated']
                    assert failed['failures']['SETUP' if name == 'setup-failure' else 'RECEIPT_CAP'] > 0
                    if name == 'setup-failure': assert failed['measurement']['parkedDeliveryLatency']['count'] == 0
                    else: assert failed['verification']['acknowledgedRecords'] == 1 and failed['verification']['passed']
                    result['expected_failure_checks'].append(dict(name=name, classification_verified=True, result=failed))
        summaries = []
        for workload in config['workloads']:
            trials = [t['result'] for t in result['trials'] if t['result']['scenario'] == workload['scenario'] and t['result']['concurrency'] == workload['concurrency']]
            rates = [t['measurement']['successfulRpcsPerSecond'] for t in trials]
            summaries.append(dict(**workload, completed_repetitions=len(rates), successes_per_second=rates,
                                  minimum=min(rates), median=statistics.median(rates), maximum=max(rates)))
        result['variability'] = summaries; result['passed'] = True
    except Exception as error:
        result['passed'] = False; result['failures'].append(f'{type(error).__name__}: {error}')
    finally:
        (run/'result.json').write_text(json.dumps(result, indent=2)+'\n'); (output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(passed=result['passed'], result=str(run/'result.json'), completed_trials=len(result['trials']), failures=result['failures']), indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
