#!/usr/bin/env python3
"""Exercise the documented native operator path with packaged production clients only."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
from release_support import Broker, client, command, get, json_lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('app/build/reports/release-smoke'))
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=args.output)).resolve(); data = run/'data'
    result = dict(run=str(run), errors=[], phases=[], claim='packaged native operator path; process restart, not power-loss proof')
    try:
        witness = []
        for phase in range(2):
            with Broker(run/f'phase-{phase}', data, [('orders', 1), ('json-orders', 1)],
                        extra=['--kaflow.durable.groups.lease=2s']) as broker:
                broker.assert_loopback()
                assert get(broker.base+'/actuator/health/liveness')[0] == 200
                assert get(broker.base+'/api/topics/orders')[0] == 404
                demo = json_lines(command(client(broker.grpc, 'demo'), run/f'demo-{phase}.log'))[-1]
                assert (demo['resumedA'], demo['resumedB']) == (phase*2, phase)
                assert (demo['committedA'], demo['committedB']) == ((phase+1)*2, phase+1)
                assert demo['replayUnchanged']
                witness += demo['receipts']; actual = {r['offset']: r for r in demo['inspectedRecords']}
                assert all(actual[r['offset']] == r for r in witness)
                result['phases'].append(demo)
                if phase == 0:
                    command(client(broker.grpc, 'produce', '--topic', 'json-orders', '--key-hex', ''), run/'produce.log')
                    command(client(broker.grpc, 'consume', '--topic', 'json-orders', '--group', 'json-example', '--process-delay-ms', '2500'), run/'consume.log')
                    status, meter = get(broker.base+'/actuator/metrics/kaflow.rpc.outcomes?tag=method:Heartbeat&tag=status:OK')
                    assert status == 200 and meter['measurements'][0]['value'] >= 1
            if phase == 0:
                # Complete process termination precedes an exclusive whole-directory copy.
                assert broker.process.poll() is not None and broker.result['listeners_closed']
                shutil.copytree(data, run/'closed-backup')
        (run/'acknowledgements.json').write_text(json.dumps(witness, indent=2)+'\n')
        shutil.copytree(run/'closed-backup', run/'restored')
        with Broker(run/'restore-process', run/'restored', [('orders', 1), ('json-orders', 1)]) as broker:
            restored = json_lines(command(client(broker.grpc, 'demo'), run/'restore-demo.log'))[-1]
            assert (restored['resumedA'], restored['resumedB']) == (2, 1)
            actual = {r['offset']: r for r in restored['inspectedRecords']}
            assert all(actual[r['offset']] == r for r in witness[:2])
            result['closed_backup_restore'] = restored

        invalid = Broker(run/'late-conflict', data, [('earlier-missing', 1), ('orders', 2)])
        try:
            invalid.reject_startup()
        finally:
            invalid.stop()
        with Broker(run/'after-conflict', data, [('orders', 1)]) as broker:
            text = command(client(broker.grpc, 'produce', '--topic', 'earlier-missing'), run/'no-partial-topic.log', expected=1)
            assert 'NOT_FOUND' in text
        result['late_conflict_created_no_topic'] = True

        for name, extra in [('unknown', '--kaflow.durable.typo=1'), ('invalid', '--kaflow.durable.shutdown-timeout=0s')]:
            invalid = Broker(run/name, run/(name+'-data'), extra=[extra])
            try:
                invalid.reject_startup()
            finally:
                invalid.stop()
            assert not (run/(name+'-data')).exists()
        result['invalid_configuration_no_store'] = True

        external = run/'operator.properties'; external.write_text('kaflow.durable.max-storage-bytes=4294967296\n')
        for source, expected in [('file', 4294967296), ('environment', 2147483648), ('cli', 3221225472)]:
            env = os.environ.copy(); env.pop('KAFLOW_DURABLE_MAXSTORAGEBYTES', None)
            extra = ['--spring.config.additional-location='+external.as_uri()]
            if source != 'file': env['KAFLOW_DURABLE_MAXSTORAGEBYTES'] = '2147483648'
            if source == 'cli': extra += ['--kaflow.durable.max-storage-bytes=3221225472']
            with Broker(run/('precedence-'+source), run/('precedence-'+source+'-data'), extra=extra, env=env) as broker:
                status, metric = get(broker.base+'/actuator/metrics/kaflow.storage.quota.bytes')
                assert status == 200 and metric['measurements'][0]['value'] == expected
        result['precedence'] = 'CLI over environment over external file; actual storage quota gauge checked'

        with Broker(run/'retention', run/'retention-data', [('orders', 1)], extra=[
                '--kaflow.durable.segment-bytes=160', '--kaflow.durable.retention-bytes=200']) as broker:
            command(client(broker.grpc, 'produce', '--count', '10'), run/'retention-produce.log')
            text = command(client(broker.grpc, 'consume', '--group', 'lagging'), run/'retention-rejected.log', expected=1)
            assert 'OUT_OF_RANGE' in text
            text = command(client(broker.grpc, 'consume', '--group', 'lagging', '--accept-retained-start', 'true'), run/'retention-explicit.log')
            assert 'Explicit retained-start choice' in text
            result['explicit_retention_choice'] = True
        result['passed'] = True
    except Exception as error:
        result['passed'] = False; result['errors'].append(f'{type(error).__name__}: {error}')
    finally:
        (run/'result.json').write_text(json.dumps(result, indent=2)+'\n')
        (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2)); return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
