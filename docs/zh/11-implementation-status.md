# MCOS 实现状态

> **语言:** [English](../en/11-implementation-status.md) · 中文（当前）

> **Status:** Living document  
> **Last Updated:** 2026-08-06  
> **Audience:** 需要了解**什么是仅规范、什么仍待构建**的贡献者与评估者。

MCOS 目前是一个**仅含设计**的仓库：命令协议、运行时架构、插件 SDK 及其周边子系统在 `docs/00`–`10` 中有规范，但**尚无任何实现代码**。本文档跟踪规范如何映射到未来的实现工作。

---

## 0. 黄金法则

> **文档即规范。实现向文档对齐——而不是反过来。**
>
> 当实现与文档不一致时，默认以文档为准（除非文档自身内部自相矛盾）。不要"修"文档去迁就实现。相反，在此处登记差距，并朝文档去实现。

---

## 1. 当前仓库状态

本仓库**只包含文档**：

```text
mcos/
├── docs/                 # 12 RFCs (00–11) + fixtures + REPOSITORIES.md
├── doc/                  # Early Chinese brainstorm notes
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **源代码模块：** 无（已规划，见 §2）
- **构建系统：** 无（Gradle 多模块将在 P1 中创建）
- **Golden fixture：** ✅ 已具备——[`docs/fixtures/`](../fixtures/) 下有 8 个用例（5 个正向往返，3 个负向拒绝）

> 此前曾存在一个 Phase-0 代码骨架（6 个 Gradle 模块、8 个 Kotlin 文件）。它已被移除，以保持仓库为干净的设计基线；下文的规范就是一次全新实现必须瞄准的目标。

---

## 2. 目标模块拓扑

以下是实现将引入的模块，按依赖顺序排列。完整参考卡片见 [`REPOSITORIES.md`](./REPOSITORIES.md)。

**P1 首批模块**（6 个——定义于 [REPOSITORIES.md](./REPOSITORIES.md) §2）：

| Module | 角色 | 目标阶段 |
|--------|------|--------------|
| `mcos-sdk` | 插件契约（`McosPlugin`、`CommandHandler`、`CommandDescriptor`、…） | P1（叶子） |
| `mcos-runtime` | Parser → IR、Registry、Permission Kernel、Scheduler、Executor、Audit | P1 |
| `mcos-android` | Jetpack Compose 客户端外壳（CLI / Chat） | P1 |
| `plugins:mcos-plugin-hello` | 参考示例插件 | P1 |
| `plugins:mcos-plugin-system` | `sys.notify`、`sys.share`、`sys.intent.start` | P1 |
| `plugins:mcos-plugin-camera` | `camera.capture`、`camera.scan` | P1 |

**计划模块**（后续阶段——定义于 [REPOSITORIES.md](./REPOSITORIES.md) §3）：

| Module | 角色 | 目标阶段 |
|--------|------|--------------|
| `mcos-plugin-files` | `file.*`、`photo.search`、`photo.compress` | P1 |
| `mcos-plugin-iot` | `home.*`、`iot.*`（Home Assistant / Tuya / Matter） | P2 |
| `mcos-plugin-mcp` | MCP 客户端适配器 → `mcp.*` | P2 spike / P3 production |
| `mcos-server` | 同步、市场索引、远程策略 | P3 |

---

## 3. 子系统实现矩阵

下列每个子系统目前都**仅为规范**；"目标阶段"是实现开始的时机。

| Subsystem | 规范文档 | 目标阶段 |
|-----------|----------|--------------|
| **Parser**（DSL → IR） | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1（最先） |
| **IR 类型** | [02](./02-command-protocol.md) §7 | P1 |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 |
| **Executor** | [03](./03-runtime.md) §9 | P1 |
| **Audit 日志** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1（基础） |
| **Planner 桥** | [06](./06-agent.md) | P1（单一 provider） |
| **Network Egress 策略** | [08](./08-security.md) §12（`decideEgress`） | P1 |
| **Prompt Injection 检测** | [08](./08-security.md) §11 | P1（编译器侧） |
| **Rate Limiting** | [08](./08-security.md) §10 | P1（每插件/分钟） |
| **Secret 管理**（`{{secret}}` 模板） | [08](./08-security.md) §9 | P1 |
| **Crash-loop 隔离** | [08](./08-security.md) §15.3 | P1 |
| **进程隔离** | [08](./08-security.md) §8 | P1（尽力而为）→ P3（第三方默认） |
| **Workflow 引擎** | [05](./05-workflow.md) | P2 |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 |
| **Memory** | [07](./07-memory.md) | P2 |
| **企业策略** | [08](./08-security.md) §13 | P3 |
| **Marketplace** | [09](./09-marketplace.md) | P3 |

> **最高杠杆的第一步是实现 `DslParser`**，因为它打通整条执行流水线，并被 golden fixture 覆盖（§4）。
>
> **安全底线：** P1 交付一个完整（尽管最小化）的安全体系——`decideConfirmation`（[08 §4](./08-security.md)）、`decideEgress`（[08 §12](./08-security.md)）、prompt-injection 编译器检查（[08 §11](./08-security.md)）、rate limiting（[08 §10](./08-security.md)）以及 crash 隔离（[08 §15.3](./08-security.md)）。安全阶段表见 [08 §17](./08-security.md)。

---

## 4. Fixture 覆盖

[`docs/fixtures/`](../fixtures/) 下的 golden 用例。它们已经存在，定义了未来解析器必须满足的一致性范围。

### 4.1 正向（DSL → IR 往返）

| Case | 覆盖 | Protocol § |
|------|--------|------------|
| `01-empty-args` | 空参数 + `# mcos-dsl:` 头 | §6.1, §6.5 |
| `02-named-string` | 具名字符串参数 | §6.1 |
| `03-array-and-int` | int + 字符串数组 | §6.2 |
| `04-sequence` | 多语句 + 注释 → `sequence` | §6.4 |
| `05-mixed-literals` | bool / float / null，键已排序 | §6.2, §7.4 |

### 4.2 负向（必须拒绝）

| Case | 非法输入 | 预期错误 | Protocol § |
|------|---------------|----------------|------------|
| `06-nested-call` | 参数中的嵌套调用 | `PARSE_ERROR` | §6.2, §15.1 |
| `07-positional-arg` | 位置参数 | `PARSE_ERROR` | §6.1 |
| `08-malformed` | 括号不平衡 | `PARSE_ERROR` | §18 |

---

## 5. 全局特性矩阵（P0 → P3）

聚合自 [05](./05-workflow.md) §15、[06](./06-agent.md) §17、[07](./07-memory.md) §16、[08](./08-security.md) §17、[09](./09-marketplace.md) §15 中的 "MVP vs V1" 阶段表。P0（现在）是**规范完整、代码缺失**。阶段术语：P1 = MVP、P2 = V1、P3 = V2（[10](./10-roadmap.md) §2.1）。

| Subsystem | P0（仅规范） | P1 MVP | P2 | P3 |
|-----------|----------------|--------|----|----|
| Parser + IR | 规范完成 | ✅ 实现完整 DSL↔IR | — | — |
| Registry + Executor | 规范完成 | ✅ | — | — |
| Permission Kernel（`decideConfirmation`） | 规范完成 | ✅ Android + 确认（[08 §17](./08-security.md)） | — | — |
| ConfirmationPrompt | 规范完成 | ✅ NORMAL/ELEVATED（[08 §17](./08-security.md)） | ✅ 破坏性 typed-ack | — |
| Network Egress（`decideEgress`） | 规范完成 | ✅（[08 §17](./08-security.md)） | — | — |
| Prompt Injection 检测 | 规范完成 | ✅ 编译器侧（[08 §17](./08-security.md)） | — | + 自适应模型侧 |
| Rate Limiting | 规范完成 | ✅ 每插件/分钟（[08 §17](./08-security.md)） | ✅ + 每 recipe/小时 | + 自适应 |
| Audit | 规范完成 | 基础（未加密） | 加密 + 导出（HMAC） | 远程证明 |
| Workflow | 规范完成 | 仅顺序 | 并行 / 条件 / 重试 / 确认（[05 §15](./05-workflow.md)） | — |
| Event Bus | 规范完成 | 桩 / 少量事件 | 完整 | — |
| Memory | 规范完成 | profile + remember | 情景 + 模糊引用（[07 §16](./07-memory.md)） | 云端同步 |
| Planner | 规范完成 | 1 个 provider，chat→DSL | 多 provider + 探测（[06 §17](./06-agent.md)） | — |
| Plugins | 规范完成 | hello + system + camera + files（20+ 命令，[10 §4.3.1](./10-roadmap.md)） | IoT + Intent | MCP spike (P2) / MCP 生产 + 市场 (P3) |
| Marketplace | 规范完成 | — | 调试侧载 | 公共索引 + 签名（[09 §15](./09-marketplace.md)） |
| 进程隔离 | 规范完成 | 尽力而为（进程内） | — | 第三方默认 |
| 企业策略 | 规范完成 | — | — | ✅ 允许/拒绝名单（[08 §13](./08-security.md)） |
| Crash-loop 隔离 | 规范完成 | ✅（[08 §15.3](./08-security.md)） | ✅ | ✅ |

---

## 6. 推荐开发路径（P1）

按依赖排序；每一步都标注了要对照实现的规范章节。

1. **Gradle 多模块构建**——按 [REPOSITORIES.md](./REPOSITORIES.md) 创建 `mcos-sdk`、`mcos-runtime`、`mcos-android`、各插件。
2. **`DslParser`**——按 [02](./02-command-protocol.md) §6 + §18 实现；通过 §4 中全部 fixture。*（打通一切）*
3. **`CommandRegistry`**——从插件加载 `CommandDescriptor`；按 ID 解析。按 [03](./03-runtime.md) §6。
4. **`Executor`**——用校验过的参数调用 `CommandHandler`；把异常映射为 `PLUGIN_ERROR`。按 [03](./03-runtime.md) §9。
5. **Schema 校验**——执行前按 `inputSchema` 校验参数。按 [02](./02-command-protocol.md) §9.1。
6. **`PermissionKernel`**——按 `sideEffectClass` 实现 grant/deny/confirm 流程。按 [08](./08-security.md) §4。
7. **Audit（基础）**——本地追加 run 记录。按 [03](./03-runtime.md) §13。
8. **真实插件处理器**——把 `camera.capture` / `sys.notify` 接到 Android API。按 [04](./04-plugin-sdk.md) §7。
9. **`files` 插件**——`photo.search` / `photo.compress`。按 [10](./10-roadmap.md) §4.3。
10. **一个 LLM provider**——话语 → 单条命令 / 短序列。按 [06](./06-agent.md) §3。

> 先做纵向切片做演示：`camera.capture()` → `photo.compress(quality=80)` → `sys.notify(...)`，端到端穿过真实 Runtime。这是 MVP 的退出标准（[10](./10-roadmap.md) §4.6）。

---

## 7. SDK API 目标（规范契约）

完整的插件契约在 [04-plugin-sdk.md](./04-plugin-sdk.md) §5–6 中规定。实现至少要提供：

- `McosPlugin`——`id`、`version`、`commands()`、`handler(commandId)`，以及按规范的生命周期（`onLoad`/`onUnload`）。
- `CommandDescriptor`——全部规范字段，包括 `permissions`、`inputSchema`、`outputSchema`、`sideEffectClass`。
- `CommandResult`——`Ok(value, artifacts)` 与 `Err(code, message, retryable, details)`，其中 `details: JsonObject` 携带按错误码的结构化上下文（[04 §5.2](./04-plugin-sdk.md)、[02 §8.3](./02-command-protocol.md)）。
- `McosException`——插件声明的错误通道，用于区别于 `CommandResult.Err` 的可恢复失败（[04 §9.5](./04-plugin-sdk.md)）。
- `ExecutionContext`——`runId`、`commandId`、`args`、`stepId`、`auth`、`deadline`、`progress`、`services`。
- `HostServices` 门面：`files`、`net`、`ui`、`secureStore`、`clock`、`json`、`memory`——统一的插件侧门面（[04 §6](./04-plugin-sdk.md)、[01 §11.1](./01-architecture.md)）。历史名称 `PluginHost` 已废弃。

权威的 Kotlin IDL 见 [04-plugin-sdk.md](./04-plugin-sdk.md) §5。

---

## 8. 如何更新本文件

- **当代码落地：** 把相关行从"仅规范"移到"已实现"，并引用对应的 PR/commit。
- **当规范变化：** 升级 RFC 版本号，并更新 §4 fixture + §3/§5 矩阵。
- **不要**删除行——把它们标记为 superseded，以保留意图的历史。

本文件变化很快；把它当作一份清单，而非纪念碑。
