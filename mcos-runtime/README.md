# MCOS Runtime

运行时内核——DSL 解析、命令注册、权限校验、执行调度、审计日志、LLM 规划。

## 包结构

```
com.morainet.mcos.runtime/
├── api/          # McosRuntime 门面 + 核心类型
├── audit/        # 审计日志（内存追加式）
├── error/        # 统一错误码
├── executor/     # 命令执行器（解析→验证→授权→调用→审计）
├── ir/           # 中间表示（ExecutionIr）
├── llm/          # LLM 规划器 + ChatOrchestrator
├── memory/       # MemoryStore（键值存储 + 语义标签）
├── parse/        # DSL 解析器（Lexer → Parser → Canonicalizer）
├── permission/   # 权限内核（授权/拒绝/确认）
├── registry/     # 命令注册表
├── validate/     # JSON Schema 验证器
└── workflow/     # 工作流引擎（P2 提前实现）
```

## 核心类

### api — 运行时门面
- `McosRuntime` — 顶层门面，串联所有子系统，提供 `execute()` / `preview()` / `cancel()` / `observe()`
- `RuntimeEvent` — 11 变体 sealed class 事件系统（RunStarted, StepStarted, …RunCancelled）
- `EventBus` / `SimpleEventBus` — P1 事件总线桩（P2 替换为完整 pub/sub）

### executor — 命令执行
- `Executor` — 完整执行管道：解析 → 验证 → 授权 → 调用 → 审计，支持超时/取消

### llm — AI 规划
- `LlmPlanner` — NL → DSL 翻译，支持 MemoryStore 注入构建用户上下文
- `ChatOrchestrator` — 端到端编排器：`NL → Plan → Execute → ChatResult`
- `LlmProvider` — LLM 后端抽象（内置 `OpenAiLlmProvider` 实现）

### parse — DSL 解析
- `DslParser` — 解析入口
- `Lexer` / `Parser` — 词法分析 + 语法分析
- `Token` / `Canonicalizer` — Token 类型 + IR 规范化

### 其他子系统
- `CommandRegistry` — 按 ID 查找命令，支持版本共存
- `PermissionKernel` — `decideConfirmation` 算法 + 授权缓存
- `MemoryStore` — 层级键值存储，支持 TTL、语义标签、resolveRef
- `SchemaValidator` — JSON Schema 校验（required/type/enum/minLength/maxLength…）
- `AuditLog` — 仅追加日志，记录每次执行的完整审计信息
- `WorkflowEngine` — 工作流引擎（P2，支持 sequence/parallel/if/loop/retry/compensation）

## 依赖

- `mcos-sdk`（api）
- `kotlinx.coroutines.core`
- `kotlinx.serialization.json`
