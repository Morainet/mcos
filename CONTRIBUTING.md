# Contributing to MCOS

Thanks for helping build **Mobile Command OS** — an open command bus for mobile capabilities, where AI generates commands and the Runtime owns security.

## Table of Contents

- [Ways to Contribute](#ways-to-contribute)
- [Principles](#principles)
- [Getting Started](#getting-started)
- [Repo Layout](#repo-layout)
- [Code Style & Standards](#code-style--standards)
- [Testing](#testing)
- [Command Protocol & Fixtures](#command-protocol--fixtures)
- [Commits & Pull Requests](#commits--pull-requests)
- [Bilingual Docs](#bilingual-docs)
- [Writing a Plugin](#writing-a-plugin)
- [License](#license)

## Ways to Contribute

- 🐛 **Report bugs** — open an [issue](https://github.com/Morainet/mcos/issues) with the command DSL, expected vs actual behavior, and log excerpts (redact secrets!).
- 💡 **Propose features** — protocol-level ideas beat UI mockups; frame them against the [Command Protocol RFC](./docs/en/02-command-protocol.md).
- 🔌 **Write a plugin** — plugins live in `plugins/`, depend only on `mcos-sdk`, and are the lowest-risk way to touch the system. See [Writing a Plugin](#writing-a-plugin).
- 📚 **Improve docs** — the RFC set is the product; precision there is as valuable as code.
- 🔧 **Fix something** — pick an issue, keep the slice small and demoable.

## Principles

1. **Protocol first** — prefer Command Protocol / RFC changes over one-off UI hacks.
2. **Specs before skins** — Runtime and DSL before prettier chat.
3. **Safety by default** — permissions, confirmations, and audit are not optional polish.
4. **Small vertical slices** — every PR should leave something demoable.

Read [`docs/en/README.md`](./docs/en/README.md) before large design changes; [`docs/en/11-implementation-status.md`](./docs/en/11-implementation-status.md) is the docs ↔ code map of what exists today.

## Getting Started

| Requirement | Version |
|:------------|:--------|
| JDK | 17 (Gradle toolchain) |
| Android SDK | Platform 35 (build-tools via AGP 8.7.3; `minSdk` is 26) |
| Kotlin | 2.0.21 (set by the build — no local install needed) |
| Gradle | 8.10 via the wrapper |

> `gradlew` in this repo has no execute bit — invoke it through the shell: `sh gradlew <task>`. (CI does `chmod +x` itself.)

Clone and run the full gate once before anything else:

```bash
git clone https://github.com/Morainet/mcos.git
cd mcos
sh gradlew build          # JVM + Android + all tests (~10-25 min cold)
```

Faster loops while you work:

```bash
sh gradlew test                                   # JVM module tests only
sh gradlew :mcos-runtime-core:test                # one module
sh gradlew :mcos-android-sdk:testDebugUnitTest    # Android library tests
sh gradlew :mcos-android:assembleDebug            # demo shell APK
```

## Repo Layout

| Path | Role |
|:-----|:-----|
| `mcos-sdk/` | Plugin contracts (`McosPlugin`, `CommandHandler`, `HostServices`) — the leaf every plugin depends on |
| `mcos-security/` | Permission kernel, AuthStamp signing, rate limits, egress policy, audit |
| `mcos-runtime-core/` | DSL parser → IR, command registry, 7-stage executor, workflow engine, memory |
| `mcos-llm/` | AI planner/chat, multi-provider registry, constrained decoding |
| `mcos-marketplace/` | Index client, install pipeline, recipe store, telemetry |
| `mcos-runtime/` | Facade (`McosRuntime`) wiring the subsystems together |
| `mcos-android-sdk/` | UI-free Android host SDK (composition root, receivers, bridges, isolation RPC) |
| `mcos-android/` | Compose demo shell — replaceable reference UI |
| `mcos-server/` | Self-hosted sync endpoint |
| `plugins/` | Independently buildable plugins (`hello`, `system`, `camera`, `files`, `mcp`, `iot`) |
| `docs/en/` · `docs/zh/` | Bilingual RFC set (English authoritative) |
| `docs/fixtures/` | Golden DSL ↔ IR fixtures (shared by both languages) |
| `.agents/skills/mcos-dev-standards/` | Coding standards (see below) |

Module dependency direction is fixed — see [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md) and the `PackageBoundariesTest` that enforces package → module mapping.

## Code Style & Standards

The authoritative rules live in [`.agents/skills/mcos-dev-standards/SKILL.md`](./.agents/skills/mcos-dev-standards/SKILL.md) — read it before your first Kotlin change. The essentials:

- Kotlin official style, 4-space indent, **120-column lines**.
- `kotlinx.serialization.json` extension properties (`jsonObject`, `jsonPrimitive`, `jsonArray`) must be **explicitly imported** — wildcard imports don't cover them.
- **Import before use**: no fully-qualified class names at call sites; alias imports when two short names collide.
- Objects that turn security off must be greppable: `Permissive*` / `AllowAll*` / `Trusting*` / `Noop*` prefixes; security exemptions must go through `SecurityConfig.permissive()` — never bare construction.
- Public SDK APIs need KDoc. Honest boundaries are a house style: if something is untested on the JVM or not yet implemented, say so in KDoc/docs rather than implying otherwise.
- No nested command calls in DSL v0.1 — use Workflow IR.

## Testing

- Tests mirror the source package structure (`src/test/kotlin/` ↔ `src/main/kotlin/`).
- The full gate is `sh gradlew build`; Android changes should also pass `:mcos-android-sdk:testDebugUnitTest :mcos-android:testDebugUnitTest :mcos-android:assembleDebug`.
- When your change adds or moves tests, update the **test baseline** (count + module breakdown) in the item entry of [`docs/en/11-implementation-status.md`](./docs/en/11-implementation-status.md) and its Chinese mirror — the baseline is re-measured from `build/test-results/**/TEST-*.xml` (`tests="…"` attributes).
- CI runs on every PR: module test shards, the Android build, and an **unsigned publish dry-run** so publication task-graph drift fails in review, not at tag time.

## Command Protocol & Fixtures

Command Protocol changes must:

1. Update [`docs/en/02-command-protocol.md`](./docs/en/02-command-protocol.md) **and** [`docs/zh/02-command-protocol.md`](./docs/zh/02-command-protocol.md) (bump version when normative).
2. Add or update golden cases under `docs/fixtures/`.
3. Keep the parser green against those fixtures.

Fixture layout:

```text
docs/fixtures/<case-id>/
  input.dsl
  expected.ir.json        # positive cases
  expected.error.json     # negative cases
```

## Commits & Pull Requests

Commits follow [Conventional Commits](https://www.conventionalcommits.org) with a module scope, `!` for breaking API changes:

```text
feat(marketplace): recipe dependency resolver suggests versions (09 §4, item 12)
fix(files): sandbox traversal rejects backslash paths (04 §7)
feat!: §6 full-signature alignment — NetService/SecureStore/Clock (04 §6, item 46)
```

Roadmap-sized work references the RFC section and the implementation-status item number — that's how the docs ↔ code map stays greppable.

**PR checklist:**

- [ ] One concern per PR, with a short **why** in the description.
- [ ] Runtime / protocol changes link the RFC section and fixture IDs they affect.
- [ ] English and Chinese docs updated together (or the gap flagged).
- [ ] `sh gradlew build` green locally.
- [ ] No secrets committed (API keys, signing keystores, `.env`) — release credentials live only in GitHub Secrets.

## Bilingual Docs

MCOS maintains a full bilingual doc set under `docs/en/` (authoritative) and `docs/zh/`. When you change an English doc, update its Chinese counterpart in the same PR — or explicitly flag the gap so it can be tracked.

## Writing a Plugin

Start with [`docs/en/04-plugin-sdk.md`](./docs/en/04-plugin-sdk.md) (manifest, handler contract, host services) and copy the shape of the smallest existing plugin, [`plugins/mcos-plugin-hello`](./plugins/mcos-plugin-hello). Plugins:

- depend only on `mcos-sdk` (enforced direction);
- declare permissions, network scopes, and side-effect classes in their manifest — the kernel does the enforcing, plugins stay honest;
- get registered in `PackageBoundariesTest` and the CI plugin shard when added.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](./LICENSE).
