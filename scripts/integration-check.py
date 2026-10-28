"""Run non-destructive checks against a fresh, isolated Compose project."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import time
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
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    project = f"stockrescue-check-{uuid4().hex[:8]}"
    working = root / ".tools"
    working.mkdir(exist_ok=True)
    environment_file = working / f"{project}.env"
    environment_file.write_text(
        f"POSTGRES_ADMIN_PASSWORD={uuid4().hex}\nDATABASE_PASSWORD={uuid4().hex}\n"
        f"WEB_PORT={arguments.port}\n",
        encoding="utf-8",
    )
    compose = ["docker", "compose", "--env-file", str(environment_file), "-p", project]
    base_url = f"http://127.0.0.1:{arguments.port}"

    def run(*command, failure_expected=False, timeout=240):
        result = subprocess.run(compose + list(command), capture_output=True, text=True, timeout=timeout)
        if not failure_expected and result.returncode:
            (working / f"{project}-failure.log").write_text(result.stdout + result.stderr, encoding="utf-8")
            raise RuntimeError(f"Compose {' '.join(command[:3])} failed; diagnostic log is in .tools/")
        if failure_expected and result.returncode == 0:
            raise AssertionError("The unavailable-broker smoke incorrectly succeeded")
        return result

    def status(path):
        try:
            with urlopen(base_url + path, timeout=10) as response:
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

    def smoke(failure_expected=False):
        result = run(
            "exec", "-T", "backend", "java", "-jar", "/app/stockrescue.jar",
            "--spring.main.web-application-type=none", "--stockrescue.kafka.smoke=true",
            failure_expected=failure_expected, timeout=45,
        )
        if not failure_expected and "Kafka infrastructure smoke consumed its record" not in result.stdout:
            raise AssertionError("No actual consumer receipt was observed")

    configuration = json.loads(run("config", "--format", "json").stdout)
    for name, service in configuration["services"].items():
        ports = service.get("ports", [])
        if name == "web":
            require(len(ports) == 1 and ports[0]["host_ip"] == "127.0.0.1", "Proxy is not loopback-only")
        else:
            require(not ports, f"Private service {name} publishes a host port")
    require(configuration["networks"]["internal"]["internal"] is True, "Private network is not internal")
    require(not any("DATABASE" in name or "POSTGRES" in name
                    for name in configuration["services"]["intelligence"].get("environment", {})),
            "Python receives operational DB credentials")
    print("PASS: private ports, loopback proxy and Python credential boundary", flush=True)

    try:
        run("up", "--no-build", "-d", "--wait", "--wait-timeout", "180")
        require(json.loads(wait_for("/api/health/readiness", 200))["status"] == "UP", "Backend is not ready")
        require(json.loads(wait_for("/api/health/integration", 200))["status"] == "UP", "Integration is not healthy")
        require(b"StockRescue" in wait_for("/", 200), "Frontend was not served")
        for service in ("web", "backend", "intelligence"):
            identifier = run("ps", "--quiet", service).stdout.strip()
            runtime = json.loads(subprocess.check_output(["docker", "inspect", identifier], text=True))[0]
            require(runtime["Config"]["User"].split(":")[0] not in ("", "0", "root"), f"{service} runs as root")
            if service == "intelligence":
                require(not any(item.startswith(("DATABASE_", "POSTGRES_"))
                                for item in runtime["Config"].get("Env", [])), "Python image contains DB credentials")
        smoke()
        print("PASS: proxy, Flyway/PG readiness, real Java-to-Python request and Kafka consumer receipt", flush=True)

        for service in ("intelligence", "kafka", "postgres"):
            try:
                run("stop", service)
                path = "/api/health/readiness" if service == "postgres" else "/api/health/integration"
                wait_for(path, 503)
                require(status("/api/health/liveness")[0] == 200, "Dependency outage breaks process liveness")
                if service != "postgres":
                    require(status("/api/health/readiness")[0] == 200, "Optional dependency blocks DB readiness")
                if service == "kafka":
                    smoke(failure_expected=True)
            finally:
                run("start", service)
                wait_for("/api/health/readiness", 200)
                wait_for("/api/health/integration", 200)
            print(f"PASS: {service} outage is visible; liveness and recovery verified", flush=True)

        run("restart", "backend")
        wait_for("/api/health/readiness", 200)
        wait_for("/api/health/integration", 200)
        version = run("exec", "-T", "postgres", "psql", "-U", "stockrescue_admin", "-d", "stockrescue",
                      "-Atc", "SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL'").stdout.strip()
        require(version == "1", "Migration history changed across non-destructive restart")
        privileges = run("exec", "-T", "postgres", "psql", "-U", "stockrescue_admin", "-d", "stockrescue",
                         "-Atc", "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname='stockrescue_app'").stdout.strip()
        require(privileges == "f", "Application role has administrative privileges")
        smoke()
        if arguments.browser:
            subprocess.run(
                ["pnpm", "--dir", "frontend", "run", "test:e2e"], check=True,
                env={**os.environ, "BASE_URL": base_url}, timeout=120,
            )
        print("PASS: integrated foundation checks", flush=True)
    finally:
        run("down", timeout=60)
        print(f"Preserved isolated test volume: {project}_postgres_dev_data", flush=True)


if __name__ == "__main__":
    main()
