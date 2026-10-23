# Contributing to StockRescue AI

Thank you for considering a contribution! StockRescue AI is in an early planning stage. Ideas, documentation improvements, tests, and future implementation contributions are welcome.


## Before you begin

1. Read the [README](README.md) and [roadmap](ROADMAP.md).
2. Check open issues and pull requests to avoid duplicating work.
3. For a substantial feature, breaking change, or architecture decision, open an issue first to agree on scope.
4. Keep changes focused; avoid unrelated refactors and speculative abstractions.
5. Never include secrets, access tokens, real retailer/customer records, or confidential material in issues, examples, or pull requests.

## Set up a development environment

There is **no runnable application or finalized build toolchain yet**. Environment requirements and verified commands will be documented alongside the first working modules. Until then, documentation-only changes can be contributed without a running application.

When build tooling is introduced, the relevant setup and validation commands will be specified in the module documentation and CI workflows. Do not invent success claims for checks that cannot yet run.

## Contribution workflow

1. Fork the repository or create a feature branch if you have write access.
2. Branch from the current default branch, presently `master`.
5. Add or update relevant documentation and tests when the affected components exist.
6. Open a pull request using the repository template. Link the relevant issue when there is one.
7. Address review comments and keep the change reasonably easy to review.

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
