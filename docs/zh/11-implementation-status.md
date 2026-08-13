# MCOS 实现状态

> **语言:** [English](../en/11-implementation-status.md) · 中文（当前）

> **Status:** Living document  
> **Last Updated:** 2026-08-12  
> **Audience:** 需要了解**什么是仅规范、什么仍待构建**的贡献者与评估者。

MCOS 已交付 **P1 MVP 与大部分 P2**：命令协议、运行时、插件 SDK 与外壳已用 Kotlin 实现，分布在 `mcos-sdk`、`mcos-runtime`、`mcos-android` 与四个插件中。下表将每个子系统标记为 **已实现 / 部分 / 未开始**，并引用落地代码的 commit；仍为纯规范的行就是剩余工作。

---

## 0. 黄金法则

> **文档即规范。实现向文档对齐——而不是反过来。**
>
> 当实现与文档不一致时，默认以文档为准（除非文档自身内部自相矛盾）。不要"修"文档去迁就实现。相反，在此处登记差距，并朝文档去实现。

---

## 1. 当前仓库状态

本仓库包含**文档与可工作的多模块实现**：

```text
mcos/
├── docs/                 # 12 RFCs (00–11) + fixtures + REPOSITORIES.md
├── doc/                  # Early Chinese brainstorm notes
├── mcos-sdk/             # 插件契约（McosPlugin、CommandHandler、…）+ Memory/ResolveResult 类型
├── mcos-runtime/         # Parser → IR、Registry、Executor、Permission、Audit、Workflow、EventBus、Memory、LLM
├── mcos-android/         # Jetpack Compose CLI / Chat 外壳 + Android host services
├── plugins/              # hello、system、camera、files
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **源代码模块：** ✅ 6 个（sdk、runtime、android + 4 插件），见 §2
- **构建系统：** ✅ Gradle Kotlin DSL 多模块（JDK 17、Kotlin 2.0.21、AGP 8.7.3、minSdk 26）
- **Golden fixture：** ✅ 8 个用例（[`docs/fixtures/`](../fixtures/) 下 5 正向 + 3 负向）——由 `DslParserTest` 执行并通过

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

状态图例：✅ 已实现 · 🟡 部分 · ⬜ 仅规范（未开始）。落地 commit 按行引用（2026-08-12，`main` 分支）。

| Subsystem | 规范文档 | 目标阶段 | 状态 |
|-----------|----------|--------------|------|
| **Parser**（DSL → IR） | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1（最先） | ✅ `parse/`（Lexer、Parser、DslParser、Canonicalizer）；fixture 全绿 |
| **IR 类型** | [02](./02-command-protocol.md) §7 | P1 | ✅ `ir/IrTypes`（ExecutionIr + Step、payload 信封） |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 | ✅ `registry/CommandRegistry`（register/resolve/unregister，id=name） |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 | ✅ `permission/PermissionKernel.decideConfirmation`（NORMAL/ELEVATED） |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 | 🟡 `McosRuntime` 内进程内 FIFO 队列；尚无优先级通道 |
| **Executor** | [03](./03-runtime.md) §9 | P1 | ✅ `executor/Executor`（步骤、产物、确认、取消、限流） |
| **Audit 日志** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1（基础） | ✅ `audit/AuditLog`（append、filter、rotate、sha256 + HMAC 链） |
| **Planner 桥** | [06](./06-agent.md) | P1（单一 provider） | ✅ `llm/` LlmPlanner + OpenAiLlmProvider + ChatOrchestrator，通过可插拔 `LlmHttpTransport`（JDK `HttpClient` 默认 + Android `HttpURLConnection`）接入 Android 聊天外壳；API key 经 `AndroidSecureStore` 持久化 |
| **Network Egress 策略** | [08](./08-security.md) §12（`decideEgress`） | P1 | ✅ `security/NetworkEgressPolicy.decideEgress` |
| **Prompt Injection 检测** | [08](./08-security.md) §11 | P1（编译器侧） | ✅ `llm/PromptInjectionDetector` |
| **Rate Limiting** | [08](./08-security.md) §10 | P1（每插件/分钟） | ✅ `security/RateLimiter`（每插件/分钟） |
| **Secret 管理**（`{{secret}}` 模板） | [08](./08-security.md) §9 | P1 | ⬜ 未实现 |
| **Crash-loop 隔离** | [08](./08-security.md) §15.3 | P1 | ⬜ 未实现 |
| **进程隔离** | [08](./08-security.md) §8 | P1（尽力而为）→ P3（第三方默认） | 🟡 仅进程内尽力而为的并发控制 |
| **Workflow 引擎** | [05](./05-workflow.md) | P2 | ✅ `workflow/WorkflowEngine` — sequential/parallel/if/loop/retry/try/confirm，命名 store + JSON 解码；已接入 `McosRuntime.runWorkflow`（`d533c05`） |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 | ✅ `events/EventBus` — 类型化信封、前缀 + where 过滤、订阅者隔离、丢最旧背压 + 审计（`22ba52b`） |
| **Memory** | [07](./07-memory.md) | P2 | ✅ `memory/MemoryStore` — TTL、标签、模糊 resolveRef + 置信度、CREATED/UPDATED/CONFLICT 写入语义、superseded 历史（`d549236`） |
| **企业策略** | [08](./08-security.md) §13 | P3 | ⬜ 未开始 |
| **Marketplace** | [09](./09-marketplace.md) | P3 | ⬜ 未开始 |

> **已完成：** `DslParser`（最高杠杆的第一步）与其余 P1 流水线一同交付。P1 安全底线中 `decideConfirmation`、`decideEgress`、prompt-injection 检查与 rate limiting 均已实现；**crash 隔离与 `{{secret}}` 模板仍为空白**。
>
> **测试基线（2026-08-13）：** 全模块 490 个测试——parser fixture、executor、permission、audit（含 `x-mcos-secret` 脱敏）、workflow（W1-W6）、event bus（8）、memory（M1-M33 + 情景 E1-E14 + 摘要 S1-S11）、secret 解析器、crash 隔离、插件、多 provider（R1-R8 registry + F1-F6 回退链 + T1-T8 原生工具调用 + O1-O10 本地隐私闸门 + **T-transport 7 个测试：provider↔transport 错误映射 + 对本地 HTTP 服务器的真实 JDK `HttpClient` 往返**）、Android。

---

## 4. Fixture 覆盖

[`docs/fixtures/`](../fixtures/) 下的 golden 用例。全部 8 个均由 `DslParserTest` 执行并通过。

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
| Parser + IR | 规范完成 | ✅ **已实现** 完整 DSL↔IR | — | — |
| Registry + Executor | 规范完成 | ✅ **已实现** | — | — |
| Permission Kernel（`decideConfirmation`） | 规范完成 | 🟡 runtime 已实现；Android 确认 UI 未接线 | — | — |
| ConfirmationPrompt | 规范完成 | ✅ **已实现** NORMAL/ELEVATED | ✅ 破坏性 typed-ack | — |
| Network Egress（`decideEgress`） | 规范完成 | ✅ **已实现** | — | — |
| Prompt Injection 检测 | 规范完成 | ✅ **已实现** 编译器侧 | — | + 自适应模型侧 |
| Rate Limiting | 规范完成 | ✅ **已实现** 每插件/分钟 | ✅ + 每 recipe/小时 | + 自适应 |
| Audit | 规范完成 | ✅ **已实现** 基础 | ✅ **已实现** 加密 + 导出（HMAC 链） | 远程证明 |
| Workflow | 规范完成 | ✅ 顺序 | ✅ **已实现** 并行 / 条件 / 循环 / 重试 / try / 确认 | — |
| Event Bus | 规范完成 | ✅ run 事件通道 | ✅ **已实现** 完整（信封、过滤、隔离、背压） | — |
| Memory | 规范完成 | ✅ profile + remember | 🟡 模糊引用 + 冲突检测已完成；**情景层 + 云端同步未做** | 云端同步 |
| Planner | 规范完成 | ✅ 1 个 provider，chat→DSL | 🟡 多 provider + 探测未做（[06 §17](./06-agent.md)） | — |
| Plugins | 规范完成 | ✅ hello + system + camera + files（20+ 命令，[10 §4.3.1](./10-roadmap.md)） | ⬜ IoT + Intent | MCP spike (P2) / MCP 生产 + 市场 (P3) |
| Marketplace | 规范完成 | — | ⬜ 调试侧载 | 公共索引 + 签名（[09 §15](./09-marketplace.md)） |
| 进程隔离 | 规范完成 | 🟡 尽力而为（进程内） | — | 第三方默认 |
| 企业策略 | 规范完成 | — | — | ⬜ 允许/拒绝名单（[08 §13](./08-security.md)） |
| Crash-loop 隔离 | 规范完成 | ⬜ **未实现** | ✅ | ✅ |

---

## 6. 推荐开发路径

步骤 1–7 与 10 已实现（2026-08-12）；8–9 已通过 `AndroidHostServices` 部分接线。

1. ✅ **Gradle 多模块构建**——`mcos-sdk`、`mcos-runtime`、`mcos-android`、4 个插件，按 [REPOSITORIES.md](./REPOSITORIES.md)。
2. ✅ **`DslParser`**——按 [02](./02-command-protocol.md) §6 + §18；§4 中全部 fixture 通过。
3. ✅ **`CommandRegistry`**——从插件加载 `CommandDescriptor`；按 ID 解析。按 [03](./03-runtime.md) §6。
4. ✅ **`Executor`**——用校验过的参数调用 `CommandHandler`；异常映射为 `PLUGIN_ERROR`。按 [03](./03-runtime.md) §9。
5. ✅ **Schema 校验**——执行前按 `inputSchema` 校验参数。按 [02](./02-command-protocol.md) §9.1。
6. ✅ **`PermissionKernel`**——按 `sideEffectClass` 实现 grant/deny/confirm 流程。按 [08](./08-security.md) §4。
7. ✅ **Audit（基础 + HMAC 链）**——本地追加 run 记录。按 [03](./03-runtime.md) §13。
8. 🟡 **真实插件处理器**——`camera.capture` / `sys.notify` 已通过 `AndroidHostServices` 接 Android API；确认 UI 待接线。按 [04](./04-plugin-sdk.md) §7。
9. 🟡 **`files` 插件**——`photo.search` / `photo.compress` 已实现；Android 相册搜索待做。按 [10](./10-roadmap.md) §4.3。
10. ✅ **多 provider Planner**——`LlmProvider` 能力模型（`Capability`：CHAT/PLAN/TOOL_CALL/EMBED）、`LlmProviderRegistry`（注册、能力路由、健康探测）以及 `LlmPlanner` 中按优先级排序的回退链（retryable 错误 → 下一 provider；§18.1 本地→云端回退）。按 [06](./06-agent.md) §17 V1。
11. ✅ **PlanMode `NATIVE_TOOL_CALL`**——按 provider 选择模式（TOOL_CALL → 原生工具调用，否则 FREEFORM_JSON）、`ToolCall`/`ToolDescriptor`/`TokenUsage` 类型、registry 命令投影（含尽力而为的示例解析）以及 `OpenAiLlmProvider` 中的 OpenAI `tools` 协议支持。按 [06](./06-agent.md) §3.2/§17 V1。
12. ✅ **本地→云端回退与隐私闸门**——`LlmProvider` 上的 `ProviderTier`（ON_DEVICE/CLOUD）、`LlmPlanner.cloudFallbackEnabled`（"允许云端 planner" 显式开关，06 §13.2）以及隐私闸门：一旦 ON_DEVICE provider 失败，升级到 CLOUD 需要显式开启——否则失败以 `CLOUD_FALLBACK_DISABLED` 拒绝呈现，且数据不出设备。标准错误码 `CAPABILITY_EXCEEDED`/`CLOUD_FALLBACK_DISABLED`；`LlmProviderRegistry.onDeviceProviders()`/`cloudProviders()` 分层过滤。按 [06](./06-agent.md) §13.0/§13.2/§17 V2。
13. ✅ **Android 聊天外壳（Planner 接入应用）**——可插拔 `LlmHttpTransport`（`llm/` 中的 `LlmHttpTransport`/`HttpTransportResponse`/`LlmTransportException`；JDK `HttpClient` 默认实现保持 JVM 测试绿色；`AndroidLlmHttpTransport` 使用 `HttpURLConnection`——Android 无 `java.net.http` 模块）、`OpenAiLlmProvider(transport=…)` 注入、Manifest 中的 `INTERNET` 权限，以及 `MainActivity` 中的 **AI Chat 卡片**（自然语言输入 → `ChatOrchestrator` → 计划/DSL 预填到 DSL 编辑器 → 执行事件记录；OpenAI API key 经 `AndroidSecureStore` 持久化）。按 [06](./06-agent.md) §17。

**下一步（建议）：** PlanMode `CONSTRAINED`（06 §17 V2），然后做云端同步 Memory 层（§16）。

---

## 7. SDK API 目标（规范契约）

完整的插件契约在 [04-plugin-sdk.md](./04-plugin-sdk.md) §5–6 中规定。实现已提供以下全部内容（四个插件均基于其编译）：

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
