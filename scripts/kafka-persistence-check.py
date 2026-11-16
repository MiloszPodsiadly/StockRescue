"""Verify Kafka log persistence by recreating a uniquely isolated Compose broker."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
PROJECT = f"stockrescue-kafka-check-{uuid4().hex[:12]}"
ENV_FILE = ROOT / ".env.example"
LOG_DIRECTORY = "/var/lib/kafka/data"


def docker(*arguments: str, input_text: str | None = None, timeout: int = 30) -> str:
    result = subprocess.run(
        ["docker", *arguments],
        cwd=ROOT,
        input=input_text,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"Docker command failed ({result.returncode}): {detail}")
    return result.stdout


def compose(*arguments: str, timeout: int = 60) -> str:
    return docker(
        "compose",
        "--project-name",
        PROJECT,
        "--env-file",
        str(ENV_FILE),
        *arguments,
        timeout=timeout,
    )


def container_id() -> str:
    identifier = compose("ps", "--quiet", "kafka").strip()
    if not identifier:
        raise RuntimeError("The isolated Kafka container is not running")
    return identifier


def inspect_volume(identifier: str, expected_volume: str) -> None:
    runtime = json.loads(docker("inspect", identifier))[0]
    data_mounts = [mount for mount in runtime["Mounts"] if mount["Destination"] == LOG_DIRECTORY]
    if len(data_mounts) != 1:
        raise RuntimeError(f"Kafka must have one mount at {LOG_DIRECTORY}")
    mount = data_mounts[0]
    if mount["Type"] != "volume" or mount["Name"] != expected_volume:
        raise RuntimeError("Kafka log directory is not backed by the isolated named volume")
    if runtime["Config"].get("Env", []).count(f"KAFKA_LOG_DIRS={LOG_DIRECTORY}") != 1:
        raise RuntimeError("Kafka runtime log directory does not match its named volume")
    properties = docker(
        "exec",
        identifier,
        "/bin/sh",
        "-c",
        "grep -E '^(log.dirs|log.retention.hours)=' /opt/kafka/config/server.properties",
    )
    values = dict(line.split("=", 1) for line in properties.splitlines() if "=" in line)
    if values.get("log.dirs") != LOG_DIRECTORY or values.get("log.retention.hours") != "168":
        raise RuntimeError("Effective Kafka log directory or finite seven-day retention is incorrect")


def main() -> None:
    if not ENV_FILE.is_file():
        raise RuntimeError("The repository .env.example file is required")

    config = json.loads(compose("config", "--format", "json"))
    service = config["services"]["kafka"]
    expected_volume = config["volumes"]["kafka_dev_data"]["name"]
    if service["environment"].get("KAFKA_LOG_DIRS") != LOG_DIRECTORY:
        raise RuntimeError("Compose Kafka log directory does not match the inspected broker path")
    if service["environment"].get("KAFKA_LOG_RETENTION_HOURS") != "168":
        raise RuntimeError("Compose Kafka retention must remain finite at seven days")
    if not any(volume.get("source") == "kafka_dev_data" and volume.get("target") == LOG_DIRECTORY
               for volume in service.get("volumes", [])):
        raise RuntimeError("Compose does not mount kafka_dev_data at the broker log directory")

    try:
        compose("up", "-d", "--wait", "--wait-timeout", "120", "kafka", timeout=150)
        before = container_id()
        inspect_volume(before, expected_volume)

        topic = f"stockrescue-persistence-{uuid4().hex}"
        record = f"durable-record-{uuid4().hex}"
        docker("exec", before, "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092",
               "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1")
        docker("exec", "-i", before, "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server",
               "localhost:9092", "--topic", topic, input_text=record + "\n")

        compose("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "120", "kafka", timeout=150)
        after = container_id()
        if before == after:
            raise RuntimeError("Compose did not replace the isolated Kafka container")
        inspect_volume(after, expected_volume)
        consumed = docker("exec", after, "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server",
                          "localhost:9092", "--topic", topic, "--from-beginning", "--max-messages", "1",
                          "--timeout-ms", "15000", timeout=25)
        if record not in consumed.splitlines():
            raise RuntimeError("The uniquely identified Kafka record did not survive broker recreation")

        running = compose("ps", "--services", "--status", "running").splitlines()
        if running != ["kafka"]:
            raise RuntimeError("The isolated durability check started a non-Kafka service")
        print("PASS: uniquely identified Kafka record survived isolated container recreation on the named volume")
    finally:
        compose("down", "--volumes", "--remove-orphans", timeout=60)
        print("PASS: isolated Kafka Compose project and test volume removed")


if __name__ == "__main__":
    main()
