# MCOS Implementation Status

> **Status:** Living document  
> **Last Updated:** 2026-08-13  
> **Audience:** Contributors and evaluators who need to know **what is spec-only vs. what still needs to be built**.

MCOS has shipped the **P1 MVP and most of P2**: the Command Protocol, Runtime, Plugin SDK, and shell are implemented in Kotlin across `mcos-sdk`, `mcos-runtime`, `mcos-android` and four plugins. The tables below mark each subsystem **implemented / partial / not started**, citing the commits that landed the code. Rows that remain spec-only are the remaining work.

---

## 0. Golden Rule

> **The docs are the spec. Implementation is aligned to the docs — not the other way around.**
>
> When an implementation and a doc disagree, the doc is correct by default (unless the doc itself is internally contradictory). Do not "fix" a doc to match an implementation. Instead, file the gap here and implement toward the doc.

---

## 1. Current Repository State

The repository contains **documentation and a working multi-module implementation**:

```text
mcos/
├── docs/                 # 12 RFCs (00–11) + fixtures + REPOSITORIES.md
├── doc/                  # Early Chinese brainstorm notes
├── mcos-sdk/             # Plugin contracts (McosPlugin, CommandHandler, …) + Memory/ResolveResult types
├── mcos-runtime/         # Parser → IR, Registry, Executor, Permission, Audit, Workflow, EventBus, Memory, LLM
├── mcos-android/         # Jetpack Compose CLI / Chat shell + Android host services
├── plugins/              # hello, system, camera, files
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **Source code modules:** ✅ 6 (sdk, runtime, android + 4 plugins) per §2
- **Build system:** ✅ Gradle Kotlin DSL multi-module (JDK 17, Kotlin 2.0.21, AGP 8.7.3, minSdk 26)
- **Golden fixtures:** ✅ 8 cases under [`docs/fixtures/`](../fixtures/) (5 positive round-trip, 3 negative reject) — exercised by `DslParserTest`

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

Status legend: ✅ implemented · 🟡 partial · ⬜ spec-only (not started). Landing commits are cited per row (2026-08-12, branch `main`).

| Subsystem | Spec doc | Target phase | Status |
|-----------|----------|--------------|--------|
| **Parser** (DSL → IR) | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1 (first) | ✅ `parse/` (Lexer, Parser, DslParser, Canonicalizer); fixtures green |
| **IR types** | [02](./02-command-protocol.md) §7 | P1 | ✅ `ir/IrTypes` (ExecutionIr + Step, payload envelope) |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 | ✅ `registry/CommandRegistry` (register/resolve/unregister, id=name) |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 | ✅ `permission/PermissionKernel.decideConfirmation` (NORMAL/ELEVATED) |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 | 🟡 in-process FIFO queue inside `McosRuntime`; no priority lanes yet |
| **Executor** | [03](./03-runtime.md) §9 | P1 | ✅ `executor/Executor` (steps, artifacts, confirm, cancellation, rate-limit) |
| **Audit Log** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1 (basic) | ✅ `audit/AuditLog` (append, filter, rotate, sha256 + HMAC chain) |
| **Planner Bridge** | [06](./06-agent.md) | P1 (one provider) | ✅ `llm/` LlmPlanner + OpenAiLlmProvider + ChatOrchestrator; not wired into Android UI |
| **Planner (multi-provider)** | [06](./06-agent.md) §17 V1 | P2 | ✅ `llm/LlmProviderRegistry` — capability model (`Capability`: CHAT/PLAN/TOOL_CALL/EMBED), health probes (`probe()`), priority-ordered fallback chain in `LlmPlanner` (retryable error → next provider; §18.1 on-device→cloud fallback) |
| **Network Egress Policy** | [08](./08-security.md) §12 (`decideEgress`) | P1 | ✅ `security/NetworkEgressPolicy.decideEgress` |
| **Prompt Injection Detection** | [08](./08-security.md) §11 | P1 (compiler-side) | ✅ `llm/PromptInjectionDetector` |
| **Rate Limiting** | [08](./08-security.md) §10 | P1 (per-plugin/min) | ✅ `security/RateLimiter` (per-plugin/min) |
| **Secret Management** (`{{secret}}` templates) | [08](./08-security.md) §9 | P1 | ✅ `security/SecretResolver` + `Executor` NetService decorator (values never written back into args; unknown keys stay inert; `x-mcos-secret` audit redaction) |
| **Crash-loop Quarantine** | [08](./08-security.md) §15.3 | P1 | ✅ `security/CrashQuarantine` (3 crashes / 60s → quarantine + unregister + audit `plugin.quarantined`; success resets window; explicit re-enable only) |
| **Process Isolation** | [08](./08-security.md) §8 | P1 (best effort) → P3 (third-party default) | 🟡 best-effort in-process concurrency only |
| **Workflow Engine** | [05](./05-workflow.md) | P2 | ✅ `workflow/WorkflowEngine` — sequential/parallel/if/loop/retry/try/confirm, named store + JSON decode; wired into `McosRuntime.runWorkflow` (`d533c05`) |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 | ✅ `events/EventBus` — typed envelopes, prefix+where filters, subscriber isolation, drop-oldest backpressure + audit (`22ba52b`) |
| **Memory** | [07](./07-memory.md) | P2 | ✅ `memory/MemoryStore` — TTL, tags, fuzzy resolveRef + confidence, CREATED/UPDATED/CONFLICT write semantics, superseded history (`d549236`) + ✅ `memory/EpisodicMemory` — run summaries, time-decay recall (§8.1), 50→5 auto-summarize + 90-day retention (§8.2) + ✅ `memory/RunSummarizer` — §9.4 run-completion hook: commands/workflows record `EpisodicRecord` (entities from `namespace.path` args, summary from DSL text) |
| **Enterprise Policy** | [08](./08-security.md) §13 | P3 | ⬜ not started |
| **Marketplace** | [09](./09-marketplace.md) | P3 | ⬜ not started |

> **Done:** the `DslParser` (the highest-leverage first step) shipped together with the rest of the P1 pipeline. The P1 safety floor is closed: `decideConfirmation`, `decideEgress`, prompt-injection checks, rate limiting, **`{{secret}}` template resolution (§9.2)** and **crash-loop quarantine (§15.3)** are all implemented.
>
> **Test baseline (2026-08-13):** 399 tests across all modules — parser fixtures, executor, permission, audit (incl. `x-mcos-secret` redaction), workflow (W1-W6), event bus (8), memory (M1-M33 + episodic E1-E14 + summarizer S1-S11), secret resolver, crash quarantine, plugins, multi-provider (R1-R8 registry + F1-F6 fallback chain), Android.

---

## 4. Fixture Coverage

Golden cases under [`docs/fixtures/`](../fixtures/). All eight are exercised by `DslParserTest` and pass on the current parser.

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
| Parser + IR | spec done | ✅ **implemented** full DSL↔IR | — | — |
| Registry + Executor | spec done | ✅ **implemented** | — | — |
| Permission Kernel (`decideConfirmation`) | spec done | 🟡 implemented in runtime; Android confirmation UI not wired | — | — |
| ConfirmationPrompt | spec done | ✅ **implemented** NORMAL/ELEVATED | ✅ destructive typed-ack | — |
| Network Egress (`decideEgress`) | spec done | ✅ **implemented** | — | — |
| Prompt Injection Detection | spec done | ✅ **implemented** compiler-side | — | + adaptive model-side |
| Rate Limiting | spec done | ✅ **implemented** per-plugin/min | ✅ + per-recipe/hour | + adaptive |
| Audit | spec done | ✅ **implemented** basic | ✅ **implemented** encrypted + export (HMAC chain) | remote attestation |
| Workflow | spec done | ✅ sequence | ✅ **implemented** parallel / if / loop / retry / try / confirm | — |
| Event Bus | spec done | ✅ run-event channel | ✅ **implemented** full (envelopes, filters, isolation, backpressure) | — |
| Memory | spec done | ✅ profile + remember | ✅ fuzzy refs + conflict detection + **episodic layer** (E1-E14) + **run-completion summarizer** (S1-S11, §9.4); cloud sync open | cloud sync |
| Planner | spec done | ✅ 1 provider, chat→DSL | 🟡 multi-provider + probes open ([06 §17](./06-agent.md)) | — |
| Plugins | spec done | ✅ hello + system + camera + files (20+ commands, [10 §4.3.1](./10-roadmap.md)) | ⬜ IoT + Intent | MCP spike (P2) / MCP production + marketplace (P3) |
| Marketplace | spec done | — | ⬜ sideload debug | public index + signing ([09 §15](./09-marketplace.md)) |
| Process Isolation | spec done | 🟡 best effort (in-process) | — | third-party default |
| Enterprise Policy | spec done | — | — | ⬜ allowlist/denylist ([08 §13](./08-security.md)) |
| Crash-loop Quarantine | spec done | ✅ | ✅ | ✅ |

---

## 6. Recommended Development Path

Steps 1–7 and 10 are **implemented** (2026-08-12); 8–9 are partially wired through `AndroidHostServices`.

1. ✅ **Gradle multi-module build** — `mcos-sdk`, `mcos-runtime`, `mcos-android`, 4 plugins per [REPOSITORIES.md](./REPOSITORIES.md).
2. ✅ **`DslParser`** — per [02](./02-command-protocol.md) §6 + §18; all fixtures pass (§4).
3. ✅ **`CommandRegistry`** — `CommandDescriptor`s loaded from plugins; resolved by ID. Per [03](./03-runtime.md) §6.
4. ✅ **`Executor`** — `CommandHandler` invoked with validated args; exceptions mapped to `PLUGIN_ERROR`. Per [03](./03-runtime.md) §9.
5. ✅ **Schema validation** — args validated against `inputSchema` before execute. Per [02](./02-command-protocol.md) §9.1.
6. ✅ **`PermissionKernel`** — grant/deny/confirm flow by `sideEffectClass`. Per [08](./08-security.md) §4.
7. ✅ **Audit (basic + HMAC chain)** — run records appended locally. Per [03](./03-runtime.md) §13.
8. 🟡 **Real plugin handlers** — `camera.capture` / `sys.notify` wired to Android APIs via `AndroidHostServices`; confirmation UI pending. Per [04](./04-plugin-sdk.md) §7.
9. 🟡 **`files` plugin** — `photo.search` / `photo.compress` implemented; Android gallery search pending. Per [10](./10-roadmap.md) §4.3.
10. ✅ **Multi-provider Planner** — `LlmProvider` capability model (`Capability`: CHAT/PLAN/TOOL_CALL/EMBED), `LlmProviderRegistry` (registration, capability routing, health probes), and a priority-ordered fallback chain in `LlmPlanner` (retryable error → next provider; §18.1 on-device→cloud fallback). Per [06](./06-agent.md) §17 V1. Android chat UI still not connected.

**Next up (suggested):** wire the Planner into the Android chat shell (needs an API key), then PlanMode `NATIVE_TOOL_CALL` + on-device model fallback chain (06 §17), then the cloud-sync Memory tier (§16).

---

## 7. SDK API Target (spec contract)

The full plugin contract is specified in [04-plugin-sdk.md](./04-plugin-sdk.md) §5–6. The implementation provides all of the following (all four plugins compile against it):

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
