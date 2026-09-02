<div align="center">

<img src="docs/images/logo.jpeg" width="500" alt="MCOS logo"/>

# MCOS — Mobile Command OS

**Make every cooperative capability on the phone a command that AI can call — safely.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.morainet/mcos-bom.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.morainet/mcos-bom/overview)
[![CI](https://img.shields.io/github/actions/workflow/status/Morainet/mcos/ci.yml.svg?branch=main&label=CI)](https://github.com/Morainet/mcos/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/Morainet/mcos.svg?color=blue)](https://github.com/Morainet/mcos/blob/main/LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?logo=github&logoColor=white)](./CONTRIBUTING.md)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white)](./mcos-android-sdk/README.md)
[![JDK](https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Platform](https://img.shields.io/badge/platform-JVM%20%7C%20Android-lightgrey)](#build)
[![Last Commit](https://img.shields.io/github/last-commit/Morainet/mcos?color=blue)](https://github.com/Morainet/mcos/commits/main)

[Quick Start](#quick-start) · [How It Works](#how-it-works) · [What's Inside](#whats-inside) · [Documentation](#documentation) · [Report a Bug](https://github.com/Morainet/mcos/issues) · [中文文档](./README.zh-CN.md)

*An open **mobile command bus** — think the phone's Kubernetes + MCP + Claude Code Runtime. The moat is the open Command Protocol, not any single model or vendor.*

</div>

---

## Table of Contents

- [Why MCOS](#why-mcos)
- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [What's Inside](#whats-inside)
- [Documentation](#documentation)
- [Build](#build)
- [Release](#release)
- [License & Contributing](#license--contributing)

## Why MCOS

- 🧠 **AI generates commands — it never touches the device.** Intents, accessibility, Bluetooth, IoT — every real operation is executed by the Runtime *after* permission checks. AI stays in the sandbox.
- 🔒 **The Runtime owns security.** Permissions, rate limits, confirmation policy, and audit all live in the runtime kernel — never left to a plugin's goodwill.
- 🔀 **Model-agnostic.** OpenAI, Gemini, Qwen, DeepSeek, Claude, on-device models — swap the model, keep the same command surface.
- 📜 **Protocol over model.** HTTP unified the web; SQL unified data access. MCOS aims to unify mobile app capabilities with one open Command Protocol.
- 🧱 **Opt-in process isolation.** Plugins can run in a separate `:mcos_plugin` process behind Binder RPC with stamp-scoped facades.
- 🌍 **Open, self-hostable ecosystem.** Marketplace client, recipe store, sync server, AI planning — everything in one repo, all open source.

## How It Works

Natural language in, auditable commands out — the AI plans, the Runtime enforces, plugins execute:

```text
   "Compress today's photos and send them to Tom"
                      │
                      ▼
        ┌─────────────────────────┐
        │       AI Planner        │  model-agnostic: OpenAI · Gemini · Qwen ·
        └───────────┬─────────────┘  Claude · DeepSeek · on-device
                    ▼
              DSL ──► IR            typed · auditable · replayable
                    ▼
        ┌─────────────────────────┐
        │    7-stage Executor     │  permission · rate-limit · confirmation ·
        └───────────┬─────────────┘  stamp scope · audit
                    ▼
        ┌─────────────────────────┐
        │  Plugins (isolated in   │  camera · files · iot · mcp · system …
        │  optional :mcos_plugin) │
        └───────────┬─────────────┘
                    ▼
               HostServices        net · files · ui · secureStore · clock
                    │
                    ▼
                Audit Log          every side effect on the record
```

Example utterances and their compiled commands:

```text
> camera.scan          "scan this barcode"             帮我扫一下这个二维码
> photo.compress       "compress today's photos"       把今天拍的照片压缩一下
> home.scene.movie     "movie time"                    电影模式
> iot.ac.set           "turn on the AC at 24°C"        打开空调，24 度
```

## Quick Start

### Android host app

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()        // required by the Android SDK's androidx dependencies
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts — consume via the BOM so every module stays version-aligned
dependencies {
    implementation(platform("io.github.morainet:mcos-bom:0.0.3"))
    implementation("io.github.morainet:mcos-android-sdk")
}
```

```kotlin
class MyApplication : Application(), McosHostApp {
    override lateinit var deps: AppDeps
    override fun onCreate() {
        super.onCreate()
        deps = CompositionRoot.create(this)      // processIsolation = true enables the sandbox
        RuntimeBootstrap.ensureRehydrated(deps)  // restore plugins + reschedule persisted alarms
    }
}
```

That's it — the SDK's manifest merge brings in the receivers, permissions, FileProvider, and the optional `:mcos_plugin` isolation process for free. See the [SDK README](./mcos-android-sdk/README.md) for the full integration guide.

### JVM host & plugin authors

```kotlin
dependencies {
    implementation(platform("io.github.morainet:mcos-bom:0.0.3"))
    implementation("io.github.morainet:mcos-runtime")  // JVM host (server / CLI / desktop)
    // implementation("io.github.morainet:mcos-sdk")   // plugin authors: contracts only
}
```

## What's Inside

A working multi-module Gradle project (every module has its own README):

| Module | Content | Status |
|:-------|:--------|:------:|
| 📜 [`mcos-sdk`](./mcos-sdk/README.md) | Plugin contracts (`McosPlugin`, `CommandHandler`, `HostServices`, `ExecutionContext`, `AuthStamp`, `DirectorySandbox`) | ✅ |
| 🔒 [`mcos-security`](./mcos-security/README.md) | Permission kernel (AuthStamp minting/signing), rate limiter, egress policy + `DomainGlob`, enterprise policy, plugin trust gate, crash quarantine, audit log, schema validation | ✅ |
| ⚙️ [`mcos-runtime-core`](./mcos-runtime-core/README.md) | DSL parser → IR, command registry (incl. manifest-only registration), 7-stage executor, isolation dispatch seam + stamp-scoped facades, scheduler (§8 priority lanes + §8.5 device mutex), event bus, workflow engine, memory | ✅ |
| 🧠 [`mcos-llm`](./mcos-llm/README.md) | AI planner/chat, multi-provider registry, grammar-constrained decoding, prompt-injection guard, multi-turn agent loop | ✅ |
| 🛒 [`mcos-marketplace`](./mcos-marketplace/README.md) | Index client, install pipeline (incl. manifest-only), blocklist verification, recipe store, dependency resolution, user reports, opt-in telemetry, install wizard | ✅ |
| 🚀 [`mcos-runtime`](./mcos-runtime/README.md) | Facade (`McosRuntime` builder, confirmation coordinator, §8 scheduler admission, trigger coordinator) wiring all submodules | ✅ |
| 🤖 [`mcos-android-sdk`](./mcos-android-sdk/README.md) | UI-free Android host SDK: composition root, headless bootstrap, schedule/boot receivers, activity-result & permission bridges, MCP server management, dynamic `.mcos` loading, opt-in `:mcos_plugin` process isolation | ✅ |
| 📱 [`mcos-android`](./mcos-android/README.md) | Compose demo shell built on the SDK (replaceable reference UI) | ✅ |
| 🖧 [`mcos-server`](./mcos-server/README.md) | Self-hosted sync endpoint: `SyncBlobTransport` REST contract + mandatory Bearer-token auth, opaque blob store | ✅ |

🔌 Plugins are independently buildable in `plugins/`: `mcos-plugin-hello`, `mcos-plugin-system`, `mcos-plugin-camera`, `mcos-plugin-files`, `mcos-plugin-mcp`, `mcos-plugin-iot` (each with tests and a README).

## Documentation

Available in two languages — [English](./docs/en/README.md) · [中文](./docs/zh/README.md):

| # | Topic | What's in it |
|:--:|:------|:-------------|
| 00 | [Vision](./docs/en/00-vision.md) | why MCOS exists · principles · non-goals |
| 01 | [Architecture](./docs/en/01-architecture.md) | layers · request lifecycle · process model · IPC contracts · threading |
| 02 | [Command Protocol RFC](./docs/en/02-command-protocol.md) | **the spec core**: DSL · IR · type system · error codes |
| 03 | [Runtime](./docs/en/03-runtime.md) | parser · registry · executor · scheduler · permission kernel · audit |
| 04 | [Plugin SDK](./docs/en/04-plugin-sdk.md) | manifest · handler contract · host services |
| 05 | [Workflow Engine](./docs/en/05-workflow.md) | graph IR: sequence, parallel, retry, compensation, triggers |
| 06 | [AI Planner](./docs/en/06-agent.md) | AIProvider · command compiler · repair loop |
| 07 | [Memory](./docs/en/07-memory.md) | memory tiers · reference resolution · privacy |
| 08 | [Security](./docs/en/08-security.md) | threat model · defense in depth · confirmation UX |
| 09 | [Marketplace](./docs/en/09-marketplace.md) | signing · install/update · recipe store |
| 10 | [Roadmap](./docs/en/10-roadmap.md) | P0 → P4 roadmap |
| 11 | [Implementation Status](./docs/en/11-implementation-status.md) | docs ↔ code mapping (**read this first for the current state**) |

Golden DSL ↔ IR fixtures: [`docs/fixtures/`](./docs/fixtures/). For the full dependency graph, see [`docs/en/REPOSITORIES.md`](./docs/en/REPOSITORIES.md).

## Build

`gradlew` in this repo has no execute bit — invoke it through the shell:

```bash
sh gradlew build        # full gate (JVM + Android + tests)
sh gradlew test         # JVM module tests only
sh gradlew :mcos-android:assembleDebug   # demo shell APK
```

## Release

Artifacts are published to Maven Central under `io.github.morainet` (namespace verified via the [Morainet](https://github.com/Morainet) GitHub org). Pushing a `v<version>` tag triggers the [release workflow](./.github/workflows/release.yml): build gate → GPG-signed publish → Central Portal upload → GitHub Release with the demo APK. Local dry-run: `sh gradlew publish` fills `build/central-bundle` (unsigned, no secrets needed).

## License & Contributing

[![License](https://img.shields.io/github/license/Morainet/mcos.svg?color=blue)](./LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?logo=github&logoColor=white)](./CONTRIBUTING.md)

[Apache License 2.0](./LICENSE) · [CONTRIBUTING.md](./CONTRIBUTING.md) · [CHANGELOG.md](./CHANGELOG.md)

---

<div align="center">

**中文说明：见 [README.zh-CN.md](./README.zh-CN.md)。**

⭐ Star the repo if MCOS sounds like the layer your AI app is missing.

</div>
