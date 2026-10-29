import json
from pathlib import Path

import jsonschema
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from stockrescue_intelligence.app import ServiceHealth, create_app

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def test_health_matches_shared_contract_and_does_not_advertise_business_features():
    fixture = json.loads((CONTRACTS / "fixtures/intelligence-health-v1.json").read_text())
    schema = json.loads((CONTRACTS / "intelligence-health-v1.schema.json").read_text())
    assert ServiceHealth.model_json_schema() == schema
    with TestClient(create_app()) as client:
        for route in ("/health/live", "/health/ready"):
            response = client.get(route)
            assert response.status_code == 200
            assert response.json() == fixture
            jsonschema.validate(response.json(), schema)
        for route in ("/predict", "/inventory", "/docs", "/openapi.json"):
            assert client.get(route).status_code == 404
        assert client.post("/health/ready", json={"status": "UP"}).status_code == 405


def test_incomplete_startup_is_not_ready_but_process_is_live():
    client = TestClient(create_app())
    assert client.get("/health/live").status_code == 200
    assert client.get("/health/ready").status_code == 503


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"contractVersion": "2", "service": "stockrescue-intelligence", "status": "UP"},
        {"contractVersion": "1", "service": "other-service", "status": "UP"},
        {"contractVersion": "1", "service": "stockrescue-intelligence", "status": "UP", "extra": 1},
    ],
)
def test_invalid_technical_envelopes_are_rejected(payload):
    with pytest.raises(ValidationError):
        ServiceHealth.model_validate(payload)


def test_invalid_configuration_prevents_startup_without_reflecting_input(monkeypatch):
    monkeypatch.setenv("STOCKRESCUE_INTELLIGENCE_PORT", "invalid-private-value")
    with pytest.raises(ValidationError) as failure, TestClient(create_app()):
        pass
    assert "invalid-private-value" not in str(failure.value)
