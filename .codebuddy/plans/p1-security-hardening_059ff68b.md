---
name: p1-security-hardening
overview: 完成 P1 安全底线加固：修复 PermissionKernel 未接入 Executor 的 bug、实现 RateLimiter 速率限制、实现 NetworkEgressPolicy 网络出站策略。
todos:
  - id: fix-permission-kernel-wiring
    content: 修复 McosRuntime.Builder 将 PermissionKernel 传入 Executor，激活 Stage 6 授权检查
    status: completed
  - id: implement-rate-limiter
    content: 新建 RateLimiter 令牌桶实现，支持每插件每分钟调用限制和每小时破坏性操作限制
    status: completed
  - id: implement-egress-policy
    content: 新建 NetworkEgressPolicy 实现 decideEgress 纯函数，四步检查管线（killSwitch → HTTPS → 域名作用域 → 企业策略）
    status: completed
  - id: integrate-executor-checks
    content: 在 Executor 中新增 RateLimiter + EgressPolicy 参数，插入 Stage 5.5 速率限制和 Stage 5.6 出站检查点
    status: completed
    dependencies:
      - fix-permission-kernel-wiring
      - implement-rate-limiter
      - implement-egress-policy
  - id: add-tests
    content: Use [skill:mcos-dev-standards] 编写 RateLimiterTest、NetworkEgressPolicyTest 单元测试，扩展 ExecutorTest 添加速率限制和出站策略集成测试
    status: completed
    dependencies:
      - integrate-executor-checks
---

## 用户需求

继续 P1 安全底线开发。上一阶段已完成 LLM 管线（LlmPlanner、ChatOrchestrator、PromptInjectionDetector）。当前 P1 安全底线仍缺失三部分：PermissionKernel 未接入 Executor 的 Bug、RateLimiter 速率限制、NetworkEgressPolicy 网络出站策略。

## 产品概述

完成 MCOS P1 MVP 安全底线的最后拼图。修复 Executor 管线中授权检查被跳过的关键 Bug，并补齐 08-security.md 规范要求的速率限制和网络出站检查，使所有安全关卡在命令执行前正确生效。

## 核心功能

- **修复 PermissionKernel wiring**：McosRuntime.Builder 将 PermissionKernel 正确传入 Executor，激活 Stage 6 授权检查
- **RateLimiter 令牌桶实现**：每插件每分钟调用限制 + 每小时破坏性操作限制，超限返回 RATE_LIMITED 错误
- **NetworkEgressPolicy 实现**：decideEgress 四步检查（全局杀死开关 → HTTPS 强制 → 域名作用域 glob 匹配 → 企业策略），纯函数设计
- **Executor 管线集成**：Stage 5.5 插入速率限制检查，Stage 5.6 插入网络出站检查，Stage 6 授权检查被正确激活

## Tech Stack

- 语言：Kotlin 2.0.21，JDK 17
- 模块：mcos-runtime（依赖 mcos-sdk）
- 包路径：`com.mcos.runtime.security`（新建包）
- 测试框架：kotlin.test + kotlinx.coroutines

## 实现方案

### 整体策略

采用**最小侵入、逐段接入**方式。Executor 已具备完整的 Stage 3→5→6→8→10 管线骨架，无需重构管线结构。只需要：(1) 修复 wiring，(2) 在现有管线中插入两个新检查点，(3) 实现两个新组件。

### Bug 修复：PermissionKernel Wiring

**问题根因**：`McosRuntime.Builder.build()` 第 309 行 `val exec = executor ?: Executor(reg, StubHostServices(memory))`，Executor 构造函数的 `permissionKernel` 参数有默认值 `null`，未被传入。

**修复**：`val exec = executor ?: Executor(reg, StubHostServices(memory), permissionKernel = perm, auditLog = ...)`。构建 Executor 时传入已创建的 `perm` 实例。

**影响范围**：仅一行代码改动。修复后所有通过 `McosRuntime.execute()` 的命令都将经过 Stage 6 授权检查（Authorized / Denied / ConfirmationNeeded 三分支）。P1 的 `needsConfirmation()` 为简化版（基于 sideEffectClass >= write），这是规范允许的 MVP 范围。

### RateLimiter 设计

采用**内存令牌桶（Token Bucket）**算法，线程安全：

- 每个插件 `(pluginId, rate kind)` 维护一个`TokenBucket`状态：`{ tokens: Int, lastRefillMs: Long }`
- `maxInvokesPerMinute`（默认 60）：每分钟补充到 60 个令牌，每次调用消耗 1 个令牌
- `maxDestructivePerHour`（默认 5）：每小时补充到 5 个令牌，仅 `sideEffectClass >= destructive` 的命令消耗
- `tryConsume(pluginId, sideEffectClass): RateLimitResult` 返回 `Allowed` 或 `Limited(retryAfterMs)`
- 使用 `ConcurrentHashMap` 保证线程安全，无需外部锁
- 纯内存实现，P1 无需持久化

**性能**：O(1) 核心路径（HashMap get + 简单算术），无 I/O，不影响执行延迟。

### NetworkEgressPolicy 设计

**纯函数** `decideEgress(url: String, authStamp: AuthStamp?, globalKillSwitch: Boolean): EgressDecision`：

1. **全局杀死开关**：first check，absolute，返回 `Deny("kill_switch_active")`
2. **HTTPS 强制**：非 `https://` 且非 DEBUG 构建 → `Deny("https_required")`
3. **域名作用域**：提取 host，检查 `authStamp.grantsUsed` 中是否有 `network.<domain>` 作用域匹配，使用 glob 规则
4. **企业策略**（P1 预留接口，EnterprisePolicy 参数可选，默认 null 跳过）

**注意**：P1 MVP 为尽力而为检查。进程内插件可绕过 NetService 直接使用 HTTP 客户端，规范已明确此为已知限制。

### 管线集成

Executor.invokeHandler() 新增 Stage 5.5 和 Stage 5.6：

```
Stage 5: Schema validation
  ↓
Stage 5.5: Rate limiting check [NEW]
  - 检查 per-plugin invoke limit
  - 检查 per-plugin destructive limit
  - 超限返回 CommandResult.Err(RATE_LIMITED)
  ↓
Stage 5.6: Network egress check [NEW]
  - 仅对 sideEffectClass == network 的命令检查
  - 从命令 args 中提取 url 参数
  - 调用 decideEgress()，拒绝返回 CommandResult.Err(PERMISSION_DENIED)
  ↓
Stage 6: Authorization (now correctly wired) [FIXED]
```

## 目录结构

```
mcos-runtime/src/main/kotlin/com/mcos/runtime/
├── api/
│   └── McosRuntime.kt              # [MODIFY] Builder.build() 传入 permissionKernel 给 Executor
├── executor/
│   └── Executor.kt                  # [MODIFY] 新增 rateLimiter + egressPolicy 参数，Stage 5.5/5.6 检查点
└── security/
    ├── RateLimiter.kt               # [NEW] 令牌桶速率限制器
    └── NetworkEgressPolicy.kt       # [NEW] 网络出站策略 decideEgress 实现

mcos-runtime/src/test/kotlin/com/mcos/runtime/
├── executor/
│   └── ExecutorTest.kt              # [MODIFY] 新增速率限制和出站策略测试
└── security/
    ├── RateLimiterTest.kt           # [NEW] 令牌桶单元测试
    └── NetworkEgressPolicyTest.kt   # [NEW] 出站策略单元测试
```

## 关键代码结构

### RateLimiter 接口

```
// mcos-runtime/.../security/RateLimiter.kt
class RateLimiter(
    private val maxInvokesPerMinute: Int = 60,
    private val maxDestructivePerHour: Int = 5,
) {
    fun tryConsume(pluginId: String, sideEffectClass: SideEffectClass): RateLimitResult
    fun getRemainingTokens(pluginId: String, kind: RateLimitKind): Int
}
sealed class RateLimitResult {
    data object Allowed : RateLimitResult()
    data class Limited(val retryAfterMs: Long, val kind: RateLimitKind) : RateLimitResult()
}
enum class RateLimitKind { INVOKE_PER_MINUTE, DESTRUCTIVE_PER_HOUR }
```

### NetworkEgressPolicy 接口

```
// mcos-runtime/.../security/NetworkEgressPolicy.kt
class NetworkEgressPolicy {
    fun decideEgress(
        url: String,
        authStamp: AuthStamp?,
        globalKillSwitch: Boolean = false,
    ): EgressDecision
}
sealed class EgressDecision {
    data object Allow : EgressDecision()
    data class Deny(val reason: String, val missingDomain: String? = null) : EgressDecision()
}
```

### Executor 新增参数

```
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val permissionKernel: PermissionKernel? = null,
    private val auditLog: AuditLog? = null,
    private val rateLimiter: RateLimiter? = null,          // [NEW]
    private val egressPolicy: NetworkEgressPolicy? = null,  // [NEW]
)
```

### McosRuntime.Builder 修复

```
fun build(): McosRuntime {
    val reg = registry ?: CommandRegistry()
    val perm = permissionKernel ?: PermissionKernel()
    val exec = executor ?: Executor(
        reg, StubHostServices(memory),
        permissionKernel = perm,           // [FIXED] 传入授权内核
        rateLimiter = RateLimiter(),       // [NEW]
        egressPolicy = NetworkEgressPolicy(), // [NEW]
    )
    return McosRuntime(parser, reg, perm, exec, memory, eventBus)
}
```

## Agent Extensions

### Skill

- **mcos-dev-standards**
- Purpose: 确保所有新增和修改的 Kotlin 代码遵循 MCOS 开发规范（jsonPrimitive 导入规则、模块间依赖约束、代码风格、Git 提交前检查流程）
- Expected outcome: 零编译错误，零 lint 警告，代码一次提交通过

### SubAgent

- **code-explorer**
- Purpose: 探索现有 ExecutorTest.kt 和 PermissionKernelTest.kt 的测试模式与 helper 函数，确保新测试与现有模式一致
- Expected outcome: 新测试文件风格与项目保持一致，正确复用 createPlugin/createDescriptor 等测试工具函数