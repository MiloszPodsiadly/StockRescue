# Contributing to StockRescue AI

StockRescue AI provides its technical foundation, account authentication, organization onboarding, audited access management and store workflows. Keep contributions focused and document only what helps someone run, change or review the application.

## Before you begin

1. Read the [README](README.md) and [architecture notes](documents/architecture.md).
2. Check open issues and pull requests to avoid duplicating work.
3. For a substantial feature, breaking change, or architecture decision, open an issue first to agree on scope.
4. Keep changes focused; avoid unrelated refactors and speculative abstractions.
5. Never include secrets, access tokens, real retailer/customer records, or confidential material in issues, examples, or pull requests.

## Set up a development environment

Use Oracle JDK 27.0.1.0.3+1, Node 26.13.0, pnpm 12.16.1, Python 3.15.2 and uv 0.13.6.
Gradle 9.8.2 is supplied by the committed wrapper. Docker must be running for
the backend's PostgreSQL and Kafka tests. The Compose quickstart in the README
requires only Docker; host development tools are needed for the checks below.

From the repository root, run `./scripts/dev-check.ps1` on Windows or
`bash scripts/dev-check.sh` on Linux/macOS. Add `-Integration` on Windows or
`--integration` on Linux/macOS to build the images and check the real Compose
services, dependency failures and recovery. These checks use a uniquely named
Compose project and remove only its containers and PostgreSQL test volume when
they finish. For failure debugging, run the integration checker with
`--keep-on-failure` to retain that project's resources.

For browser checks, install Chromium with
`pnpm --dir frontend exec playwright install chromium`, then run
`uv run --project intelligence --locked python scripts/integration-check.py --browser`
against the Compose images built by the checker. Prefer targeted checks for subsequent changes;
the commands for individual components are in the development scripts and CI.
The store browser scenario creates four stores, restarts only the generated
test PostgreSQL service and replaces its backend, then compares persisted data.
The checker supplies and validates that isolated project's name and environment path.
The operator journey provisions its accounts, organizations, stores, zones,
transport and reviewed staff access entirely through the UI. It also checks
revocation, organization switching, cross-tab logout and a different account's
subsequent login. Mobile, keyboard and form-label checks are accessibility smoke
coverage, not a complete accessibility audit.

For an intentional HTTP contract change, run
`./backend/gradlew -p backend generateOpenApi` and
`pnpm --dir frontend run api:generate`, then review both generated artifacts.
The normal backend tests and `pnpm --dir frontend run api:check` reject drift.
The OpenAPI generator workspace owns its supported TypeScript 5 API dependency;
the application continues to compile with TypeScript 7. API documentation endpoints
are enabled only by the explicit `contract` profile used by the generator test.

## Contribution workflow

1. Fork the repository or create a feature branch if you have write access.
2. Branch from the current default branch, presently `master`.
3. Add or update relevant documentation and tests when the affected components exist.
4. Open a pull request using the repository template. Link the relevant issue when there is one.
5. Address review comments and keep the change reasonably easy to review.

Coding agents work on the branch already selected by the owner. They must ask
for permission before committing or pushing; tests and non-destructive checks
can run without a separate approval. Preserve existing user changes.

Use the latest stable compatible upstream versions when introducing tooling,
then pin exact versions and commit lockfiles and wrapper artifacts. Installed
local versions do not determine the target. Report incompatibilities with
evidence rather than silently downgrading or selecting prereleases.

## Commit convention

Use clear, imperative commit subjects. [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) are preferred:

```text
feat: add batch expiration validation
fix: reject negative transfer quantities
test: cover concurrent stock reservations
docs: explain forecast evaluation approach
chore: add repository community templates
```

## Pull request checklist

Before requesting review, confirm that:

- The change has a clear goal and focused scope.
- Tests and docs were added or updated where applicable.

## Bug reports and feature requests

Use the [issue templates](https://github.com/MiloszPodsiadly/StockRescue/issues/new/choose). For bugs, provide reproducible steps, expected and actual behavior, versions, and *redacted* diagnostic information. For feature requests, describe the user problem and a concrete success criterion.

## Licensing contributions

The repository is distributed under the [Apache License, Version 2.0](LICENSE). Contributions intentionally submitted for inclusion are handled under Section 5 of that license unless explicitly stated otherwise or governed by a separate agreement. Submit only content you have the right to contribute, and identify any relevant third-party licenses.

## Reviews and conduct

Reviews should be respectful, specific, and evidence-based. Challenging a design is welcome; personal attacks are not. Maintainers may request revisions or decline changes that conflict with project scope or quality standards.
