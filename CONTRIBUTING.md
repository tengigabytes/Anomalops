# Contributing to Anomalops

Thanks for your interest. The project is in its design phase; please open an issue before starting work so
it can be matched to a milestone in [docs/product/roadmap.md](docs/product/roadmap.md).

## Languages

- Issues and pull request discussions: Traditional Chinese (Taiwan) or English.
- Code, comments, KDoc, log messages, test names and commit messages: English.
- Project documentation under `docs/` is written in Traditional Chinese; English translations use the
  `.en.md` suffix.

## Rules that CI enforces

The limits check runs on every push and pull request now; the Android build, lint and test checks
start once the Gradle project exists (milestone M0).

- `python scripts/check_limits.py` must pass: Kotlin files ≤ 300 lines, tests ≤ 400, documents ≤ 300 lines
  or 20 KB, every `docs/` directory indexed by its `README.md`, no broken links.
- `./gradlew detekt` (detekt with its ktlint wrapper) enforces functions ≤ 60 lines, classes ≤ 250 lines and
  lines ≤ 120 characters; `./gradlew detekt --auto-correct` fixes formatting.
- `python scripts/check_module_deps.py` enforces the module dependency rules of ADR-0007.
- Both `play` and `foss` build flavors must build and pass lint and unit tests.
- Every source file starts with SPDX headers:

  ```kotlin
  // SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

## Commits

- [Conventional Commits](https://www.conventionalcommits.org/), e.g.
  `feat(camera): apply depth-band WB gains (FR-21)`.
- Reference requirement and ADR IDs (FR-xx, NFR-x, ADR-xxxx) in the commit body.
- Sign off every commit (`git commit -s`) to certify the
  [Developer Certificate of Origin](https://developercertificate.org/). Your contribution is licensed under
  GPL-3.0-or-later together with the additional terms in [NOTICE.md](NOTICE.md).

## AI-assisted contributions

This project is itself built with Claude Code: the maintainer writes the requirements, makes the decisions
and reviews every change, and Claude writes the code. Contributions made with AI tools are welcome under the
same rules:

- State in the pull request which parts were AI-generated and which tool you used.
- You are responsible for the change: review it yourself; your `Signed-off-by` certifies the DCO for it.
- Add a `Co-Authored-By` trailer for the AI tool when it wrote substantial parts.

## Scope

The v1.0 scope is frozen ([docs/product/mvp-scope.md](docs/product/mvp-scope.md)). Architectural changes need
an ADR in [docs/adr/](docs/adr/README.md) first.

The full development rules (in Traditional Chinese) are in [docs/dev/](docs/dev/README.md).
