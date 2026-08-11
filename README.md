# MCOS — Mobile Command OS

> **Make every cooperative capability on the phone a command that AI can call — safely.**

MCOS is an open design for a **Mobile Command Bus**: a typed Command Protocol, on-device Runtime, Plugin SDK, and optional AI planner that compiles natural language into auditable DSL — not opaque side effects.

## Status

**Phase 1 — core runtime implemented (Draft v0.1.0).**

The repository now contains a working multi-module Gradle project:

| Module | Content | Status |
|--------|---------|--------|
| `mcos-sdk` | Plugin contracts (`McosPlugin`, `CommandHandler`, `HostServices`, `ExecutionContext`, `AuthStamp`) | ✅ |
| `mcos-runtime` | DSL parser, command registry, 7-stage executor, permission kernel, rate limiter, egress policy, audit log, LLM planner/chat, workflow engine, event bus, memory | ✅ |
| `mcos-android` | Compose CLI / Chat shell | ⬜ planned |

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
mcos-runtime      Parser · Registry · Executor (7-stage) · Audit · Security · LLM
mcos-sdk          Plugin contracts
plugins/
  mcos-plugin-hello     Reference sample (hello.world)        ✅
  mcos-plugin-system    sys.* (notify, share)                 ✅
  mcos-plugin-camera    camera.* (capture, scan)              ✅
  mcos-plugin-files     file.* / photo.*                      ✅
  mcos-plugin-iot       home.* / iot.*              (planned)
  mcos-plugin-mcp       mcp.* adapter               (planned)
mcos-android      Compose CLI / Chat shell           (planned)
mcos-server       Sync · marketplace                (planned)
```

For the full dependency graph and per-module target, see [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md).

## License

[Apache License 2.0](./LICENSE)

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Changes are recorded in [CHANGELOG.md](./CHANGELOG.md).
