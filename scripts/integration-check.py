#!/usr/bin/env python
"""Run checks against a fresh Compose project that is isolated from developer data."""

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from uuid import uuid4


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--browser", action="store_true")
    parser.add_argument(
        "--keep-on-failure",
        action="store_true",
        help="retain this run's isolated Compose project and generated env file for debugging",
    )
    arguments = parser.parse_args()

    def terminate(signum, _frame):
        raise SystemExit(128 + signum)

    for signal_name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        signum = getattr(signal, signal_name, None)
        if signum is not None:
            signal.signal(signum, terminate)

    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    project = f"stockrescue-check-{uuid4().hex[:16]}"
    working = root / ".tools"
    working.mkdir(exist_ok=True)
    environment_file = working / f"{project}.env"
    postgres_admin_password = uuid4().hex
    database_password = uuid4().hex
    environment_file.write_text(
        f"POSTGRES_ADMIN_PASSWORD={postgres_admin_password}\nDATABASE_PASSWORD={database_password}\n"
        f"WEB_PORT={arguments.port}\n",
        encoding="utf-8",
    )
    compose = ["docker", "compose", "--env-file", str(environment_file), "-p", project]
    base_url = f"http://127.0.0.1:{arguments.port}"
    sensitive_values = (postgres_admin_password, database_password)

    def redact(text):
        for secret in sensitive_values:
            text = text.replace(secret, "[REDACTED]")
        return text

    def execute(command, failure_expected=False, timeout=240, input_text=None):
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            input=input_text,
            timeout=timeout,
        )
        if not failure_expected and result.returncode:
            try:
                (working / f"{project}-failure.log").write_text(
                    redact(result.stdout + result.stderr), encoding="utf-8"
                )
            except OSError:
                pass
            raise RuntimeError(
                "Infrastructure check command failed; diagnostic log is in .tools/"
            )
        if failure_expected and result.returncode == 0:
            raise AssertionError("A command expected to fail unexpectedly succeeded")
        return result

    def run(*command, **options):
        return execute(compose + list(command), **options)

    def kafka_cli(tool, *command, **options):
        return execute(
            [
                "docker",
                "run",
                "--rm",
                "--init",
                "-i",
                "--network",
                configuration["networks"]["internal"]["name"],
                "--memory",
                "256m",
                "--cpus",
                "1",
                "--pids-limit",
                "128",
                "--env",
                "KAFKA_HEAP_OPTS=-Xms16m -Xmx64m",
                "--entrypoint",
                "/usr/bin/timeout",
                configuration["services"]["kafka"]["image"],
                "--kill-after=2s",
                "15s",
                f"/opt/kafka/bin/{tool}.sh",
                *command,
            ],
            timeout=25,
            **options,
        )

    def backend_process():
        identifier = run("ps", "--quiet", "backend").stdout.strip()
        processes = subprocess.check_output(
            ["docker", "top", identifier, "-eo", "pid,args"], text=True, timeout=10
        )
        applications = [
            line for line in processes.splitlines() if "/app/stockrescue.jar" in line
        ]
        require(len(applications) == 1, "Expected exactly one backend application JVM")
        return applications[0].split()[0]

    def status(path):
        return status_at(base_url, path)

    def status_at(url, path):
        try:
            with urlopen(url + path, timeout=10) as response:
                return response.status, response.read()
        except HTTPError as error:
            return error.code, error.read()
        except (URLError, TimeoutError):
            return 0, b""

    def wait_for(path, expected, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            actual, body = status(path)
            if actual == expected:
                return body
            time.sleep(0.25)
        raise AssertionError(f"{path} did not report HTTP {expected} within {seconds}s")

    def probe_container(container, command, seconds=60):
        deadline = time.monotonic() + seconds
        result = None
        while time.monotonic() < deadline:
            result = subprocess.run(
                ["docker", "exec", container, *command],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=10,
            )
            if result.returncode == 0:
                return result.stdout
            time.sleep(1)
        logs = subprocess.run(
            ["docker", "logs", container],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
        )
        raise AssertionError(
            f"Standalone {container} did not pass its probe: "
            f"{redact(result.stderr.strip())}\n{redact(logs.stdout + logs.stderr)}"
        )

    standalone_containers = []

    def launch_standalone(name, image, environment=()):
        container = f"{project}-{name}"
        command = [
            "docker",
            "run",
            "--detach",
            "--name",
            container,
            "--network",
            configuration["networks"]["internal"]["name"],
            "--memory",
            "512m" if name == "standalone-backend" else "256m",
            "--cpus",
            "1",
            "--pids-limit",
            "128",
            "--stop-timeout",
            "20",
            "--read-only",
        ]
        if name == "standalone-web":
            command.extend(
                [
                    "--tmpfs",
                    "/etc/nginx/conf.d:rw,nosuid,nodev,noexec,size=1m,uid=101,gid=101,mode=0755",
                ]
            )
        command.extend(["--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m"])
        for key, value in environment:
            command.extend(["--env", f"{key}={value}"])
        execute(command + [image], timeout=30)
        standalone_containers.append(container)
        return container

    def stop_standalone(container):
        execute(["docker", "stop", "--time", "20", container], timeout=25)
        execute(["docker", "rm", container], timeout=15)
        standalone_containers.remove(container)

    smoke_topic = f"stockrescue.infrastructure.smoke.{uuid4().hex}"
    smoke_offset = 0

    def smoke(failure_expected=False):
        nonlocal smoke_offset
        backend_pid = backend_process()
        correlation = uuid4().hex
        record = f"correlation-id:{correlation}\t{correlation}\tstockrescue-infrastructure-check"
        kafka_cli(
            "kafka-console-producer",
            "--bootstrap-server",
            "kafka:9092",
            "--topic",
            smoke_topic,
            "--sync",
            "--reader-property",
            "parse.key=true",
            "--reader-property",
            "parse.headers=true",
            "--command-property",
            "max.block.ms=2000",
            "--command-property",
            "request.timeout.ms=2000",
            "--command-property",
            "delivery.timeout.ms=5000",
            "--command-property",
            "linger.ms=0",
            input_text=record + "\n",
            failure_expected=failure_expected,
        )
        if not failure_expected:
            # Only this checker writes to partition 0 of its fresh, isolated topic.
            consumed = kafka_cli(
                "kafka-console-consumer",
                "--bootstrap-server",
                "kafka:9092",
                "--topic",
                smoke_topic,
                "--partition",
                "0",
                "--offset",
                str(smoke_offset),
                "--max-messages",
                "1",
                "--timeout-ms",
                "8000",
                "--command-property",
                "enable.auto.commit=false",
                "--command-property",
                "allow.auto.create.topics=false",
                "--command-property",
                "default.api.timeout.ms=5000",
                "--command-property",
                "request.timeout.ms=2000",
                "--formatter-property",
                "print.key=true",
                "--formatter-property",
                "print.headers=true",
            )
            require(
                record in consumed.stdout.splitlines(),
                "No matching Kafka key, payload and correlation header received",
            )
            smoke_offset += 1
            print(
                f"PASS: Kafka CLI consumed record; correlationId={correlation}",
                flush=True,
            )
        require(
            backend_process() == backend_pid,
            "Smoke replaced the backend application process",
        )

    configuration = {}
    completed = False
    primary_error = None
    try:
        configuration = json.loads(run("config", "--format", "json").stdout)
        for name, service in configuration["services"].items():
            ports = service.get("ports", [])
            if name == "web":
                require(
                    len(ports) == 1 and ports[0]["host_ip"] == "127.0.0.1",
                    "Proxy is not loopback-only",
                )
            else:
                require(not ports, f"Private service {name} publishes a host port")
        require(
            configuration["networks"]["internal"]["internal"] is True,
            "Private network is not internal",
        )
        require(
            not any(
                "DATABASE" in name or "POSTGRES" in name
                for name in configuration["services"]["intelligence"].get("environment", {})
            ),
            "Python receives operational DB credentials",
        )
        print(
            "PASS: private ports, loopback proxy and Python credential boundary", flush=True
        )

        run("up", "--build", "-d", "--wait", "--wait-timeout", "180")
        require(
            json.loads(wait_for("/api/health/readiness", 200))["status"] == "UP",
            "Backend is not ready",
        )
        require(
            json.loads(wait_for("/api/health/integration", 200))["status"] == "UP",
            "Integration is not healthy",
        )
        require(b"StockRescue" in wait_for("/", 200), "Frontend was not served")
        intelligence_container = launch_standalone(
            "standalone-intelligence",
            configuration["services"]["intelligence"]["image"],
        )
        require(
            json.loads(
                probe_container(
                    intelligence_container,
                    (
                        "python",
                        "-c",
                        (
                            "import urllib.request; print(urllib.request.urlopen("
                            "'http://127.0.0.1:8000/health/ready', timeout=2).read().decode())"
                        ),
                    ),
                )
            )["status"]
            == "UP",
            "Standalone Python image is not ready",
        )
        standalone_runtime = json.loads(
            subprocess.check_output(
                ["docker", "inspect", intelligence_container], text=True, timeout=10
            )
        )[0]
        require(
            not any(
                item.startswith(("DATABASE_", "POSTGRES_"))
                for item in standalone_runtime["Config"].get("Env", [])
            ),
            "Standalone Python image received DB credentials",
        )
        python_client = execute(
            [
                "docker",
                "run",
                "--rm",
                "--network",
                configuration["networks"]["internal"]["name"],
                "--entrypoint",
                "python",
                configuration["services"]["intelligence"]["image"],
                "-c",
                (
                    "import urllib.request; print(urllib.request.urlopen("
                    f"'http://{intelligence_container}:8000/health/ready', timeout=2)"
                    ".read().decode())"
                ),
            ],
            timeout=30,
        )
        require(
            json.loads(python_client.stdout)["status"] == "UP",
            "Standalone Python image is not reachable from another container",
        )

        missing_config = execute(
            [
                "docker",
                "run",
                "--rm",
                "--network",
                configuration["networks"]["internal"]["name"],
                "--memory",
                "512m",
                "--env",
                "SERVER_ADDRESS=0.0.0.0",
                configuration["services"]["backend"]["image"],
            ],
            failure_expected=True,
            timeout=30,
        )
        missing_message = missing_config.stdout + missing_config.stderr
        require(
            "Could not resolve placeholder" in missing_message
            and any(
                name in missing_message
                for name in (
                    "DATABASE_URL",
                    "DATABASE_USERNAME",
                    "DATABASE_PASSWORD",
                    "KAFKA_BOOTSTRAP_SERVERS",
                    "INTELLIGENCE_BASE_URL",
                )
            ),
            "Backend did not clearly reject missing required service configuration",
        )

        backend_container = launch_standalone(
            "standalone-backend",
            configuration["services"]["backend"]["image"],
            (
                ("SERVER_ADDRESS", "0.0.0.0"),
                ("DATABASE_URL", "jdbc:postgresql://postgres:5432/stockrescue"),
                ("DATABASE_USERNAME", "stockrescue_app"),
                ("DATABASE_PASSWORD", database_password),
                ("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
                ("INTELLIGENCE_BASE_URL", "http://intelligence:8000"),
            ),
        )
        require(
            probe_container(
                backend_container,
                (
                    "java",
                    "-Xms8m",
                    "-Xmx16m",
                    "-cp",
                    "/app/healthcheck",
                    "Healthcheck",
                ),
                seconds=90,
            )
            == "",
            "Standalone backend image is not ready",
        )
        web_container = launch_standalone(
            "standalone-web",
            configuration["services"]["web"]["image"],
            (("BACKEND_HOST", backend_container), ("BACKEND_PORT", "8080")),
        )
        require(
            probe_container(
                web_container,
                ("wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/healthz"),
            )
            == "",
            "Standalone web health failed",
        )
        require(
            json.loads(
                probe_container(
                    web_container,
                    (
                        "wget",
                        "-q",
                        "-O",
                        "-",
                        "http://127.0.0.1:8080/api/health/readiness",
                    ),
                )
            )["status"]
            == "UP",
            "Standalone proxy could not reach its configured backend hostname",
        )
        page = probe_container(
            web_container, ("wget", "-q", "-O", "-", "http://127.0.0.1:8080/")
        ).encode()
        require(b"StockRescue" in page, "Standalone web did not serve its page")
        scripts = re.findall(rb'<script[^>]+src="([^"]+\.js)"', page)
        require(scripts, "Standalone web page did not reference its JavaScript bundle")
        bundles = b"".join(
            probe_container(
                web_container,
                (
                    "wget",
                    "-q",
                    "-O",
                    "-",
                    f"http://127.0.0.1:8080{script.decode('ascii')}",
                ),
            ).encode()
            for script in scripts
        )
        require(
            b"local-admin-change-me" not in bundles
            and b"local-app-change-me" not in bundles,
            "Development credentials were embedded in the static frontend",
        )
        for service in ("web", "backend", "intelligence"):
            image = configuration["services"][service]["image"]
            image_runtime = json.loads(
                subprocess.check_output(
                    ["docker", "image", "inspect", image], text=True, timeout=10
                )
            )[0]
            require(
                not any(
                    item.startswith(("DATABASE_PASSWORD=", "POSTGRES_ADMIN_PASSWORD="))
                    for item in image_runtime["Config"].get("Env", [])
                ),
                f"{service} image contains a development credential",
            )
        for container in (web_container, backend_container, intelligence_container):
            stop_standalone(container)
        print(
            "PASS: app images run outside Compose with explicit service addresses, read-only roots, "
            "health checks and clear missing-config failure",
            flush=True,
        )
        for service in ("web", "backend", "intelligence"):
            identifier = run("ps", "--quiet", service).stdout.strip()
            runtime = json.loads(
                subprocess.check_output(
                    ["docker", "inspect", identifier], text=True, timeout=10
                )
            )[0]
            require(
                runtime["Config"]["User"].split(":")[0] not in ("", "0", "root"),
                f"{service} runs as root",
            )
            if service == "intelligence":
                require(
                    not any(
                        item.startswith(("DATABASE_", "POSTGRES_"))
                        for item in runtime["Config"].get("Env", [])
                    ),
                    "Python image contains DB credentials",
                )
        topics = kafka_cli(
            "kafka-topics", "--bootstrap-server", "kafka:9092", "--list"
        ).stdout.splitlines()
        require(
            not any(
                topic.startswith("stockrescue.infrastructure.smoke.")
                for topic in topics
            ),
            "Default runtime created a smoke topic",
        )
        kafka_cli(
            "kafka-topics",
            "--bootstrap-server",
            "kafka:9092",
            "--create",
            "--topic",
            smoke_topic,
            "--partitions",
            "1",
            "--replication-factor",
            "1",
            "--config",
            "retention.ms=60000",
        )
        smoke()
        smoke()
        print(
            "PASS: proxy, Flyway/PG readiness, real Java-to-Python request "
            "and repeated isolated Kafka receipt",
            flush=True,
        )

        for service in ("intelligence", "kafka", "postgres"):
            outage_error = None
            try:
                run("stop", service)
                path = (
                    "/api/health/readiness"
                    if service == "postgres"
                    else "/api/health/integration"
                )
                wait_for(path, 503)
                require(
                    status("/api/health/liveness")[0] == 200,
                    "Dependency outage breaks process liveness",
                )
                if service != "postgres":
                    require(
                        status("/api/health/readiness")[0] == 200,
                        "Optional dependency blocks DB readiness",
                    )
                if service == "kafka":
                    smoke(failure_expected=True)
            except BaseException as error:
                outage_error = error
            try:
                run("start", service)
                wait_for("/api/health/readiness", 200)
                wait_for("/api/health/integration", 200)
            except BaseException:
                if outage_error is None:
                    raise
                outage_error.add_note(
                    f"Recovery also failed for {service}; see the isolated failure diagnostics."
                )
            if outage_error is not None:
                raise outage_error
            print(
                f"PASS: {service} outage is visible; liveness and recovery verified",
                flush=True,
            )

        run("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "180", "backend")
        wait_for("/api/health/readiness", 200)
        wait_for("/api/health/integration", 200)
        version = run(
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "stockrescue_admin",
            "-d",
            "stockrescue",
            "-Atc",
            "SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL'",
        ).stdout.strip()
        require(
            version == "1", "Migration history changed across non-destructive restart"
        )
        privileges = run(
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "stockrescue_admin",
            "-d",
            "stockrescue",
            "-Atc",
            "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles "
            "WHERE rolname='stockrescue_app'",
        ).stdout.strip()
        require(privileges == "f", "Application role has administrative privileges")
        smoke()
        kafka_cli(
            "kafka-topics",
            "--bootstrap-server",
            "kafka:9092",
            "--delete",
            "--topic",
            smoke_topic,
        )
        deletion_deadline = time.monotonic() + 10
        while True:
            topics = kafka_cli(
                "kafka-topics", "--bootstrap-server", "kafka:9092", "--list"
            ).stdout.splitlines()
            if smoke_topic not in topics:
                break
            require(
                time.monotonic() < deletion_deadline,
                "Isolated smoke topic was not removed",
            )
            time.sleep(0.25)
        wait_for("/api/health/integration", 200)
        print(
            "PASS: single backend application JVM; diagnostics healthy without a smoke topic",
            flush=True,
        )
        if arguments.browser:
            subprocess.run(
                ["pnpm", "--dir", "frontend", "run", "test:e2e"],
                check=True,
                env={**os.environ, "BASE_URL": base_url},
                timeout=120,
            )
        print("PASS: integrated foundation checks", flush=True)
        completed = True
    except BaseException as error:
        primary_error = error
        raise
    finally:
        cleanup_errors = []
        preserve_resources = not completed and arguments.keep_on_failure
        if not preserve_resources:
            for container in standalone_containers:
                try:
                    result = subprocess.run(
                        ["docker", "rm", "--force", container],
                        check=False,
                        capture_output=True,
                        text=True,
                        encoding="utf-8",
                        errors="replace",
                        timeout=15,
                    )
                    if result.returncode:
                        cleanup_errors.append(redact(result.stderr or result.stdout))
                except Exception as error:
                    cleanup_errors.append(redact(str(error)))
            try:
                run("down", "--volumes", "--remove-orphans", timeout=60)
            except Exception as error:
                cleanup_errors.append(redact(str(error)))

        keep_environment = preserve_resources or bool(cleanup_errors)
        if not keep_environment:
            try:
                environment_file.unlink(missing_ok=True)
            except OSError as error:
                cleanup_errors.append(redact(str(error)))

        if preserve_resources:
            print(
                f"DEBUG: retained isolated project {project} and env file {environment_file}",
                flush=True,
            )
        elif completed and not cleanup_errors:
            print(
                f"PASS: isolated Compose project {project} and its ephemeral volume removed",
                flush=True,
            )

        if cleanup_errors:
            diagnostic = working / f"{project}-cleanup.log"
            diagnostic_written = True
            try:
                diagnostic.write_text("\n".join(cleanup_errors), encoding="utf-8")
            except OSError:
                diagnostic_written = False
                if primary_error is None:
                    raise RuntimeError("Isolated project cleanup failed")
            if primary_error is not None:
                detail = (
                    f"safe diagnostics are in {diagnostic}"
                    if diagnostic_written
                    else "safe cleanup diagnostics could not be written"
                )
                print(
                    f"WARNING: cleanup also failed; {detail}",
                    file=sys.stderr,
                    flush=True,
                )
            else:
                raise RuntimeError(
                    f"Isolated project cleanup failed; safe diagnostics are in {diagnostic}"
                )


if __name__ == "__main__":
    main()
