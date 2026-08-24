# MCOS Vision

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-24  
> **Audience:** Contributors, partners, and anyone evaluating Mobile Command OS as infrastructure

---

## 1. One-Sentence Definition

**Mobile Command OS (MCOS)** turns the capabilities a phone exposes through cooperative APIs — system APIs, cooperating apps (via App Functions / Intents), IoT devices, and remote tools — into a uniform, AI-callable **command**.

```text
Natural language  →  Command DSL  →  Runtime  →  Plugins / System / MCP
```

MCOS is not a chatbot with shortcuts.  
It is a **Mobile Command Bus**: the missing layer between LLM planners and the real mobile world.

---

## 2. Why This Exists

### 2.1 The Tool Bus Landscape Today

| Layer | Example | What it unifies |
|-------|---------|-----------------|
| Code tools | Claude Code, Gemini CLI | Developer machine actions |
| Tool protocol | MCP (Model Context Protocol) | Desktop / server tool servers |
| App capability | Android App Functions | In-app callable functions |
| Mobile command (OS-integrated) | Google App Functions + Gemini; Apple App Intents + Apple Intelligence | OS-level command bus — but each locks into its own ecosystem |
| Mobile command (open standard) | **MCOS (this project)** | Open protocol + replaceable model + cross-vendor plugin ecosystem |

The industry already has:

- **Claude Code** ≈ Code Command Bus  
- **Gemini CLI** ≈ AI Command Bus  
- **MCP** ≈ Tool Bus  
- **Android App Functions** ≈ App Capability Bus  
- **Google (App Functions + Gemini)** / **Apple (App Intents + Apple Intelligence)** ≈ OS-integrated Mobile Command Bus — but closed within their own platforms

What is still missing:

> **An open, model-agnostic Mobile Command Bus standard** — a stable protocol and runtime so that AI can safely drive *any* phone (not just one vendor's ecosystem), with a replaceable model and a cross-platform plugin marketplace, the way MCP standardizes desktop/server tools.

**Honest acknowledgment:** Google and Apple are building OS-level command buses with structural advantages MCOS cannot match — pre-installed distribution, OS-level permissions, and the power to mandate app-vendor cooperation. MCOS is a sandboxed third-party app. The bet is not "we can out-build the OS vendors at their own game"; the bet is that **an open, cross-platform, model-replaceable standard** is a different value proposition than a locked-in OS feature — the same way the open web coexists with native platform APIs. If that bet is wrong, MCOS's ecosystem thesis fails regardless of code quality (see [10 §10](./10-roadmap.md) Risk Register).

### 2.2 The Problem With “Just Call Apps”

Direct approaches fail at scale:

1. **Intent / Deep Link chaos** — every app invents its own URL scheme and extras.  
2. **Accessibility scraping** — brittle, privacy-hostile, hard to version.  
3. **One-off Agent demos** — each product re-implements planner + permissions + tools.  
4. **No shared vocabulary** — models cannot learn a stable mobile command surface.

MCOS’s bet:

> **Standardize the command surface first. Keep the LLM replaceable.**

The moat is not a particular model. The moat is **Command Protocol + Runtime + Plugin ecosystem**.

---

## 3. Product Positioning

### 3.1 What MCOS Is

```text
                    Mobile Command OS

                    Natural Language
                           │
                ┌──────────┴──────────┐
                │                     │
              Voice                Command CLI
                │                     │
                └──────────┬──────────┘
                           │
                     LLM Planner
                           │
                  Command Compiler
                           │
               Command Bus (Runtime)
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
 Android System      Plugin SDK           MCP / Cloud
        │                  │                  │
 Intent            App Plugin           Remote Tools
 Accessibility     IoT Plugin
 App Functions     HTTP Plugin
 Deep Link
```

MCOS is:

- A **protocol** for describing mobile capabilities as commands  
- A **runtime** that parses, plans, schedules, executes, and audits those commands  
- A **plugin SDK** so third parties can expose capabilities without forking the OS  
- An **optional AI planner** that compiles language into DSL — never into ad-hoc side effects  

MCOS is **not**:

- A traditional Unix terminal on Android  
- A single vertical AI chat app  
- A replacement for the Android permission system  
- A closed agent that only works with one LLM vendor  

Analogy (intentionally strong):

> **Phone-side Kubernetes + MCP + Claude Code Runtime** — orchestration, tool protocol, and agent execution loop, adapted to mobile constraints (permissions, battery, offline, UI context).

### 3.2 Core User Promise

| Persona | Promise |
|---------|---------|
| End user | Speak or type a goal; MCOS compiles it into auditable commands and runs them with explicit consent. |
| Power user | Use a stable CLI/DSL (`home.scene.movie`, `camera.scan`) without waiting for UI clicks. |
| Plugin developer | Ship a `plugin.json` + handlers; appear in the Command Registry without touching Runtime. |
| Platform / OEM | Host a command bus that can integrate system, OEM apps, and partner IoT. |
| AI product team | Swap planners/providers; keep the same command surface and security model. |

### 3.3 Example Interactions

```text
> camera scan
> home movie
> github pr
> photo clean
```

Natural language equivalents:

```text
帮我打开空调
导航回公司
把今天拍的照片压缩一下发给 Tom
Wi‑Fi 连上公司网络后自动开 VPN
```

All of the above compile to **Command DSL**, then execute through Runtime.

---

## 4. Design Principles

> **Terminology note:** the principles below are numbered **Principle 1–8** — conceptual layers of the MCOS design philosophy. This is distinct from the implementation **phases** P1 (=MVP) / P2 (=V1) / P3 (=V2) used throughout the rest of the repository (see [10-roadmap §2.1](./10-roadmap.md)). The `P1`/`P2`/`P3` notation is reserved exclusively for roadmap phases to avoid ambiguity.

### Principle 1 — Protocol First

Define **Command Protocol** before UI polish.  
HTTP unified the Web; SQL unified data access; Git unified versioning.  
MCOS aims for the same role for **mobile capability**.

### Principle 2 — DSL Between AI and Side Effects

Never let the model directly poke Intent / Accessibility / Bluetooth.  

```text
User utterance → Planner → Command DSL → Runtime Executor → Plugin
```

Benefits: auditability, replay, permission checks, testing, and model portability.

### Principle 3 — Runtime Owns Safety

Permissions, rate limits, confirmation policies, and sandbox boundaries live in Runtime — not in each plugin’s goodwill.

### Principle 4 — Plugins Own Domain Logic

Camera, Home Assistant, GitHub, WeChat bridges, etc. are plugins.  
Runtime stays thin; capability grows at the edge.

### Principle 5 — AI Is a Provider

```kotlin
interface AIProvider {
    suspend fun plan(...): Plan
    suspend fun chat(...): ChatResult
    suspend fun toolCall(...): ToolCallResult
    suspend fun embed(...): Embedding
}
```

OpenAI, Gemini, Qwen, DeepSeek, Claude, on-device MLC-LLM — all interchangeable.

### Principle 6 — Events Are First-Class

Mobile’s advantage is continuous context: battery, location, notifications, connectivity.  
Workflows can be triggered by events, not only by chat.

### Principle 7 — Open Ecosystem

Apache-style openness: open protocol, open SDK, open marketplace APIs.  
Prefer many plugins over one megapp.

### Principle 8 — Offline / On-Device Reality

Assume intermittent network, battery limits, and privacy expectations.  
Local execution and local memory are default; cloud is sync/enhancement.

---

## 5. Repository Topology

> ✅ **Implementation status:** P1 (MVP) and most of P2 are delivered — the topology below is the **actual** repository layout (12 Gradle source modules incl. `mcos-server`), not a target. Phase-by-phase history: [10-roadmap.md](./10-roadmap.md); per-subsystem status: [11-implementation-status.md](./11-implementation-status.md) §3.

```text
mcos/
├── mcos-android          # Jetpack Compose client (CLI + Chat + Store + Settings)
├── mcos-runtime          # Parser, Registry, Executor, Workflow, Memory, EventBus, Audit
├── mcos-sdk              # Plugin SDK (manifest, command handlers, permission declarations)
├── plugins/
│   ├── mcos-plugin-hello     # Reference sample (hello.world)               ← P1
│   ├── mcos-plugin-system    # System / Intent / Notification plugins       ← P1
│   ├── mcos-plugin-camera    # Camera plugin                                ← P1
│   ├── mcos-plugin-files     # Files / media plugin                         ← P1
│   ├── mcos-plugin-iot       # Home Assistant / Tuya / Matter bridges       ← P2
│   └── mcos-plugin-mcp       # MCP client adapter                           ← P2 spike / P3 production
├── mcos-server           # Sync, marketplace, config (Spring Boot or Go)    ← P3
└── docs                  # This architecture & RFC set (exists today)
```

> For the per-module dependency graph, package names, and build coordinates, see [REPOSITORIES.md](./REPOSITORIES.md). For implementation phasing, see [11-implementation-status.md](./11-implementation-status.md).

Logical stack:

```text
App  →  Runtime  →  Plugin SDK  →  Plugins  →  (optional) Cloud
```

---

## 6. Non-Goals (v1)

To keep the project honest:

1. **Not** a full Android OS fork or custom ROM requirement.  
2. **Not** unrestricted Accessibility automation marketed as “universal RPA”.  
3. **Not** a guarantee that every third-party app can be driven without that app’s cooperation.  
4. **Not** a single cloud lock-in for LLM or marketplace.  
5. **Not** a desktop-first MCP clone that ignores mobile permission UX.

Cooperative plugins and official App Functions / Intent bridges are preferred over fragile UI automation. Accessibility-based bridges, if any, are opt-in, heavily gated, and documented as fragile.

---

## 7. Success Metrics

### 7.1 Technical

| Metric | Target (V1) |
|--------|-------------|
| Stable command IDs in core registry | ≥ 50 documented commands |
| End-to-end DSL execute latency (local plugin, P50) | < 200 ms excluding device I/O |
| Permission denial correctness | 100% blocked when grant missing |
| Planner → DSL parse success on golden set | ≥ 90% |
| Plugin load / unload without Runtime restart | Required |

### 7.2 Ecosystem

| Metric | Target |
|--------|--------|
| External plugins publishable via SDK | Yes |
| MCP tools usable as commands | Yes (adapter) |
| Multi-LLM provider support | ≥ 3 providers |

### 7.3 User Value

| Scenario | Definition of done |
|----------|--------------------|
| CLI power use | User completes multi-step home / media / file tasks via DSL without UI hunting |
| Voice goal | Single utterance → confirmed plan → executed workflow |
| Event automation | At least 3 event→workflow recipes (Wi‑Fi, battery, notification) |

---

## 8. Relationship to Adjacent Standards

| Standard | Relationship |
|----------||----------------|
| **MCP** | First-class adapter: MCP tools map into Command Registry. |
| **Android App Functions** | Preferred high-quality bridge for cooperating apps. |
| **Intents / Deep Links** | Supported via system plugins; wrapped as commands. |
| **Matter / Home APIs / vendor IoT SDKs** | Exposed through IoT plugins, not hard-coded in Runtime. |
| **OpenAI tool calling / etc.** | Used inside AI Provider; output constrained to Command DSL. |

MCOS does not replace these standards. It **composes** them under one command bus.

---

## 9. Naming

| Term | Meaning |
|------|---------|
| **MCOS** | Mobile Command OS — the project |
| **Command** | A versioned, namespaced capability ID with typed args |
| **Command DSL** | Text / AST form AI and humans use to invoke commands |
| **Runtime** | Process that validates, authorizes, schedules, and executes |
| **Plugin** | Packaged set of commands + handlers + permissions |
| **Workflow** | Graph of steps (seq / parallel / condition / retry) |
| **Planner** | AI (or rules) that compile goals into DSL / workflows |
| **Memory** | Durable user / device / preference context for planning |
| **Marketplace** | Distribution channel for signed plugins |

---

## 10. Document Map

This vision is chapter `00`. The full design set:

| Doc | Title |
|-----|-------|
| [00-vision.md](./00-vision.md) | Project vision (this file) |
| [01-architecture.md](./01-architecture.md) | System architecture |
| [02-command-protocol.md](./02-command-protocol.md) | Command Protocol RFC |
| [03-runtime.md](./03-runtime.md) | Runtime design |
| [04-plugin-sdk.md](./04-plugin-sdk.md) | Plugin SDK |
| [05-workflow.md](./05-workflow.md) | Workflow engine |
| [06-agent.md](./06-agent.md) | AI Planner / Agent |
| [07-memory.md](./07-memory.md) | Memory system |
| [08-security.md](./08-security.md) | Permissions & security |
| [09-marketplace.md](./09-marketplace.md) | Plugin marketplace |
| [10-roadmap.md](./10-roadmap.md) | MVP → V1 roadmap |

---

## 11. Call to Contributors

If you care about **protocol**, **runtime**, **SDK**, or **security**, start there — not with another chat skin.

Priority contribution areas:

1. Command Protocol schemas & golden test suites  
2. Runtime executor & permission kernel  
3. First-party plugins (system, camera, files)  
4. MCP adapter fidelity  
5. On-device / privacy-preserving planner paths  

---

## 12. Summary

**MCOS = Mobile Command Bus.**

- AI generates **commands**, not opaque side effects.  
- Runtime **executes and polices** those commands.  
- Plugins **extend** the surface without forking the core.  
- The long-term asset is the **Command Protocol** and the ecosystem that speaks it.

> Make every cooperative capability on the phone a command that AI can call — safely, audibly, and interchangeably.
