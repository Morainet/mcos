# MCOS Runtime

运行时门面模块——`McosRuntime` Builder/`RunManager`/`ConfirmationCoordinator`，组装
mcos-runtime-core / mcos-security / mcos-marketplace 各子系统（模块拆分见根 README）。

## 包结构

```
com.morainet.mcos.runtime/
├── api/          # 门面：McosRuntime（Builder）、RunManager、ConfirmationCoordinator
└── （测试）       # RunSummarizerTest（runtime.core.memory 的门面级集成测试）、PackageBoundariesTest
```

核心管线类型（`ExecuteRequest`/`RuntimeEvent`/`Payload`/`StubHostServices` 等）在
**mcos-runtime-core** 的 `com.morainet.mcos.runtime.core.api`。包名与模块一一对齐
（`runtime.` 前缀仅属门面与 core，其余模块各占 `com.morainet.mcos.<module>.*`），
跨模块 split-package 已禁止（见 docs/zh/01-architecture.md §3.3）。

## 核心类

### api — 运行时门面
- `McosRuntime` — 顶层门面，串联所有子系统，提供 `execute()` / `preview()` / `cancel()` / `observe()`
- `RuntimeEvent`（core.api）— 11 变体 sealed class 事件系统（RunStarted, StepStarted, …RunCancelled）
- `EventBus`（core.events）— 事件总线（信封、过滤、订阅者隔离）

各子系统的实现位置（本模块仅做组装）：

- `Executor`（7 阶段执行管道）、`DslParser`、`CommandRegistry`、`EventBus`、
  `WorkflowEngine`、`MemoryStore`/`EpisodicMemory`/`RunSummarizer` —
  **mcos-runtime-core**（`com.morainet.mcos.runtime.core.*`）
- `PermissionKernel`、`AuditLog`（HMAC 链）、`SchemaValidator` —
  **mcos-security**（`com.morainet.mcos.security.*`）
- `LlmPlanner`、`ChatOrchestrator`、`OpenAiLlmProvider` —
  **mcos-llm**（`com.morainet.mcos.llm`）
- `MarketplaceIndex`、`PluginInstaller` —
  **mcos-marketplace**（`com.morainet.mcos.marketplace`）

## 依赖

- `mcos-sdk`、`mcos-security`、`mcos-runtime-core`、`mcos-marketplace`（均 api）
- `kotlinx.coroutines.core`
- `kotlinx.serialization.json`
