# MCOS 运行时（Runtime）设计

> **语言:** [English](../en/03-runtime.md) · 中文（当前）

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-24  
> **Package:** `mcos-runtime`  
> **Depends on:** [01-architecture.md](./01-architecture.md), [02-command-protocol.md](./02-command-protocol.md)

> ✅ **实现状态：** 下述运行时管线**已实现**——Parser、Registry、Executor、Workflow Engine、Event Bus、Memory 与 Audit 位于 `mcos-runtime-core`；Permission Kernel、egress 策略与审计落盘位于 `mcos-security`；薄门面 `McosRuntime` 位于 `mcos-runtime`。已知 🟡 差距：Scheduler 仍是进程内 FIFO 队列（尚无优先级通道）；第三方进程隔离尚未强制（P3，见 [08-security.md](./08-security.md) §8）。逐子系统状态见 [11-implementation-status.md](./11-implementation-status.md) §3。

---

## 1. 角色

运行时（Runtime）是**命令总线内核（Command Bus kernel）**。

它不理解相机、空调或 GitHub。  
它只理解：**parse → validate → authorize → schedule → execute → audit**。

```text
DSL / IR / Workflow
        │
        ▼
┌───────────────────┐
│      Parser       │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Command Registry  │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Permission Kernel │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│ Scheduler / WF    │
└─────────┬─────────┘
          ▼
┌───────────────────┐
│     Executor      │
└─────────┬─────────┘
          ▼
      Plugin Host
```

---

## 2. 设计目标

| 目标 | 细节 |
|------|--------|
| 正确性（Correctness） | 在授权 + schema 通过之前不产生任何副作用 |
| 确定性（Determinism） | 相同的 IR + 相同的授权 → 相同的调度路径 |
| 可隔离性（Isolability） | 插件崩溃不得杀死内核 |
| 可观测性（Observability） | 每次运行都有 `RunId` 和审计轨迹 |
| 可测试性（Testability） | 解析器/策略可在无 Android 环境下进行纯 JVM 测试 |
| 可扩展性（Extensibility） | 无需发布运行时新版本即可添加新插件 |

---

## 3. 模块映射

```text
mcos-runtime/
├── parser/          # DSL lexer, parser, IR codec
├── registry/        # Command Registry + plugin loader hooks
├── planner-bridge/  # Optional interface to AI Planner (not the LLM itself)
├── permission/      # Permission Kernel
├── scheduler/       # Queues, concurrency, cancellation
├── workflow/        # Graph interpreter (see 05)
├── executor/        # Handler invocation
├── memory/          # Facade over Memory engine (see 07)
├── eventbus/        # Typed event pub/sub
├── audit/           # Append-only execution log
├── host/            # Android/JVM host adapters
└── api/             # Public Runtime API for App / IPC
```

### 3.1 启动与关停时序

运行时以固定顺序启动与排空，确保任何阶段都不会看到一个尚未启动的依赖。

**启动顺序**（每一步都会等待其发出 READY 信号后才开始下一步）：

```text
1. AuditLog           — open encrypted store; single-writer channel ready
2. CommandRegistry    — discover manifests, verify signatures, register descriptors
3. PermissionKernel   — warm grant cache from persistent store
4. EventBus           — subscribe built-in event sources (power/connectivity/…)
5. Scheduler          — spin up queue channels + worker coroutines
6. McosRuntime        — bind subsystems, publish RuntimeFacade, mark READY
```

原因：Audit 最先启动，这样后续每一步的加载事件都会被记录；Registry 必须在 PermissionKernel 能够校验授权主体之前就填充好；Scheduler 在各子系统中最后启动，使其无法在 Authorization 能够打戳（stamp）之前接受工作。

**关停顺序**（逆序，并带有显式的宽限）：

```text
1. Stop accepting new ExecuteRequest         (RuntimeFacade returns UNAVAILABLE)
2. Scheduler: drain queues with graceTimeout (default 5 s); reject enqueues
3. Cancel in-flight runs                     (cooperative cancel → forced after 2 s)
4. Flush AuditLog                             (await channel drain; ≤20 ms budget)
5. Release plugin classloaders                (per §16 unload)
6. Release subsystems / close store
```

**前台服务（foreground service）钉扎状态机**（Android；见 [01 §7.3](./01-architecture.md)）：

```text
runStarted (active runs: 0 → 1)  → startForeground(notification, cancelAction)
runEnded   (active runs: 1 → 0)  → stopForeground(after short grace, to avoid flapping)
```

运行时**绝不能**将自身生命周期绑定到单个插件的前台服务生命周期上 —— 只能绑定到"存在任意活跃运行"这一聚合判定上。

---

## 4. 公共运行时 API（逻辑层）

> `McosRuntime` 是 [01 §7.2](./01-architecture.md) 中规范化定义的 `RuntimeFacade` 接口的**进程内实现**。在 MVP（P1，单进程）阶段，App 直接持有 `McosRuntime` 引用；在 V1（多进程）阶段，一个 AIDL 代理跨 `:runtime` 进程边界代理同一套接口（`IRuntimeService.aidl` 方法到 AIDL 的映射见 [01 §7.3](./01-architecture.md)）。下面的 `execute` / `preview` / `cancel` / `observe` 方法与 `RuntimeFacade` 一一对应。

```kotlin
interface McosRuntime {
    suspend fun execute(request: ExecuteRequest): ExecuteHandle
    suspend fun preview(request: ExecuteRequest): PreviewResult   // parse+auth dry-run
    fun cancel(runId: RunId)
    fun observe(runId: RunId): Flow<RuntimeEvent>

    fun registry(): CommandRegistry
    fun permissions(): PermissionKernel
    fun memory(): MemoryFacade
    fun events(): EventBus
}
```

### 4.1 `ExecuteRequest`

> 完整字段集（包括 `Source`、`Payload`、`ConfirmationMode`，以及 `correlationId`/`traceId` 溯源字段）规范定义见 [01 §11.6](./01-architecture.md)。下面的形状是简写形式。

```kotlin
data class ExecuteRequest(
    val source: Source,              // CLI, CHAT, VOICE, EVENT, API
    val payload: Payload,            // DslText | IrJson | WorkflowRef
    val dryRun: Boolean = false,
    val confirmationMode: ConfirmationMode = ConfirmationMode.POLICY,  // POLICY | ALWAYS_CONFIRM | NEVER_CONFIRM (latter only for read)
    val correlationId: String? = null,
)
```

### 4.2 `RuntimeEvent`

`RuntimeEvent` 是一个 **11 变体的密封类（sealed class）**，规范定义见 [01 §11.5](./01-architecture.md)（`RunStarted`、`StepStarted`、`Progress`、`Artifact`、`Log`、`ConfirmationNeeded`、`StepSucceeded`、`StepFailed`、`RunSucceeded`、`RunFailed`、`RunCancelled`）。本文档不再重复声明；实现**必须**使用架构文档中的定义。`observe(runId)` 返回一个冷流 `Flow<RuntimeEvent>`，当运行到达终态（`RunSucceeded` / `RunFailed` / `RunCancelled`）时该流完成。

**Run 事件通道保证**（`TypedEventBus` 的实现语义）：

| 属性 | 规则 |
|------|------|
| **隔离** | 每个 run 的事件位于**独立的** per-run 流中，拥有自己的重放缓冲（`RUN_REPLAY = 512`）。一个 run 的历史永远不可能被**其他** run 的流量挤出——这正是 per-run 隔离所消除的故障模式。 |
| **早订阅者** | `execute()` 在 launch 的协程发布 `RunStarted` 之前就返回 handle，因此对尚未发布过事件的 run id 调用 `observe()` 会**等待**首个事件，而不是空完成。 |
| **晚订阅者** | 先重放该 run 的缓冲历史，再跟随实时事件；在终态事件处完成。 |
| **保留** | 最近 `MAX_RETAINED_FINISHED_RUNS = 128` 个已结束 run 的重放历史按 FIFO 保留。观察已被驱逐的结束 run 会**空完成**（tombstone 记录）；超出 tombstone 窗口的古老 id 行为等同于尚未开始的 run。 |
| **背压** | 在 run 自己的缓冲内 drop-oldest；发布方永不阻塞。 |

---

## 5. 解析器（Parser）子系统

职责：

1. 对 DSL 文本进行词法分析  
2. 构建调用 / 序列 AST  
3. 编码/解码 IR JSON  
4. 附加 `dslVersion`  
5. 产出可操作的 `PARSE_ERROR` 诊断（行/列）  

非职责：

- 解析 Memory 引用（由 Executor / pre-exec expand 阶段处理）  
- 调用 LLM  
- 加载插件  

**严格模式：** IR 对象中出现未知键 → 报错。  
**宽松模式（仅限开发）：** 可仅告警；生产环境默认绝不启用。

### 5.1 内部流水线架构

`parser/` 包是一个四阶段流水线。它所实现的**文法**（token 表、ABNF、数字/字符串边界、错误定位精度）规范定义见 [02 §6](./02-command-protocol.md)；本节规定的是解析器的*内部*结构，而非文法本身。

```text
DSL text
   │
   ▼
┌──────────┐   tokens    ┌──────────┐   raw AST   ┌──────────┐  ExecutionIr  ┌──────────┐
│  Lexer   │ ──────────▶ │  Parser  │ ──────────▶ │ IR Codec │ ────────────▶ │ Canonical │
│ (tokens) │             │ (AST)    │             │ (mem)    │                │ izer      │
└──────────┘             └──────────┘             └──────────┘                └──────────┘
                              │                                                     │
                              ▼                                                     ▼
                        PARSE_ERROR (shape A, §02/8.3)                    canonical IR (hashable)
```

**Lexer（词法分析器）。** 按 [02 §6.6](./02-command-protocol.md) 产出 token 流。空白/BOM 规则同该节。词法分析器是行/列计账的唯一拥有者 —— 每一个 `PARSE_ERROR.location` 都源自此处。

**Parser（解析器）。** 对 token 流做递归下降（recursive-descent）。产出一份**原始 AST**（未按键排序、未小写化），随后交给编解码器。v0.1 为**快速失败（fail-fast）**：第一个语法错误即中止解析，并发出 [02 §8.3](./02-command-protocol.md) shape A 的单一错误信封。多错误收集是未来的扩展（非规范化；标记为 v0.2）—— 一致性测试夹具（`docs/fixtures/06-08`）只断言单错误信封，因此快速失败是一致的行为。

**IR Codec（`ExecutionIr`）。** 线上传输的 JSON 形状见 [02 §7](./02-command-protocol.md)；内存中的 Kotlin 类型与之不同，以便运行时在不序列化的情况下携带溯源信息：

```kotlin
sealed class ExecutionIr {
    abstract val dslVersion: String
    abstract val meta: IrMeta?            // §02/8.2 provenance; null for non-llm sources

    data class Invoke(
        override val dslVersion: String,
        val id: String,                   // pre-canonicalization: may be mixed-case
        val args: JsonObject,             // un-sorted at this stage
        override val meta: IrMeta? = null,
    ) : ExecutionIr()

    data class Sequence(
        override val dslVersion: String,
        val steps: List<Invoke>,          // order preserved (semantically meaningful)
        override val meta: IrMeta? = null,
    ) : ExecutionIr()

    data class WorkflowRef(
        override val dslVersion: String,
        val workflowId: String,
        val body: JsonObject,             // Workflow IR; opaque to this layer (see 05-workflow.md)
        override val meta: IrMeta? = null,
    ) : ExecutionIr()
}
```

**规范化器（Canonicalizer）。** 应用 [02 §7.5](./02-command-protocol.md) 的递归算法 —— 小写化 `id`、递归排序对象键（数组**不**排序；`meta` **不**排序）、按 schema 规范化数字。输出是**规范化 IR**，其 UTF-8 JSON 字节正是审计记录和被固定工作流所哈希（SHA-256）的内容。

**编解码对称性契约（往返保证）。** 解析 `input.dsl` 并重新序列化规范化 IR，**必须**产出与 [`docs/fixtures/`](../fixtures/) 中对应 `expected.ir.json` 字节完全一致的输出。这是 [02 §16.1](./02-command-protocol.md) 的一致性断言。因此该编解码器是对称的：对每个正例夹具都有 `serialize(canonicalize(parse(text))) == expected`，对每个负例夹具 `parse(text)` 都产出匹配的 `PARSE_ERROR`。

**语法糖宏注册表（Stage 4 钩子）。** 解析器**不**展开语法糖 —— 那是执行流水线中的 [Stage 4 — Expand](./01-architecture.md)。运行时维护一张小型、内置的语法糖表，在 Stage 4 查阅：

| 语法糖 | 展开 | 所有者 |
|-------|-----------|-------|
| `date="today"` / `"yesterday"` / 相对日期 | RFC 3339 时间戳范围 | 运行时（硬编码；P1） |
| `x-mcos-ref` 字段 | `MemoryFacade.resolveRef()` → 具体 id | 记忆门面（[07 §6](./07-memory.md)） |
| `x-mcos-default-from-memory` | 参数缺失时从 Memory 注入 | 记忆门面 |

插件声明的自定义语法糖是**未来的扩展**（非规范化；v0.2）—— v0.1 的语法糖完全由运行时内置。解析器必须将语法糖 token 原样保留在 AST 中；展开发生在 Registry 解析之后，以便语法糖表能感知 schema。

---

## 6. 命令注册表（Command Registry）

### 6.1 职责

- 按命令 ID 索引描述符  
- 跟踪插件归属和版本  
- 向规划器（Planner）提供工具 schema（经过过滤的视图）  
- 在插件安装/卸载时热重载（hot-reload）  

### 6.2 解析策略

```text
request id + optional version range
  → exact match preferred
  → else highest compatible minor/patch for same major
  → else UNKNOWN_COMMAND
```

被固定（Pinned）的工作流**应当（SHOULD）**存储已解析的版本以保证可复现性。

### 6.3 注册表视图

| 视图 | 消费者 |
|------|----------|
| Full（完整） | 开发者工具 |
| User-enabled（用户已启用） | 规划器 + CLI 自动补全 |
| Policy-allowlisted（策略白名单） | 企业模式 |

规划器永不会看到已禁用或被禁止的命令。

### 6.4 内部数据结构

注册表在同一个 `CommandDescriptor` 集合上维护三个内存索引（描述符 schema 规范定义见 [02 §8](./02-command-protocol.md) + [01 §10](./01-architecture.md)）：

| 索引 | 键 | 结构 | 服务于 |
|-------|-----|-----------|--------|
| **by-id** | `commandId`（小写） | `HashMap<String, SortedSet<DescriptorEntry>>` | Stage 3 Resolve（主查找） |
| **by-alias** | `alias`（小写） | `HashMap<String, String>` → 主 id | Stage 3 别名解析 |
| **by-namespace** | 命名空间前缀（如 `camera`、`sys`） | Trie（前缀树） | 规划器自动补全 / "列出 `camera.*` 下所有命令" |

`DescriptorEntry` 用其 `pluginId` 和已安装的 `version` 包装一个 `CommandDescriptor`。每个 command-id 下的 `SortedSet` 持有该 id 的**所有已安装版本**，按 SemVer 降序排列 —— 这正是使版本共存（[02 §4.4](./02-command-protocol.md)）具体化的数据结构。

**版本选择算法**（Stage 3 Resolve）：

```text
resolve(requestedId, versionRange?):
  entries = by-id[requestedId.lowercase()]      # may walk alias map first
  if entries == null: → UNKNOWN_COMMAND (details.requestedId, optional suggestions from by-namespace trie)
  candidates = entries.filter(e -> versionRange.compatible(e.version))
  if candidates.empty: → UNKNOWN_COMMAND (reason: "no_compatible_version")
  return candidates.first()                       # SortedSet is desc by SemVer → highest compatible
```

"兼容（Compatible）" = 相同 major，minor/patch ≥ 请求的最低版本。被固定的工作流存储精确的已解析 `version`，使跨重装的重新解析具有确定性。

**注册表视图**（§6.3）在 `by-id` 上惰性计算："User-enabled" 视图过滤掉其插件被禁用的描述符；"Policy-allowlisted" 视图进一步与 `RuntimeConfig.enterpriseAllowlist` 取交集。视图在插件安装/卸载以及 `enterpriseAllowlist` 热重载（§19.1）时重新计算。

### 6.5 热重载（Hot-Reload）机制

插件安装与卸载**不是**瞬时完成的 —— 它们必须与飞行中的运行协调。

**安装（Install）：**

```text
1. Discover manifest (classpath / download dir / sideload APK — see §16)
2. Verify signature                              (§16; marketplace key or built-in trust)
3. Namespace-conflict arbitration                (02 §4.4 priority table; loser → load denied + details)
4. Register descriptors into by-id / by-alias / by-namespace
5. Bind handlers (in-process singleton or remote Binder)
6. Emit RegistryChanged event
7. Audit: plugin.installed
```

**卸载（Unload）：**

```text
1. Mark plugin STOPPING → reject new invokes targeting its commands (Stage 3 → UNKNOWN_COMMAND, reason "unloading")
2. Drain in-flight: wait up to graceTimeout (default 5 s) for runs referencing this plugin to finish
3. After grace: force-cancel remaining runs (cooperative → forced per §9.4) → they emit RunFailed(CANCELLED)
4. Unregister descriptors from all three indexes
5. Release plugin classloader (§16)
6. Emit RegistryChanged event
7. Audit: plugin.uninstalled
```

**被固定的工作流引用了已卸载的描述符：** 如果一个被固定的工作流（存储了已解析版本）在其插件被卸载之后执行，Stage 3 Resolve 返回 `UNKNOWN_COMMAND`，其中 `details.reason = "plugin_unloaded"` 和 `details.requestedId`。UI 层**应当（SHOULD）**将其呈现为"提供 `<command>` 的插件已被移除 —— 请重装或选择其他命令"。运行时**绝不**静默回退到其他插件的同名命令。

---

## 7. 权限内核（Permission Kernel）

详细模型见 [08-security.md](./08-security.md)。运行时的职责：

```text
for each invocation:
  required = descriptor.permissions ∪ plugin.permissions
  missing = required - grants
  if missing: emit ConfirmationNeeded or fail PERMISSION_DENIED
  if sideEffect needs confirm: emit ConfirmationNeeded
  else: stamp auth token onto ExecutionContext
```

授权令牌是**短生命周期、运行作用域（run-scoped）内**的，插件无法伪造。

---

## 8. 调度器（Scheduler）

### 8.1 队列

| 队列 | 用途 |
|-------|-----|
| `interactive` | 面向用户的 CLI / 聊天（低延迟） |
| `workflow` | 多步骤任务 |
| `background` | 事件触发 / 延迟执行 |
| `expedited` | 仅用于用户确认的安全关键型取消 |

### 8.2 并发

默认策略（可调）：

- 全局最大并行调用数：`4`  
- 每个插件最大并行数：`2`  
- `destructive` 最大并行数：`1`  
- IoT 控制命令：按设备 ID 串行  

### 8.3 取消

```text
cancel(runId)
  → mark run CANCEL_REQUESTED
  → cancel coroutine jobs
  → best-effort plugin cancel()
  → audit RunCancelled
```

### 8.4 公平性与背压（Backpressure）

四个队列（§8.1）中的每一个都是一个**有界 `Channel<Runnable>`**（容量 64）。当某个队列已满时，Stage 7（Schedule）拒绝入队并返回 `RATE_LIMITED`，附带 `details.retryAfterMs`（指数退避：首次拒绝 500 ms，同一运行的每次重复拒绝翻倍，上限 30 s）。

**队列内顺序：** 默认 FIFO。`expedited` 是唯一支持**抢占式优先级（preemption-style priority）**的队列：在 `expedited` 上入队的取消，**仅当**全局并发上限没有空闲槽位时，才会被先于更旧的 `interactive`/`workflow`/`background` 项拉取 —— 它不会抢占正在运行的处理器（取消是协作式的，见 §8.3 / §9.4）。只有取消类运行请求可以使用 `expedited`；任何其他命令类型入队到该队列都属于 `PARSE_ERROR` 类配置错误（在 Stage 7 以 `INTERNAL` 拒绝）。

**跨队列公平性：** 队列之间没有严格的优先级 —— 每个队列都有一个由其并发上限设定大小的专用工作线程池，因此一个饱和的 `background` 队列不会饿死 `interactive`。全局上限（4）由一个共享的信号量（semaphore）强制执行，在分发前获取、在处理器完成时释放。

**可观测性：** 队列深度和信号量等待时间作为运行时指标暴露（日志 + 可选的指标接收端）。持续深度 > 32（容量的一半）会触发一个 `SchedulerBackpressure` 日志事件，以便 UI 呈现"系统繁忙"。

### 8.5 死锁（Deadlock）预防（按设备串行）

"IoT 控制按设备 ID 串行"这一规则（§8.2）由一个 `Mutex<DeviceId>` 映射强制执行：一个针对设备 `D` 的 `control`/`destructive` 命令在处理器分发前获取 `mutex[D]`，并在完成时释放。这防止了两个并发命令竞争同一个物理设备。

**死锁风险：** 一个工作流在设备 A 上调用 `control`，然后（在仍持有 A 的情况下）在设备 B 上调用 `control`，而 B 本身又在等待 A，就会死锁。MCOS 用**严格的禁止嵌套获取规则**来防止这种情况：

- 一个工作流步骤**可以（MAY）**声明 `requiresDevices: ["device-A"]`（由运行时在 Stage 4 Expand 时通过设备 ID 字段从参数中解析）。
- 运行时在 Stage 7、分发之前，**原子地**获取所有已声明的设备互斥量（按排序的加锁顺序）。
- 一个工作流运行**绝不能**跨步骤边界将一个设备互斥量带入一个获取另一个不同设备的步骤。运行时检测到嵌套获取尝试（同一 `runId` 已持有一个互斥量时又请求第二个 `requiresDevices`），并以 `CONFLICT` 拒绝（`details: { heldDevice, requestedDevice, runId }`）。

这使得按设备加锁变为**分级的**（一次性全部获取、按序），而非增量式 —— 这是标准的无死锁加锁准则。确实需要对两个设备操作的工作流必须在单个步骤的 `requiresDevices` 中声明两者。

---

## 9. 执行器（Executor）

### 9.1 ExecutionContext

```kotlin
data class ExecutionContext(
    val runId: RunId,
    val stepId: StepId?,
    val commandId: String,
    val args: JsonObject,          // validated
    val auth: AuthStamp,
    val deadline: TimeMark,
    val progress: ProgressEmitter,
    val services: HostServices,    // limited facade
)
```

插件只能接收到其清单（manifest）允许的 `HostServices` —— 长期来看不是一个原始的 `Context` 上帝对象。MVP 阶段可以传入 Android `Context`，但需辅以 lint 纪律；V1 会收紧这一点。

### 9.2 分发

```text
Registry.resolve(commandId)
  → PluginManager.lookup(pluginId)
  → handler.invoke(ctx)
  → map outcomes to RuntimeEvent
```

> [04 §6](./04-plugin-sdk.md) 中历史上用于面向插件服务门面的 `PluginHost` 名称，已在 [01 §11.1](./01-architecture.md) 中标准化为 **`HostServices`**。`HostServices` 是执行器注入到 `ExecutionContext.services` 的对象；`PluginManager`（上文）是执行器用来查找处理器实例的内部句柄。它们是不同的对象 —— 不要混淆。

### 9.3 失败映射

未处理的异常 → `PLUGIN_ERROR`，附带脱敏（sanitized）后的消息。  
栈轨迹仅在开发者模式的审计通道中出现。完整的异常 → 错误码映射规范定义见 [01 §10.3](./01-architecture.md)（`Throwable.toMcosError()`）；每个错误码的 `details` schema 是 [02 §8.3](./02-command-protocol.md) shape B。

### 9.4 分发生命周期（超时与协作式取消）

执行器通过结构化并发强制执行描述符的 `timeoutMs`（[02 §8](./02-command-protocol.md)，默认 60000）。完整的取消/超时语义表见 [01 §8.2](./01-architecture.md)；运行时特有的生命周期如下：

```text
handler.invoke(ctx) with withTimeout(timeoutMs):
  ├─ normal return     → CommandResult.Ok → Stage 9 (ValidateOutput)
  ├─ handler throws    → toMcosError() → CommandResult.Err
  ├─ timeout fires     → cooperativeCancel() then await up to cancelGraceMs (default 2000)
  │      ├─ handler returned within grace → TIMEOUT (clean)
  │      └─ grace expired → job.cancel() (forced) → TIMEOUT; mark plugin "cancel-unresponsive"
  └─ external cancel   → same cooperative-then-forced path, emit RunCancelled
```

**针对不让出插件（non-yielding plugins）的看门狗。** 一个反复忽略协作式取消（触发强制取消路径）的插件会被跟踪。在 60 秒内发生三次强制取消会触发**熔断器（circuit breaker）**（[01 §15.3](./01-architecture.md)）：该插件被标记为不健康，并施加 30 秒冷却期，在此期间 Stage 8 对该插件返回 `UNAVAILABLE`，并通知用户。持续触发会升级为自动卸载（§16）。

**Activity-result 桥接。** 某些处理器（[04 §7.2](./04-plugin-sdk.md)）需要一个 Android `Activity` 结果（例如通过 `ACTION_IMAGE_CAPTURE` 的相机拍照）。运行时桥接这一点：插件调用 `ctx.services.ui().startActivityForResult(intent)`，这会**挂起处理器协程**；运行时注册一个 `onActivityResult` 回调；当操作系统交付结果时，协程带着 `Intent` extras 恢复。这使得处理器仍然是一个纯粹的挂起函数，对插件作者而言没有可见的回调线程。`timeoutMs` 截止时间在 activity-result 挂起期间继续运行 —— 一个永远不返回该 activity 的用户最终会触发 `TIMEOUT`。

### 9.5 `McosException`（插件声明的错误通道）

[01 §10.3](./01-architecture.md) 引用了 `McosException` 作为插件为声明特定错误码而抛出的异常类型；它在此定义：

```kotlin
package com.morainet.mcos.sdk

data class McosException(
    val code: String,            // MUST be a valid McosErrorCode (01 §15.1) or plugin-namespaced code
    override val message: String,
    val retryable: Boolean = false,
    val details: JsonObject = JsonObject(emptyMap()),  // adheres to 02 §8.3 shape B for the code
) : RuntimeException(message)
```

**映射规则：** 当处理器抛出 `McosException` 时，执行器**直接**映射它 —— `error.code = exception.code`，`error.details = exception.details`，`error.retryable = exception.retryable`。它**不**经过通用的 `Throwable.toMcosError()` 启发式。这是插件能够发出非 `PLUGIN_ERROR` 错误码的**唯一**通道（例如一个相机插件抛出 `McosException("UNAVAILABLE", "camera hardware busy", retryable=true)`）。

来自处理器的任何其他 `Throwable` → 通过通用映射变为 `PLUGIN_ERROR`（`message` 被脱敏，非开发者审计中无原始栈）。插件**应当（SHOULD）**优先为预期的失败模式抛出 `McosException`，并让意外异常以 `PLUGIN_ERROR` 形式浮出。

---

## 10. 工作流集成

运行时内嵌工作流引擎（Workflow Engine，见 [05-workflow.md](./05-workflow.md)）：

- 多行 DSL 中的顺序语法糖会变成一个平凡的工作流  
- 并行 / 条件 / 重试位于工作流 IR 中  
- 每个节点的执行都会重新进入权限内核 + 执行器  

运行时只在**工作流声明的补偿动作（compensating actions）**范围内负责**事务** —— 不负责跨 IoT 厂商的分布式两阶段提交（2PC）。

---

## 11. 事件总线（Event Bus）

### 11.1 事件信封

```json
{
  "type": "connectivity.wifi.connected",
  "timestamp": "2026-08-06T14:00:00+08:00",
  "payload": { "ssid": "Office", "bssid": "..." },
  "source": "sys.connectivity"
}
```

### 11.2 订阅

工作流和插件可以带过滤器订阅：

```json
{
  "typePrefix": "connectivity.wifi.",
  "where": { "ssid": "Office" }
}
```

**安全：** 事件 → 动作规则所需的权限与手动调用相同。禁止通过事件进行静默的权限提升。

### 11.3 内置事件源（MVP+）

| 来源 | 示例 |
|--------|----------|
| 电源（Power） | `battery.low`, `battery.charging` |
| 连接性（Connectivity） | `wifi.connected`, `wifi.disconnected` |
| 通知（Notification） | `notify.posted`（需用户选择加入的监听器） |
| 位置（Location） | `location.significant_change`（需用户选择加入） |
| 时间（Time） | `time.schedule` |
| 插件自定义（Plugin custom） | 命名空间归属于插件 ID |

> `time.schedule` **为宿主保留**：桥接持久调度器（`AlarmManager`/`WorkManager`）的宿主可以把它当作普通事件发出。运行时自身的调度触发器（[05 §9.3](./05-workflow.md)）则**刻意**不经总线 —— [§11.4](#114-投递语义) 的投递是 at-most-once 无重投，与 misfire 恢复不兼容；它们直接启动工作流。

### 11.4 投递语义

| 属性 | 规则 |
|----------|------|
| **订阅匹配** | `typePrefix` 是对 `event.type` 的**字符串前缀**匹配（如 `"connectivity.wifi."` 匹配 `"connectivity.wifi.connected"`）。`where` 是**深度相等（deep equality）** —— 递归对象比较；过滤器中的每个键都必须以相等的值存在于 `event.payload` 中（载荷中额外的键被忽略）。无通配符、无正则、无 JSONPath。 |
| **投递保证** | **最多一次（At-most-once）**（进程内）。事件总线不持久化事件；在触发时未注册的订阅者会错过该事件。没有重投递。 |
| **订阅者隔离** | 每个订阅者在事件总线作用域的一个子 `SupervisorJob` 下运行。一个订阅者抛出异常**不会**终止兄弟订阅者或总线本身 —— 该异常被记录并作为 `SubscriberError` 警告审计，对其他订阅者的投递继续进行。 |
| **顺序** | 来自**单个来源**的事件按 FIFO 投递。来自**不同来源**的事件没有定义跨来源顺序 —— 订阅者**绝不能**假设存在全局顺序。 |
| **背压（Backpressure）** | 每个订阅者通过一个 `Channel.BUFFERED`（容量 64）排空其事件。如果订阅者跟不上且通道已满，则**丢弃最旧的未投递事件**并记录一条 `BackpressureDrop` 审计警告。发布者永远不会被阻塞。需要无损处理的订阅者**必须**及时消费，或使用工作流的 `wait_event` 节点（它有自己的队列）。 |
| **跨进程（V1）** | 事件总线位于 `:runtime` 进程中。插件进程中的订阅者通过 AIDL 回调接口（`IEventListener.onEvent(EventEnvelope)`）接收事件，通过 `RuntimeFacade.subscribe` 注册。远程订阅者的 Binder 死亡会自动取消其订阅。对远程订阅者的投递继承相同的最多一次 + 背压时丢弃语义（无分布式队列）。 |

---

## 12. 记忆门面（Memory Facade）

运行时通过一个狭窄的 API 向规划器/工作流暴露记忆（Memory）：

- `get(key)` / `put(key, value, policy)`  
- `resolveRef(ref)` 用于 `x-mcos-ref`  
- `search(query)` 用于语义召回  

存储引擎细节见 [07-memory.md](./07-memory.md)。

---

## 13. 审计日志（Audit Log）

### 13.1 记录形状

```json
{
  "runId": "run_abc",
  "timestamp": "...",
  "source": "CHAT",
  "ir": { "...": "canonical IR redacted" },
  "steps": [
    {
      "commandId": "camera.capture",
      "auth": { "grantsUsed": ["CAMERA"] },
      "result": { "ok": true },
      "durationMs": 900
    }
  ]
}
```

### 13.2 属性

- 追加式本地存储（推荐使用 Room / SQLCipher）  
- 敏感字段脱敏（redaction）  
- 用户可导出  
- 仅在显式选择加入时才进行远程同步  

### 13.3 存储 Schema 与脱敏（Redaction）

**存储 schema**（Room，经 SQLCipher 加密）。单一追加式表：

```sql
CREATE TABLE audit (
  run_id       TEXT    NOT NULL,
  ts           INTEGER NOT NULL,   -- epoch millis
  source       TEXT    NOT NULL,   -- CLI | CHAT | VOICE | EVENT | API
  ir_redacted  TEXT    NOT NULL,   -- canonical IR JSON, secrets redacted
  steps_json   TEXT    NOT NULL,   -- array of per-step records (commandId, auth, result, durationMs)
  PRIMARY KEY (run_id)
);
CREATE INDEX idx_audit_ts     ON audit(ts DESC);
CREATE INDEX idx_audit_source ON audit(source);
```

写入经过一个**单写者**协程（`Dispatchers.IO.limitedParallelism(1)`，[01 §8](./01-architecture.md)），由一个通道喂入；这保证了有序、非阻塞的写入，且永远不会拖累成功路径（通过卸载满足了 §17 中 >20 ms 的预算）。

**脱敏遍历算法。** 在 IR 被存储之前，运行时递归遍历 `ir.args`（以及 `steps[*].args`），应用以下规则：

1. 任何 schema 声明为 `x-mcos-secret: true`（[02 §5.3](./02-command-protocol.md)）的字段 → 值替换为 `"***REDACTED***"`。
2. 任何名为 `password`、`token`、`secret`、`apiKey`、`credential`（不区分大小写）且尚未标记的字段 → 同样替换（纵深防御；优先采用 schema 标记）。
3. Artifact URI：去掉查询字符串（如 `content://media/...?auth=abc` → `content://media/...`）；scheme + authority + path 保留以供取证。
4. `meta` 溯源字段（`source`、`confidence`、`utteranceId`、`correlationId`、`traceId`）**绝不**脱敏 —— 它们不是用户数据。

该遍历是确定性的，每次运行在 Stage 10（Audit）时、在规范化 IR 的一个副本上执行一次（飞行中的 `ExecutionContext.args` 不受影响）。

**保留策略。** 默认：**30 天 TTL** + **10,000 条记录上限**，先到者为准（最旧的被驱逐）。两者都可通过 `RuntimeConfig`（§19）调整。用户**可以**从"设置"中手动清除所有审计记录；运行时**绝不能**将已清除的记录自动同步到任何远程存储。开启 `auditFailClosed = true` 的企业模式：如果一次 Stage-10 写入失败（例如磁盘已满），**运行本身失败**并返回 `INTERNAL`，而不是静默丢弃审计记录。

**导出格式。** JSONL —— 每行一个 JSON 对象，每行是一个完整的审计记录（§13.1 的形状）。导出由用户通过 `RuntimeFacade.exportAudit(range?)` 发起，写入用户选择的 URI。导出**可选地签名**：对 JSONL 字节做 HMAC-SHA256，密钥是 Android Keystore 中一个设备绑定的密钥，签名作为末尾一行 `{"signature": "...", "algorithm": "HMAC-SHA256"}` 追加。这让下游消费者能够验证导出未被篡改，而无需运行时声称具备 CA 式证明。

---

## 14. 规划器桥（Planner Bridge）

运行时**不**内嵌 OpenAI 等厂商 SDK。

```kotlin
interface PlannerBridge {
    suspend fun compile(goal: GoalRequest): CompileResult
}
```

`CompileResult` 必须是 DSL/IR/工作流 —— 绝不是协议之外的插件调用列表。

### 14.1 修复循环（Repair-Loop）契约

当规划器发出的 IR 未通过运行时校验（解析或 schema）时，运行时**不**执行它。相反，它向桥返回一个结构化错误，以便规划器自我修正。规划器侧的循环规范定义见 [06 §7](./06-agent.md)；运行时侧的契约如下：

```kotlin
sealed class CompileResult {
    data class Ok(val ir: ExecutionIr, val warnings: List<String>) : CompileResult()
    data class Repair(val errors: List<ValidationError>) : CompileResult()   // ← re-prompt the planner
    data class Clarify(
        val question: String,
        val options: List<ClarifyOption>? = null,   // renders as option cards in the UI
        val slots: List<ClarifySlot>? = null,        // structured slot-fill prompts
    ) : CompileResult()
    data class Refuse(
        val reason: String,
        val category: RefuseCategory,                // why the plan was refused
        val suggestions: List<String>? = null,        // alternative approaches, if any
    ) : CompileResult()
}

data class ClarifyOption(val label: String, val value: String, val description: String? = null)
data class ClarifySlot(val name: String, val type: String, val required: Boolean)
enum class RefuseCategory { POLICY, IMPOSSIBLE, QUOTA, CAPABILITY }

data class ValidationError(
    val path: String,        // JSON-pointer into the IR, e.g. "/args/uris/0"
    val expected: String,    // type or constraint, e.g. "string" or "maxLength 65536"
    val actual: String,      // what was found, e.g. "number 80"
    val code: String,        // the McosErrorCode this would map to, e.g. "SCHEMA_VIOLATION"
)
```

`CompileResult` 是这些形状的**规范定义源**；[06 §5](./06-agent.md) 和 [06 §6](./06-agent.md) 引用它而不重定义。结构化的 `Clarify`/`Refuse` 载荷让 MCOS 与主流 AI agent 做法对齐：UI 可以把 `ClarifyOption` 渲染成选项卡片、把 `ClarifySlot` 渲染成槽位填写表单，并按 `RefuseCategory`（拒绝类别）分类拒绝，而不是解析自由文本。

`ValidationError` 的字段名刻意与 [02 §8.3](./02-command-protocol.md) 的 `SCHEMA_VIOLATION.details` 形状对齐，这样无论失败源自一个有类型的 `ExecuteRequest` 还是来自规划器的修复循环，用户看到的都是同样的诊断。

**最大尝试次数。** 运行时允许云端提供商 `maxRepair = 2`，端侧提供商 `maxRepair = 1`（[06 §6](./06-agent.md)）。运行时按 `utteranceId`（[02 §8.2](./02-command-protocol.md)）计数 Repair 轮次；当超过限制时，运行时返回 `Refuse("max_repair_exceeded")`，对用户呈现为 `COMPILE_FAILED`（[01 §15.1](./01-architecture.md)）—— 编译失败时执行器永远不会被进入。

**安全不变式。** 无论修复多少次，规划器的输出都是**不可信的（untrusted）**（[06 §14](./06-agent.md)）：它无法扩大授权（grants），无法绕过 Stage 6（Authorize），也无法隐藏 `destructive` 确认。一份被修复的方案在最终被接受的 IR 上支付与手工输入的 DSL 相同的完整 Stage 3→10 成本。

---

## 15. 宿主适配器（Host Adapters）

| 宿主 | 说明 |
|------|-------|
| Android | 长工作流使用前台服务（foreground service）；进程生命周期；通过 Binder API 对接 UI |
| JVM 单元测试 | 伪时钟、伪权限、内存注册表 |
| Desktop（未来） | 可选；不在 V1 范围内 |

### 15.1 Android 前台策略

- 交互式运行：尽可能绑定到 UI 可见性  
- 后台工作流：前台服务 + 带取消动作的通知  
- 精确闹钟 / 位置：仅在插件权限 + 用户启用允许时  

---

## 16. 插件加载（Plugin Loading）

注册表/加载器在完整的插件生命周期上协同。高层加载/卸载流程（保留以保持连续性）在下文用发现、校验、隔离和失败升级机制加以扩展。

```text
Discover manifests
  → verify signature (marketplace) / trust built-ins
  → register descriptors
  → bind handlers (in-process or remote Binder)
  → emit RegistryChanged event
```

卸载：

```text
Deny new invokes → wait in-flight (timeout) → unregister → release classloaders
```

（卸载排空机制详见 §6.5。）

### 16.1 清单发现

| 插件类别 | 加载器查找位置 | 信任级别 |
|--------------|------------------------|-------|
| **内置（Built-in）** | Classpath / 模块资源下的 `META-INF/mcos/plugin.json` | 受信任（随运行时一同发布） |
| **市场（Marketplace）** | 专用下载目录（由市场客户端管理，[09](./09-marketplace.md)） | 已校验（市场签名） |
| **旁加载（Sideload）** | 用户通过"设置" → "安装插件"选择的 APK/URI | 告警（生产构建中仅限调试） |
| **不可信（Untrusted）** | 任何未通过签名校验的内容 | 生产中阻止；仅在显式标记时于开发环境加载 |

发现发生在启动时（§3.1 第 2 步），以及由安装/卸载触发 `RegistryChanged` 时再次发生。加载器按 `plugin.id` 去重 —— 来自两个来源的同一 id 是一个命名空间冲突（§16.4）。

### 16.2 签名校验

市场插件**必须**携带市场签发的签名（[09 §6](./09-marketplace.md)）。运行时在注册任何描述符之前，针对市场公钥校验插件包。**离线行为：** 运行时缓存校验结果（key-id + 插件哈希 → 校验时刻），这样一个曾经校验过的插件无需重新联系市场即可加载。一个缓存校验时间早于市场吊销 TTL 的插件会在下次在线时被重新校验；如果无法重新校验且市场报告已吊销，则该插件按 §6.5 卸载。

内置插件跳过校验（它们随运行时自身的已签名 APK 一同发布）。生产构建中的旁加载插件被直接拒绝；在调试构建中它们以持续的"未校验"警告加载，并在每次确认时向用户呈现。

### 16.3 类加载器隔离

| 插件类别 | 类加载器 | 原因 |
|--------------|-------------|-----------|
| 内置 | 运行时类加载器（共享） | 受信任；需要直接访问运行时内部 |
| 市场 / 旁加载 | 每个插件一个专用 `DexClassLoader`（parent = 运行时类加载器） | 不可信；不得向运行时或兄弟插件泄漏类 |

**重复类规则：** 一个插件的隔离类加载器**可以**加载与另一个插件或运行时具有相同全限定名（FQ name）的类 —— 不存在全局的"先到先得"。插件先从自己的加载器解析自己的类，再从运行时父加载器解析。这意味着两个插件都能各自携带 `com.example.Logger` 而不发生冲突。运行时**绝不**反射进入插件的类加载器，除非调用其声明的 `entry` 类。

**卸载时释放：** 当 §6.5 的排空完成时，加载器丢弃对插件 `DexClassLoader` 的引用，允许 GC 回收。在 Android 上，关联的优化 DEX（`odex`/`oat`）留给系统回收；运行时不强制删除。

### 16.4 加载时的命名空间冲突仲裁

当两个插件试图注册相同的 command id（或别名）时，运行时应用 [02 §4.4](./02-command-protocol.md) 的优先级表：

```text
reserved roots  >  manifest-declared verified  >  first-to-load
```

仲裁的**失败方**在加载时被拒绝：其针对该冲突 id 的描述符不会被注册，加载会发出一个 `RegistryChanged` 事件，通过 `details: { requestedId, winningPlugin, losingPlugin, winningManifest }` 注明冲突。失败插件的**非冲突**命令仍会正常加载 —— 冲突是按 id 的，而非按插件的。版本共存（[02 §4.4](./02-command-protocol.md)）与之不同：*同一个*插件为其自身 id 注册不同版本是允许的（按 §6.4 存储在 `SortedSet` 中）；跨插件的同 id 才是冲突。

### 16.5 失败时卸载（熔断器升级）

[01 §15.3](./01-architecture.md) 的熔断器（3 次强制取消 / 60 秒 → 30 秒冷却）可以升级。如果一个插件在**10 分钟内触发了 3 次**熔断器（即持续不健康，而非瞬态抖动），运行时自动将其卸载：

```text
plugin marked unhealthy (3rd breaker trip in 10 min)
  → SchedulerGate: reject all new invokes for plugin (UNAVAILABLE, retryable=false)
  → drain in-flight per §6.5 (grace 5 s)
  → unload classloader per §16.3
  → emit PluginAutoUnloaded event + audit
  → notify user: "Plugin <name> was disabled due to repeated failures. Re-enable?"
```

重新启用是显式的（用户在"设置"中操作）；它会重新运行完整的加载流程（§16.1–16.4）。运行时**绝不**自动重新加载一个它自动卸载过的插件 —— 禁止静默抖动。

---

## 17. 性能预算

| 路径 | 预算 |
|------|--------|
| 解析 + 校验本地 DSL | < 5 ms 典型 |
| 注册表查询 | O(1) 哈希 |
| 权限检查（已缓存授权） | < 1 ms |
| 执行开销（不含插件） | < 10 ms |
| 审计写入（允许异步） | 不得阻塞成功路径超过 20 ms |

---

## 18. 故障域

| 故障 | 运行时行为 |
|---------|------------------|
| 插件异常 | 步骤失败；按策略继续工作流 |
| 插件进程死亡 | `UNAVAILABLE`；标记插件为不健康 |
| LLM 超时 | 编译失败；除非用户请求流式投机方案（默认关闭），否则不部分执行 |
| 磁盘满（审计） | 告警；企业模式可配置为 fail-closed |

---

## 19. 配置

```kotlin
data class RuntimeConfig(
    val maxParallel: Int = 4,
    val defaultTimeoutMs: Long = 60_000,
    val strictSchemaOutput: Boolean = false,
    val auditRedaction: RedactionLevel = RedactionLevel.DEFAULT,
    val eventTriggersEnabled: Boolean = true,
    val enterpriseAllowlist: Allowlist? = null,
    val networkAllowList: List<String> = emptyList(),   // 主机通配符，如 "*.example.com"；由网络出口策略执行（[08 §12](./08-security.md)）
    val rateLimits: RateLimits = RateLimits(),           // [08 §10.1](./08-security.md)
    val scheduler: SchedulerConfig = SchedulerConfig(),  // [08 §10.1](./08-security.md)
    val userPolicy: UserPolicy = UserPolicy(),           // [08 §4.2](./08-security.md) 用户全局收紧
)

data class RateLimits(
    val maxInvokesPerMinute: Int = 60,         // 每插件
    val maxDestructivePerHour: Int = 5,        // 每插件
    val maxBackgroundFiresPerHour: Int = 20,   // 每配方
)

data class SchedulerConfig(
    val maxConcurrentInvokes: Int = 4,         // 全局
    val maxConcurrentPerPlugin: Int = 2,       // 每插件
    val maxConcurrentDestructive: Int = 1,     // 全局（串行）
)

data class UserPolicy(
    val confirmEveryNetwork: Boolean = false,                 // [08 §4.2](./08-security.md) "确认每个网络调用"
    val backgroundEventsRequireForeground: Boolean = false,   // "后台事件需要前台确认"
    val disableSessionGrants: Boolean = false,                // "禁用会话授权"
)
```

### 19.1 校验、来源与热重载

**校验规则**（在加载时应用；无效配置会以 `INTERNAL` + `details.component = "config"` 拒绝，而不是被静默强制转换）：

| 字段 | 约束 |
|-------|-----------|
| `maxParallel` | `∈ [1, 16]` |
| `defaultTimeoutMs` | `∈ [1000, 600000]`（1 秒 – 10 分钟） |
| `auditRedaction` | `∈ {DEFAULT, STRICT, OFF}` |
| `eventTriggersEnabled` | 布尔值（无范围） |
| `enterpriseAllowlist` | 若非空：`auditRedaction` 被强制升级为 `STRICT`（企业审计不得被削弱）；白名单条目必须引用当前已注册的 command id，否则加载被推迟 |

**来源（优先级由高到低）：**

1. **MDM / 企业推送** —— 最高；可以收紧但不得放松用户设置（用户始终可以将 `auditRedaction` 提高到 MDM 要求之上，但绝不能降低）。
2. **用户设置**（DataStore，按设备）—— 消费者构建的默认来源。
3. **内置默认值** —— 上面 data class 中 `= …` 的值。

当来源不一致时，对于安全相关字段（`auditRedaction`、企业说"关闭"时的 `eventTriggersEnabled`），运行时取**最严格**的值；否则取最高优先级来源的值。

**热重载语义：** 配置**不**在启动时冻结。变更按以下方式生效：

| 变更字段 | 效果 |
|---------------|--------|
| `maxParallel` | 应用于**变更之后入队**的运行；飞行中的运行**不**被打断（其信号量是在旧上限下获取的）。 |
| `auditRedaction` | 应用于**下一次 Stage-10 审计写入**；已存储的记录**不**被重新遍历。 |
| `eventTriggersEnabled` | **立即**：翻转为 false 时，已武装的事件触发器停止触发；翻转为 true 时恢复。无需重启。 |
| `enterpriseAllowlist` | **立即重新计算**"Policy-allowlisted"注册表视图（§6.3 / §6.4）。新被排除在白名单之外的命令对新调用在 Stage 3 变为 `UNKNOWN_COMMAND`；飞行中的运行被允许完成。 |
| `defaultTimeoutMs` / `strictSchemaOutput` | 应用于变更之后入队的运行。 |

运行时发出一个 `ConfigChanged` 审计事件（始终审计，见 [08 §14](./08-security.md)），记录安全相关字段的前后差异。配置变更绝不静默。

---

## 20. 测试矩阵

| 测试 | 断言 |
|------|---------|
| 解析器黄金文件 | DSL ↔ IR |
| 授权矩阵 | 每一种 sideEffectClass × 授权状态 |
| 调度器 | 飞行中取消、公平性 |
| 执行器 | 超时、异常映射 |
| 事件 | 过滤器匹配 → 工作流启动 |
| 混沌（Chaos） | 调用中杀死插件 |

---

## 21. MVP 切片 vs V1

| 特性 | MVP | V1 |
|---------|-----|----|
| 解析器 + 执行器 + 注册表 | ✓ | ✓ |
| 权限提示 | ✓ | ✓ |
| 审计 | 基础 | 加密 + 导出 |
| 工作流 | 仅顺序 | 完整图 |
| 事件总线 | 桩 / 少量事件 | 完整 |
| 多进程 | 可选 | 推荐默认 |
| 企业白名单 | — | ✓ |

---

## 22. 总结

运行时是值得信赖的中间层：

- 只讲**命令协议（Command Protocol）**  
- 强制执行**权限与策略**  
- 运行**工作流与事件**，而不内置领域逻辑  
- 保持**与 LLM 无关（LLM-agnostic）**

下一篇：第三方如何扩展这条总线 —— [04-plugin-sdk.md](./04-plugin-sdk.md)。
