# MCOS — Mobile Command OS

> **Make every cooperative capability on the phone a command that AI can call — safely.**

MCOS is an open design for a **Mobile Command Bus**: a typed Command Protocol, on-device Runtime, Plugin SDK, and optional AI planner that compiles natural language into auditable DSL — not opaque side effects.

## Status

**Phase 1 — core runtime implemented (Draft v0.1.0). Phase 2 — workflow engine, typed event bus, memory enhancements, Android shell — implemented. Phase 3 — marketplace client (index, install pipeline, recipe store, reports, telemetry) and process isolation (dispatch seam + trust-level routing, AuthStamp facade scope gate, isolation RPC pure layer, isolated plugin runner, Binder byte transport, manifest-only `.mcos` registration — opt-in via `processIsolation = true`) — implemented.**

The repository now contains a working multi-module Gradle project (every module has its own README):

| Module | Content | Status |
|--------|---------|--------|
| [`mcos-sdk`](./mcos-sdk/README.md) | Plugin contracts (`McosPlugin`, `CommandHandler`, `HostServices`, `ExecutionContext`, `AuthStamp`, `DirectorySandbox`) | ✅ |
| [`mcos-security`](./mcos-security/README.md) | Permission kernel (AuthStamp minting/signing), rate limiter, egress policy + `DomainGlob`, enterprise policy, plugin trust gate, crash quarantine, audit log, schema validation | ✅ |
| [`mcos-runtime-core`](./mcos-runtime-core/README.md) | DSL parser → IR, command registry (incl. manifest-only registration), 7-stage executor, isolation dispatch seam + stamp-scoped facades, event bus, workflow engine, memory | ✅ |
| [`mcos-llm`](./mcos-llm/README.md) | AI planner/chat, multi-provider registry, grammar-constrained decoding, prompt-injection guard, multi-turn agent loop | ✅ |
| [`mcos-marketplace`](./mcos-marketplace/README.md) | Index client, install pipeline (incl. manifest-only), blocklist verification, recipe store, dependency resolution, user reports, opt-in telemetry, install wizard | ✅ |
| [`mcos-runtime`](./mcos-runtime/README.md) | Facade (`McosRuntime` builder, confirmation coordinator, run manager, trigger coordinator) wiring all submodules | ✅ |
| [`mcos-android-sdk`](./mcos-android-sdk/README.md) | UI-free Android host SDK: composition root, headless bootstrap, schedule/boot receivers, activity-result & permission bridges, MCP server management, dynamic `.mcos` loading, opt-in `:mcos_plugin` process isolation | ✅ |
| [`mcos-android`](./mcos-android/README.md) | Compose demo shell built on the SDK (replaceable reference UI) | ✅ |
| [`mcos-server`](./mcos-server/README.md) | Self-hosted sync endpoint: `SyncBlobTransport` REST contract + mandatory Bearer-token auth, opaque blob store | ✅ |

Plugins are independently buildable in `plugins/`: `mcos-plugin-hello`, `mcos-plugin-system`, `mcos-plugin-camera`, `mcos-plugin-files`, `mcos-plugin-mcp`, `mcos-plugin-iot` (each with tests and a README).

See [`docs/en/11-implementation-status.md`](./docs/en/11-implementation-status.md) for the detailed status matrix.

## Docs

Documentation is available in two languages:

- **English:** [`docs/en/README.md`](./docs/en/README.md)
- **中文:** [`docs/zh/README.md`](./docs/zh/README.md)

| # | Topic |
|---|--------|
| 00 | [Vision](./docs/en/00-vision.md) |
| 01 | [Architecture](./docs/en/01-architecture.md) |
| 02 | [Command Protocol RFC](./docs/en/02-command-protocol.md) |
| 03 | [Runtime](./docs/en/03-runtime.md) |
| 04 | [Plugin SDK](./docs/en/04-plugin-sdk.md) |
| 05 | [Workflow Engine](./docs/en/05-workflow.md) |
| 06 | [AI Planner](./docs/en/06-agent.md) |
| 07 | [Memory](./docs/en/07-memory.md) |
| 08 | [Security](./docs/en/08-security.md) |
| 09 | [Marketplace](./docs/en/09-marketplace.md) |
| 10 | [Roadmap](./docs/en/10-roadmap.md) |
| 11 | [Implementation Status](./docs/en/11-implementation-status.md) |

中文说明：见 [`README.zh-CN.md`](./README.zh-CN.md)。

Golden DSL ↔ IR fixtures: [`docs/fixtures/`](./docs/fixtures/).

## Target Modules

Implemented topology:

```text
mcos-sdk          Plugin contracts (leaf module, no internal deps)
mcos-security     Permission kernel · AuthStamp · Audit · Signatures · Egress · Rate limit · Enterprise policy
mcos-runtime-core Parser → IR · Registry (incl. manifest-only) · Executor (7-stage + isolation routing) · EventBus · Workflow · Memory
mcos-llm          AI planner · Multi-provider registry · GBNF / JSON-schema decoding · Agent loop
mcos-marketplace  Index client · Install pipeline · Recipe store · Reports · Telemetry
mcos-runtime      Facade: McosRuntime builder · Confirmation coordinator · Run/Trigger managers
plugins/
  mcos-plugin-hello     Reference sample (hello.world)        ✅
  mcos-plugin-system    sys.* (13 commands)                   ✅
  mcos-plugin-camera    camera.* (capture, scan)              ✅
  mcos-plugin-files     file.* / photo.* (8 commands)         ✅
  mcos-plugin-mcp       mcp.* adapter (dynamic synthesis)     ✅
  mcos-plugin-iot       home.* / iot.* (Home Assistant)  ✅
mcos-android-sdk  UI-free Android host SDK (+ opt-in :mcos_plugin process isolation) ✅
mcos-android      Compose demo shell (on the SDK)    ✅
mcos-server       Sync endpoint (REST + Bearer auth) ✅
mcos-server       marketplace index (host-side)     (planned, P3)
```

For the full dependency graph and per-module target, see [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md).

## Build

`gradlew` in this repo has no execute bit — invoke it through the shell:

```bash
sh gradlew build        # full gate (JVM + Android + tests)
sh gradlew test         # JVM module tests only
sh gradlew :mcos-android:assembleDebug   # demo shell APK
```

## Artifacts

Published to Maven Central under `io.github.morainet` (namespace verified via the [Morainet](https://github.com/Morainet) GitHub org; a dedicated domain may replace it later). Pushing a `v<version>` tag triggers [`.github/workflows/release.yml`](./.github/workflows/release.yml): build gate → GPG-signed publish → Central Portal upload → GitHub Release with the demo APK.

Once the first version is out, consume via the BOM so all modules stay version-aligned:

```kotlin
dependencies {
    implementation(platform("io.github.morainet:mcos-bom:<version>"))
    implementation("io.github.morainet:mcos-android-sdk")   // Android host
    // JVM host: implementation("io.github.morainet:mcos-runtime")
    // Plugin authors only need: implementation("io.github.morainet:mcos-sdk")
}
```

Local dry-run: `sh gradlew publish` fills `build/central-bundle` (unsigned, no secrets needed); `sh gradlew publishToMavenLocal` installs into `~/.m2`.

## License

[Apache License 2.0](./LICENSE)

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Changes are recorded in [CHANGELOG.md](./CHANGELOG.md).
