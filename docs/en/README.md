# MCOS documentation

[中文](../zh/README.md)

## Start here

| What you want to do | Read |
|---|---|
| Understand current ownership, dependencies, and the architectural redesign | [Current architecture and development boundaries](./13-architecture-convergence.md) |
| Build and debug the Android Agent | [Android app guide](../../mcos-android/README.md) |
| Embed commands in your own Android app | [Android SDK](../../mcos-android-sdk/README.md) · [Embedded-host regression guide](./14-embedded-host.md) |
| Verify exports on an authorized device | [ADB guide](../../tools/mcos-device/README.md) |
| Check implementation, verification, and priorities | [Current implementation](./11-implementation-status.md) · [Product direction](./15-product-direction.md) |
| Contribute code or documentation | [Contributing guide](../../CONTRIBUTING.md) |

## Technical references

| Area | Documents |
|---|---|
| Commands and plugins | [Command Protocol](./02-command-protocol.md) · [Plugin SDK](./04-plugin-sdk.md) · [Golden fixtures](../fixtures/README.md) |
| Execution and security | [Runtime](./03-runtime.md) · [Security](./08-security.md) · [Workflows](./05-workflow.md) |
| Agent and memory | [Agent](./06-agent.md) · [Memory](./07-memory.md) |
| Optional extensions | [Marketplace](./09-marketplace.md) · [Index service](./12-index-server.md) |

The technical references retain RFC design contracts. For current classes, module edges, command availability, and tests, check the current architecture, Gradle configuration, source, and fixtures. See the [vision draft](./00-vision.md), [architecture draft](./01-architecture.md), and [historical roadmap](./10-roadmap.md) for earlier decisions. See the [changelog](../../CHANGELOG.md) for release history.
