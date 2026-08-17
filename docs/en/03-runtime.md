# MCOS Runtime Design

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-06  
> **Package:** `mcos-runtime`  
> **Depends on:** [01-architecture.md](./01-architecture.md), [02-command-protocol.md](./02-command-protocol.md)

> 🚧 **Implementation status:** this document describes the Runtime **target architecture**. The repository is currently design-only — **no Runtime code exists yet**. The Parser, Registry, Permission Kernel, Scheduler, Workflow Engine, Executor, Event Bus, and Audit below are all **P1+** to implement. See [11-implementation-status.md](./11-implementation-status.md) §3.

---

## 1. Role

The Runtime is the **Command Bus kernel**.

It does not understand cameras, air conditioners, or GitHub.  
It understands: **parse → validate → authorize → schedule → execute → audit**.

```text
DSL / IR / Workflow
        │
        ▼
┌───────────────────┐
│      Parser       │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Command Registry  │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Permission Kernel │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Scheduler / WF    │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│     Executor      │
└─────────┬─────────┘
          ▼
      Plugin Host
```

---

## 2. Design Goals

| Goal | Detail |
|------|--------|
| Correctness | No side effects before auth + schema pass |
| Determinism | Same IR + same grants → same dispatch path |
| Isolability | Plugin crashes must not kill the kernel |
| Observability | Every run has `RunId` and audit trail |
| Testability | Pure JVM tests for parser/policy without Android |
| Extensibility | New plugins without Runtime releases |

---

## 3. Module Map

```text
mcos-runtime/
├── parser/          # DSL lexer, parser, IR codec
├── registry/        # Command Registry + plugin loader hooks
├── planner-bridge/  # Optional interface to AI Planner (not the LLM itself)
├── permission/      # Permission Kernel
├── scheduler/       # Queues, concurrency, cancellation
├── workflow/        # Graph interpreter (see 05)
├── executor/        # Handler invocation
├── memory/          # Facade over Memory engine (see 07)
├── eventbus/        # Typed event pub/sub
├── audit/           # Append-only execution log
├── host/            # Android/JVM host adapters
└── api/             # Public Runtime API for App / IPC
```

### 3.1 Startup & Shutdown Sequencing

The Runtime boots and drains in a fixed order so that no stage ever sees a dependency that has not started.

**Startup order** (blocks until each step signals READY before the next begins):

```text
1. AuditLog           — open encrypted store; single-writer channel ready
2. CommandRegistry    — discover manifests, verify signatures, register descriptors
3. PermissionKernel   — warm grant cache from persistent store
4. EventBus           — subscribe built-in event sources (power/connectivity/…)
5. Scheduler          — spin up queue channels + worker coroutines
6. McosRuntime        — bind subsystems, publish RuntimeFacade, mark READY
```

Rationale: Audit starts first so every subsequent step's load events are recorded; Registry must be populated before PermissionKernel can validate grant subjects; Scheduler is last among subsystems so it cannot accept work before Authorization can stamp it.

**Shutdown order** (reverse, with explicit grace):

```text
1. Stop accepting new ExecuteRequest         (RuntimeFacade returns UNAVAILABLE)
2. Scheduler: drain queues with graceTimeout (default 5 s); reject enqueues
3. Cancel in-flight runs                     (cooperative cancel → forced after 2 s)
4. Flush AuditLog                             (await channel drain; ≤20 ms budget)
5. Release plugin classloaders                (per §16 unload)
6. Release subsystems / close store
```

**Foreground-service pin state machine** (Android; see [01 §7.3](./01-architecture.md)):

```text
runStarted (active runs: 0 → 1)  → startForeground(notification, cancelAction)
runEnded   (active runs: 1 → 0)  → stopForeground(after short grace, to avoid flapping)
```

The Runtime MUST NOT bind to the foreground service lifetime of individual plugins — only to the aggregate "any run active" predicate.

---

## 4. Public Runtime API (Logical)

> `McosRuntime` is the **in-process implementation** of the `RuntimeFacade` interface defined normatively in [01 §7.2](./01-architecture.md). In MVP (P1, single-process) the App holds a direct `McosRuntime` reference; in V1 (multi-process) an AIDL delegate proxies the same surface across the `:runtime` process boundary (see [01 §7.3](./01-architecture.md) for the `IRuntimeService.aidl` method-to-AIDL mapping). The `execute` / `preview` / `cancel` / `observe` methods below correspond 1:1 to `RuntimeFacade`.

```kotlin
interface McosRuntime {
    suspend fun execute(request: ExecuteRequest): ExecuteHandle
    suspend fun preview(request: ExecuteRequest): PreviewResult   // parse+auth dry-run
    fun cancel(runId: RunId)
    fun observe(runId: RunId): Flow<RuntimeEvent>

    fun registry(): CommandRegistry
    fun permissions(): PermissionKernel
    fun memory(): MemoryFacade
    fun events(): EventBus
}
```

### 4.1 `ExecuteRequest`

> The full field set (including `Source`, `Payload`, `ConfirmationMode`, and the `correlationId`/`traceId` provenance fields) is normative in [01 §11.6](./01-architecture.md). The shape below is the short form.

```kotlin
data class ExecuteRequest(
    val source: Source,              // CLI, CHAT, VOICE, EVENT, API
    val payload: Payload,            // DslText | IrJson | WorkflowRef
    val dryRun: Boolean = false,
    val confirmationMode: ConfirmationMode = ConfirmationMode.POLICY,  // POLICY | ALWAYS_CONFIRM | NEVER_CONFIRM (latter only for read)
    val correlationId: String? = null,
)
```

### 4.2 `RuntimeEvent`

`RuntimeEvent` is an **11-variant sealed class** defined normatively in [01 §11.5](./01-architecture.md) (`RunStarted`, `StepStarted`, `Progress`, `Artifact`, `Log`, `ConfirmationNeeded`, `StepSucceeded`, `StepFailed`, `RunSucceeded`, `RunFailed`, `RunCancelled`). This document does not re-declare it; implementations MUST use the architecture doc's definition. `observe(runId)` returns a cold `Flow<RuntimeEvent>` that completes when the run reaches a terminal state (`RunSucceeded` / `RunFailed` / `RunCancelled`).

**Run-event channel guarantees** (as implemented by `TypedEventBus`):

| Property | Rule |
|----------|------|
| **Isolation** | Each run's events live in a **per-run** stream with its own replay buffer (`RUN_REPLAY = 512`). A run's history can never be evicted by traffic from *other* runs — the failure mode that motivated per-run isolation. |
| **Early subscribers** | `execute()` returns its handle before the launched coroutine publishes `RunStarted`, so `observe()` on a not-yet-published run id **waits** for the first event rather than completing empty. |
| **Late subscribers** | Replays the run's buffered history, then follows live; completes at the terminal event. |
| **Retention** | Replay history is kept for the `MAX_RETAINED_FINISHED_RUNS = 128` most recently finished runs (FIFO). Observing an evicted finished run completes **empty** (tombstoned ids); observing an id older than the tombstone window behaves like a not-yet-started run. |
| **Backpressure** | Within a run's own buffer: drop-oldest. The publisher never blocks. |

---

## 5. Parser Subsystem

Responsibilities:

1. Lex DSL text  
2. Build invocation / sequence AST  
3. Encode/decode IR JSON  
4. Attach `dslVersion`  
5. Produce actionable `PARSE_ERROR` diagnostics (line/col)

Non-responsibilities:

- Resolving Memory refs (Executor / pre-exec expand stage)  
- Calling LLMs  
- Loading plugins  

**Strict mode:** unknown keys in IR objects → error.  
**Lenient mode (dev only):** may warn; never in production default.

### 5.1 Internal Pipeline Architecture

The `parser/` package is a four-stage pipeline. The **grammar** it implements (token table, ABNF, number/string bounds, error-location precision) is normative in [02 §6](./02-command-protocol.md); this section specifies the parser's *internal* structure, not the grammar.

```text
DSL text
   │
   ▼
┌──────────┐   tokens    ┌──────────┐   raw AST   ┌──────────┐  ExecutionIr  ┌──────────┐
│  Lexer   │ ──────────▶ │  Parser  │ ──────────▶ │ IR Codec │ ────────────▶ │ Canonical │
│ (tokens) │             │ (AST)    │             │ (mem)    │                │ izer      │
└──────────┘             └──────────┘             └──────────┘                └──────────┘
                              │                                                     │
                              ▼                                                     ▼
                        PARSE_ERROR (shape A, §02/8.3)                    canonical IR (hashable)
```

**Lexer.** Produces the token stream per [02 §6.6](./02-command-protocol.md). Whitespace/BOM rules per the same section. The lexer is the sole owner of line/column bookkeeping — every `PARSE_ERROR.location` originates here.

**Parser.** Recursive-descent over the token stream. Produces a **raw AST** (un-key-sorted, un-lowercased) which is then handed to the codec. v0.1 is **fail-fast**: the first syntax error aborts parsing and emits the single error envelope of [02 §8.3](./02-command-protocol.md) shape A. Multi-error collection is a future extension (non-normative; flagged v0.2) — the conformance fixtures (`docs/fixtures/06-08`) only assert single-error envelopes, so fail-fast is the conformant behavior.

**IR Codec (`ExecutionIr`).** The wire JSON shape is [02 §7](./02-command-protocol.md); the in-memory Kotlin type is distinct so the Runtime can carry provenance without serializing it:

```kotlin
sealed class ExecutionIr {
    abstract val dslVersion: String
    abstract val meta: IrMeta?            // §02/8.2 provenance; null for non-llm sources

    data class Invoke(
        override val dslVersion: String,
        val id: String,                   // pre-canonicalization: may be mixed-case
        val args: JsonObject,             // un-sorted at this stage
        override val meta: IrMeta? = null,
    ) : ExecutionIr()

    data class Sequence(
        override val dslVersion: String,
        val steps: List<Invoke>,          // order preserved (semantically meaningful)
        override val meta: IrMeta? = null,
    ) : ExecutionIr()

    data class WorkflowRef(
        override val dslVersion: String,
        val workflowId: String,
        val body: JsonObject,             // Workflow IR; opaque to this layer (see 05-workflow.md)
        override val meta: IrMeta? = null,
    ) : ExecutionIr()
}
```

**Canonicalizer.** Applies the recursive algorithm of [02 §7.5](./02-command-protocol.md) — lowercase `id`, recursively sort object keys (arrays NOT sorted; `meta` NOT sorted), normalize numbers per schema. Output is the **canonical IR** whose UTF-8 JSON bytes are what audit records and pinned workflows hash (SHA-256).

**Codec symmetry contract (round-trip guarantee).** Parsing `input.dsl` and re-serializing the canonical IR MUST produce byte-identical output to the corresponding `expected.ir.json` in [`docs/fixtures/`](../fixtures/). This is the conformance assertion of [02 §16.1](./02-command-protocol.md). The codec is therefore symmetric: `serialize(canonicalize(parse(text))) == expected` for every positive fixture, and `parse(text)` yields the matching `PARSE_ERROR` for every negative fixture.

**Sugar macro registry (Stage 4 hook).** The parser does NOT expand sugar — that is [Stage 4 — Expand](./01-architecture.md) in the execution pipeline. The Runtime maintains a small, built-in sugar table consulted at Stage 4:

| Sugar | Expansion | Owner |
|-------|-----------|-------|
| `date="today"` / `"yesterday"` / relative dates | RFC 3339 timestamp range | Runtime (hardcoded; P1) |
| `x-mcos-ref` fields | `MemoryFacade.resolveRef()` → concrete id | Memory facade ([07 §6](./07-memory.md)) |
| `x-mcos-default-from-memory` | injected from Memory if arg absent | Memory facade |

Plugin-declared custom sugar is a **future extension** (non-normative; v0.2) — v0.1 sugar is entirely Runtime-built-in. The parser must leave sugar tokens verbatim in the AST; expansion happens after Registry resolution so the sugar table can be schema-aware.

---

## 6. Command Registry

### 6.1 Responsibilities

- Index descriptors by command ID  
- Track plugin ownership and versions  
- Serve Planner tool schemas (filtered views)  
- Hot-reload on plugin install/uninstall  

### 6.2 Resolution Policy

```text
request id + optional version range
  → exact match preferred
  → else highest compatible minor/patch for same major
  → else UNKNOWN_COMMAND
```

Pinned workflows **SHOULD** store resolved versions for reproducibility.

### 6.3 Registry Views

| View | Consumer |
|------|----------|
| Full | Developer tools |
| User-enabled | Planner + CLI completion |
| Policy-allowlisted | Enterprise mode |

Planner never sees disabled or disallowed commands.

### 6.4 Internal Data Structures

The Registry maintains three in-memory indexes over the same `CommandDescriptor` set (descriptor schema is normative in [02 §8](./02-command-protocol.md) + [01 §10](./01-architecture.md)):

| Index | Key | Structure | Serves |
|-------|-----|-----------|--------|
| **by-id** | `commandId` (lowercase) | `HashMap<String, SortedSet<DescriptorEntry>>` | Stage 3 Resolve (primary lookup) |
| **by-alias** | `alias` (lowercase) | `HashMap<String, String>` → primary id | Stage 3 alias resolution |
| **by-namespace** | namespace prefix (e.g. `camera`, `sys`) | Trie (prefix tree) | Planner autocomplete / "list all in `camera.*`" |

`DescriptorEntry` wraps a `CommandDescriptor` with its `pluginId` and installed `version`. The `SortedSet` per command-id holds **all installed versions** of that id, ordered by SemVer descending — this is the data structure that makes version coexistence ([02 §4.4](./02-command-protocol.md)) concrete.

**Version selection algorithm** (Stage 3 Resolve):

```text
resolve(requestedId, versionRange?):
  entries = by-id[requestedId.lowercase()]      # may walk alias map first
  if entries == null: → UNKNOWN_COMMAND (details.requestedId, optional suggestions from by-namespace trie)
  candidates = entries.filter(e -> versionRange.compatible(e.version))
  if candidates.empty: → UNKNOWN_COMMAND (reason: "no_compatible_version")
  return candidates.first()                       # SortedSet is desc by SemVer → highest compatible
```

"Compatible" = same major, minor/patch ≥ requested minimum. Pinned workflows store the exact resolved `version` so re-resolution is deterministic across reinstalls.

**Registry views** (§6.3) are computed lazily over `by-id`: the "User-enabled" view filters out descriptors whose plugin is disabled; the "Policy-allowlisted" view further intersects with `RuntimeConfig.enterpriseAllowlist`. Views are recomputed on plugin install/unload and on `enterpriseAllowlist` hot-reload (§19.1).

### 6.5 Hot-Reload Mechanics

Plugin install and unload are NOT instantaneous — they must coordinate with in-flight runs.

**Install:**

```text
1. Discover manifest (classpath / download dir / sideload APK — see §16)
2. Verify signature                              (§16; marketplace key or built-in trust)
3. Namespace-conflict arbitration                (02 §4.4 priority table; loser → load denied + details)
4. Register descriptors into by-id / by-alias / by-namespace
5. Bind handlers (in-process singleton or remote Binder)
6. Emit RegistryChanged event
7. Audit: plugin.installed
```

**Unload:**

```text
1. Mark plugin STOPPING → reject new invokes targeting its commands (Stage 3 → UNKNOWN_COMMAND, reason "unloading")
2. Drain in-flight: wait up to graceTimeout (default 5 s) for runs referencing this plugin to finish
3. After grace: force-cancel remaining runs (cooperative → forced per §9.4) → they emit RunFailed(CANCELLED)
4. Unregister descriptors from all three indexes
5. Release plugin classloader (§16)
6. Emit RegistryChanged event
7. Audit: plugin.uninstalled
```

**Pinned workflow referencing an unloaded descriptor:** if a pinned workflow (stored with a resolved version) is later executed after its plugin was unloaded, Stage 3 Resolve returns `UNKNOWN_COMMAND` with `details.reason = "plugin_unloaded"` and `details.requestedId`. The UI layer SHOULD surface this as "the plugin providing `<command>` was removed — reinstall or choose another." The Runtime does NOT silently fall back to a different plugin's same-named command.

---

## 7. Permission Kernel

Detailed model in [08-security.md](./08-security.md). Runtime duties:

```text
for each invocation:
  required = descriptor.permissions ∪ plugin.permissions
  missing = required - grants
  if missing: emit ConfirmationNeeded or fail PERMISSION_DENIED
  if sideEffect needs confirm: emit ConfirmationNeeded
  else: stamp auth token onto ExecutionContext
```

Auth tokens are **short-lived, run-scoped**, not forgeable by plugins.

---

## 8. Scheduler

### 8.1 Queues

| Queue | Use |
|-------|-----|
| `interactive` | User-facing CLI/chat (low latency) |
| `workflow` | Multi-step jobs |
| `background` | Event-triggered / deferred |
| `expedited` | User-confirmed safety-critical cancellations only |

### 8.2 Concurrency

Default policies (tunable):

- Max parallel invocations globally: `4`  
- Max parallel per plugin: `2`  
- Max parallel `destructive`: `1`  
- IoT control commands: serial per device id  

### 8.3 Cancellation

```text
cancel(runId)
  → mark run CANCEL_REQUESTED
  → cancel coroutine jobs
  → best-effort plugin cancel()
  → audit RunCancelled
```

### 8.4 Fairness & Backpressure

Each of the four queues (§8.1) is a **bounded `Channel<Runnable>`** (capacity 64). When a queue is full, Stage 7 (Schedule) rejects the enqueue and returns `RATE_LIMITED` with `details.retryAfterMs` (exponential backoff: first rejection 500 ms, doubling per repeated rejection of the same run, capped at 30 s).

**Within-queue ordering:** FIFO by default. The `expedited` queue is the only one that supports **preemption-style priority**: a cancel enqueued on `expedited` is pulled before older `interactive`/`workflow`/`background` items **only when** the global concurrency cap has no free slot — it does not preempt a running handler (cancellation is cooperative, per §8.3 / §9.4). Only cancellation run-requests may use `expedited`; any other command type enqueued there is a `PARSE_ERROR`-class configuration bug (rejected at Stage 7 with `INTERNAL`).

**Cross-queue fairness:** no strict priority across queues — each queue has a dedicated worker pool sized by its concurrency cap, so a saturated `background` queue cannot starve `interactive`. The global cap (4) is enforced by a shared semaphore acquired before dispatch and released on handler completion.

**Observability:** queue depth and semaphore wait-time are exposed as Runtime metrics (log + optional metric sink). Sustained depth > 32 (half capacity) triggers a `SchedulerBackpressure` log event so the UI can surface "system busy."

### 8.5 Deadlock Prevention (Per-Device Serial)

The "IoT control serial per device id" rule (§8.2) is enforced by a `Mutex<DeviceId>` map: a `control`/`destructive` command targeting device `D` acquires `mutex[D]` before handler dispatch and releases it on completion. This prevents two concurrent commands from racing the same physical device.

**Deadlock risk:** a Workflow that invokes `control` on device A, then (while still holding A) invokes `control` on device B which is itself waiting on A, would deadlock. MCOS prevents this with a **strict no-nested-acquisition rule**:

- A Workflow step MAY declare `requiresDevices: ["device-A"]` (resolved from args by the Runtime at Stage 4 Expand, via the device-id field).
- The Runtime acquires all declared device mutexes **atomically** (sorted lock ordering) at Stage 7, before dispatch.
- A Workflow run **MUST NOT** hold a device mutex across a step boundary into a step that acquires a different device. The Runtime detects a nested-acquisition attempt (a second `requiresDevices` while one is already held by the same `runId`) and rejects it with `CONFLICT` (`details: { heldDevice, requestedDevice, runId }`).

This makes per-device locking **leveled** (acquire-all-at-once, sorted) rather than incremental, which is the standard deadlock-free locking discipline. Workflows that genuinely need to act on two devices must declare both in a single step's `requiresDevices`.

---

## 9. Executor

### 9.1 ExecutionContext

```kotlin
data class ExecutionContext(
    val runId: RunId,
    val stepId: StepId?,
    val commandId: String,
    val args: JsonObject,          // validated
    val auth: AuthStamp,
    val deadline: TimeMark,
    val progress: ProgressEmitter,
    val services: HostServices,    // limited facade
)
```

Plugins receive **only** `HostServices` allowed by their manifest — not a raw `Context` god-object in the long term. MVP may pass Android `Context` with lint discipline; V1 tightens this.

### 9.2 Dispatch

```text
Registry.resolve(commandId)
  → PluginManager.lookup(pluginId)
  → handler.invoke(ctx)
  → map outcomes to RuntimeEvent
```

> The `PluginHost` name historically used in [04 §6](./04-plugin-sdk.md) for the plugin-facing service facade is standardized as **`HostServices`** in [01 §11.1](./01-architecture.md). `HostServices` is what the Executor injects into `ExecutionContext.services`; `PluginManager` (above) is the internal handle the Executor uses to find the handler instance. They are different objects — do not conflate.

### 9.3 Failure Mapping

Unhandled exceptions → `PLUGIN_ERROR` with sanitized message.  
Stack traces only in developer mode audit channel. The full exception → error-code mapping is normative in [01 §10.3](./01-architecture.md) (`Throwable.toMcosError()`); the per-code `details` schema is [02 §8.3](./02-command-protocol.md) shape B.

### 9.4 Dispatch Lifecycle (Timeout & Cooperative Cancel)

The Executor enforces the descriptor `timeoutMs` ([02 §8](./02-command-protocol.md), default 60000) via structured concurrency. The full cancel/timeout semantics table is in [01 §8.2](./01-architecture.md); the Runtime-specific lifecycle is:

```text
handler.invoke(ctx) with withTimeout(timeoutMs):
  ├─ normal return     → CommandResult.Ok → Stage 9 (ValidateOutput)
  ├─ handler throws    → toMcosError() → CommandResult.Err
  ├─ timeout fires     → cooperativeCancel() then await up to cancelGraceMs (default 2000)
  │      ├─ handler returned within grace → TIMEOUT (clean)
  │      └─ grace expired → job.cancel() (forced) → TIMEOUT; mark plugin "cancel-unresponsive"
  └─ external cancel   → same cooperative-then-forced path, emit RunCancelled
```

**Watchdog for non-yielding plugins.** A plugin that repeatedly ignores cooperative cancellation (the forced-cancel path fires) is tracked. Three forced-cancels within 60 s trip the **circuit breaker** ([01 §15.3](./01-architecture.md)): the plugin is marked unhealthy, a 30 s cooldown is imposed during which Stage 8 returns `UNAVAILABLE` for that plugin, and the user is notified. Persistent trips escalate to auto-unload (§16).

**Activity-result bridging.** Some handlers ([04 §7.2](./04-plugin-sdk.md)) need an Android `Activity` result (e.g. camera capture via `ACTION_IMAGE_CAPTURE`). The Runtime bridges this: the plugin calls `ctx.services.ui().startActivityForResult(intent)` which **suspends the handler coroutine**; the Runtime registers an `onActivityResult` callback; when the OS delivers the result, the coroutine resumes with the `Intent` extras. This keeps the handler a pure suspend function with no callback threading visible to the plugin author. The `timeoutMs` deadline continues to run across the activity-result suspension — a user who never returns to the activity will eventually trip `TIMEOUT`.

### 9.5 `McosException` (Plugin-Declared Error Channel)

[01 §10.3](./01-architecture.md) references `McosException` as the exception type plugins throw to declare a specific error code; it is defined here:

```kotlin
package com.morainet.mcos.sdk

data class McosException(
    val code: String,            // MUST be a valid McosErrorCode (01 §15.1) or plugin-namespaced code
    override val message: String,
    val retryable: Boolean = false,
    val details: JsonObject = JsonObject(emptyMap()),  // adheres to 02 §8.3 shape B for the code
) : RuntimeException(message)
```

**Mapping rule:** when a handler throws `McosException`, the Executor maps it **directly** — `error.code = exception.code`, `error.details = exception.details`, `error.retryable = exception.retryable`. It does NOT pass through the generic `Throwable.toMcosError()` heuristic. This is the **only** channel by which a plugin can emit a non-`PLUGIN_ERROR` code (e.g. a camera plugin throwing `McosException("UNAVAILABLE", "camera hardware busy", retryable=true)`).

Any other `Throwable` from a handler → `PLUGIN_ERROR` via the generic mapping (`message` sanitized, no raw stack in non-dev audit). Plugins SHOULD prefer throwing `McosException` for expected failure modes and let unexpected exceptions surface as `PLUGIN_ERROR`.

---

## 10. Workflow Integration

Runtime embeds the Workflow Engine (see [05-workflow.md](./05-workflow.md)):

- Sequence sugar from multi-line DSL becomes a trivial workflow  
- Parallel / conditional / retry live in Workflow IR  
- Each node execution re-enters Permission Kernel + Executor  

Runtime owns **transactions** only as far as compensating actions declared by workflows — not distributed 2PC across IoT vendors.

---

## 11. Event Bus

### 11.1 Event Envelope

```json
{
  "type": "connectivity.wifi.connected",
  "timestamp": "2026-08-06T14:00:00+08:00",
  "payload": { "ssid": "Office", "bssid": "..." },
  "source": "sys.connectivity"
}
```

### 11.2 Subscriptions

Workflows and plugins can subscribe with filters:

```json
{
  "typePrefix": "connectivity.wifi.",
  "where": { "ssid": "Office" }
}
```

**Safety:** Event→action rules require the same permissions as manual invoke. Silent privilege escalation via events is forbidden.

### 11.3 Built-in Event Sources (MVP+)

| Source | Examples |
|--------|----------|
| Power | `battery.low`, `battery.charging` |
| Connectivity | `wifi.connected`, `wifi.disconnected` |
| Notification | `notify.posted` (opt-in listener) |
| Location | `location.significant_change` (opt-in) |
| Time | `time.schedule` |
| Plugin custom | namespaced under plugin id |

### 11.4 Delivery Semantics

| Property | Rule |
|----------|------|
| **Subscription match** | `typePrefix` is a **string prefix** match on `event.type` (e.g. `"connectivity.wifi."` matches `"connectivity.wifi.connected"`). `where` is **deep equality** — recursive object comparison; every key in the filter must exist in `event.payload` with an equal value (extra payload keys are ignored). No wildcards, no regex, no JSONPath. |
| **Delivery guarantee** | **At-most-once** (in-process). The EventBus does not persist events; a subscriber that is not registered at fire time misses the event. There is no redelivery. |
| **Subscriber isolation** | Each subscriber runs under a child `SupervisorJob` of the EventBus scope. A subscriber throwing does NOT terminate sibling subscribers or the bus — the exception is logged and audited as a `SubscriberError` warn, and delivery to other subscribers proceeds. |
| **Ordering** | Events from a **single source** are delivered FIFO. Events from **different sources** have no defined cross-source ordering — subscribers MUST NOT assume global ordering. |
| **Backpressure** | Each subscriber drains its events through a `Channel.BUFFERED` (capacity 64). If the subscriber cannot keep up and the channel is full, the **oldest undelivered event is dropped** and a `BackpressureDrop` audit warn is recorded. The publisher is never blocked. Subscribers that need lossless processing MUST consume promptly or use a Workflow `wait_event` node (which has its own queue). |
| **Cross-process (V1)** | The EventBus lives in the `:runtime` process. Subscribers in plugin processes receive events via an AIDL callback interface (`IEventListener.onEvent(EventEnvelope)`), registered through `RuntimeFacade.subscribe`. Binder death of a remote subscriber auto-unsubscribes it. Delivery to remote subscribers inherits the same at-most-once + drop-on-backpressure semantics (no distributed queue). |

---

## 12. Memory Facade

Runtime exposes Memory to Planner/Workflow via a narrow API:

- `get(key)` / `put(key, value, policy)`  
- `resolveRef(ref)` for `x-mcos-ref`  
- `search(query)` for semantic recall  

Storage engine details: [07-memory.md](./07-memory.md).

---

## 13. Audit Log

### 13.1 Record Shape

```json
{
  "runId": "run_abc",
  "timestamp": "...",
  "source": "CHAT",
  "ir": { "...": "canonical IR redacted" },
  "steps": [
    {
      "commandId": "camera.capture",
      "auth": { "grantsUsed": ["CAMERA"] },
      "result": { "ok": true },
      "durationMs": 900
    }
  ]
}
```

### 13.2 Properties

- Append-oriented local store (Room / SQLCipher recommended)  
- Secret fields redacted  
- Exportable by user  
- Optional remote sync only with explicit opt-in  

### 13.3 Storage Schema & Redaction

**Storage schema** (Room, encrypted via SQLCipher). A single append-only table:

```sql
CREATE TABLE audit (
  run_id       TEXT    NOT NULL,
  ts           INTEGER NOT NULL,   -- epoch millis
  source       TEXT    NOT NULL,   -- CLI | CHAT | VOICE | EVENT | API
  ir_redacted  TEXT    NOT NULL,   -- canonical IR JSON, secrets redacted
  steps_json   TEXT    NOT NULL,   -- array of per-step records (commandId, auth, result, durationMs)
  PRIMARY KEY (run_id)
);
CREATE INDEX idx_audit_ts     ON audit(ts DESC);
CREATE INDEX idx_audit_source ON audit(source);
```

Writes go through a **single-writer** coroutine (`Dispatchers.IO.limitedParallelism(1)`, [01 §8](./01-architecture.md)) fed by a channel; this guarantees ordered, non-blocking writes that never stall the success path (the >20 ms budget of §17 is met by offloading).

**Redaction walk algorithm.** Before an IR is stored, the Runtime recursively walks `ir.args` (and `steps[*].args`) applying these rules:

1. Any field whose schema declares `x-mcos-secret: true` ([02 §5.3](./02-command-protocol.md)) → value replaced with `"***REDACTED***"`.
2. Any field named `password`, `token`, `secret`, `apiKey`, `credential` (case-insensitive) AND not already marked → same replacement (defense-in-depth; schema marker is preferred).
3. Artifact URIs: the query string is stripped (e.g. `content://media/...?auth=abc` → `content://media/...`); scheme + authority + path are preserved for forensics.
4. `meta` provenance fields (`source`, `confidence`, `utteranceId`, `correlationId`, `traceId`) are **never** redacted — they are not user data.

The walk is deterministic and runs once per run, at Stage 10 (Audit), on a copy of the canonical IR (the in-flight `ExecutionContext.args` is untouched).

**Retention policy.** Default: **30-day TTL** + **10,000-record cap**, whichever is hit first (oldest evicted). Both are tunable via `RuntimeConfig` (§19). The user MAY manually clear all audit records from Settings; the Runtime MUST NOT auto-sync cleared records to any remote store. Enterprise mode with `auditFailClosed = true`: if a Stage-10 write fails (e.g. disk full), the **run itself fails** with `INTERNAL` rather than silently dropping the audit record.

**Export format.** JSONL — one JSON object per line, each line a full audit record (the §13.1 shape). Export is user-initiated via `RuntimeFacade.exportAudit(range?)` and written to a user-chosen URI. Export is **optionally signed**: HMAC-SHA256 over the JSONL bytes, keyed by a device-bound key in the Android Keystore, with the signature appended as a trailing `{"signature": "...", "algorithm": "HMAC-SHA256"}` line. This lets a downstream consumer verify the export was not tampered with, without the Runtime claiming a CA-style attestation.

---

## 14. Planner Bridge

Runtime **does not** embed vendor SDKs for OpenAI/etc.

```kotlin
interface PlannerBridge {
    suspend fun compile(goal: GoalRequest): CompileResult
}
```

`CompileResult` must be DSL/IR/Workflow — never a plugin call list outside the protocol.

### 14.1 Repair-Loop Contract

When the Planner emits an IR that fails Runtime validation (parse or schema), the Runtime does NOT execute it. Instead it returns a structured error to the bridge so the Planner can self-correct. The planner-side loop is specified in [06 §7](./06-agent.md); the Runtime-side contract is:

```kotlin
sealed class CompileResult {
    data class Ok(val ir: ExecutionIr, val warnings: List<String>) : CompileResult()
    data class Repair(val errors: List<ValidationError>) : CompileResult()   // ← re-prompt the planner
    data class Clarify(
        val question: String,
        val options: List<ClarifyOption>? = null,   // renders as option cards in the UI
        val slots: List<ClarifySlot>? = null,        // structured slot-fill prompts
    ) : CompileResult()
    data class Refuse(
        val reason: String,
        val category: RefuseCategory,                // why the plan was refused
        val suggestions: List<String>? = null,        // alternative approaches, if any
    ) : CompileResult()
}

data class ClarifyOption(val label: String, val value: String, val description: String? = null)
data class ClarifySlot(val name: String, val type: String, val required: Boolean)
enum class RefuseCategory { POLICY, IMPOSSIBLE, QUOTA, CAPABILITY }

data class ValidationError(
    val path: String,        // JSON-pointer into the IR, e.g. "/args/uris/0"
    val expected: String,    // type or constraint, e.g. "string" or "maxLength 65536"
    val actual: String,      // what was found, e.g. "number 80"
    val code: String,        // the McosErrorCode this would map to, e.g. "SCHEMA_VIOLATION"
)
```

`CompileResult` is the **normative definition source** for these shapes; [06 §5](./06-agent.md) and [06 §6](./06-agent.md) reference it without redefining. The structured `Clarify`/`Refuse` payloads align MCOS with mainstream AI-agent practice so the UI can render option cards (`ClarifyOption`) or slot-fill forms (`ClarifySlot`) and classify a refusal by `RefuseCategory` rather than free-text parsing.

`ValidationError` field names are deliberately aligned with the `SCHEMA_VIOLATION.details` shape of [02 §8.3](./02-command-protocol.md), so the same diagnostic reaches the user whether the failure originates from a typed `ExecuteRequest` or from a Planner repair cycle.

**Max attempts.** The Runtime permits `maxRepair = 2` for cloud providers and `maxRepair = 1` for on-device providers ([06 §6](./06-agent.md)). The Runtime counts Repair rounds per `utteranceId` ([02 §8.2](./02-command-protocol.md)); when the limit is exceeded the Runtime returns `Refuse("max_repair_exceeded")` which surfaces to the user as `COMPILE_FAILED` ([01 §15.1](./01-architecture.md)) — the Executor is never entered for a failed compile.

**Safety invariant.** Regardless of repair count, the Planner's output is **untrusted** ([06 §14](./06-agent.md)): it cannot expand grants, cannot bypass Stage 6 (Authorize), and cannot hide a `destructive` confirmation. A repaired plan pays the full Stage 3→10 cost on the final accepted IR exactly as a hand-typed DSL would.

---

## 15. Host Adapters

| Host | Notes |
|------|-------|
| Android | Foreground service for long workflows; Process lifecycle; Binder API to UI |
| JVM unit test | Fake clock, fake permissions, in-memory registry |
| Desktop (future) | Optional; not V1 scope |

### 15.1 Android Foreground Policy

- Interactive runs: tied to UI visibility where possible  
- Background workflows: foreground service + notification with cancel action  
- Exact alarms / location: only if plugin permissions + user enablement allow  

---

## 16. Plugin Loading

The Registry/Loader cooperates on the full plugin lifecycle. The high-level load/unload flow (kept for continuity) is expanded with discovery, verification, isolation, and failure-escalation mechanics below.

```text
Discover manifests
  → verify signature (marketplace) / trust built-ins
  → register descriptors
  → bind handlers (in-process or remote Binder)
  → emit RegistryChanged event
```

Unload:

```text
Deny new invokes → wait in-flight (timeout) → unregister → release classloaders
```

(The unload drain mechanics are specified in detail in §6.5.)

### 16.1 Manifest Discovery

| Plugin class | Where the loader looks | Trust |
|--------------|------------------------|-------|
| **Built-in** | Classpath / module resources at `META-INF/mcos/plugin.json` | Trusted (shipped with Runtime) |
| **Marketplace** | Dedicated download dir (managed by marketplace client, [09](./09-marketplace.md)) | Verified (marketplace signature) |
| **Sideload** | User-selected APK/URI via Settings → "Install plugin" | Warned (debug only in production builds) |
| **Untrusted** | Anything failing signature verification | Blocked in production; dev-only with explicit flag |

Discovery runs at startup (§3.1 step 2) and again whenever `RegistryChanged` is triggered by install/uninstall. The loader deduplicates by `plugin.id` — the same id from two sources is a namespace conflict (§16.4).

### 16.2 Signature Verification

Marketplace plugins MUST carry a marketplace-issued signature ([09 §6](./09-marketplace.md)). The Runtime verifies the plugin package against the marketplace's public key before registering any descriptor. **Offline behavior:** the Runtime caches the verification result (key-id + plugin hash → verified-at timestamp) so a previously-verified plugin loads without re-contacting the marketplace. A plugin whose cached verification is older than the marketplace's revocation TTL is re-verified on next online opportunity; if it cannot be re-verified and the marketplace reports revocation, the plugin is unloaded per §6.5.

Built-in plugins skip verification (they ship in the Runtime's own signed APK). Sideload plugins in production builds are rejected outright; in debug builds they load with a persistent "unverified" warning surfaced to the user on every confirm.

### 16.3 Classloader Isolation

| Plugin class | Classloader | Rationale |
|--------------|-------------|-----------|
| Built-in | Runtime classloader (shared) | Trusted; needs direct access to Runtime internals |
| Marketplace / sideload | Dedicated `DexClassLoader` per plugin (parent = Runtime classloader) | Untrusted; must not leak classes into Runtime or sibling plugins |

**Duplicate-class rule:** a plugin's isolated classloader MAY load classes with the same FQ name as another plugin or the Runtime — there is no global "first class wins." Plugins resolve their own classes from their own loader first, then the Runtime parent. This means two plugins can each ship `com.example.Logger` without collision. The Runtime NEVER reflects into a plugin's classloader except to call the declared `entry` class.

**Release on unload:** when §6.5 drain completes, the loader drops its reference to the plugin's `DexClassLoader`, allowing GC. On Android, the associated optimized DEX (`odex`/`oat`) is left for the system to reclaim; the Runtime does not force-delete it.

### 16.4 Namespace Conflict Arbitration at Load Time

When two plugins attempt to register the same command id (or alias), the Runtime applies the priority table of [02 §4.4](./02-command-protocol.md):

```text
reserved roots  >  manifest-declared verified  >  first-to-load
```

The **loser** of the arbitration is rejected at load: its descriptors for the conflicting id are not registered, and the load emits a `RegistryChanged` event noting the conflict with `details: { requestedId, winningPlugin, losingPlugin, winningManifest }`. The losing plugin's **non-conflicting** commands still load normally — conflict is per-id, not per-plugin. Version coexistence ([02 §4.4](./02-command-protocol.md)) is distinct: the *same* plugin registering a different version of its own id is allowed (stored in the `SortedSet` per §6.4); cross-plugin same-id is a conflict.

### 16.5 Unload-on-Failure (Circuit Breaker Escalation)

The circuit breaker of [01 §15.3](./01-architecture.md) (3 forced-cancels / 60 s → 30 s cooldown) can escalate. If a plugin trips the breaker **3 times within 10 minutes** (i.e. sustained unhealthiness, not a transient blip), the Runtime auto-unloads it:

```text
plugin marked unhealthy (3rd breaker trip in 10 min)
  → SchedulerGate: reject all new invokes for plugin (UNAVAILABLE, retryable=false)
  → drain in-flight per §6.5 (grace 5 s)
  → unload classloader per §16.3
  → emit PluginAutoUnloaded event + audit
  → notify user: "Plugin <name> was disabled due to repeated failures. Re-enable?"
```

Re-enable is explicit (user action in Settings); it re-runs the full load flow (§16.1–16.4). The Runtime does NOT auto-reload a plugin it auto-unloaded — silent flapping is forbidden.

---

## 17. Performance Budgets

| Path | Budget |
|------|--------|
| Parse + validate local DSL | < 5 ms typical |
| Registry lookup | O(1) hash |
| Permission check (cached grant) | < 1 ms |
| Execute overhead excluding plugin | < 10 ms |
| Audit write (async ok) | must not block success path > 20 ms |

---

## 18. Failure Domains

| Failure | Runtime behavior |
|---------|------------------|
| Plugin exception | Fail step; continue workflow per policy |
| Plugin process death | `UNAVAILABLE`; mark plugin unhealthy |
| LLM timeout | Fail compile; no partial execute unless user asked streaming speculative plans (default off) |
| Disk full (audit) | Warn; configurable fail-closed for enterprise |

---

## 19. Configuration

```kotlin
data class RuntimeConfig(
    val maxParallel: Int = 4,
    val defaultTimeoutMs: Long = 60_000,
    val strictSchemaOutput: Boolean = false,
    val auditRedaction: RedactionLevel = RedactionLevel.DEFAULT,
    val eventTriggersEnabled: Boolean = true,
    val enterpriseAllowlist: Allowlist? = null,
    val networkAllowList: List<String> = emptyList(),   // host globs, e.g. "*.example.com"; enforced by Network Egress Policy ([08 §12](./08-security.md))
    val rateLimits: RateLimits = RateLimits(),           // [08 §10.1](./08-security.md)
    val scheduler: SchedulerConfig = SchedulerConfig(),  // [08 §10.1](./08-security.md)
    val userPolicy: UserPolicy = UserPolicy(),           // [08 §4.2](./08-security.md) user global tightening
)

data class RateLimits(
    val maxInvokesPerMinute: Int = 60,         // per-plugin
    val maxDestructivePerHour: Int = 5,        // per-plugin
    val maxBackgroundFiresPerHour: Int = 20,   // per-recipe
)

data class SchedulerConfig(
    val maxConcurrentInvokes: Int = 4,         // global
    val maxConcurrentPerPlugin: Int = 2,       // per-plugin
    val maxConcurrentDestructive: Int = 1,     // global (serial)
)

data class UserPolicy(
    val confirmEveryNetwork: Boolean = false,                 // [08 §4.2](./08-security.md) "Confirm every network call"
    val backgroundEventsRequireForeground: Boolean = false,   // "Background events require foreground confirm"
    val disableSessionGrants: Boolean = false,                // "Disable session grants"
)
```

### 19.1 Validation, Sources & Hot-Reload

**Validation rules** (applied at load; an invalid config is rejected with `INTERNAL` + `details.component = "config"` rather than silently coerced):

| Field | Constraint |
|-------|-----------|
| `maxParallel` | `∈ [1, 16]` |
| `defaultTimeoutMs` | `∈ [1000, 600000]` (1 s – 10 min) |
| `auditRedaction` | `∈ {DEFAULT, STRICT, OFF}` |
| `eventTriggersEnabled` | boolean (no range) |
| `enterpriseAllowlist` | if non-null: `auditRedaction` is force-upgraded to `STRICT` (enterprise audit cannot be weakened); allowlist entries must reference currently-registered command ids or load is deferred |

**Sources (precedence high → low):**

1. **MDM / enterprise push** — highest; can tighten but not loosen user settings (a user may always raise `auditRedaction` above what MDM mandates, never lower it).
2. **User Settings** (DataStore, per-device) — the default source for consumer builds.
3. **Built-in defaults** — the `= …` values in the data class above.

When sources disagree, the Runtime takes the **most restrictive** value for security-relevant fields (`auditRedaction`, `eventTriggersEnabled` when an enterprise says off) and the highest-precedence source's value otherwise.

**Hot-reload semantics:** configuration is NOT frozen at startup. Changes take effect as follows:

| Field changed | Effect |
|---------------|--------|
| `maxParallel` | Applies to **runs enqueued after** the change; in-flight runs are NOT interrupted (their semaphore was acquired under the old cap). |
| `auditRedaction` | Applies to **the next Stage-10 audit write**; already-stored records are NOT re-walked. |
| `eventTriggersEnabled` | **Immediate**: if flipped to false, armed event triggers stop firing; if flipped to true, they resume. No restart needed. |
| `enterpriseAllowlist` | **Immediate recompute** of the "Policy-allowlisted" Registry view (§6.3 / §6.4). Commands newly outside the allowlist become `UNKNOWN_COMMAND` at Stage 3 for new invokes; in-flight runs are allowed to finish. |
| `defaultTimeoutMs` / `strictSchemaOutput` | Apply to runs enqueued after the change. |

The Runtime emits a `ConfigChanged` audit event (always audited, per [08 §14](./08-security.md)) recording the before/after diff of security-relevant fields. Config changes are never silent.

---

## 20. Testing Matrix

| Test | Asserts |
|------|---------|
| Parser golden files | DSL ↔ IR |
| Auth matrix | Every sideEffectClass × grant state |
| Scheduler | Cancel mid-flight, fairness |
| Executor | Timeout, exception mapping |
| Event | Filter match → workflow start |
| Chaos | Plugin kill mid-invoke |

---

## 21. MVP Slice vs V1

| Feature | MVP | V1 |
|---------|-----|----|
| Parser + Executor + Registry | ✓ | ✓ |
| Permission prompts | ✓ | ✓ |
| Audit | basic | encrypted + export |
| Workflow | sequence only | full graph |
| Event bus | stub / few events | full |
| Multi-process | optional | recommended default |
| Enterprise allowlist | — | ✓ |

---

## 22. Summary

Runtime is the trustworthy middle:

- Speaks only **Command Protocol**  
- Enforces **permissions and policies**  
- Runs **workflows and events** without baking in domain logic  
- Remains **LLM-agnostic**

Next: how third parties extend the bus — [04-plugin-sdk.md](./04-plugin-sdk.md).
