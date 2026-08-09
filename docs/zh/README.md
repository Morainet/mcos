# MCOS 文档（中文）

**Mobile Command OS (MCOS)** 官方设计文档中文版——架构、RFC 与路线图。

> 一句话：**让手机上的每一个能力，都变成 AI 可以安全调用的命令。**

> **语言:** [English](../en/README.md) · 中文（当前）

## 阅读顺序

| # | 文档 | 说明 |
|---|------|------|
| 00 | [愿景](./00-vision.md) | 为什么做 MCOS；设计原则；非目标 |
| 01 | [架构](./01-architecture.md) | 分层系统、请求流、进程模型、IPC 契约、线程模型、Kotlin 类型、错误码 |
| 02 | [命令协议（RFC）](./02-command-protocol.md) | 规范性 DSL / IR / 注册中心契约 |
| 03 | [运行时](./03-runtime.md) | 解析器、注册中心、执行器、事件、审计 |
| 04 | [插件 SDK](./04-plugin-sdk.md) | Manifest、Handler、Host 服务 |
| 05 | [工作流引擎](./05-workflow.md) | 图、并行、触发器、重试 |
| 06 | [AI 规划器](./06-agent.md) | Provider、编译器、修复循环 |
| 07 | [记忆](./07-memory.md) | Profile、引用、隐私、同步 |
| 08 | [安全](./08-security.md) | 权限、确认、威胁模型 |
| 09 | [插件市场](./09-marketplace.md) | 分发、签名、配方 |
| 10 | [路线图](./10-roadmap.md) | MVP → V1 → 生态 |
| 11 | [实现状态](./11-implementation-status.md) | Spec ↔ 实现路线图（已建 vs 待建） |
| — | [仓库与模块](./REPOSITORIES.md) | 模块依赖图与索引 |

## 相关

- 早期脑暴笔记（中文原始稿）：[`../../doc/前期架构设计框架.md`](../../doc/前期架构设计框架.md)
- 英文 README：[`../../README.md`](../../README.md)
- 变更记录：[`../../CHANGELOG.md`](../../CHANGELOG.md)
- 英文文档：[`../en/`](../en/README.md)

## 状态

所有文档截至 2026-08-06 均为 **Draft v0.1.0**。协议变更时应升级版本号，并在代码落地时更新 fixture。

> **翻译约定：** 代码块、命令 ID、JSON 示例、错误码、字段名、ABNF/EBNF 语法均保留英文原文；仅散文部分译为中文。技术术语首次出现时附英文原词，如"运行时（Runtime）"。
