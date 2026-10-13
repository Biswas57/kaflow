#!/usr/bin/env python3
"""Bounded startup/shutdown check of the actual Spring Boot executable JAR.

Uses only Python's standard library. Run from the repository root after bootJar.
This checks startup and administration, not message delivery or durability.
"""

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
import zipfile


def executable_jars():
    for path in sorted(Path("app/build/libs").glob("*.jar")):
        with zipfile.ZipFile(path) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF")
            if b"Start-Class: tributary.TributaryApplication" in manifest:
                yield path


def listener_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, help="Executable bootJar; otherwise discover it")
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/smoke"))
    args = parser.parse_args()
    jars = [args.jar] if args.jar else list(executable_jars())
    if len(jars) != 1 or not jars[0].is_file():
        parser.error(f"Expected one executable bootJar, found {jars}; run the clean build first")

    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    args.output.mkdir(parents=True, exist_ok=True)
    log_path = args.output / "application.log"
    result = {"jar": str(jars[0]), "startup_timeout_seconds": 60,
              "shutdown_timeout_seconds": 20, "errors": []}
    # Reserve two different ports together. A bind race after release is possible;
    # such a conflict is a failed smoke run, retained in the application log.
    with socket.socket() as http_socket, socket.socket() as grpc_socket:
        http_socket.bind(("127.0.0.1", 0))
        grpc_socket.bind(("127.0.0.1", 0))
        http_port = http_socket.getsockname()[1]
        grpc_port = grpc_socket.getsockname()[1]
    result.update(http_port=http_port, grpc_port=grpc_port)
    command = [java, "-jar", str(jars[0]), "--server.address=127.0.0.1",
               f"--server.port={http_port}", "--grpc.server.address=127.0.0.1",
               f"--grpc.server.port={grpc_port}"]
    result["command"] = command
    # Do not send loopback health/admin requests through a configured HTTP proxy.
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f"http://127.0.0.1:{http_port}"
    proc = None
    with log_path.open("w") as log:
        try:
            proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            result["pid"] = proc.pid
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f"Application exited during startup: {proc.returncode}")
                try:
                    with http.open(base + "/actuator/health", timeout=1) as response:
                        health = json.load(response)
                        if response.status == 200 and health.get("status") == "UP" and listener_open(grpc_port):
                            result.update(health=health, http_status=200, grpc_listener_open=True)
                            break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.2)
            else:
                result["startup_timed_out"] = True
                raise RuntimeError("Startup/health/listener check exceeded 60 seconds")

            # Exercise the administration mapping with state local to this process.
            topic_id = "kf001-smoke"
            request = urllib.request.Request(
                base + "/api/topics", method="POST",
                data=json.dumps({"id": topic_id, "type": "String"}).encode(),
                headers={"Content-Type": "application/json"})
            with http.open(request, timeout=3) as response:
                result["admin_create_status"] = response.status
                if response.status != 201:
                    raise RuntimeError(f"Topic creation returned {response.status}")
            with http.open(base + "/api/topics/" + topic_id, timeout=3) as response:
                result["admin_get_status"] = response.status
                result["admin_topic"] = json.load(response)
                if response.status != 200 or result["admin_topic"].get("id") != topic_id:
                    raise RuntimeError("Topic administration did not return the created topic")
            result["startup_passed"] = True
        except (Exception, KeyboardInterrupt) as error:
            result["errors"].append(f"{type(error).__name__}: {error}")
        finally:
            if proc is not None:
                if proc.poll() is None:
                    result["termination_signal"] = "SIGTERM"
                    start = time.monotonic()
                    proc.terminate()
                    try:
                        proc.wait(timeout=20)
                        result["shutdown_timed_out"] = False
                    except subprocess.TimeoutExpired:
                        result["shutdown_timed_out"] = True
                        result["errors"].append("Shutdown exceeded 20 seconds; killed only this child")
                        proc.kill()
                        proc.wait(timeout=5)
                    result["shutdown_seconds"] = round(time.monotonic() - start, 3)
                else:
                    result["errors"].append("Application exited before the termination check")
                result["process_exit_code"] = proc.returncode
                # HotSpot commonly reports 143 for SIGTERM; Python may report -15.
                if proc.returncode not in (0, 143, -15):
                    result["errors"].append(f"Unexpected termination exit code: {proc.returncode}")
                result["http_listener_closed"] = not listener_open(http_port)
                result["grpc_listener_closed"] = not listener_open(grpc_port)
                if not result["http_listener_closed"] or not result["grpc_listener_closed"]:
                    result["errors"].append("A selected port still accepts connections after child exit")
    result["passed"] = result.get("startup_passed", False) and not result["errors"]
    (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    print(f"Application log: {log_path}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
