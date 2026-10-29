param([switch]$Integration)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\backend\gradlew.bat -p backend --no-daemon test bootJar
    if ($LASTEXITCODE -ne 0) { throw 'Backend checks failed' }
    & pnpm --dir frontend install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lockfile check failed' }
    foreach ($check in @('typecheck', 'lint', 'test', 'build')) {
        & pnpm --dir frontend run $check
        if ($LASTEXITCODE -ne 0) { throw "Frontend $check failed" }
    }
    & uv sync --project intelligence --locked
    if ($LASTEXITCODE -ne 0) { throw 'Python lockfile check failed' }
    & uv run --project intelligence --locked ruff check intelligence
    if ($LASTEXITCODE -ne 0) { throw 'Python lint failed' }
    & uv run --project intelligence --locked ruff format --check intelligence
    if ($LASTEXITCODE -ne 0) { throw 'Python formatting failed' }
    & uv run --project intelligence --locked pytest intelligence/tests
    if ($LASTEXITCODE -ne 0) { throw 'Python tests failed' }
    if ($Integration) {
        & uv run --project intelligence --locked python scripts/integration-check.py
        if ($LASTEXITCODE -ne 0) { throw 'Integration checks failed' }
    }
} finally {
    Pop-Location
}
