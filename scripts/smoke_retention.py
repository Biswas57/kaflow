#!/usr/bin/env python3
"""Bounded separate-process packaged retention/restart and parked-load shutdown evidence."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from release_support import executable_jars, extract_boot_jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--output', type=Path, default=Path('app/build/reports/retention-smoke'))
    args = parser.parse_args()
    jars = [args.jar] if args.jar else list(executable_jars())
    if len(jars) != 1 or not jars[0].is_file():
        parser.error('Expected one executable bootJar')
    tests = Path('app/build/classes/java/test').resolve()
    if not (tests / 'kaflow/storage/RetentionSmokeClient.class').is_file():
        parser.error('Run :app:testClasses first')
    args.output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=args.output)).resolve()
    extracted = run / 'packaged'
    extracted.mkdir()
    result = {'jar': str(jars[0]), 'run_directory': str(run), 'phases': [], 'errors': [],
              'claim': 'separate JVMs using production classes/dependencies from bootJar; process restart, not power-loss proof'}
    try:
        extract_boot_jar(jars[0], extracted)
        logging = extracted / 'client-logback.xml'
        logging.write_text('''<configuration><appender name="stderr" class="ch.qos.logback.core.ConsoleAppender">
          <target>System.err</target><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender>
          <root level="INFO"><appender-ref ref="stderr"/></root></configuration>''')
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        classpath = os.pathsep.join([str(tests), str(extracted/'BOOT-INF/classes'), str(extracted/'BOOT-INF/lib/*')])
        for phase in range(2):
            report = run / f'phase-{phase}.json'
            command = [java, f'-Dlogging.config={logging}', '-cp', classpath, 'kaflow.storage.RetentionSmokeClient',
                       str(run/'data'), str(report), str(phase)]
            began = time.monotonic()
            with (run/f'phase-{phase}.log').open('w') as log:
                child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                try:
                    status = child.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
                    raise TimeoutError(f'Retention phase {phase} exceeded 60 seconds')
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait(timeout=5)
            if status != 0:
                raise RuntimeError(f'Retention phase {phase} exited {status}; see phase log')
            data = json.loads(report.read_text())
            data.update(pid=child.pid, exit_code=status, seconds=round(time.monotonic()-began, 3))
            result['phases'].append(data)
            if not data.get('passed') or not data.get('listeners_closed'):
                raise RuntimeError(f'Retention phase {phase} failed')
    except Exception as exc:
        result['errors'].append(str(exc))
    finally:
        shutil.rmtree(extracted)
        result['passed'] = len(result['phases']) == 2 and not result['errors']
        (run/'result.json').write_text(json.dumps(result, indent=2)+'\n')
        (args.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
