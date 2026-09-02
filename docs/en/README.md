# MCOS Documentation

Official design documentation for **Mobile Command OS (MCOS)** — architecture, RFCs, and roadmap intended for an open-source GitHub repository.

> One-liner: **Make every capability on the phone a command that AI can call — safely.**

## Reading Order

| # | Document | Description |
|---|----------|-------------|
| 00 | [Vision](./00-vision.md) | Why MCOS exists; principles; non-goals |
| 01 | [Architecture](./01-architecture.md) | Layered system, flows, process model, IPC contract, threading, Kotlin types, error codes |
| 02 | [Command Protocol (RFC)](./02-command-protocol.md) | Normative DSL / IR / registry contracts |
| 03 | [Runtime](./03-runtime.md) | Parser, registry, executor, scheduler, events, audit |
| 04 | [Plugin SDK](./04-plugin-sdk.md) | Manifests, handlers, host services |
| 05 | [Workflow Engine](./05-workflow.md) | Graphs, parallel, triggers, retries |
| 06 | [AI Planner](./06-agent.md) | Providers, compiler, repair loop |
| 07 | [Memory](./07-memory.md) | Profile, refs, privacy, sync |
| 08 | [Security](./08-security.md) | Permissions, confirmations, threat model |
| 09 | [Marketplace](./09-marketplace.md) | Distribution, signing, recipes |
| 10 | [Roadmap](./10-roadmap.md) | MVP → V1 → ecosystem |
| 11 | [Implementation Status](./11-implementation-status.md) | Spec ↔ implementation roadmap (what is built vs. planned) |
| — | [Repositories](./REPOSITORIES.md) | Module dependency graph & index |

## Related

- Early notes (Chinese brainstorm): [`../../doc/前期架构设计框架.md`](../../doc/前期架构设计框架.md)
- Chinese README: [`../../README.zh-CN.md`](../../README.zh-CN.md)
- Changelog: [`../../CHANGELOG.md`](../../CHANGELOG.md)

## Status

All documents are **Draft v0.1.0**; per-document headers carry implementation-status notes kept current as code lands (latest refresh: 2026-09-02). Protocol changes should bump versions and update fixtures when code lands.
