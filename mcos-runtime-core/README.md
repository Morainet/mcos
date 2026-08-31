# MCOS Runtime Core

纯 JVM 运行时内核——DSL 解析 → IR → 命令注册 → 带安全阶段的执行管线 → 事件总线 →
工作流/触发器引擎 → 记忆子系统。不含门面组装与任何平台（Android/Server）代码。

## 模块定位

- **无门面的一层**：消费方（`mcos-runtime` 门面、`mcos-llm` Agent）经
  `core.api.RuntimeGateway` 窄端口驱动执行，二者是内核的平级客户端（01-architecture §3.2），
  模块图保持无环。
- 进程隔离以 `IsolationHost` 宿主 seam 表达（与 `WakeScheduler` 同模式），
  Binder 边界全部留在 `mcos-android-sdk`——本模块零平台依赖。
- 规格：`docs/zh/02-command-protocol.md`、`03-runtime.md`、`05-workflow.md`、`07-memory.md`。

## 包结构

```
com.morainet.mcos.runtime.core/
├── api/        # RuntimeGateway、RuntimeTypes（Payload/RuntimeEvent/Source）、StubHostServices
├── parse/      # DSL v0.1 词法/语法/规范化：Lexer、Parser、Canonicalizer、DslParser
├── ir/         # 执行 IR：ExecutionIr（IrInvoke/IrSequence）+ 结构化解析错误
├── error/      # 统一错误码 McosErrorCode（retryable 标志；PERMISSION_DENIED 刻意不可重试）
├── events/     # EventBus 接口 + TypedEventBus（per-run 隔离通道、终态自动完成）
├── registry/   # CommandRegistry（四索引）+ registerManifest 清单式注册
├── plugin/     # PluginLoader 信任门控加载（load / loadManifest）
├── executor/   # 七阶段执行管线、PluginIsolation（隔离路由）、ScopedFacade（stamp 域门）
├── memory/     # MemoryStore、EpisodicMemory、RunSummarizer、同步与 AES-GCM 加密
└── workflow/   # WorkflowEngine、事件/cron 双触发器、ArmedScheduleStore、WakeScheduler seam
```

## 核心 API

| API | 位置 | 说明 |
|-----|------|------|
| `RuntimeGateway` | `api/RuntimeGateway.kt` | 消费侧窄端口：`execute` / `observe` / `executeProbe` |
| `RuntimeEvent` | `api/RuntimeTypes.kt` | 11 变体 sealed（RunStarted…RunCancelled） |
| `TypedEventBus` | `events/EventBus.kt` | per-run SharedFlow 隔离；终态事件后流完成；回放上限 512 |
| `CommandRegistry` | `registry/CommandRegistry.kt` | byId/byAlias/byPlugin/byNamespace 四索引，全方法 synchronized |
| `registerManifest` | 同上 | **清单式注册**：仅凭 wire `plugin.json` 注册，不加载插件代码；缺 handler 的命令挂 `IsolationRequiredHandler`（诚实报 `isolation_required`） |
| `PluginLoader.loadManifest` | `plugin/PluginLoader.kt` | 与 `load()` 相同的信任门 + id 伪装防护 |
| `Executor` | `executor/Executor.kt` | 管线：Resolve → Schema → 限流 → Authorize → Egress(6.5) → Invoke(8, 隔离路由) → Audit(10) + 崩溃隔离 |
| `IsolationPolicy` / `IsolatedInvocation` / `IsolationHost` | `executor/PluginIsolation.kt` | BUILTIN→IN_PROCESS，其余→ISOLATED；`IsolatedInvocation` 完全可序列化、刻意不含 HostServices |
| `StampScopedNetService` | `executor/ScopedFacade.kt` | §8.2 confused-deputy 防御：facade 网络调用逐次校验 stamp 签名/TTL/`network.<domain>` glob |
| `MemoryStore` / `EpisodicMemory` | `memory/` | 实现 SDK `MemoryFacade`；run 摘要归档（07 §9.4） |
| `WorkflowEngine` | `workflow/WorkflowEngine.kt` | Sequential/Parallel/If/Loop/Retry/Try+compensation 七步型 |
| `EventTriggerManager` / `ScheduleTriggerManager` | `workflow/` | 双触发器家族；跨进程持久化重挂经 `rehydrateSchedules()` |

Stage-4 HostServices 装饰链（`stage4Services`）：
`StampScopedNetService(SecretResolvingNetService(net))` + `NamespacedSandbox(sandbox, pluginId)`
——该装饰栈同时被 Android 隔离 facade server 复用，进程内与进程隔离两个边界语义一致。

## 典型用法

```kotlin
// 清单式注册（隔离模式：主进程只持 manifest，不带插件代码）
registry.registerManifest(wireManifest, TrustLevel.MARKETPLACE_VERIFIED)

// 隔离路由（摘自 PluginIsolationTest）
val exec = Executor(registry, services, SecurityConfig.permissive(), isolationHost = host)
exec.execute("m.cmd", args, source = "CHAT")   // 非 BUILTIN → host.invoke(IsolatedInvocation)

// DSL 解析 + IR JSON 互转
val parse = DslParser.parse("""camera.capture(facing="rear")""")
val irJson = DslParser.toJson(parse.irOrThrow())
```

## 依赖

- `mcos-sdk`、`mcos-security`（均 api）
- `kotlinx.coroutines.core`、`kotlinx.serialization.json`（均 api）
