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
from urllib.request import Request, urlopen
from uuid import uuid4


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def write_private_environment_file(path, contents):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name != "nt":
        os.chmod(path.parent, 0o700)

    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as environment:
            descriptor = None
            if os.name == "nt":
                identity = subprocess.run(
                    ["whoami"],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    timeout=10,
                )
                principal = identity.stdout.strip()
                if identity.returncode or not principal:
                    raise PermissionError(
                        "Could not resolve the current Windows ACL principal"
                    )
                acl = subprocess.run(
                    [
                        "icacls",
                        str(path),
                        "/inheritance:r",
                        "/grant:r",
                        f"{principal}:(F)",
                    ],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    timeout=10,
                )
                if acl.returncode:
                    raise PermissionError(
                        "Could not restrict the integration env file ACL"
                    )
            else:
                os.chmod(path, 0o600)
                require(
                    path.stat().st_mode & 0o777 == 0o600
                    and path.parent.stat().st_mode & 0o077 == 0,
                    "Integration env file or .tools directory is not private",
                )
            environment.write(contents)
            environment.flush()
            os.fsync(environment.fileno())
    except BaseException as error:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError as cleanup_error:
                error.add_note(f"Could not close the integration env file: {cleanup_error}")
        try:
            path.unlink(missing_ok=True)
        except OSError as cleanup_error:
            error.add_note(
                f"Could not remove the partial integration env file {path}: {cleanup_error}"
            )
        raise


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
    write_private_environment_file(
        environment_file,
        f"POSTGRES_ADMIN_PASSWORD={postgres_admin_password}\n"
        f"DATABASE_PASSWORD={database_password}\nWEB_PORT={arguments.port}\n",
    )
    compose = ["docker", "compose", "--env-file", str(environment_file), "-p", project]
    base_url = f"http://127.0.0.1:{arguments.port}"
    sensitive_values = (postgres_admin_password, database_password)

    def redact(text):
        for secret in sensitive_values:
            text = text.replace(secret, "[REDACTED]")
        return text

    def save_diagnostic(contents):
        diagnostic = working / f"{project}-failure.log"
        try:
            write_private_environment_file(diagnostic, redact(contents))
        except Exception:
            return None
        return diagnostic

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
            diagnostic = save_diagnostic(result.stdout + result.stderr)
            detail = (
                f"diagnostic log is in {diagnostic}"
                if diagnostic
                else "diagnostic could not be written"
            )
            raise RuntimeError(
                f"Infrastructure check command failed; {detail}"
            )
        if failure_expected and result.returncode == 0:
            diagnostic = save_diagnostic(result.stdout + result.stderr)
            detail = (
                f"diagnostic log is in {diagnostic}"
                if diagnostic
                else "diagnostic could not be written"
            )
            raise AssertionError(
                f"A command expected to fail unexpectedly succeeded; {detail}"
            )
        return result

    def run(*command, **options):
        return execute(compose + list(command), **options)

    scenario_projects = []

    def new_scenario(label, server_port=None):
        scenario_project = f"{project}-{label}"
        scenario_environment_file = working / f"{scenario_project}.env"
        environment = (
            f"POSTGRES_ADMIN_PASSWORD={postgres_admin_password}\n"
            f"DATABASE_PASSWORD={database_password}\n"
            f"WEB_PORT={arguments.port}\n"
        )
        if server_port is not None:
            environment += f"SERVER_PORT={server_port}\n"
        write_private_environment_file(scenario_environment_file, environment)
        scenario = {
            "project": scenario_project,
            "environment_file": scenario_environment_file,
            "compose": [
                "docker",
                "compose",
                "--env-file",
                str(scenario_environment_file),
                "-p",
                scenario_project,
            ],
            "base_url": base_url,
        }
        scenario_projects.append(scenario)
        return scenario

    def run_scenario(scenario, *command, **options):
        return execute(scenario["compose"] + list(command), **options)

    def cleanup_scenario(scenario):
        run_scenario(scenario, "down", "--volumes", "--remove-orphans", timeout=60)
        scenario["environment_file"].unlink(missing_ok=True)
        scenario_projects.remove(scenario)

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

    def kafka_container_address():
        identifiers = run("ps", "--quiet", "kafka").stdout.splitlines()
        require(len(identifiers) == 1, "Running Kafka container could not be identified")
        inspection = json.loads(
            execute(["docker", "inspect", identifiers[0]], timeout=10).stdout
        )[0]
        network_name = configuration["networks"]["internal"]["name"]
        network = inspection["NetworkSettings"]["Networks"].get(network_name, {})
        address = network.get("IPAddress")
        require(address, "Kafka container has no address on the integration network")
        return f"{address}:9092"

    def require_broker_stopped():
        identifiers = run("ps", "--all", "--quiet", "kafka").stdout.splitlines()
        require(len(identifiers) == 1, "Stopped Kafka container could not be identified")
        state = execute(
            ["docker", "inspect", "--format", "{{.State.Status}}", identifiers[0]],
            timeout=10,
        ).stdout.strip()
        require(state == "exited", f"Kafka container should be stopped, observed state={state!r}")

    def require_kafka_unavailable_failure(result):
        output = result.stdout + result.stderr
        broker_failure = (
            "org.apache.kafka.common.errors.TimeoutException" in output
            or "Broker may not be available" in output
        )
        network_failure = any(
            marker in output
            for marker in (
                "UnknownHostException",
                "Name or service not known",
                "NoRouteToHostException",
                "Network is unreachable",
            )
        )
        if result.returncode == 1 and broker_failure and not network_failure:
            return
        diagnostic = save_diagnostic(output)
        detail = (
            f"diagnostic log is in {diagnostic}"
            if diagnostic
            else "diagnostic could not be written"
        )
        raise AssertionError(
            f"Kafka CLI did not report broker unavailability with exit code 1; {detail}"
        )

    def backend_process(compose_command=None):
        command = compose if compose_command is None else compose_command
        identifier = execute(command + ["ps", "--quiet", "backend"]).stdout.strip()
        require(identifier, "Backend container is not running")
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

    def request_at(url, path, headers=None):
        request = Request(url + path, headers=headers or {})
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, response.read(), response.headers
        except HTTPError as error:
            return error.code, error.read(), error.headers
        except (URLError, TimeoutError):
            return 0, b"", {}

    def wait_for(path, expected, seconds=45):
        return wait_for_at(base_url, path, expected, seconds)

    def wait_for_at(url, path, expected, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            actual, body = status_at(url, path)
            if actual == expected:
                return body
            time.sleep(0.25)
        raise AssertionError(f"{path} did not report HTTP {expected} within {seconds}s")

    def verify_backend_structured_logs(container):
        valid_id = "integration-valid-01"
        valid_status, _, valid_headers = request_at(
            base_url,
            "/api/health/readiness",
            {"X-Correlation-ID": valid_id},
        )
        require(
            valid_status == 200 and valid_headers.get("X-Correlation-ID") == valid_id,
            "Backend did not preserve a valid X-Correlation-ID",
        )

        invalid_id = "invalid correlation test secret"
        invalid_status, _, invalid_headers = request_at(
            base_url,
            "/api/health/readiness",
            {"X-Correlation-ID": invalid_id},
        )
        generated_id = invalid_headers.get("X-Correlation-ID")
        require(
            invalid_status == 400
            and bool(re.fullmatch(r"[a-f0-9-]{36}", generated_id or "")),
            "Backend did not reject an unsafe X-Correlation-ID and return a safe generated ID",
        )

        result = execute(["docker", "logs", container], timeout=15)
        output = result.stdout + result.stderr
        require(
            all(secret not in output for secret in (*sensitive_values, invalid_id)),
            "Backend logs exposed a database or invalid-header test secret",
        )

        records = []
        jvm_diagnostics = []
        jvm_prefixes = ("OpenJDK ", "Java HotSpot(TM) ", "WARNING: ", "Picked up ")
        for line in output.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                if line.startswith(jvm_prefixes):
                    jvm_diagnostics.append(line)
                    continue
                raise AssertionError(
                    f"Backend emitted a non-JSON application log line: {line[:160]}"
                ) from error
            require(isinstance(record, dict), "Backend log record is not a JSON object")
            require(
                all(
                    record.get(field)
                    for field in ("@timestamp", "level", "logger_name", "message")
                ),
                "Backend JSON log is missing timestamp, severity, logger or message",
            )
            records.append(record)

        require(records, "Backend emitted no parseable Spring/SLF4J console records")
        rejected_events = [
            record
            for record in records
            if record["message"] == "Rejected request with invalid or duplicate X-Correlation-ID"
        ]
        require(
            any(
                record.get("correlationId") == generated_id
                and record.get("logger_name") == "com.stockrescue.platform.http.CorrelationIdFilter"
                and record.get("level") == "WARN"
                for record in rejected_events
            ),
            "Invalid-header application log was not JSON-correlated with its safe response ID",
        )
        sample = next(
            record
            for record in rejected_events
            if record.get("correlationId") == generated_id
        )
        print(
            "SAMPLE_JSON_LOG: " + json.dumps(sample, ensure_ascii=False, separators=(",", ":")),
            flush=True,
        )
        print(
            "PASS: backend Spring/SLF4J logs are JSON with safe MDC correlation; "
            f"non-JSON JVM diagnostics kept separate={len(jvm_diagnostics)}",
            flush=True,
        )

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

    def browser_cold_start(url, expectation):
        environment = {
            **os.environ,
            "BASE_URL": url,
            "EXPECT_COLD_START": expectation,
        }
        scenario_test = (
            "cold-start with a missing optional integration"
            if expectation == "ready"
            else "cold-start without a backend"
        )
        subprocess.run(
            [
                "pnpm",
                "--dir",
                "frontend",
                "exec",
                "playwright",
                "test",
                "--grep",
                scenario_test,
            ],
            check=True,
            env=environment,
            timeout=120,
        )

    def verify_optional_dependency_cold_start(
        label, available_service, server_port=None, browser=False
    ):
        scenario = new_scenario(label, server_port)
        scenario_configuration = json.loads(
            run_scenario(scenario, "config", "--format", "json").stdout
        )
        services = scenario_configuration["services"]
        missing_service = "kafka" if available_service == "intelligence" else "intelligence"
        require(
            not run_scenario(scenario, "ps", "--quiet", "backend").stdout.strip(),
            f"{label} is not a fresh backend project",
        )
        run_scenario(
            scenario,
            "up",
            "--build",
            "-d",
            "--wait",
            "--wait-timeout",
            "180",
            "postgres",
            available_service,
            timeout=240,
        )
        require(
            bool(run_scenario(scenario, "ps", "--quiet", available_service).stdout.strip()),
            f"The supporting {available_service} service did not start",
        )
        require(
            not run_scenario(scenario, "ps", "--quiet", missing_service).stdout.strip(),
            f"The optional {missing_service} service started before the cold-start check",
        )
        require(
            not run_scenario(scenario, "ps", "--quiet", "backend").stdout.strip(),
            f"{label} started the backend before the cold-start check",
        )
        run_scenario(
            scenario,
            "up",
            "--build",
            "-d",
            "--wait",
            "--wait-timeout",
            "180",
            "backend",
            "web",
            timeout=300,
        )

        backend_port = services["backend"]["environment"]["SERVER_PORT"]
        proxy_port = services["web"]["environment"]["BACKEND_PORT"]
        require(
            str(backend_port) == str(proxy_port),
            "Backend SERVER_PORT and web BACKEND_PORT do not share one effective value",
        )
        if server_port is not None:
            require(
                str(backend_port) == str(server_port),
                "Compose did not apply the requested backend port override",
            )

        readiness_status, readiness_body = status_at(scenario["base_url"], "/api/health/readiness")
        liveness_status, _ = status_at(scenario["base_url"], "/api/health/liveness")
        integration_status, integration_body = status_at(
            scenario["base_url"], "/api/health/integration"
        )
        require(
            readiness_status == 200 and json.loads(readiness_body)["status"] == "UP",
            f"{label} did not keep database readiness healthy",
        )
        require(liveness_status == 200, f"{label} did not keep liveness healthy")
        require(
            integration_status == 503 and json.loads(integration_body)["status"] == "DOWN",
            f"{label} did not report the missing optional integration as degraded",
        )
        require(
            status_at(scenario["base_url"], "/")[0] == 200,
            f"{label} web shell did not start",
        )

        backend_pid = backend_process(scenario["compose"])
        if browser:
            browser_cold_start(scenario["base_url"], "ready")

        run_scenario(
            scenario,
            "up",
            "--build",
            "-d",
            "--wait",
            "--wait-timeout",
            "180",
            missing_service,
            timeout=240,
        )
        wait_for_at(scenario["base_url"], "/api/health/integration", 200)
        require(
            backend_process(scenario["compose"]) == backend_pid,
            f"Starting {missing_service} restarted the backend instead of recovering health live",
        )
        if server_port is not None:
            for invalid_port in ("0", "65536", "not-a-port"):
                invalid_healthcheck = execute(
                    [
                        "docker",
                        "run",
                        "--rm",
                        "--env",
                        f"SERVER_PORT={invalid_port}",
                        "--entrypoint",
                        "java",
                        services["backend"]["image"],
                        "-cp",
                        "/app/healthcheck",
                        "Healthcheck",
                    ],
                    failure_expected=True,
                    timeout=30,
                )
                require(
                    "SERVER_PORT must be"
                    in invalid_healthcheck.stdout + invalid_healthcheck.stderr,
                    f"Healthcheck accepted invalid SERVER_PORT={invalid_port}",
                )
        cleanup_scenario(scenario)
        print(
            f"PASS: cold start without {missing_service}; readiness, degraded integration "
            "and live recovery",
            flush=True,
        )

    def verify_web_cold_start_without_backend(browser=False):
        scenario = new_scenario("cold-web-only")
        require(
            not run_scenario(scenario, "ps", "--quiet", "backend").stdout.strip(),
            "Web-only scenario is not a fresh project",
        )
        run_scenario(
            scenario,
            "up",
            "--build",
            "-d",
            "--wait",
            "--wait-timeout",
            "120",
            "web",
            timeout=240,
        )
        require(
            wait_for_at(scenario["base_url"], "/healthz", 204) == b"",
            "Web health endpoint failed without a backend container",
        )
        page_status, page = status_at(scenario["base_url"], "/")
        require(
            page_status == 200 and b"StockRescue" in page,
            "Web shell failed without a backend",
        )
        api_status, _ = status_at(scenario["base_url"], "/api/health/readiness")
        require(
            api_status == 502,
            f"Missing backend returned unexpected readiness status {api_status}",
        )
        require(
            not run_scenario(scenario, "ps", "--quiet", "backend").stdout.strip(),
            "Web-only scenario unexpectedly started a backend",
        )
        if browser:
            browser_cold_start(scenario["base_url"], "unavailable")
        cleanup_scenario(scenario)
        print("PASS: web cold start without backend DNS; UI reports unavailable", flush=True)

    smoke_topic = f"stockrescue.infrastructure.smoke.{uuid4().hex}"
    smoke_offset = 0

    def smoke(failure_expected=False, broker_address="kafka:9092"):
        nonlocal smoke_offset
        backend_pid = backend_process()
        correlation = uuid4().hex
        record = f"correlation-id:{correlation}\t{correlation}\tstockrescue-infrastructure-check"
        produced = kafka_cli(
            "kafka-console-producer",
            "--bootstrap-server",
            broker_address,
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
        if failure_expected:
            return produced
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
        backend_dependencies = configuration["services"]["backend"].get("depends_on", {})
        require(
            set(backend_dependencies) == {"postgres"}
            and backend_dependencies["postgres"]["condition"] == "service_healthy",
            "Backend must wait only for healthy PostgreSQL before startup",
        )
        require(
            not configuration["services"]["web"].get("depends_on"),
            "Web startup must not depend on backend DNS or health",
        )
        require(
            str(configuration["services"]["backend"]["environment"]["SERVER_PORT"])
            == str(configuration["services"]["web"]["environment"]["BACKEND_PORT"]),
            "Default backend and proxy ports do not share one Compose value",
        )
        print(
            "PASS: private ports, loopback proxy and Python credential boundary", flush=True
        )

        verify_optional_dependency_cold_start(
            "cold-kafka", "intelligence", server_port=18081, browser=arguments.browser
        )
        verify_optional_dependency_cold_start("cold-python", "kafka")
        verify_web_cold_start_without_backend(browser=arguments.browser)

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
        verify_backend_structured_logs(run("ps", "--quiet", "backend").stdout.strip())
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
            "PASS: app images run outside Compose with explicit service addresses, "
            "read-only roots, "
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
                broker_address = kafka_container_address() if service == "kafka" else "kafka:9092"
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
                    require_broker_stopped()
                    require_kafka_unavailable_failure(
                        smoke(failure_expected=True, broker_address=broker_address)
                    )
            except Exception as error:
                outage_error = error
            try:
                run("start", service)
                wait_for("/api/health/readiness", 200)
                wait_for("/api/health/integration", 200)
            except Exception:
                if outage_error is None:
                    raise
                outage_error.add_note(
                    f"Recovery also failed for {service}; see the isolated failure diagnostics."
                )
            if outage_error is not None:
                raise outage_error
            if service == "kafka":
                smoke()
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
                [
                    "pnpm",
                    "--dir",
                    "frontend",
                    "exec",
                    "playwright",
                    "test",
                    "--grep-invert",
                    "cold-start",
                ],
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
            for scenario in reversed(scenario_projects):
                try:
                    result = subprocess.run(
                        scenario["compose"] + ["down", "--volumes", "--remove-orphans"],
                        check=False,
                        capture_output=True,
                        text=True,
                        encoding="utf-8",
                        errors="replace",
                        timeout=60,
                    )
                    if result.returncode:
                        cleanup_errors.append(redact(result.stderr or result.stdout))
                    else:
                        scenario["environment_file"].unlink(missing_ok=True)
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
            for scenario in scenario_projects:
                print(
                    "DEBUG: retained isolated project "
                    f"{scenario['project']} and env file {scenario['environment_file']}",
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
                write_private_environment_file(diagnostic, "\n".join(cleanup_errors))
            except Exception as error:
                diagnostic_written = False
                if primary_error is None:
                    raise RuntimeError(
                        "Isolated project cleanup failed and its diagnostics could not be written"
                    ) from error
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
