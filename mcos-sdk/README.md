# MCOS SDK

插件契约层——定义插件与运行时之间的全部公共契约：命令协议数据类型、插件入口与
Host 能力门面、以及一个 JVM/Android 共用的沙箱参考实现。零运行时逻辑、零框架依赖，
是依赖图的最底层（不依赖任何 MCOS 模块）。

## 包结构

```
com.morainet.mcos.sdk/
```

所有公开类型挂 KDoc 并标注对应规格章节（如 `Matches [02-command-protocol.md 8]`）。

## 核心契约

### 命令协议

| 文件 | 类型 | 说明 |
|------|------|------|
| `CommandDescriptor.kt` | 数据类 | 命令注册项：id/version/pluginId、inputSchema（JSON Schema 2020-12）、permissions、sideEffectClass、idempotent、timeoutMs(1000..600000)、tags、examples、aliases |
| `SideEffectClass.kt` | 枚举 | `read < write < network < destructive < control`——**ordinal 顺序即严重度**，代码有 `>= write` 比较依赖此序，不可重排 |
| `CommandResult.kt` | sealed | `Ok(value, artifacts)` / `Err(code, message, retryable, details)`；`Artifact`（type/uri/mimeType/metadata） |
| `McosException.kt` | 异常 | 插件抛结构化错误码的唯一通道（绕过通用 PLUGIN_ERROR 启发式） |

### 插件与执行上下文

| 文件 | 类型 | 说明 |
|------|------|------|
| `McosPlugin.kt` | 接口 | `manifest` / `onLoad(services)` / `onUnload()` / `handlers(): Map<String, CommandHandler>` |
| `ExecutionContext.kt` | 数据类 | runId/commandId/stepId/args/auth/deadline/progress/services |
| `ExecutionContext.kt` | `AuthStamp` | 权限内核铸造的授权印章：grantsUsed + TTL + HMAC 签名（空串=未签名，视为不可信）——签名/校验在 mcos-security |
| `PluginManifest.kt` | 数据类 | plugin.json 序列化模型：entry、permissions、namespaces、eventsEmitted/Consumed、threadHint、i18n |

### HostServices 能力门面（`McosPlugin.kt`）

必选：`files` / `net` / `ui` / `secureStore` / `clock` / `json` / `memory`。
可选（null = 宿主不支持，插件必须返回 UNAVAILABLE 而非伪造）：
`notifications` / `media` / `deviceInfo` / `clipboard` / `haptics` / `events` / `sandbox`。

关键语义：

- `NetService` 逐调用过出网策略；`SecureStore` 对 Planner 不可见。
- `ClipboardService` 不可读时返回 null，内容按不可信输入处理。
- `MemoryFacade` 只读视图 + 模糊解析：`ResolveResult` 为 `Resolved(id, confidence)` /
  `Ambiguous(candidates)`（应触发 Clarify）/ `NotFound(reason)`；写语义
  `MemoryWriteResult`（CREATED/UPDATED/CONFLICT/REJECTED，含 supersededPath）。
- `EventPublisher` 发布系统事件（write 级副作用）。

### 参考实现

| 文件 | 说明 |
|------|------|
| `DirectorySandbox.kt` | 纯 java.nio 沙箱（`SandboxFileService`）：两层路径安全——语法校验（空段/`..`/`\`/NUL → `SCHEMA_VIOLATION`）+ 包含性与逐组件 symlink 检查（→ `PERMISSION_DENIED`/`sandbox_escape`）。JVM 运行时与 Android host（`filesDir/plugin-sandbox`）共用 |

## 典型用法

```kotlin
// 沙箱读写往返（摘自 DirectorySandboxTest）
val sandbox = DirectorySandbox(Files.createTempDirectory("mcos-sandbox-"))
sandbox.write("notes/data.bin", byteArrayOf(1, 2, 3, 4))
assertContentEquals(byteArrayOf(1, 2, 3, 4), sandbox.read("notes/data.bin"))
```

编写插件的最小样板见 `plugins/mcos-plugin-hello`。

## 依赖

- `kotlinx.coroutines.core`（api）
- `kotlinx.serialization.json`（api）
