#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

bash backend/gradlew -p backend --no-daemon test bootJar
pnpm --dir frontend install --frozen-lockfile
for check in api:check typecheck lint test build; do
  pnpm --dir frontend run "$check"
done
uv sync --project intelligence --locked
uv run --project intelligence --locked ruff check intelligence
uv run --project intelligence --locked ruff format --check intelligence
uv run --project intelligence --locked pytest intelligence/tests

if [[ "${1:-}" == "--integration" ]]; then
  uv run --project intelligence --locked python scripts/integration-check.py
fi
