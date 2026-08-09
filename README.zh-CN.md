# MCOS — 移动命令操作系统

> **让手机上的每一个合作能力，都变成 AI 可以安全调用的命令。**

[English](./README.md) · **中文**

MCOS 是一个开源的**移动命令总线（Mobile Command Bus）**设计：一套类型化的命令协议（Command Protocol）、设备端运行时（Runtime）、插件 SDK，以及可选的 AI 规划器（Planner）。规划器把自然语言编译成**可审计的 DSL**，而不是不可见的副作用。

---

## 核心理念

```
自然语言  →  命令 DSL  →  Runtime  →  插件 / 系统 / MCP
```

- **AI 只生成命令，不直接触碰设备。** Intent、Accessibility、蓝牙、IoT 等真实操作一律由 Runtime 在权限校验后执行。
- **Runtime 掌管安全。** 权限、限流、确认策略、审计都在 Runtime 内核，不依赖插件的自觉。
- **AI 是可替换的 Provider。** OpenAI、Gemini、Qwen、DeepSeek、Claude、端侧模型——换模型不换命令面。
- **护城河是协议，不是某个模型。** 就像 HTTP 统一了 Web、SQL 统一了数据访问，MCOS 希望用 Command Protocol 统一移动应用能力。

一句话类比：**手机端的 Kubernetes + MCP + Claude Code Runtime。**

---

## 它和现有项目的关系

| 层 | 代表项目 | 统一了什么 |
|----|----------|-----------|
| 代码工具 | Claude Code | 开发机上的操作 |
| 工具协议 | MCP（Model Context Protocol） | 桌面 / 服务端工具服务器 |
| 应用能力 | Android App Functions | App 内可调用函数 |
| 移动命令（OS 集成） | Google App Functions + Gemini；Apple App Intents + Apple Intelligence | OS 级命令总线——但各自锁定自身生态 |
| **移动命令（开放标准）** | **MCOS（本项目）** | **开放协议 + 可换模型 + 跨厂商插件生态** |

业界已经有了代码总线、工具总线、应用能力总线，也有了 OS 厂商内置的移动命令总线（Google / Apple）——**唯独缺一个开放的、模型无关的、不锁定单一 OS 厂商的移动命令总线标准**。

---

## 模块拓扑

目前尚无已构建的模块。计划中的模块拓扑（将在 Phase 1 开始搭建）见 [`docs/zh/00-vision.md`](./docs/zh/00-vision.md) §5 和 [`docs/zh/REPOSITORIES.md`](./docs/zh/REPOSITORIES.md)：

```
mcos-android      Compose 客户端（CLI / Chat 外壳）
mcos-runtime      解析器 · 注册中心 · 执行器 · 审计
mcos-sdk          插件契约（McosPlugin、CommandHandler ……）
plugins/
  mcos-plugin-hello     参考示例（hello.world）
  mcos-plugin-system    sys.*（notify、share）
  mcos-plugin-camera    camera.*（capture、scan）
  mcos-plugin-files     file.* / photo.*           （规划）
  mcos-plugin-iot       home.* / iot.*             （规划）
  mcos-plugin-mcp       mcp.* 适配器                 （规划）
mcos-server             同步 · 插件市场              （规划）
```

---

## 项目状态

**Phase 0 — 纯设计文档（Draft v0.1.0）。** 本仓库目前**只包含设计文档和黄金测试用例**，尚无任何实现代码或构建系统。实现工作将在 Phase 1 开始——详见 [`docs/zh/11-implementation-status.md`](./docs/zh/11-implementation-status.md)。

---

## 下一步

本仓库为纯文档，无需构建。如需开始实现，请阅读：

- [`docs/zh/11-implementation-status.md`](./docs/zh/11-implementation-status.md) §6 的推荐开发路径
- [`docs/zh/REPOSITORIES.md`](./docs/zh/REPOSITORIES.md) 的目标模块依赖图与构建坐标

---

## 文档导航

设计 RFC 提供**中英双语**：中文见 `docs/zh/`，英文见 `docs/en/`。建议按以下顺序阅读：

> **语言切换：** [English docs](./docs/en/README.md) · [中文文档](./docs/zh/README.md)

| # | 文档 | 说明 |
|---|------|------|
| 00 | [愿景](./docs/zh/00-vision.md) | 为什么做 MCOS；原则；非目标 |
| 01 | [系统架构](./docs/zh/01-architecture.md) | 分层架构、请求生命周期、进程模型、IPC 契约、线程模型、完整 Kotlin 类型、错误码 |
| 02 | [命令协议（RFC）](./docs/zh/02-command-protocol.md) | **规范核心**：DSL / IR / 类型系统 / 错误码 |
| 03 | [运行时](./docs/zh/03-runtime.md) | 解析器、注册中心、执行器、权限内核、审计 |
| 04 | [插件 SDK](./docs/zh/04-plugin-sdk.md) | Manifest、Handler 契约、Host 服务 |
| 05 | [工作流引擎](./docs/zh/05-workflow.md) | 图 IR：顺序、并行、重试、补偿、触发器 |
| 06 | [AI 规划器](./docs/zh/06-agent.md) | AIProvider、命令编译器、修复循环 |
| 07 | [记忆](./docs/zh/07-memory.md) | 记忆分层、引用解析、隐私 |
| 08 | [安全](./docs/zh/08-security.md) | 威胁模型、纵深防御、确认 UX |
| 09 | [插件市场](./docs/zh/09-marketplace.md) | 签名、安装/更新、配方商店 |
| 10 | [路线图](./docs/zh/10-roadmap.md) | P0 → P4 路线图 |
| 11 | [实现状态](./docs/zh/11-implementation-status.md) | 文档 ↔ 代码实现对照（**先读这篇了解现状**） |
| — | [模块索引](./docs/zh/REPOSITORIES.md) | 模块依赖图与职责索引 |

- 黄金测试用例（DSL ↔ IR）：[`docs/fixtures/`](./docs/fixtures/)
- 变更记录：[`CHANGELOG.md`](./CHANGELOG.md)
- 早期中文脑暴稿（项目起点）：[`doc/前期架构设计框架.md`](./doc/前期架构设计框架.md)

---

## 示例交互

```
> camera.scan
> home.movie
> github.pr
> photo.clean
```

对应的自然语言：

```
帮我打开空调
导航回公司
把今天拍的照片压缩一下发给 Tom
Wi‑Fi 连上公司网络后自动开 VPN
```

以上都会被编译成**命令 DSL**，再经由 Runtime 执行。

---

## 贡献与许可

- 贡献指引：[CONTRIBUTING.md](./CONTRIBUTING.md)
- 许可证：[Apache License 2.0](./LICENSE)

> **协议优先，运行时优先，插件优先，AI 可替换**——而不是先做一个聊天界面。
