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
| **Planner Bridge** | [06](./06-agent.md) | P1 (one provider) | ✅ `llm/` LlmPlanner + OpenAiLlmProvider + ChatOrchestrator, wired into the Android chat shell via pluggable `LlmHttpTransport` (JDK `HttpClient` default + Android `HttpURLConnection`); API key persisted via `AndroidSecureStore`; 4 PlanModes — NATIVE_TOOL_CALL / FREEFORM_JSON / CONSTRAINED / LATENCY_TIERED |
| **Planner (multi-provider)** | [06](./06-agent.md) §17 V1 | P2 | ✅ `llm/LlmProviderRegistry` — capability model (`Capability`: CHAT/PLAN/TOOL_CALL/EMBED), health probes (`probe()`), priority-ordered fallback chain in `LlmPlanner` (retryable error → next provider; §18.1 on-device→cloud fallback), **PlanMode `NATIVE_TOOL_CALL`** (`ToolCall`/`ToolDescriptor`/`TokenUsage`, per-provider mode selection, OpenAI `tools` protocol), **on-device→cloud privacy gate** (`ProviderTier`, "Allow cloud planner" opt-in, §13.2) |
| **Network Egress Policy** | [08](./08-security.md) §12 (`decideEgress`) | P1 | ✅ `security/NetworkEgressPolicy.decideEgress` |
| **Prompt Injection Detection** | [08](./08-security.md) §11 | P1 (compiler-side) | ✅ `llm/PromptInjectionDetector` |
| **Rate Limiting** | [08](./08-security.md) §10 | P1 (per-plugin/min) | ✅ `security/RateLimiter` (per-plugin/min) |
| **Secret Management** (`{{secret}}` templates) | [08](./08-security.md) §9 | P1 | ✅ `security/SecretResolver` + `Executor` NetService decorator (values never written back into args; unknown keys stay inert; `x-mcos-secret` audit redaction) |
| **Crash-loop Quarantine** | [08](./08-security.md) §15.3 | P1 | ✅ `security/CrashQuarantine` (3 crashes / 60s → quarantine + unregister + audit `plugin.quarantined`; success resets window; explicit re-enable only) |
| **Process Isolation** | [08](./08-security.md) §8 | P1 (best effort) → P3 (third-party default) | 🟡 best-effort in-process concurrency only |
| **Workflow Engine** | [05](./05-workflow.md) | P2 | ✅ `workflow/WorkflowEngine` — sequential/parallel/if/loop/retry/try/confirm, named store + JSON decode; wired into `McosRuntime.runWorkflow` (`d533c05`) |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 | ✅ `events/EventBus` — typed envelopes, prefix+where filters, subscriber isolation, drop-oldest backpressure + audit (`22ba52b`) |
| **Memory** | [07](./07-memory.md) | P2 | ✅ `memory/MemoryStore` — TTL, tags, fuzzy resolveRef + confidence, CREATED/UPDATED/CONFLICT write semantics, superseded history (`d549236`) + ✅ `memory/EpisodicMemory` — run summaries, time-decay recall (§8.1), 50→5 auto-summarize + 90-day retention (§8.2) + ✅ `memory/RunSummarizer` — §9.4 run-completion hook: commands/workflows record `EpisodicRecord` (entities from `namespace.path` args, summary from DSL text) + ✅ **`memory/MemorySync` — §11 device-to-device sync: `VectorClock` (tick/strict-dominance `isAfter`/`isConcurrentWith`/component-wise `merge`, §11.1), per-entry `syncable` flag (§11.0: only `syncable=true` entries ever leave the device), `SyncEntry` snapshot export/import with LWW table (local/remote dominates → silent; concurrent → surfaced `SyncConflict` → `KEEP_LOCAL`/`KEEP_REMOTE`/`KEEP_BOTH`), `SyncPolicy` (§11.3: `enabled` = disableCloudMemorySync, `allowedCategories` = allowedSyncCategories) with AuditLog policy-violation records** + ✅ **`memory/MemoryBlobCrypto` — §11.0 E2E-encrypted blobs: AES-256-GCM + random 12-byte IV (identical plaintexts yield different ciphertexts), `HkdfSha256` (RFC 5869 HKDF-SHA256; the JDK has no built-in HKDF) deriving a purpose-specific key from the device-local **account key** (`AccountKeyProvider`, §11.0: shared across the account's devices, Android wraps it in Keystore; §10.1: high-entropy master key → no PBKDF2/Argon2), blob version bound as GCM AAD — any tamper of ciphertext/IV/version → `BlobIntegrityException`; wire format `EncryptedBlob{version, iv, ciphertext}` (Base64 JSON)** + ✅ **`memory/MemorySyncClient` + `SyncBlobTransport` (server stores opaque blobs only): `push()` exports the syncable snapshot → encrypt → upload (returns blobId); `pull()` downloads → decrypts locally → `importSnapshot` (LWW + enterprise policy apply end to end); `JdkSyncBlobTransport` (`java.net.http.HttpClient`, `PUT|GET|DELETE /blobs/{id}`, 404 → non-retryable `SyncBlobException("NOT_FOUND")`; Android lacks `java.net.http` → inject an `HttpURLConnection` transport, same pattern as `LlmHttpTransport`) + E2E against an in-process JDK `HttpServer` reference server (E1-E9: round-trip, server sees ciphertext only, LWW, concurrent conflict, `local_only` never leaves the device, `disableCloudMemorySync`, 404, idempotent re-pull)** |
| **Enterprise Policy** | [08](./08-security.md) §13 | P3 | ⬜ not started |
| **Marketplace** | [09](./09-marketplace.md) | P3 | ⬜ not started |

> **Done:** the `DslParser` (the highest-leverage first step) shipped together with the rest of the P1 pipeline. The P1 safety floor is closed: `decideConfirmation`, `decideEgress`, prompt-injection checks, rate limiting, **`{{secret}}` template resolution (§9.2)** and **crash-loop quarantine (§15.3)** are all implemented.
>
> **Test baseline (2026-08-15):** 604 tests across all modules — parser fixtures, executor, permission, audit (incl. `x-mcos-secret` redaction), workflow (W1-W6), event bus (8), memory (M1-M33 + episodic E1-E14 + summarizer S1-S11 + **sync V1-V4 vector-clock semantics: tick / strict-dominance `isAfter` / concurrent / component-wise merge + S1-S15 sync flow: syncable-only export, new-path apply, local/remote dominance LWW, concurrent→`SyncConflict` surface, idempotent re-import, `allowedSyncCategories` filter, `disableCloudMemorySync` abort + AuditLog records, KEEP_LOCAL/REMOTE/BOTH resolution, clock-merge monotonicity, snapshot payload** + **B1-B3 HKDF-SHA256 (RFC 5869 §A.1 official PRK/OKM test vectors + length bounds) + C1-C7 `MemoryBlobCrypto` (round-trip, different ciphertexts for identical plaintext, ciphertext/IV tamper → `BlobIntegrityException`, version gating, cross-account-key rejection, opaque wire) + E1-E9 encrypted sync E2E (device A push → HTTP → reference server → device B pull/decrypt: round-trip, server sees ciphertext only, remote LWW wins, local newer kept, concurrent → `SyncConflict`, `local_only` never leaves the device, `disableCloudMemorySync` + AuditLog, 404 → `NOT_FOUND`, idempotent re-pull)**), secret resolver, crash quarantine, plugins, multi-provider (R1-R8 registry + F1-F6 fallback chain + T1-T8 native tool-calling + O1-O10 on-device privacy gate + T-transport 7 + **C1-C16 CONSTRAINED: mode selection (TOOL_CALL > CONSTRAINED), IR `invoke`/`sequence`/`clarify`/`refuse` parsing, malformed→`LLM_PARSE_ERROR`, retryable fallback + non-retryable stop, grammar injection (GBNF / JSON Schema selection), `parseIrJson` unit tests** + **G1-G12 GBNF grammar generation: root enumerates catalog commands, args constraints (keys/types/enum/const/nesting), step rules, empty catalog, shared JSON rules, escaping** + **P1-P10 GrammarLlmProvider: llama.cpp `grammar` / vLLM `guided_grammar`/`guided_json` injection, format mismatch→`CAPABILITY_EXCEEDED`, transport error mapping** + **U1-U13 utterance classification (§13.1 routing heuristics: EXACT_CLI/KNOWN_RECIPE/PRIVACY_SENSITIVE/COMPLEX/SIMPLE, precedence EXACT_CLI > KNOWN_RECIPE > PRIVACY > COMPLEX > SIMPLE)** + **R1-R8 RecipeMatcher (exact/normalized/containment matching, short-trigger safety, first-match wins)** + **L1-L11 LATENCY_TIERED tiered routing: EXACT_CLI parser-only (zero LLM), KNOWN_RECIPE local recipe (zero LLM), SIMPLE on-device-first (even when cloud is primary), COMPLEX cloud-first (when opted in), PRIVACY forced on-device, fast-path miss falls back to the LLM chain, latencyMs/route telemetry, privacy gate preserved, default-mode backward compatibility**), Android.

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
| Permission Kernel (`decideConfirmation`) | spec done | ✅ **implemented** runtime + Android confirmation dialog wired (suspended `ConfirmationNeeded` → `respondConfirmation` Approve/Reject, timeout) | — | — |
| ConfirmationPrompt | spec done | ✅ **implemented** NORMAL/ELEVATED | ✅ destructive typed-ack | — |
| Network Egress (`decideEgress`) | spec done | ✅ **implemented** | — | — |
| Prompt Injection Detection | spec done | ✅ **implemented** compiler-side | — | + adaptive model-side |
| Rate Limiting | spec done | ✅ **implemented** per-plugin/min | ✅ + per-recipe/hour | + adaptive |
| Audit | spec done | ✅ **implemented** basic | ✅ **implemented** encrypted + export (HMAC chain) | remote attestation |
| Workflow | spec done | ✅ sequence | ✅ **implemented** parallel / if / loop / retry / try / confirm | — |
| Event Bus | spec done | ✅ run-event channel | ✅ **implemented** full (envelopes, filters, isolation, backpressure) | — |
| Memory | spec done | ✅ profile + remember | ✅ fuzzy refs + conflict detection + **episodic layer** (E1-E14) + **run-completion summarizer** (S1-S11, §9.4) + **§11 sync layer (vector-clock LWW + policy)** + **§11.0 E2E-encrypted blobs** (`MemoryBlobCrypto`: AES-256-GCM + HKDF derivation + version-bound AAD) | ✅ **cloud sync (Phase 3) device-side done**: `MemorySyncClient` + `SyncBlobTransport` (server stores opaque blobs only) + reference-server E2E; standalone `mcos-server` deployment pending |
| Planner | spec done | ✅ 1 provider, chat→DSL | ✅ multi-provider + probes + NATIVE_TOOL_CALL + on-device privacy gate ([06 §17](./06-agent.md)); CONSTRAINED open | 🟡 latency-tiered routing ([§13.1](./06-agent.md) classifier + zero-latency paths + tiered chain) implemented |
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
10. ✅ **Multi-provider Planner** — `LlmProvider` capability model (`Capability`: CHAT/PLAN/TOOL_CALL/EMBED), `LlmProviderRegistry` (registration, capability routing, health probes), and a priority-ordered fallback chain in `LlmPlanner` (retryable error → next provider; §18.1 on-device→cloud fallback). Per [06](./06-agent.md) §17 V1.
11. ✅ **PlanMode `NATIVE_TOOL_CALL`** — per-provider mode selection (TOOL_CALL → native tool calling, else FREEFORM_JSON), `ToolCall`/`ToolDescriptor`/`TokenUsage` types, registry-command projection (incl. best-effort example parsing), and OpenAI `tools` protocol support in `OpenAiLlmProvider`. Per [06](./06-agent.md) §3.2/§17 V1.
12. ✅ **On-device → cloud fallback with privacy gate** — `ProviderTier` (ON_DEVICE/CLOUD) on `LlmProvider`, `LlmPlanner.cloudFallbackEnabled` ("Allow cloud planner" opt-in, 06 §13.2) and a privacy gate: once an ON_DEVICE provider fails, escalation to CLOUD requires the opt-in — without it the failure surfaces as `CLOUD_FALLBACK_DISABLED` refusal and no data leaves the device. Standard codes `CAPABILITY_EXCEEDED`/`CLOUD_FALLBACK_DISABLED`; `LlmProviderRegistry.onDeviceProviders()`/`cloudProviders()` tier filtering. Per [06](./06-agent.md) §13.0/§13.2/§17 V2.
13. ✅ **Android chat shell (Planner wired into the app)** — pluggable `LlmHttpTransport` (`LlmHttpTransport`/`HttpTransportResponse`/`LlmTransportException` in `llm/`; JDK `HttpClient` default keeps JVM tests green; `AndroidLlmHttpTransport` uses `HttpURLConnection` — Android has no `java.net.http` module), `OpenAiLlmProvider(transport=…)` injection, `INTERNET` permission in the manifest, and an **AI Chat card** in `MainActivity` (natural-language input → `ChatOrchestrator` → plan/DSL prefilled into the DSL editor → execution events logged; OpenAI API key persisted via `AndroidSecureStore`). Per [06](./06-agent.md) §17.
14. ✅ **PlanMode `CONSTRAINED` (grammar-constrained decoding)** — `Capability.CONSTRAINED` + `PlanMode.CONSTRAINED`, `LlmProvider.constrainedChat(messages, grammar: LlmGrammar)` (default `CAPABILITY_EXCEEDED`, non-retryable), mode selection `NATIVE_TOOL_CALL > CONSTRAINED > FREEFORM_JSON`, `buildIrJsonSchema()` (MCOS IR JSON Schema: `invoke`/`sequence`/`clarify`/`refuse`), and `parseIrJson()` — the model's reply is a single IR JSON object; malformed output yields retryable `LLM_PARSE_ERROR` so the fallback chain continues. `OpenAiLlmProvider` implements constrainedChat with OpenAI `response_format: json_object` + the schema appended to the system message (an API-side approximation; decode-level grammars landed in #16). CONSTRAINED system prompt only lists commands/memory (no DSL format section). Per [06](./06-agent.md) §3.2 V2 / §17 V2.
15. ✅ **Memory device-to-device sync (§11)** — `memory/VectorClock` (§11.1: `tick` / strict-dominance `isAfter` / `isConcurrentWith` / component-wise `merge`; CRDTs deliberately NOT adopted — for factual key-value memory "latest correct value" is the desired semantics), `MemoryEntry` gains `syncable`/`vectorClock`/`writerDeviceId` (§11.0: only `syncable=true` entries ever leave the device; local `put` auto-ticks this device's clock), and `memory/MemorySync` — `exportSnapshot()` (syncable entries only) + `importSnapshot()` (LWW table: local/remote dominates → silent keep/overwrite, concurrent → surface `SyncConflict`: "Keep local, remote, or both?") + `resolveConflict()` (`KEEP_LOCAL`/`KEEP_REMOTE`/`KEEP_BOTH`, the latter soft-deleting the local value into history so both are retained) and `SyncPolicy` (§11.3: `enabled` = disableCloudMemorySync global block, `allowedCategories` = allowedSyncCategories category restriction), violations abort the affected paths and are logged to AuditLog (`source=MEMORY_SYNC`). `MemoryStore.applySyncEntry` merges the remote clock so this device's clock catches up monotonically (LWW monotonicity). The server (Phase 3) only stores encrypted blobs — this implementation focuses on the device-side payload and decisions. Per [07](./07-memory.md) §11.
16. ✅ **CONSTRAINED grammar injection (GBNF / Outlines backend)** — `LlmGrammar`/`GrammarFormat` (`GBNF` and `JSON_SCHEMA`), `LlmProvider.grammarFormats` advertises grammar capability (planner picks the highest fidelity: GBNF > JSON_SCHEMA), `llm/GbnfGrammar` — llama.cpp GBNF generation from the command catalog: `root` enumerates catalog commands + terminal states (invoke/sequence/clarify/refuse), per-command `args-<id>` rules (key names / value types / enum / const / nested object/array; llama.cpp official `json.gbnf` style — members free-order/repeatable inside objects, `required` semantics are enforced downstream by `parseIrJson` and executor schema validation), `step-<id>` constrains commands inside `sequence`, shared JSON rules (`ws`/`string`/`number`/`boolean`/`value`/`object`/`array`), rule-name sanitization (`.` → `_`) and GBNF string escaping; plus `GrammarLlmProvider` — OpenAI-compatible with decode-level constraint: llama.cpp `llama-server` `{"grammar": "<gbnf>"}`, vLLM/Outlines `guided_grammar` (GBNF) and `guided_json` (JSON Schema) injection fields (`GrammarInjection`), format mismatch → `CAPABILITY_EXCEEDED` (non-retryable, no network I/O); `OpenAiLlmProvider` likewise rejects non-JSON_SCHEMA grammars. Per [06](./06-agent.md) §3.2 V2.
17. ✅ **PlanMode `LATENCY_TIERED` (latency-tiered routing)** — 06 §13.1 routing strategy + §15.1 performance budget: `llm/UtteranceClassifier` (`UtteranceClass`: EXACT_CLI / KNOWN_RECIPE / PRIVACY_SENSITIVE / COMPLEX / SIMPLE; keyword + heuristic, precedence EXACT_CLI > KNOWN_RECIPE > PRIVACY_SENSITIVE > COMPLEX > SIMPLE; the embedding-similarity layer is reserved for a later milestone), `llm/Recipe` + `llm/RecipeMatcher` (§13.1 FAQ / known recipes → local matcher: normalized exact/containment matching, zero latency); `LlmPlanner.plan(naturalLanguage, mode)` now accepts an explicit `LATENCY_TIERED` — first walks the zero-latency paths (EXACT_CLI → parser-only `direct-parser`; KNOWN_RECIPE → recipe DSL `recipe:<id>`, neither touches the network), then orders the LLM chain by latency tier (ON_DEVICE p95 ≤ 800 ms first, CLOUD p95 ≤ 3000 ms last; COMPLEX inverts when cloud planning is opted in; PRIVACY_SENSITIVE stays on-device-first), with the §13.2 privacy gate fully preserved inside the tiered chain; `LlmPlan` gains telemetry `utteranceClass` / `latencyMs` / `route` (§15.0); `ChatOrchestrator.chat` passes `mode` through. Per [06](./06-agent.md) §13.1/§13.2/§15.1.
18. ✅ **Cloud memory sync (Phase 3 E2E-encrypted blobs)** — 07 §11.0 "server only stores opaque blobs": `memory/HkdfSha256` (RFC 5869 HKDF-SHA256; the JDK has no built-in HKDF primitive), `memory/MemoryBlobCrypto` (AES-256-GCM + random 12-byte IV, identical plaintexts yield different ciphertexts; the AES key is HKDF-derived from the device-local **account key** (`AccountKeyProvider`, §11.0: shared across the account's devices, not a device-specific keystore key; Android wraps it in Keystore) — §10.1: high-entropy master key, deliberately no PBKDF2/Argon2; blob `version` bound as GCM AAD — any tamper of ciphertext/IV/version → `BlobIntegrityException`; wire format `EncryptedBlob{version, iv, ciphertext}`, all Base64), `memory/MemorySyncClient` (`push()`: exportSnapshot → encrypt → `transport.upload` → returns blobId; `pull()`: download → decrypt locally → `importSnapshot` — LWW + `SyncPolicy` apply end to end) + `SyncBlobTransport` (pluggable: JVM default `JdkSyncBlobTransport` (`java.net.http.HttpClient`, `PUT|GET|DELETE /blobs/{id}`, 404 → non-retryable `SyncBlobException("NOT_FOUND")`; Android has no `java.net.http` module → inject an `HttpURLConnection` transport, same pattern as `LlmHttpTransport`). E2E tests use an in-process JDK `HttpServer` reference server (stores opaque blobs only, never parses them): device A push → encrypt → HTTP → server → device B pull → decrypt → LWW import; the server sees ciphertext only (no plaintext path/value), `local_only` entries never leave the device, `disableCloudMemorySync` blocks the pull and logs to AuditLog. Per [07](./07-memory.md) §10.1/§11.0/§11.3.

**Next up (suggested):** a standalone `mcos-server` deployment (REST contract from `SyncBlobTransport` + auth), the episodic tier (§8: `EpisodicMemory` done; remaining §8.3 fuzzy references / named-entity merge) and Planner probes (§17 multi-provider probing).

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
