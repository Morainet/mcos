# Contributing to MCOS

MCOS is an Android Agent and an embeddable secure command Runtime. Start with the [current architecture](./docs/en/13-architecture-convergence.md) to find the owner of the area you are changing.

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
- 💡 **Propose features** — identify the user task and the affected layer. Assess protocol changes against the [Command Protocol RFC](./docs/en/02-command-protocol.md).
- 🔌 **Write a plugin** — plugins live in `plugins/`, depend only on `mcos-sdk`, and are the lowest-risk way to touch the system. See [Writing a Plugin](#writing-a-plugin).
- 📚 **Improve docs** — keep entry points, examples, and architecture aligned with the source and Gradle configuration.
- 🔧 **Fix something** — pick an issue, keep the slice small and demoable.

## Principles

1. Keep command validation, permissions, confirmation, and audit in the Runtime path.
2. Keep Android integrations in the host adapter and product state in the app.
3. Make changes in small, verifiable slices with explicit success and failure behavior.
4. Use the [implementation status](./docs/en/11-implementation-status.md) to distinguish current behavior from plans and verification results.

## Getting Started

| Requirement | Version |
|:------------|:--------|
| JDK | 17 (Gradle toolchain) |
| Android SDK | Platform 35 (build-tools via AGP 8.7.3; `minSdk` is 26) |
| Kotlin | 2.0.21 (set by the build — no local install needed) |
| Gradle | 8.10 via the wrapper |

> `gradlew` in this repo has no execute bit — invoke it through the shell: `sh gradlew <task>`. (CI does `chmod +x` itself.)

Clone the repository. The full build covers the JVM and Android modules:

```bash
git clone https://github.com/Morainet/mcos.git
cd mcos
sh gradlew build          # JVM and Android build, unit tests, and lint
```

Faster loops while you work:

```bash
sh gradlew :mcos-runtime-core:test                # one module
sh gradlew :mcos-android-sdk:testDebugUnitTest    # Android library tests
sh gradlew :mcos-android:assembleDebug            # Android Agent Debug APK
```

## Repo Layout

The [architecture guide](./docs/en/13-architecture-convergence.md) owns the module table, current dependency graph, and target design. `PackageBoundariesTest` checks package ownership, and `settings.gradle.kts` lists included projects. User-facing Android development starts in [`mcos-android`](./mcos-android/README.md).

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
- When verification coverage changes, update the runnable check or coverage description in the [implementation status](./docs/en/11-implementation-status.md). The conformance suite owns its executable baseline.
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

For changes to a bilingual document under `docs/en/` or `docs/zh/`, update its counterpart and preserve source links. Verify examples with current source and tests. The documentation indexes distinguish current guides, technical references, and historical designs.

## Writing a Plugin

Start with [`docs/en/04-plugin-sdk.md`](./docs/en/04-plugin-sdk.md) (manifest, handler contract, host services) and copy the shape of the smallest existing plugin, [`plugins/mcos-plugin-hello`](./plugins/mcos-plugin-hello). Plugins:

- depend only on `mcos-sdk` (enforced direction);
- declare permissions, network scopes, and side-effect classes in their manifest — the kernel does the enforcing, plugins stay honest;
- get registered in `PackageBoundariesTest` and the CI plugin shard when added.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](./LICENSE).
