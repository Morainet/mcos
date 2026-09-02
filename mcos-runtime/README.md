# MCOS Runtime

运行时门面——`McosRuntime.Builder` 把 runtime-core / security / marketplace 各子系统
组装成单一入口对象。**本模块只做组装与协调，不实现子系统**（main 仅 3 个文件）。

## 包结构

```
com.morainet.mcos.runtime/
└── api/
    ├── McosRuntime.kt            # 门面 + Builder（实现 core.api.RuntimeGateway；§8 RunScheduler 准入/取消接线）
    ├── ConfirmationCoordinator.kt# internal：人工确认流（08 §5）+ run 级 stamp 铸造
    └── TriggerCoordinator.kt     # internal：事件/cron 双触发器 arm/disarm + 重挂
```

核心管线类型（`ExecuteRequest`/`RuntimeEvent`/`Payload`/`StubHostServices` 等）在
**mcos-runtime-core** 的 `com.morainet.mcos.runtime.core.api`。包名与模块一一对齐，
跨模块 split-package 已禁止（docs/zh/01-architecture.md §3.3，
`PackageBoundariesTest` 强制）。

## McosRuntime 公开 API

| API | 说明 |
|-----|------|
| `execute(request): ExecuteHandle` | 解析 payload → Plan → 经 §8 调度器准入后启动 run；解析失败或满车道拒绝（`RATE_LIMITED` + 指数 `retryAfterMs`）同步返回 FAILED handle |
| `executeProbe(steps)` | Agent 只读探针（06 §11.3）：非 read 步整批拒绝；审计 source=`AGENT_PROBE` |
| `preview(request)` | 不执行的解析预览，未知命令进 warnings |
| `observe(runId)` | run 事件流（终态自动 complete） |
| `schedulerMetrics()` | §8 调度器观测：per-lane depth/in-flight + 全局信号量获取次数与等待时长 |
| `cancel(runId)` / `shutdown()` | 取消单 run / 幂等关停（先 disarmAll 再收 run） |
| `respondConfirmation(runId, commandId, decision)` | 人工确认应答（批准用 30s TTL 签名 stamp 恰好重试一次） |
| `armTrigger` / `disarmTrigger` / `armedTriggers` | 触发器生命周期 |
| `rehydrateSchedules()` / `driveScheduleTick()` | 进程重启后重挂持久化调度 / 宿主 WakeScheduler 回调 |
| `registry()` / `memory()` / `episodicMemory()` / `workflowStore()` | 子系统访问器 |
| `loadPlugin(...)` / `pluginInstaller()` | 信任门控加载 / 安装器（若接线） |

Builder 关键项：`withRegistry` / `withPermissionKernel` / `withMemory` / `withEventBus` /
`withWorkflowStore` / `withWorkflowEngine` 等子系统注入；`withAuthStampSigner`
（默认 HmacAuthStampSigner——签名默认开启）；`withConfirmationTimeoutMs`（默认 60s）；
`withSchedulerConfig`（§8 车道并发上限 + §8.2/§8.5 闸门参数，默认 `SchedulerConfig()`）；
`withArmedScheduleStore` + `withWakeScheduler`（持久化调度）；
**`withIsolationHost(host)`**（进程隔离 seam，默认 null = best-effort 进程内回退 +
`plugin.isolation_fallback` 审计）。

> 陷阱：注入自建 Executor 时，其 SecurityConfig 的 signer 必须与门面共享同一实例，
> 否则确认后重试的 AuthStamp 签名校验失败（见 `McosRuntimeConfirmationTest`）。

## 典型用法

```kotlin
// 构建 + 执行 + 观察（摘自 McosRuntimeTest）
val runtime = McosRuntime.Builder()
    .withRegistry(registry).withPermissionKernel(permissions).withMemory(memory)
    .build()
val handle = runtime.execute(ExecuteRequest(Source.CHAT,
    Payload.DslText("""test.hello(greeting="hi")""")))
runtime.observe(handle.runId).collect { /* RuntimeEvent 流 */ }

// 人工确认（摘自 McosRuntimeConfirmationTest）
runtime.respondConfirmation(handle.runId, "test.write", ConfirmationDecision.Approve())

// 事件触发器（摘自 McosRuntimeTriggerTest）
runtime.workflowStore().registerSpec("wifi-vpn", WorkflowSpec(
    trigger = Trigger.Event(filter = eventFilter, resolveMemory = MemoryResolution.ARM),
    step = WorkflowStep.Command("net.notify", args = ...)))
runtime.armTrigger("wifi-vpn")
```

## 子系统实现位置

- `Executor`（7 阶段）、`RunScheduler`（§8 四车道准入）、`InvocationLimiter`（§8.2）、
  `DeviceMutexMap`（§8.5）、`DslParser`、`CommandRegistry`、`EventBus`、`WorkflowEngine`、
  `MemoryStore`/`EpisodicMemory` — **mcos-runtime-core**
- `PermissionKernel`、`AuditLog`、`SchemaValidator` — **mcos-security**
- `LlmPlanner`、`ChatOrchestrator` — **mcos-llm**（经 RuntimeGateway 平级驱动，不经本门面）
- `MarketplaceIndex`、`PluginInstaller` — **mcos-marketplace**（经本门面 api re-export）

## 依赖

- `mcos-sdk`、`mcos-security`、`mcos-runtime-core`、`mcos-marketplace`（均 api）
- `kotlinx.coroutines.core`、`kotlinx.serialization.json`（api）
- `plugins:mcos-plugin-files`（**仅测试**：FE 测试走真实 FilesPlugin 沙箱 E2E）
