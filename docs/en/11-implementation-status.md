# MCOS Implementation Status

> **Status:** Living document  
> **Last Updated:** 2026-08-06  
> **Audience:** Contributors and evaluators who need to know **what is spec-only vs. what still needs to be built**.

MCOS is currently a **design-only** repository: the Command Protocol, Runtime architecture, Plugin SDK, and surrounding subsystems are specified across `docs/00`–`10`, but **no implementation code exists yet**. This document tracks how the spec maps to future implementation work.

---

## 0. Golden Rule

> **The docs are the spec. Implementation is aligned to the docs — not the other way around.**
>
> When an implementation and a doc disagree, the doc is correct by default (unless the doc itself is internally contradictory). Do not "fix" a doc to match an implementation. Instead, file the gap here and implement toward the doc.

---

## 1. Current Repository State

The repository holds **only documentation**:

```text
mcos/
├── docs/                 # 12 RFCs (00–11) + fixtures + REPOSITORIES.md
├── doc/                  # Early Chinese brainstorm notes
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **Source code modules:** none (planned, §2)
- **Build system:** none (Gradle multi-module to be created in P1)
- **Golden fixtures:** ✅ present — 8 cases under [`docs/fixtures/`](../fixtures/) (5 positive round-trip, 3 negative reject)

> A Phase-0 code skeleton previously existed (6 Gradle modules, 8 Kotlin files). It was removed to keep the repo a clean design-only baseline; the spec below is what a fresh implementation must target.

---

## 2. Target Module Topology

These are the modules the implementation will introduce, in dependency order. See [`REPOSITORIES.md`](./REPOSITORIES.md) for the full reference card.

**P1 first-party modules** (6 — defined in [REPOSITORIES.md](./REPOSITORIES.md) §2):

| Module | Role | Target phase |
|--------|------|--------------|
| `mcos-sdk` | Plugin contracts (`McosPlugin`, `CommandHandler`, `CommandDescriptor`, …) | P1 (leaf) |
| `mcos-runtime` | Parser → IR, Registry, Permission Kernel, Scheduler, Executor, Audit | P1 |
| `mcos-android` | Jetpack Compose client shell (CLI / Chat) | P1 |
| `plugins:mcos-plugin-hello` | Reference sample plugin | P1 |
| `plugins:mcos-plugin-system` | `sys.notify`, `sys.share`, `sys.intent.start` | P1 |
| `plugins:mcos-plugin-camera` | `camera.capture`, `camera.scan` | P1 |

**Planned modules** (later phases — defined in [REPOSITORIES.md](./REPOSITORIES.md) §3):

| Module | Role | Target phase |
|--------|------|--------------|
| `mcos-plugin-files` | `file.*`, `photo.search`, `photo.compress` | P1 |
| `mcos-plugin-iot` | `home.*`, `iot.*` (Home Assistant / Tuya / Matter) | P2 |
| `mcos-plugin-mcp` | MCP client adapter → `mcp.*` | P2 spike / P3 production |
| `mcos-server` | Sync, marketplace index, remote policy | P3 |

---

## 3. Subsystem Implementation Matrix

Every subsystem below is **spec-only** today; the "Target phase" is when implementation begins.

| Subsystem | Spec doc | Target phase |
|-----------|----------|--------------|
| **Parser** (DSL → IR) | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1 (first) |
| **IR types** | [02](./02-command-protocol.md) §7 | P1 |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 |
| **Executor** | [03](./03-runtime.md) §9 | P1 |
| **Audit Log** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1 (basic) |
| **Planner Bridge** | [06](./06-agent.md) | P1 (one provider) |
| **Network Egress Policy** | [08](./08-security.md) §12 (`decideEgress`) | P1 |
| **Prompt Injection Detection** | [08](./08-security.md) §11 | P1 (compiler-side) |
| **Rate Limiting** | [08](./08-security.md) §10 | P1 (per-plugin/min) |
| **Secret Management** (`{{secret}}` templates) | [08](./08-security.md) §9 | P1 |
| **Crash-loop Quarantine** | [08](./08-security.md) §15.3 | P1 |
| **Process Isolation** | [08](./08-security.md) §8 | P1 (best effort) → P3 (third-party default) |
| **Workflow Engine** | [05](./05-workflow.md) | P2 |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 |
| **Memory** | [07](./07-memory.md) | P2 |
| **Enterprise Policy** | [08](./08-security.md) §13 | P3 |
| **Marketplace** | [09](./09-marketplace.md) | P3 |

> **The single highest-leverage first step is implementing the `DslParser`**, since it unblocks the entire execution pipeline and is covered by golden fixtures (§4).
>
> **Safety floor:** P1 ships a complete (if minimal) security story — `decideConfirmation` ([08 §4](./08-security.md)), `decideEgress` ([08 §12](./08-security.md)), prompt-injection compiler checks ([08 §11](./08-security.md)), rate limiting ([08 §10](./08-security.md)), and crash quarantine ([08 §15.3](./08-security.md)). See [08 §17](./08-security.md) for the security phasing table.

---

## 4. Fixture Coverage

Golden cases under [`docs/fixtures/`](../fixtures/). These already exist and define the conformance surface a future parser must satisfy.

### 4.1 Positive (round-trip DSL → IR)

| Case | Covers | Protocol § |
|------|--------|------------|
| `01-empty-args` | empty args + `# mcos-dsl:` header | §6.1, §6.5 |
| `02-named-string` | named string arg | §6.1 |
| `03-array-and-int` | int + string array | §6.2 |
| `04-sequence` | multi-statement + comment → `sequence` | §6.4 |
| `05-mixed-literals` | bool / float / null, keys sorted | §6.2, §7.4 |

### 4.2 Negative (must reject)

| Case | Invalid input | Expected error | Protocol § |
|------|---------------|----------------|------------|
| `06-nested-call` | nested invocation in arg | `PARSE_ERROR` | §6.2, §15.1 |
| `07-positional-arg` | positional argument | `PARSE_ERROR` | §6.1 |
| `08-malformed` | unbalanced parenthesis | `PARSE_ERROR` | §18 |

---

## 5. Global Feature Matrix (P0 → P3)

Aggregated from the "MVP vs V1" phasing tables in [05](./05-workflow.md) §15, [06](./06-agent.md) §17, [07](./07-memory.md) §16, [08](./08-security.md) §17, and [09](./09-marketplace.md) §15. P0 (now) is **spec-complete, code-absent**. Phase terminology: P1 = MVP, P2 = V1, P3 = V2 ([10](./10-roadmap.md) §2.1).

| Subsystem | P0 (spec only) | P1 MVP | P2 | P3 |
|-----------|----------------|--------|----|----|
| Parser + IR | spec done | ✅ implement full DSL↔IR | — | — |
| Registry + Executor | spec done | ✅ | — | — |
| Permission Kernel (`decideConfirmation`) | spec done | ✅ Android + confirms ([08 §17](./08-security.md)) | — | — |
| ConfirmationPrompt | spec done | ✅ NORMAL/ELEVATED ([08 §17](./08-security.md)) | ✅ destructive typed-ack | — |
| Network Egress (`decideEgress`) | spec done | ✅ ([08 §17](./08-security.md)) | — | — |
| Prompt Injection Detection | spec done | ✅ compiler-side ([08 §17](./08-security.md)) | — | + adaptive model-side |
| Rate Limiting | spec done | ✅ per-plugin/min ([08 §17](./08-security.md)) | ✅ + per-recipe/hour | + adaptive |
| Audit | spec done | basic (unencrypted) | encrypted + export (HMAC) | remote attestation |
| Workflow | spec done | sequence only | parallel / if / retry / confirm ([05 §15](./05-workflow.md)) | — |
| Event Bus | spec done | stub / few events | full | — |
| Memory | spec done | profile + remember | episodic + fuzzy refs ([07 §16](./07-memory.md)) | cloud sync |
| Planner | spec done | 1 provider, chat→DSL | multi-provider + probes ([06 §17](./06-agent.md)) | — |
| Plugins | spec done | hello + system + camera + files (20+ commands, [10 §4.3.1](./10-roadmap.md)) | IoT + Intent | MCP spike (P2) / MCP production + marketplace (P3) |
| Marketplace | spec done | — | sideload debug | public index + signing ([09 §15](./09-marketplace.md)) |
| Process Isolation | spec done | best effort (in-process) | — | third-party default |
| Enterprise Policy | spec done | — | — | ✅ allowlist/denylist ([08 §13](./08-security.md)) |
| Crash-loop Quarantine | spec done | ✅ ([08 §15.3](./08-security.md)) | ✅ | ✅ |

---

## 6. Recommended Development Path (P1)

Ordered by dependency; each step cites the spec section to implement against.

1. **Gradle multi-module build** — create `mcos-sdk`, `mcos-runtime`, `mcos-android`, plugins per [REPOSITORIES.md](./REPOSITORIES.md).
2. **`DslParser`** — implement per [02](./02-command-protocol.md) §6 + §18; pass all fixtures in §4. *(unblocks everything)*
3. **`CommandRegistry`** — load `CommandDescriptor`s from plugins; resolve by ID. Per [03](./03-runtime.md) §6.
4. **`Executor`** — invoke `CommandHandler` with validated args; map exceptions to `PLUGIN_ERROR`. Per [03](./03-runtime.md) §9.
5. **Schema validation** — validate args against `inputSchema` before execute. Per [02](./02-command-protocol.md) §9.1.
6. **`PermissionKernel`** — grant/deny/confirm flow by `sideEffectClass`. Per [08](./08-security.md) §4.
7. **Audit (basic)** — append run records locally. Per [03](./03-runtime.md) §13.
8. **Real plugin handlers** — wire `camera.capture` / `sys.notify` to Android APIs. Per [04](./04-plugin-sdk.md) §7.
9. **`files` plugin** — `photo.search` / `photo.compress`. Per [10](./10-roadmap.md) §4.3.
10. **One LLM provider** — utterance → single command / short sequence. Per [06](./06-agent.md) §3.

> Vertical slice to demo first: `camera.capture()` → `photo.compress(quality=80)` → `sys.notify(...)`, end-to-end through the real Runtime. This is the MVP exit criterion ([10](./10-roadmap.md) §4.6).

---

## 7. SDK API Target (spec contract)

The full plugin contract is specified in [04-plugin-sdk.md](./04-plugin-sdk.md) §5–6. The implementation must provide at minimum:

- `McosPlugin` — `id`, `version`, `commands()`, `handler(commandId)`, plus lifecycle (`onLoad`/`onUnload`) per spec.
- `CommandDescriptor` — all spec fields including `permissions`, `inputSchema`, `outputSchema`, `sideEffectClass`.
- `CommandResult` — `Ok(value, artifacts)` and `Err(code, message, retryable, details)` where `details: JsonObject` carries per-error-code structured context ([04 §5.2](./04-plugin-sdk.md), [02 §8.3](./02-command-protocol.md)).
- `McosException` — the plugin-declared error channel for recoverable failures distinct from `CommandResult.Err` ([04 §9.5](./04-plugin-sdk.md)).
- `ExecutionContext` — `runId`, `commandId`, `args`, `stepId`, `auth`, `deadline`, `progress`, `services`.
- `HostServices` facades: `files`, `net`, `ui`, `secureStore`, `clock`, `json`, `memory` — the unified plugin-facing facade ([04 §6](./04-plugin-sdk.md), [01 §11.1](./01-architecture.md)). The historical `PluginHost` name is retired.

See [04-plugin-sdk.md](./04-plugin-sdk.md) §5 for the authoritative Kotlin IDL.

---

## 8. How to Update This File

- **When code lands:** move the relevant row from "spec-only" to "implemented" and cite the PR/commit.
- **When a spec changes:** bump the RFC version and update §4 fixtures + §3/§5 matrices.
- **Do not** delete rows — mark them superseded so the history of intent is preserved.

This file drifts fast; treat it as a checklist, not a monument.
