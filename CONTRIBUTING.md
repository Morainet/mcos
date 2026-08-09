# Contributing to MCOS

Thanks for helping build **Mobile Command OS** — a command bus for mobile capabilities.

## Principles

1. **Protocol first** — prefer Command Protocol / RFC changes over one-off UI hacks.
2. **Specs before skins** — Runtime and DSL before prettier chat.
3. **Safety by default** — permissions, confirmations, and audit are not optional polish.
4. **Small vertical slices** — every PR should leave something demoable.

Read [`docs/en/README.md`](./docs/en/README.md) before large design changes. (中文版：[`docs/zh/README.md`](./docs/zh/README.md))

> **Keeping docs in sync:** MCOS maintains a full bilingual doc set. When you change an English doc under `docs/en/`, please also update its Chinese counterpart under `docs/zh/` (or flag the gap in your PR).

## Repo layout

> The repository is currently **design-only** — no source modules or build system exist yet. The target module topology is documented in [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md).

| Path | Role |
|------|------|
| `docs/en/` | Vision, architecture, RFCs (English — authoritative) |
| `docs/zh/` | 同上，中文译本（Chinese mirror） |
| `docs/fixtures/` | Golden DSL ↔ IR fixtures (shared by both languages) |
| `doc/` | Early Chinese brainstorm notes |

Target modules (to be created in Phase 1): `mcos-android/`, `mcos-runtime/`, `mcos-sdk/`, `plugins/*`, and later `mcos-server/`.

## Protocol & fixtures

Command Protocol changes must:

1. Update [`docs/en/02-command-protocol.md`](./docs/en/02-command-protocol.md) **and** [`docs/zh/02-command-protocol.md`](./docs/zh/02-command-protocol.md) (bump version when normative).
2. Add or update golden cases under `docs/fixtures/`.
3. Ensure any future parser implementation stays green against those fixtures.

Fixture layout:

```text
docs/fixtures/<case-id>/
  input.dsl
  expected.ir.json        # positive cases
  expected.error.json     # negative cases
```

## Pull requests

- Prefer focused PRs (one concern).
- Include a short **why** in the description.
- For Runtime / protocol: link the RFC section and fixture IDs.
- Do not commit secrets (API keys, signing keystores, `.env`).

## Code style

(Applies once implementation begins.)

- Kotlin official style; match neighboring modules.
- Public SDK APIs need KDoc.
- No nested command calls in DSL v0.1 — use Workflow IR.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](./LICENSE).
