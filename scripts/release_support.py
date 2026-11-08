"""Bounded helpers for packaged operator checks; never imports broker test fixtures."""
import json
import os
from pathlib import Path
import platform
import socket
import subprocess
import time
import urllib.error
import urllib.request
from smoke_bootjar import executable_jars, listener_open

HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def ports():
    with socket.socket() as first, socket.socket() as second:
        first.bind(('127.0.0.1', 0)); second.bind(('127.0.0.1', 0))
        return first.getsockname()[1], second.getsockname()[1]


def get(url):
    try:
        with HTTP.open(url, timeout=2) as response:
            data = response.read(65537)
            if len(data) > 65536:
                raise RuntimeError('HTTP diagnostic exceeded 64 KiB')
            return response.status, json.loads(data)
    except urllib.error.HTTPError as error:
        return error.code, {}


def command(args, log, timeout=40, expected=0, env=None):
    began = time.monotonic()
    with Path(log).open('w') as output:
        process = subprocess.Popen(list(map(str, args)), stdout=output, stderr=subprocess.STDOUT,
                                   env=env, start_new_session=True)
        timed_out = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            import signal
            os.killpg(process.pid, signal.SIGTERM)
            try:
                code = process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); code = process.wait(timeout=5)
    evidence = dict(command=list(map(str, args)), exit=code, timed_out=timed_out,
                    seconds=round(time.monotonic()-began, 3), log=str(log))
    Path(str(log)+'.json').write_text(json.dumps(evidence, indent=2)+'\n')
    if timed_out or code != expected:
        raise RuntimeError(f'Command exit {code}, expected {expected}, timed_out={timed_out}; see {log}')
    return Path(log).read_text()


def client(port, *args):
    executable = Path('clients/build/install/kaflow-client/bin/kaflow-client').resolve()
    if not executable.is_file():
        raise RuntimeError('Run :clients:installDist first')
    return [str(executable), args[0], '--port', str(port), *map(str, args[1:])]


def json_lines(text):
    return [json.loads(line) for line in text.splitlines() if line.startswith('{')]


class Broker:
    def __init__(self, output, data, topics=None, extra=(), env=None):
        self.output = Path(output); self.output.mkdir(parents=True, exist_ok=True)
        self.data = Path(data).resolve(); self.topics = topics or [('orders', 1)]
        self.http, self.grpc = ports(); self.base = f'http://127.0.0.1:{self.http}'
        jars = list(executable_jars())
        if len(jars) != 1:
            raise RuntimeError('Expected exactly one bootJar; run :app:bootJar')
        java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        # Bind addresses intentionally omitted: this check exercises packaged host defaults.
        self.args = [java, '-Xms64m', '-Xmx512m', '-jar', str(jars[0].resolve()),
                     f'--server.port={self.http}', f'--grpc.server.port={self.grpc}',
                     '--kaflow.durable.enabled=true', f'--kaflow.durable.directory={self.data}']
        for i, (name, count) in enumerate(self.topics):
            self.args += [f'--kaflow.durable.topics[{i}].name={name}', f'--kaflow.durable.topics[{i}].partitions={count}']
        self.args += list(extra)
        self.log = (self.output/'broker.log').open('w')
        self.process = subprocess.Popen(self.args, stdout=self.log, stderr=subprocess.STDOUT, env=env)
        self.result = dict(command=self.args, pid=self.process.pid, http_port=self.http, grpc_port=self.grpc)

    def ready(self):
        began = time.monotonic()
        while time.monotonic()-began < 60:
            if self.process.poll() is not None:
                raise RuntimeError(f'Broker exited before readiness: {self.process.returncode}; {self.output}/broker.log')
            try:
                if get(self.base+'/actuator/health/readiness')[0] == 200 and listener_open(self.grpc):
                    self.result['ready_seconds'] = round(time.monotonic()-began, 3)
                    return self
            except OSError:
                pass
            time.sleep(.05)
        raise RuntimeError('Broker readiness exceeded 60s')

    def assert_loopback(self):
        wanted = {self.http, self.grpc}; observed = {}
        if platform.system() == 'Darwin':
            output = subprocess.check_output(['lsof', '-nP', '-a', '-p', str(self.process.pid), '-iTCP', '-sTCP:LISTEN'], text=True, timeout=5)
            for line in output.splitlines():
                for port in wanted:
                    if f':{port} (LISTEN)' in line:
                        if f'127.0.0.1:{port} (LISTEN)' not in line:
                            raise RuntimeError('Non-loopback listener: '+line)
                        observed[str(port)] = '127.0.0.1'
        elif platform.system() == 'Linux':
            inodes = set()
            for fd in Path(f'/proc/{self.process.pid}/fd').iterdir():
                try:
                    target = os.readlink(fd)
                    if target.startswith('socket:['): inodes.add(target[8:-1])
                except FileNotFoundError:
                    pass
            for name in ('tcp', 'tcp6'):
                for line in Path(f'/proc/{self.process.pid}/net/{name}').read_text().splitlines()[1:]:
                    fields = line.split(); address, port = fields[1].split(':'); port = int(port, 16)
                    if fields[3] != '0A' or fields[9] not in inodes or port not in wanted: continue
                    if address not in ('0100007F', '0000000000000000FFFF00000100007F'):
                        raise RuntimeError(f'Non-loopback listener {address}:{port}')
                    observed[str(port)] = address
        else:
            raise RuntimeError('No runtime listener verifier for this platform')
        if set(map(int, observed)) != wanted:
            raise RuntimeError('Could not establish both actual listener bind addresses')
        self.result['observed_listener_addresses'] = observed

    def reject_startup(self):
        try:
            code = self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Invalid configuration did not reject startup in 30s')
        if code == 0 or listener_open(self.http) or listener_open(self.grpc):
            raise RuntimeError('Invalid configuration unexpectedly served or succeeded')
        self.result['expected_startup_rejection'] = code

    def stop(self):
        began = time.monotonic()
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(timeout=5)
                self.result['shutdown_timed_out'] = True
        self.log.close()
        self.result.update(exit=self.process.returncode, shutdown_seconds=round(time.monotonic()-began, 3),
                           listeners_closed=not listener_open(self.http) and not listener_open(self.grpc))
        (self.output/'process.json').write_text(json.dumps(self.result, indent=2)+'\n')
        if self.result.get('shutdown_timed_out') or not self.result['listeners_closed']:
            raise RuntimeError('Broker shutdown/listener cleanup failed')
        if not self.result.get('expected_startup_rejection') and self.process.returncode not in (0, 143, -15):
            raise RuntimeError(f'Unexpected broker exit: {self.process.returncode}')

    def __enter__(self):
        try:
            return self.ready()
        except BaseException:
            self.stop(); raise

    def __exit__(self, *_):
        self.stop()
