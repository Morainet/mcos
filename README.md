# MCOS — Mobile Command OS

> **Make every cooperative capability on the phone a command that AI can call — safely.**

MCOS is an open design for a **Mobile Command Bus**: a typed Command Protocol, on-device Runtime, Plugin SDK, and optional AI planner that compiles natural language into auditable DSL — not opaque side effects.

## Status

**Phase 1 — core runtime implemented (Draft v0.1.0). Phase 2 — workflow engine, typed event bus, memory enhancements, Android shell — implemented. Phase 3 — marketplace client (index, install pipeline, recipe store, reports, telemetry) — implemented.**

The repository now contains a working multi-module Gradle project:

| Module | Content | Status |
|--------|---------|--------|
| `mcos-sdk` | Plugin contracts (`McosPlugin`, `CommandHandler`, `HostServices`, `ExecutionContext`, `AuthStamp`) | ✅ |
| `mcos-runtime-core` | DSL parser → IR, command registry, 7-stage executor, event bus, workflow engine, memory | ✅ |
| `mcos-security` | Permission kernel, audit log, artifact signatures, egress policy, rate limiter, crash quarantine, enterprise policy | ✅ |
| `mcos-llm` | AI planner/chat, multi-provider registry, grammar-constrained decoding, prompt-injection guard | ✅ |
| `mcos-marketplace` | Index client, install pipeline, blocklist verification, recipe store, dependency resolution, user reports, opt-in telemetry, install wizard | ✅ |
| `mcos-runtime` | Facade (`McosRuntime` builder, confirmation coordinator, run manager) wiring all submodules | ✅ |
| `mcos-android` | Compose CLI / Chat shell | ✅ |
| `mcos-server` | Self-hosted sync endpoint: `SyncBlobTransport` REST contract + mandatory Bearer-token auth, opaque blob store | ✅ |

Plugins are independently buildable in `plugins/`: `mcos-plugin-hello`, `mcos-plugin-system`, `mcos-plugin-camera`, `mcos-plugin-files` (each with tests).

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
mcos-runtime-core Parser → IR · Registry · Executor (7-stage) · EventBus · Workflow · Memory
mcos-security     Permission kernel · Audit · Signatures · Egress · Rate limit · Enterprise policy
mcos-llm          AI planner · Multi-provider registry · GBNF / JSON-schema decoding
mcos-marketplace  Index client · Install pipeline · Recipe store · Reports · Telemetry
mcos-runtime      Facade: McosRuntime builder · Confirmation coordinator · Run manager
mcos-sdk          Plugin contracts
plugins/
  mcos-plugin-hello     Reference sample (hello.world)        ✅
  mcos-plugin-system    sys.* (notify, share)                 ✅
  mcos-plugin-camera    camera.* (capture, scan)              ✅
  mcos-plugin-files     file.* / photo.*                      ✅
  mcos-plugin-iot       home.* / iot.*              (planned)
  mcos-plugin-mcp       mcp.* adapter               (planned)
mcos-android      Compose CLI / Chat shell           ✅
mcos-server       Sync endpoint (REST + Bearer auth) ✅
mcos-server       marketplace index (host-side)     (planned, P3)
```

For the full dependency graph and per-module target, see [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md).

## License

[Apache License 2.0](./LICENSE)

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Changes are recorded in [CHANGELOG.md](./CHANGELOG.md).
