# MCOS 插件 SDK 设计

> **语言:** [English](../en/04-plugin-sdk.md) · 中文（当前）

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-06  
> **Package:** `mcos-sdk`  
> **Depends on:** [02-command-protocol.md](./02-command-protocol.md), [03-runtime.md](./03-runtime.md)

---

## 1. 目的

插件 SDK 是生态系统在**无需 fork 运行时（Runtime）**的前提下扩展的方式。

一个插件打包包含：

- 清单（manifest，`plugin.json`）  
- 命令处理器（Command handlers）  
- 权限声明  
- 可选资源（图标、本地化字符串）  
- 可选的 MCP/HTTP 桥  

```text
plugin.json + handlers
        │
        ▼
  Plugin Loader
        │
        ▼
  Command Registry
        │
        ▼
     Executor
```

---

## 2. 设计目标

1. 让一名 Android 工程师**一天内完成首个插件**  
2. **声明式安全** —— 权限与 schema 写在清单中，而非口耳相传的经验  
3. **带版本管理的契约** —— 强制执行命令的 SemVer  
4. 针对纯逻辑处理器**无需设备农场即可测试**  
5. **前向兼容** 的宿主 API，提供弃用窗口  

---

## 3. 插件包布局

```text
my-plugin/
├── plugin.json              # required manifest
├── README.md
├── src/main/kotlin/...      # handlers
├── src/main/res/            # icons / strings (Android)
├── schemas/                 # optional externalized JSON Schemas
│   ├── camera.capture.in.json
│   └── camera.capture.out.json
└── proguard-rules.pro       # if shipping AAR
```

市场制品（Marketplace artifacts）可以是：

- AAR / APK feature 模块  
- 用于动态加载的签名 zip（视策略而定）  
- 暴露 Bound Service 的独立伴生应用  

SDK 文档关注**逻辑**形态；具体的打包口味属于宿主关心的问题。

---

## 4. 清单（Manifest，`plugin.json`）

### 4.1 示例

```json
{
  "id": "mcos.plugin.camera",
  "name": "Camera",
  "version": "1.0.0",
  "minRuntimeVersion": "0.1.0",
  "description": "Capture and scan using device cameras",
  "provider": {
    "name": "MCOS",
    "url": "https://github.com/mcos-org"
  },
  "entry": "com.morainet.mcos.plugin.camera.CameraPlugin",
  "permissions": [
    {
      "type": "android",
      "name": "android.permission.CAMERA",
      "reason": "Take photos and scan codes"
    }
  ],
  "commands": [
    {
      "id": "camera.capture",
      "version": "1.0.0",
      "title": "Capture photo",
      "description": "Capture a still image",
      "sideEffectClass": "write",
      "idempotent": false,
      "timeoutMs": 60000,
      "permissions": [],
      "inputSchema": { "$ref": "schemas/camera.capture.in.json" },
      "outputSchema": { "$ref": "schemas/camera.capture.out.json" },
      "examples": ["camera.capture()", "camera.capture(facing=\"front\")"]
    },
    {
      "id": "camera.scan",
      "version": "1.0.0",
      "title": "Scan code",
      "sideEffectClass": "read",
      "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "format": {
            "type": "string",
            "enum": ["qr", "barcode", "any"],
            "default": "any"
          }
        }
      },
      "outputSchema": {
        "type": "object",
        "required": ["text"],
        "properties": {
          "text": { "type": "string" },
          "format": { "type": "string" }
        }
      }
    }
  ],
  "eventsEmitted": ["camera.capture.completed"],
  "eventsConsumed": [],
  "tags": ["media", "camera"]
}
```

> ✅ **落地状态（item 45）：** `McosPackage.readPluginManifest`（`mcos-android-sdk`）现在从已验证的 `.mcos` 字节解码本节完整 schema——manifest-only 注册的来源（08 §8）：进程隔离打开时主进程只凭这些字节注册描述符/权限、绝不加载插件 dex；插件进程仍只实例化 `entry`。解码规则：宽松（未知字段忽略、非安全字段取默认——`name` 回退为 `id`），但安全相关值 fail-closed——未知的 `commands[].sideEffectClass` 或缺失的命令 `id` 是 `FormatException`、安装失败，绝不静默降级为 `read`。旧 schema 包（仅 id/entry/version）解码出空 `commands`、继续走 dex 加载路径。`aliases`/`replacedBy`/`deprecated`/`outputSchema` 存在即解码；`threadHint`/`i18n` 保持默认（注册不消费）。

### 4.2 必填字段

| 字段 | 规则 |
|-------|------|
| `id` | 反向 DNS 唯一标识 |
| `version` | SemVer |
| `minRuntimeVersion` | SemVer |
| `entry` | 插件类的全限定名 |
| `commands` | 能力插件须非空 |

### 4.3 命令字段继承

命令的 `permissions` 与插件级权限是**叠加（additive）**关系。

### 4.4 插件级字段参考

运行时侧由清单产出的 `CommandDescriptor`（命令描述符）在 [01 §10](./01-architecture.md)（15 字段表）和 [02 §8](./02-command-protocol.md) 中是权威定义。下表涵盖作者在 `plugin.json` 中编写的**插件清单级**字段；运行时专有字段（`pluginId`、解析后的 `version`）由 Loader 注入，不面向作者。

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|-------|------|----------|---------|------------|
| `id` | string | 是 | — | 反向 DNS，唯一；匹配 `^[a-z][a-z0-9.]*$` |
| `name` | string | 是 | — | 人类可读；可本地化（§12） |
| `version` | string（SemVer） | 是 | — | `MAJOR.MINOR.PATCH`；多次发布单调递增 |
| `minRuntimeVersion` | string（SemVer） | 是 | — | 使用新的宿主 API 时必须相应提升 |
| `description` | string | 是 | — | 单行；可本地化 |
| `provider` | `{ name, url }` | 是 | — | `url` 为发布者主页 |
| `entry` | string（全限定类名） | 是 | — | 实现 `McosPlugin` |
| `permissions` | array | 否 | `[]` | 每项 `{ type: "android"|"mcos", name, reason }`；与每命令权限叠加 |
| `commands` | array | 是（能力插件） | — | 非空；每项按下文 §4.4 命令子表 |
| `namespaces` | array | 否 | 由命令派生 | 显式声明的命名空间根（如 `["camera"]`）；用于冲突仲裁（[02 §4.4](./02-command-protocol.md)） |
| `eventsEmitted` | array | 否 | `[]` | 本插件发布的事件类型前缀（如 `["camera.capture.completed"]`） |
| `eventsConsumed` | array | 否 | `[]` | 本插件订阅的事件类型前缀；每项需要 `event.subscribe.<type>` 作用域 |
| `tags` | array | 否 | `[]` | 用于市场筛选的自由标签；`"cpu-bound"` 是 Executor 用来指导派发的保留标签（[01 §8](./01-architecture.md)） |
| `threadHint` | `"io"` \| `"cpu"` \| `"main"` | 否 | `"io"` | 插件级派发提示；`"main"` 仅限于需要 UI 线程 API 的 `control` 类插件（[01 §8](./01-architecture.md)） |
| `i18n` | object | 否 | — | 按语言区域的覆盖；见 §12 |
| `http`（按命令） | object | 否 | — | 声明式 webhook；见 §11 |

**命令级字段**（位于 `commands[]` 内）。它们与 [01 §10](./01-architecture.md) / [02 §8](./02-command-protocol.md) 中的 `CommandDescriptor` 字段一一对应；冲突时以运行时侧的表为准。

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|-------|------|----------|---------|------------|
| `id` | string | 是 | — | `namespace.name`，小写，≤128 字符 |
| `version` | string（SemVer） | 是 | — | 命令契约版本；独立于插件 `version` |
| `title` | string | 是 | — | 可本地化 |
| `description` | string | 是 | — | 可本地化 |
| `sideEffectClass` | enum | 是 | — | `read` \| `write` \| `network` \| `control` \| `destructive`（[01 §10.1](./01-architecture.md)） |
| `idempotent` | boolean | 否 | `false` | 控制工作流自动重试（[02 §9.4](./02-command-protocol.md)） |
| `timeoutMs` | integer | 否 | `60000` | Executor 截止时间；`∈ [1000, 600000]` |
| `permissions` | array | 否 | `[]` | 与插件级叠加（[§4.3](#43-command-field-inheritance)） |
| `inputSchema` | JSON Schema | 是 | — | 见 §4.5；Draft 2020-12 |
| `outputSchema` | JSON Schema | 是 | — | Draft 2020-12 |
| `aliases` | array | 否 | `[]` | 解析到本命令的备用命令 ID（[02 §4.5](./02-command-protocol.md)） |
| `examples` | array | 否 | `[]` | DSL 字符串；用于 Planner few-shot 和 CLI 帮助 |
| `tags` | array | 否 | `[]` | 每命令标签；`"cpu-bound"` 覆盖插件级 `threadHint` |
| `deprecated` | boolean | 否 | `false` | 从 Planner 建议中隐藏；CLI 给出警告 |
| `replacedBy` | string \| null | 否 | `null` | 要迁移到的命令 ID；加载时必须指向已注册的命令 |
| `http` | object | 否 | — | 声明式 webhook（§11）；存在时该命令无需 Kotlin 处理器 |

### 4.5 编写 `inputSchema`（作者指南）

`inputSchema` 是 JSON Schema Draft 2020-12。MCOS 在其上增加了四个 `x-mcos-*` 扩展（权威定义见 [02 §5.3](./02-command-protocol.md)），它们会改变运行时处理参数的方式。作者应了解这些扩展：

| 扩展 | 效果 | 何时使用 |
|-----------|--------|-------------|
| `"x-mcos-secret": true` | 在审计入库前对值进行脱敏（[03 §13.3](./03-runtime.md)）。 | 密码、令牌、凭据。 |
| `"x-mcos-ref": true` | 将值视为自然语言引用；在第 4 阶段 Expand 通过 `MemoryFacade.resolveRef()` 解析为具体 ID。 | 设备名（`"空调"`）、地名、人名。 |
| `"x-mcos-semantic": "device\|place\|person\|wifi\|..."` | 告知引用解析器应检索哪个 Memory 索引。与 `x-mcos-ref` 配合使用。 | 提升消歧准确度。 |
| `"x-mcos-default-from-memory": "path.to.key"` | 当参数缺失时，运行时在挂起中从 Memory 指定路径注入值（在第 4 阶段）。 | "默认城市"、"默认回家场景"。 |

**示例 —— 包含密钥与 memory 引用的命令：**

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "deviceId": {
      "type": "string",
      "x-mcos-ref": true,
      "x-mcos-semantic": "device",
      "description": "Device name or label, e.g. \"空调\""
    },
    "apiToken": {
      "type": "string",
      "x-mcos-secret": true,
      "description": "Vendor API token"
    },
    "mode": {
      "type": "string",
      "enum": ["cool", "heat", "auto"],
      "default": "cool"
    }
  },
  "required": ["deviceId"]
}
```

**类型边界速查表**（权威边界见 [02 §5.4](./02-command-protocol.md)）：`string` ≤ 65536 个码点；`integer` 有符号 64 位；`bytes`（base64）≤ 10 MiB；`duration` 接受 ISO-8601 字符串或整数毫秒。除非有意接受额外键，对象 schema 应使用 `additionalProperties: false` —— 否则 Planner 会臆造字段。

**编写规则：**
- 始终为每个属性提供 `description` —— Planner 依据这些描述判断哪个参数填充哪个字段。
- 显式声明 `required`；未出现在 `required` 中即表示可选。
- 对闭合取值集使用 `enum`；运行时在第 5 阶段校验并拒绝越界值。
- 支持通过 `$ref` 引用外部文件（`schemas/*.in.json`），对非平凡 schema 推荐使用；Loader 在插件加载时解析。

---

## 5. 核心 SDK 接口（Kotlin）

> ✅ **Implementation status:** `McosPlugin` / `CommandHandler`（含 `cancel()` 钩子）已在 `mcos-sdk` 落地，由四个参考插件与全量测试覆盖。与规范的已知差异：`CommandId` 为 `String` 别名；`retryable` 由运行时的 `McosException`→`CommandResult.Err` 映射承载。见 [11-implementation-status.md](./11-implementation-status.md) §7。

```kotlin
interface McosPlugin {
    val manifest: PluginManifest
    suspend fun onLoad(services: HostServices)   // renamed from PluginHost per 01 §11.1
    suspend fun onUnload()
    fun handlers(): Map<CommandId, CommandHandler>
}

interface CommandHandler {
    suspend fun invoke(ctx: ExecutionContext): CommandResult
    suspend fun cancel(ctx: ExecutionContext) { /* optional; see §7.4 */ }
}

sealed class CommandResult {
    data class Ok(
        val value: JsonElement,
        val artifacts: List<Artifact> = emptyList(),   // Artifact: see 01 §11.3
    ) : CommandResult()

    data class Err(
        val code: String,                  // a McosErrorCode (01 §15.1) or plugin-namespaced code
        val message: String,
        val retryable: Boolean = false,
        val details: JsonObject = JsonObject(emptyMap()),  // adheres to 02 §8.3 shape B for the code
    ) : CommandResult()
}
```

> **`CommandId` / `Artifact`** 是定义在 [01 §11.3–11.4](./01-architecture.md) 的值类。**`HostServices`**（主机服务）是面向插件的统一门面（[01 §11.1](./01-architecture.md)）；历史名称 `PluginHost` 已废弃。**`McosException`**（[03 §9.5](./03-runtime.md)）是处理器通过抛出声明具体错误代码的受支持方式 —— 关于使用 `CommandResult.Err` 还是抛出 `McosException`，见 §5.2。

### 5.1 进度

```kotlin
interface ProgressEmitter {
    suspend fun progress(percent: Int?, message: String? = null)
    suspend fun log(level: LogLevel, message: String)
}
```

### 5.2 声明错误（`CommandResult.Err` 对比 `McosException`）

处理器有两种上报失败的方式，二者**不可互换**：

| 模式 | 时机 | 机制 |
|---------|------|-----------|
| 返回 `CommandResult.Err(code, message, retryable, details)` | 处理器检测并可控的**预期内**失败（如 "设备离线"、"非法状态"）。正常的返回路径 —— 不抛出异常。 | Executor 将 `Err` 字段直接映射到运行时 `Failure` 信封。 |
| 抛出 `McosException(code, message, retryable, details)` | 调用栈深处的**异常**情形，返回显得别扭（如你调用的库抛出异常，而你希望在不加 try/catch 包装的情况下将其重新映射为具体 MCOS 错误代码）。 | Executor 捕获 `McosException` 并直接映射其字段 —— 不会走通用的 `Throwable.toMcosError()` 启发式（[01 §10.3](./01-architecture.md)）。 |
| 抛出其他任意 `Throwable` | 非预期的崩溃。 | Executor 映射为 `PLUGIN_ERROR`，消息经过脱敏；原始堆栈仅出现在 dev-mode 审计中。 |

**两条通道产生相同的运行时 `Failure` 信封**（[02 §10.2](./02-command-protocol.md) shape B）。`code` 必须是合法的 `McosErrorCode`（[01 §15.1](./01-architecture.md)）—— 例如 `UNAVAILABLE`、`TIMEOUT`、`PERMISSION_DENIED`、`PLUGIN_ERROR` —— 或插件命名空间代码（如 `"camera.hardware_busy"`）。`details` 对象必须符合 [02 §8.3](./02-command-protocol.md) shape B 中按代码划分的必填字段表。

**`details` 速查**（作者最常发出的错误代码；完整表见 [02 §8.3](./02-command-protocol.md)）：

| 代码 | `details` 必填 |
|------|--------------------|
| `UNAVAILABLE` | `component: string`（如 `"camera"`） |
| `TIMEOUT` |（Executor 填充 `timeoutMs`/`elapsedMs`；插件很少直接发出此错误） |
| `PERMISSION_DENIED` | `permission: string`、`sideEffectClass: string` |
| `PLUGIN_ERROR` | 无必填；可选字段中添加插件特定上下文 |

**对预期失败优先使用 `CommandResult.Err`**（更干净，无异常开销），把 `McosException` 留给依赖抛出异常而你想在不加包装的情况下标记失败的场合。示例：

```kotlin
// Expected failure — return Err
override suspend fun invoke(ctx: ExecutionContext): CommandResult {
    val device = deviceRegistry.find(ctx.refOrNull("deviceId"))
        ?: return CommandResult.Err(
            code = "UNAVAILABLE",
            message = "Device not reachable",
            retryable = true,
            details = buildJsonObject { put("component", JsonPrimitive("iot")) },
        )
    ...
}

// Exceptional — throw McosException from a catch
try { vendorSdk.actuate(device) }
catch (e: VendorBusyException) {
    throw McosException("camera.hardware_busy", "Camera busy", retryable = true)
}
```

### 5.3 `meta` 由运行时拥有（作者警告）

IR 的 `meta` 字段（[02 §8.2](./02-command-protocol.md)）—— `source`、`confidence`、`utteranceId`、`correlationId`、`traceId` —— 由 Planner 和运行时注入。**插件不得读取、写入或依赖 `meta` 内容。** 它不属于 `ExecutionContext.args`。如果插件需要来源信息（如 "这是否来自 LLM？"），应声明一个显式输入参数，而不是去读 `meta`。

---

## 6. HostServices（面向插件的门面）

> ✅ **Implementation status:** `HostServices` 与 §6.1–6.6 全部接口已在 `mcos-sdk` 落地（JVM 桩 + `mcos-android` 真实现）。v0.x 已知差异：实现用 `val` 属性（规范示意 `fun`），部分签名更精简（`NetService.request(method, url, body, headers)`、`Clock.nowMs()`）——全量签名对齐是 [11-implementation-status.md](./11-implementation-status.md) 记录的 🟡 缺口。§6.7–6.10 的可选能力为 v0.x 增补；§6.1 的作用域存储也已于 v0.x 以可选能力 `sandbox` 交付（见 §6.1 的 as-built 说明）。

插件应当依赖**门面（facades）**，而非整个 Android 框架。

```kotlin
interface HostServices {
    fun files(): FileService
    fun net(): NetService
    fun ui(): UiService
    fun secureStore(): SecureStore
    fun clock(): Clock
    fun json(): kotlinx.serialization.json.Json
    fun memory(): MemoryFacade   // read-only view for plugins; P2 (see 01 §11.1)

    // 可选平台能力（v0.x 增补，接口默认 null）：
    // 无该能力的宿主（如纯 JVM）不覆写即保持 null，
    // 插件必须如实上报 UNAVAILABLE，绝不伪造数据或假成功。
    val notifications: NotificationService?   // §6.7
    val media: MediaService?                  // §6.7
    val deviceInfo: DeviceInfoService?        // §6.8
    val clipboard: ClipboardService?          // §6.9
    val haptics: HapticsService?              // §6.10
    val events: EventPublisher?               // §6.11
    val sandbox: SandboxFileService?          // §6.1 作用域存储（可选，v0.x）
}
```

各服务接口在 §6.1–6.6 中规定。MVP 务实考虑：相机插件可能需要直接使用 CameraX。准则：**新代码优先使用门面**；如有例外，在插件 README 中说明。

### 6.1 `FileService` / `SandboxFileService` —— 媒体访问与作用域存储

As-built（v0.x），本节覆盖**两个表面**：

- **`HostServices.files: FileService`** —— **媒体库门面**：对设备媒体库的只读查询（`list(uri)`、`searchPhotos(mimeType, afterMs, beforeMs, limit)`），由 `file.list` / `file.search` / `photo.search` / `photo.compress` 消费。它**不是**通用文件 API。
- **`HostServices.sandbox: SandboxFileService?`** —— 本节最初规定的**作用域存储**，以可选能力交付（§6.7–6.11 模式：接口默认 null —— 无存储能力的宿主不覆写即保持 null，沙箱命令如实上报 `UNAVAILABLE`，绝不假成功）。所有路径均为插件相对路径，解析落在插件的**命名空间沙箱**内；插件无法读写自身目录之外的内容。参考实现为 `DirectorySandbox(root)` —— 纯 `java.nio`，其 JVM 测试套件覆盖的正是 Android 宿主运行的同一份代码（`filesDir/plugin-sandbox`）。

```kotlin
interface SandboxFileService {
    suspend fun read(path: String): ByteArray?                     // 不存在返回 null
    suspend fun write(path: String, data: ByteArray, append: Boolean = false)
    suspend fun stat(path: String): SandboxEntry?                  // 不存在返回 null
    suspend fun delete(path: String): Boolean                      // 不存在返回 false；仅限空目录
    suspend fun list(dir: String): List<SandboxEntry>              // 非递归
    suspend fun tempFile(prefix: String = "mcos", suffix: String = ".tmp"): String  // 沙箱相对路径
}

data class SandboxEntry(val path: String, val isDir: Boolean, val size: Long?)
```

**命名空间化** —— Executor 的 Stage-4 门面交给每条命令一个按插件隔离的视图：路径解析在 `<root>/<pluginId>/` 之下，一个插件永远看不到另一个插件的文件。处理器必须使用 `ctx.services.sandbox`（命名空间视图），绝不使用 `onLoad` 捕获的宿主全局门面。

**路径防御（双层）** —— 语法层：空白/`.`/`..` 段、反斜杠、NUL → `SCHEMA_VIOLATION`，`details.reason = "sandbox_path_invalid"`。物理层：词法层根包含检查 + 对每个已存在组件的严格无符号链接走查 → `PERMISSION_DENIED`，`details.reason = "sandbox_escape"`。

**命令面** —— `mcos.plugin.files` 将沙箱暴露为四条命令（§17）：`file.write {path, text, append?}`（write 级；单次写入上限 1 MiB —— 超出 → `SCHEMA_VIOLATION "file_too_large"`）、`file.read {path}`（read 级；不存在 → `files.not_found`；超限 → `files.too_large`）、`file.stat {path}`（read 级；`Ok {path, exists, isDir, size?}`）、`file.delete {path}`（write 级 —— 沙箱内删除不属于设备级不可逆操作，因此不是 `destructive`；幂等 `Ok {deleted}`）。命令面**仅限文本**；二进制数据走插件代码直接调用上述 SDK 接口。

处理器返回的产出物应使用 `tempFile(...)` 或稳定的沙箱路径，然后返回 URI —— 切勿在 `CommandResult.Ok` 中内联字节（见 §7.3）。**密钥绝不落入沙箱** —— 它是明文的应用私有存储；请使用 `SecureStore`（§6.4、[08 §9](./08-security.md)）。

> 🟡 **v0.x 差异（诚实记录）：** 上述字节 API 比最初的流式示意（`openInput`/`openOutput` → `InputFlow`/`OutputFlow`）更精简 —— 与 `NetService`/`Clock` 属同一 drift 家族，由 [11-implementation-status.md](./11-implementation-status.md) 跟踪；"用户通过系统选择器授予沙箱外访问"的流程属 V1 宿主工作；超出单次写入 1 MiB 上限的按插件配额尚未实现。

### 6.2 `NetService` —— 策略感知的 HTTP

所有网络出口都受**策略门控**。运行时在连接前检查 `network.<domain>` 作用域（[08 §3](./08-security.md)）和企业白名单。对不允许域名的请求会以 `PERMISSION_DENIED` 失败（`details.permission = "network:<domain>"`）。

```kotlin
interface NetService {
    suspend fun request(req: HttpRequest): HttpResponse
    suspend fun websocket(url: String): WebSocketSession   // P2
}

data class HttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)
```

生产构建强制使用 HTTPS；仅在 debug 中允许 HTTP。用于 `Authorization` 头的密钥必须来自 `SecureStore`，绝不能硬编码。

### 6.3 `UiService` —— Activity 桥接与通知

```kotlin
interface UiService {
    suspend fun startActivityForResult(intent: android.content.Intent): ActivityResult
    suspend fun postNotification(channel: String, title: String, body: String)
    suspend fun toast(message: String)
}

data class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
```

`startActivityForResult` 会**挂起**调用处理器的协程；运行时将操作系统 `onActivityResult` 回调桥接回来以恢复协程（[03 §9.4](./03-runtime.md)）。处理器的 `timeoutMs` 截止时间在此挂起期间持续计时。`UiService` 方法在 `Dispatchers.Main` 上派发 —— 插件代码无需切换调度器即可调用它们。

`toast` 为 v0.x 增补：默认实现抛出 `UNAVAILABLE`（无 UI 宿主不伪造弹出）；Android 宿主经主线程 `Handler` 弹出真实 `Toast`。无 `sys.toast` 命令 —— toast 供插件在自身流程内做轻量反馈，不进入命令面。

### 6.4 `SecureStore` —— 由 Keystore 支持的密钥存储

按插件命名空间的键值存储，由 Android Keystore 支持（[08 §9](./08-security.md)）。键的作用域限定在插件 ID 内；一个插件无法读取另一个插件的密钥。

```kotlin
interface SecureStore {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, value: ByteArray)
    suspend fun remove(key: String)
    suspend fun keys(): Set<String>
}
```

用于存储 API 令牌、OAuth 刷新令牌、厂商凭据。**切勿**将密钥存储在 `FileService`、`Memory` 或清单中。存储在此处的密钥会被排除在审计脱敏遍历之外（它们从不进入 IR），也被排除在 Memory 同步之外。

### 6.5 `Clock` —— 可注入的时间

```kotlin
interface Clock {
    fun now(): kotlinx.datetime.Instant        // wall clock
    fun monotonicMs(): Long                     // monotonic, for elapsed-time measurement
}
```

始终使用 `Clock`，而非直接使用 `System.currentTimeMillis()` / `Instant.now()` —— 这让处理器具有确定性和可测试性。`mcos-sdk-testing` 会注入一个 `FakeClock`（[§14.1](#141-full-mcos-sdk-testing-api)），使测试可以在不 `Thread.sleep` 的情况下推进时间。

### 6.6 `MemoryFacade` —— 插件只读视图

插件获得 Memory 引擎（[07 §5](./07-memory.md)）的**只读**视图。完整的 `get`/`put`/`delete`/`search`/`resolve`/`export`/`import` 接口由运行时/Planner 拥有；插件只能使用：

```kotlin
interface MemoryFacade {
    suspend fun get(path: String): JsonElement?
    suspend fun search(query: String, filter: MemoryFilter = MemoryFilter.ALL): List<MemoryHit>
}
```

`put` / `delete` / `import` **对插件不可用** —— 写入通过 Planner 经用户确认后进行（[07 §5.1](./07-memory.md)）。如果处理器需要持久化数据，应使用 `FileService`（设备本地）或通过 `NetService` 调用自有的后端。

### 6.7 `NotificationService` / `MediaService` —— 可选平台能力（v0.x 补记规范）

早期切片已实现但未在此处补记规范，现补齐。二者遵循**可选能力模式**：接口默认 `null`，无对应平台的宿主不覆写，插件见 `null` 时上报 `UNAVAILABLE`（`sys.notify` 的 P0-F1 语义）。

```kotlin
interface NotificationService {
    suspend fun notify(title: String, text: String): String   // returns channel/notification id
}

interface MediaService {
    suspend fun compress(
        uris: List<String>, quality: Int,
        maxWidth: Int? = null, maxHeight: Int? = null,
    ): List<String>   // JPEG 压缩/缩放后的输出 URI（保持顺序，跳过 null）
}
```

`NotificationService` 对应 `sys.notify`（`POST_NOTIFICATIONS` 运行时授权未给时命令如实报 `PERMISSION_DENIED`）。`MediaService` 服务于相机插件的照片压缩链路。

### 6.8 `DeviceInfoService` —— 设备信息（v0.x 增补）

支撑 `sys.device.*` 六条命令（battery/wifi/screen/volume/location/brightness 查询与设置）。核心契约：**如实报告**——宿主无法确定的数据项返回 `null`，绝不猜测（P2-F3 语义：曾经返回硬编码假数据的实现已被清除）。

```kotlin
interface DeviceInfoService {
    suspend fun battery(): BatteryInfo        // percent, charging, temperatureC?
    suspend fun wifi(): WifiInfo              // connected; ssid/strength 在无定位授权时为 null（Android 9+ 限制）
    suspend fun screen(): ScreenInfo          // widthPx, heightPx, densityDpi, rotation, brightness?
    suspend fun volume(): VolumeInfo          // musicPercent, ringPercent?, alarmPercent?
    suspend fun location(): LocationInfo?     // 无定位结果时返回 null —— 命令层输出 no_fix，不是错误
    suspend fun brightness(): BrightnessInfo  // level (0-255), auto
    suspend fun setBrightness(level: Int)     // WRITE_SETTINGS 特殊授权缺失时抛 PERMISSION_DENIED，不伪造成功
}
```

Android 权限约束与降级语义：`wifi` 的 SSID/RSSI 需要 `ACCESS_FINE_LOCATION` 运行时授权，缺失时返回 `connected=true, ssid=null, strength=null`；`location` 需要 `ACCESS_FINE_LOCATION`，首次缺失时在应用内弹窗请求（`RuntimePermissionBridge`，item 38），用户拒绝或无 Activity（headless 调度运行）时抛 `PERMISSION_DENIED`（附可操作的提示），授权但无定位结果返回 `null` → 命令输出 `{"status":"no_fix","location":null}`；`setBrightness` 需要 WRITE_SETTINGS 特殊授权（特殊授权没有 `requestPermissions` 对话框）——首次缺失时经 activity-result 桥直接深链到本应用的「修改系统设置」页面，返回后复查，仍不可写或无 Activity 时抛 `PERMISSION_DENIED`。未授权状态始终如实报错；只有 headless 运行才引导用户去系统设置。

### 6.9 `ClipboardService` —— 剪贴板（v0.x 增补）

支撑 `sys.clipboard` 读写两模式。

```kotlin
interface ClipboardService {
    suspend fun set(text: String)
    suspend fun get(): String?   // 空剪贴板或宿主无法读取（Android 后台限制）时返回 null
}
```

`get()` 返回 `null` 与「剪贴板为空」不可区分 —— 命令层一律如实报 `UNAVAILABLE`，绝不编造文本。**剪贴板文本是不可信输入**（[08 §11.1](./08-security.md)）：用户可能复制了对抗性文本，`sys.clipboard` 读模式的结果恒带 `untrusted: true` 标注，供下游提示注入防御使用。

### 6.10 `HapticsService` —— 震动反馈（v0.x 增补）

支撑 `sys.vibrate`。

```kotlin
interface HapticsService {
    suspend fun vibrate(durationMs: Int)
}
```

无震动器的宿主保持 `null` → 命令报 `UNAVAILABLE`。曾经的「假成功」（不触达硬件即返回 `vibrated`）已被移除 —— 审计轨迹必须只记录真实发生的震动。Android 实现走 `VibratorManager`（API 31+）/ 旧版 `Vibrator` 兼容分支。

### 6.11 `EventPublisher` —— EventBus 发布能力（v0.x 增补）

支撑 `sys.event.emit`，让插件可发出领域事件（`wifi.connected`、`user.arrived.home`）供事件触发配方（[05 §9.2](./05-workflow.md)）订阅。

```kotlin
interface EventPublisher {
    suspend fun publish(type: String, payload: JsonObject)
}
```

Runtime 在存在共享 EventBus（[03 §11](./03-runtime.md)）时自动接线；发布**不是**特权总线旁路 —— 发出的事件信封携带发布上下文，消费方（触发器、Agent 循环）各自套用自己的过滤器。无总线的宿主保持 `null` → `sys.event.emit` 报 `UNAVAILABLE`，绝不假成功。

---

## 7. 处理器模式

### 7.1 纯本地

```kotlin
class WeatherTodayHandler(
    private val client: WeatherClient
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val city = ctx.stringOrNull("city") ?: ctx.memoryDefault("places.defaultCity")?.jsonPrimitive?.contentOrNull ?: "Beijing"
        val data = client.fetchToday(city)
        return CommandResult.Ok(data.toJson())
    }
}
```

### 7.2 用户 Activity 结果

对于需要 Activity 结果的流程（拍摄预览）：

1. 处理器调用 `ctx.services.ui().startActivityForResult(intent)`，这会**挂起**处理器协程  
2. 运行时将 Android `onActivityResult` 回调桥接回来以恢复协程（[03 §9.4](./03-runtime.md)）  
3. 超时（`timeoutMs`）在挂起期间持续计时 —— 用户从不返回时触发 `TIMEOUT`  

### 7.3 流式产出物（Artifacts）

大体积媒体：以 `content://` / `file://` URI 作为产出物返回；避免在 IR 结果中使用 base64。

### 7.4 协作式取消义务

Executor 协作式地强制执行 `timeoutMs` 和外部取消（[03 §9.4](./03-runtime.md)）。当取消或超时触发时，Executor 会调用 `handler.cancel(ctx)` 并最多等待 `cancelGraceMs`（默认 2000 ms）让处理器收尾。**忽略取消的处理器是一个缺陷** —— 宽限期结束后，运行时会强制取消协程，反复的强制取消会触发熔断器（[01 §15.3](./01-architecture.md)）：3 次命中 / 60 秒 → 30 秒冷却；持续命中 → 自动卸载（[03 §16.5](./03-runtime.md)）。

**作者规则：**

1. 如果你的处理器持有需要显式释放的资源（打开的套接字、相机会话、文件锁），**重写 `cancel(ctx)`**。默认空操作仅适用于纯计算处理器。
2. **在长循环中检查取消。** 使用 Kotlin 的 `ensureActive()` 或 `currentCoroutineContext().isActive` 提前退出：
   ```kotlin
   override suspend fun invoke(ctx: ExecutionContext): CommandResult {
       val rows = ctx.services.files().list("inbox")
       for (row in rows) {
           currentCoroutineContext().ensureActive()   // throws CancellationException on cancel
           process(row)
       }
       return CommandResult.Ok(...)
   }
   ```
3. **传播 `CancellationException`。** 切勿作为通用异常捕获 —— 让它解开协程。如果必须捕获（例如在 `finally` 中），在清理后重新抛出。
4. **遵守 `ctx.deadline`。** 对没有原生超时支持的操作，在开始每个工作单元前与 `ctx.deadline`（一个 `Instant`）比较。

能及时响应取消的处理器永远不会撞上熔断器。

---

## 8. 权限声明准则

| 应当 | 不应 |
|----|-------|
| 声明最小权限 | "以防万一"地请求无障碍权限 |
| 提供人类可读的 `reason` 字符串 | 在 `read` 下隐藏网络使用 |
| 正确标记破坏性删除 | 将删除标记为 `read` |
| 出网流量使用 `network` 类别 | 在清单中内置密钥 |

在企业策略下，运行时可以**拒绝加载**请求了禁止组合的插件。

### 8.1 MCOS 作用域词汇表

除了 Android 权限之外，MCOS 还定义了自己的作用域词汇表，插件以 `"type": "mcos"` 声明。完整的权威列表见 [08 §3](./08-security.md)；面向作者的摘要：

| 作用域 | 何时声明 |
|-------|------------------|
| `command.<id>` | 从你插件拥有的每个命令自动派生 —— 你**无需**手动声明这些 |
| `memory.read` | 调用 `services.memory().get(...)` 或 `services.memory().search(...)` 所必需 |
| `memory.write` | 当本插件发出事件、而另一个插件消费以写 memory 时所必需（少见 —— 大多数插件对 memory 是只读的） |
| `network.<domain>` | 你插件联系的每个独立 eTLD+1 一个（如 `network.api.openai.com`）。每个 `http.url`（[§11.1](#111-the-http-object--field-by-field-specification)）的主机部分必须被一个声明的 `network.*` 作用域覆盖 |
| `mcp.server.<id>` | MCP 适配器为每个已配置的服务器所需；在设置中由用户按服务器授权 |
| `securestore.<keyprefix>` | 你的插件读取的每个 `SecureStore` 键前缀所必需（如 §11.1 中 `auth.secretKey` 示例所需的 `securestore.example_token`） |

**编写规则。** 在命令的 `permissions[]` 数组中以 `type: "mcos"` 声明作用域，并配以人类可读的 `reason` 字符串。示例：

```json
{
  "id": "weather.forecast",
  "permissions": [
    { "type": "android", "name": "INTERNET", "reason": "Fetch forecast data" },
    { "type": "mcos",    "name": "network.api.weather.example.com", "reason": "Forecast API endpoint" },
    { "type": "mcos",    "name": "memory.read", "reason": "Read user's default city" }
  ]
}
```

运行时的 Authorize 阶段（[01 §5](./01-architecture.md) 第 6 阶段）会拒绝执行这样的命令：其声明的 `network.*` / `memory.*` / `securestore.*` 作用域未覆盖处理器在运行时实际所做之事 —— 因此过度声明毫无益处，而声明不足会快速失败。

---

## 9. IoT 插件模式

```text
home/
  plugin.json
  commands:
    home.light.on
    home.light.off
    home.scene.movie
    home.scene.sleep
```

实现**在插件内部**与 Home Assistant / Tuya / Matter 通信。  
运行时只看到命令 ID。

设备发现**应当（SHOULD）**暴露一个 `home.device.list` 读命令，而不是另起带外的通道。

### 9.1 插件生命周期状态机

每个插件实例都会经历此状态机，由 Plugin Loader 拥有（[03 §16](./03-runtime.md)）：

```text
        onLoad(services)              handlers() registered
loaded ─────────────────► ready ─────────────────────────► active
   │                         ▲                              │
   │ onLoad failed           │ re-enable                    │ auto-unload
   ▼                         │                              ▼
unloaded (registration     paused                        unloading
   rolled back)               │                              │
                             │ user disable / policy        │ onUnload()
                             └──────────────────────────────┘
                                                            ▼
                                                        unloaded
```

**面向作者的规则：**

1. **`onLoad(services: HostServices)` 是唯一的地方**，用于获取长期存活的资源（数据库句柄、MCP 连接、监听器）。将它们存储在插件实例上。不在此处获取的任何东西在 `invoke()` 中都不可用。
2. **`onLoad` 失败 → 注册回滚。** 如果 `onLoad` 抛出，运行时会注销该插件本要发布的每个命令描述符，并记录一条 `PluginLoadFailed` 审计事件（[03 §16.2](./03-runtime.md)）。插件停留在 `unloaded` 状态，**不会**收到 `onUnload()` —— 其部分工作必须在 `finally` 块中自行清理。如果你希望运行时稍后重试加载，请抛出 `McosException("UNAVAILABLE", ..., retryable = true)`。
3. **`onUnload()` 必须幂等且快速**（目标 < 1 秒）。它在自动卸载、用户禁用和运行时关闭时被调用。释放 `onLoad` 中获取的每个资源；进行中的 `invoke()` 调用在 `onUnload` 运行前已被取消。
4. **自动卸载（熔断器）。** 如果你的处理器反复触发熔断器（60 秒内 3 次强制取消，或持续的高错误率），运行时会调用 `onUnload()` 并在未经协商的情况下将插件置入 `unloading` → `unloaded` 状态（[01 §15.3](./01-architecture.md)、[03 §16.5](./03-runtime.md)）。你得到的唯一信号是 `onUnload()` 意外地运行了 —— 编写它，使之在任何情况下都安全。
5. **重新启用流程。** 用户重新启用被停用的插件会针对一个**新的**插件实例触发全新的 `onLoad()` —— 你旧的实例状态已丢失。不要依赖静态单例在卸载/重新加载之间持久化；请将任何用户状态持久化到 `SecureStore` 或 memory（07 §5）。

---

## 10. MCP 适配器插件

特殊插件 `mcos.plugin.mcp` 将外部 [MCP](https://modelcontextprotocol.io) 服务器桥接到 MCOS 命令总线。它是一个 **P3** 生产目标（[§17](#17-built-in-plugin-set-first-party)）；一个 **P2 bridge spike**（仅限用户配置的可信 server）提前验证 schema 转换 + 生态采纳论点——spike 范围护栏见 [10-roadmap.md §5.7](./10-roadmap.md)。

```text
Connect MCP server (user-configured)
  → list tools
  → synthesize Command Descriptors under mcp.<server>.*
  → handlers proxy invoke() to MCP tool calls
  → map MCP results back to CommandResult
```

**Schema 转换：** MCP JSON-Schema → MCOS `inputSchema` 的逐字段映射（包括对 `oneOf`/`anyOf` 等不可映射类型的 fail-closed 规则）权威定义见 [02 §12.4](./02-command-protocol.md)。适配器不会发明自己的映射。

**适配器职责**（面向作者）：

| 职责 | 细节 |
|----------------|--------|
| 连接管理 | 为每个已配置的 MCP 服务器维持一条活动连接；带退避地重连；断开时将所有 `mcp.<server>.*` 命令标记为 `UNAVAILABLE` |
| 工具发现 | 连接时枚举工具并向注册表动态注册描述符（[03 §6.5](./03-runtime.md) 热重载） |
| 处理器代理 | 每个合成处理器的 `invoke()` 将 MCOS 参数转换为 MCP 工具调用参数，等待 MCP 响应，并映射为 `CommandResult.Ok`/`Err` |
| 鉴权失败映射 | MCP 鉴权错误 → `PERMISSION_DENIED`（`details.permission = "mcp.server.<id>"`）；连接错误 → `UNAVAILABLE` |
| 用户控制 | 每个服务器在设置中由用户启用/禁用；被禁用的服务器的描述符会被注销 |

市场目录桥接（将 MCP 服务器作为可发现的 MCOS 插件发布）规定于 [09 §10](./09-marketplace.md)。

---

## 11. HTTP / Webhook 插件骨架

适用于无需完整原生代码的快速集成：

```json
{
  "id": "webhook.example",
  "commands": [
    {
      "id": "hook.ping",
      "sideEffectClass": "network",
      "http": {
        "method": "POST",
        "url": "https://example.com/hook",
        "bodyTemplate": { "ok": true }
      }
    }
  ]
}
```

声明式 HTTP 是 SDK 中可选的语法糖；上架市场仍需通过安全评审。

### 11.1 `http` 对象 —— 逐字段规范

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|-------|------|----------|---------|------------|
| `method` | enum | 是 | — | `GET`、`POST`、`PUT`、`PATCH`、`DELETE` 之一 |
| `url` | string（模板） | 是 | — | 生产环境必须为 HTTPS；支持从输入参数插入 `{{arg.<name>}}`；查询字符串由 `query` 字段构建 |
| `headers` | object | 否 | `{}` | 每个值可以是 `{{arg.x}}` 模板或对 `SecureStore` 的 `{{secret.<key>}}` 引用 |
| `query` | object | 否 | `{}` | 查询参数映射；值可以是 `{{arg.x}}` 模板 |
| `bodyTemplate` | JSON 对象（模板） | 否 | `null` | `POST`/`PUT`/`PATCH` 的 JSON 体；任何叶子值都可以是 `{{arg.x}}`；**不得**引用任何标记为 `x-mcos-secret` 的字段 |
| `auth` | object | 否 | `null` | 绑定到 `SecureStore` 键：`{ "type": "bearer", "secretKey": "<key>" }` 或 `{ "type": "basic", "secretKey": "<key>" }`。运行时将解析后的凭据注入请求；该值永远不会出现在 `bodyTemplate` 或 `url` 中 |
| `timeoutMs` | integer | 否 | `15000` | 范围 `1000`–`120000` |
| `errorMapping` | object | 否 | `{}` | 将 HTTP 状态码（或范围 `"4xx"`/`"5xx"`）映射到 MCOS 错误代码；如 `{ "401": "PERMISSION_DENIED", "5xx": "UNAVAILABLE", "429": "RATE_LIMITED" }`。未映射的状态对 5xx 默认为 `INTERNAL`，对 4xx 默认为 `SCHEMA_VIOLATION` |
| `successCode` | integer | 否 | `200`–`299` | 仅将这些状态码视为成功 |

**模板插值。** 只有 `{{arg.<name>}}`（输入参数）和 `{{secret.<key>}}`（仅用于 `headers`）是被识别的模板。未知的 `{{...}}` 是字面字符串。缺失的 `arg` 引用会在执行时以 `SCHEMA_VIOLATION` 失败。

**安全约束。**

1. 生产构建中 `url` 必须为 `https://`（仅在开发标志下允许 `http://`）。
2. `bodyTemplate` 不得包含任何 `{{secret.*}}` 或来自声明为 `x-mcos-secret` 字段的任何值 —— 密钥只能通过 `auth` 或 `headers` 进入请求。`mcos-sdk-gradle` 检查器会标记违规。
3. 命令的 `sideEffectClass` 必须为 `network`，且 `url` 的主机部分必须被一个 `network.<domain>` 权限作用域覆盖（[08 §3](./08-security.md)）。

**带 `auth` + `errorMapping` 的示例：**

```json
{
  "http": {
    "method": "POST",
    "url": "https://api.example.com/v1/messages",
    "headers": { "Authorization": "Bearer {{secret.token}}" },
    "bodyTemplate": { "text": "{{arg.message}}" },
    "auth": { "type": "bearer", "secretKey": "example_token" },
    "timeoutMs": 30000,
    "errorMapping": {
      "401": "PERMISSION_DENIED",
      "429": "RATE_LIMITED",
      "5xx": "UNAVAILABLE"
    }
  }
}
```

运行时通过原生插件所用的同一个 `NetService` 执行 `http` 插件，因此企业域名白名单和代理设置统一适用。

---

## 12. 本地化与 UX 提示

清单可包含：

```json
{
  "i18n": {
    "zh-CN": {
      "name": "相机",
      "commands": {
        "camera.capture": {
          "title": "拍照",
          "description": "使用相机拍摄一张照片"
        }
      }
    }
  }
}
```

CLI 帮助和确认对话框优先使用本地化标题。

### 12.1 语言区域回退链

在解析时，运行时按以下顺序查找每个可本地化字段，命中第一个存在的键即停止：

```text
<user-locale>            e.g. zh-CN
  → <language-only>      e.g. zh
    → manifest default (en if not declared)
```

**可本地化字段列表。** 只有以下字段会被本地化；所有其他字段（命令 ID、错误代码、schema 字段名、DSL 关键字）保持英文：

| 字段 | 所有者 | 示例 |
|-------|-------|---------|
| 插件 `name` | 清单根 | `"相机"` |
| 命令 `title` | 按命令 | `"拍照"` |
| 命令 `description` | 按命令 | `"使用相机拍摄一张照片"` |
| 权限 `reason` | 按权限 | `"用于扫码"` |
| 命令 `examples[].description` | 按示例 | `"扫码登录"` |

**缺失键行为。** 缺失的键会静默回退到链中的下一个语言区域，最终回退到清单原始的（英文）值。**运行时对缺失键不会报错** —— 运行时本地化按设计采用 fail-soft。

**市场 CI 完整性检查。** 提交到市场时会运行一个 CI 门控，要求对于每个已发布的语言区域标签，`title` 和 `description` 键的完整集合必须存在（其他如 `reason`/`examples` 的键会警告但不阻塞）。见 [09 §5.1](./09-marketplace.md)。`mcos-sdk-gradle` 检查器（[§13.2](#132-mcos-sdk-gradle-validator)）在本地重现此检查，使作者能在提交前快速失败。

---

## 13. 面向插件作者的版本管理规则

1. 改变输出含义 → 命令**主版本号（MAJOR）**+1  
2. 增加可选输入 → **次版本号（MINOR）**+1  
3. 发布替代 ID → 用 `replacedBy` 弃用旧 ID  
4. 使用新的宿主 API 时，`minRuntimeVersion` 必须相应提升  

SDK 计划发布 `mcos-sdk-gradle` 检查器，用于在 CI 中校验清单。

### 13.1 可由 CI 检查的规则

这些规则是机器可检查的，并由本地 `mcos-sdk-gradle` 和市场 CI 门控（[09 §5.1](./09-marketplace.md)）共同强制执行：

| 规则 | 检查 | 失败模式 |
|------|-------|--------------|
| **SemVer 正则** | 插件 `version` 与每个命令的 `version` 匹配 `^\d+\.\d+\.\d+$` | 构建错误 |
| **命令版本耦合** | 插件 `version` 的 MAJOR 升级必须伴随其拥有的至少一个命令的 MAJOR 升级；新命令可以从 `0.1.0` 起步 | 构建错误 |
| **`minRuntimeVersion` 单调性** | 新版本的 `minRuntimeVersion` 必须 ≥ 上一个已发布版本的 `minRuntimeVersion` | 提交拒绝 |
| **`replacedBy` 解析** | 任何声明 `replacedBy: "<id>"` 的命令必须引用在本插件或某个声明依赖中注册的命令 ID | 构建错误 |
| **弃用但无 `replacedBy`** | 标记 `deprecated: true` 的命令应当声明 `replacedBy`；缺失时 CI 给出警告（不阻塞） | 警告 |
| **命名空间所有权** | 每个命令 ID 的首段必须匹配插件声明的 `namespaces[]` 之一（[02 §4.4](./02-command-protocol.md)） | 构建错误 |
| **唯一 ID** | 插件内无重复命令 ID，且不与保留命名空间（`mcos.*`、`sys.*`、`mcp.*`、`std.*`）冲突 | 构建错误 |

### 13.2 `mcos-sdk-gradle` 校验器

Gradle 插件 `mcos-sdk-gradle` 暴露一个单一任务，作者在提交前运行以镜像市场 CI：

```bash
./gradlew mcosValidate
```

**检查清单**（每项映射到一个 CI 门控）：

1. **清单 schema** —— 清单根据由 [01 §10](./01-architecture.md) 派生的 JSON Schema（`CommandDescriptor` + 清单根）解析。
2. **保留命名空间检查** —— 第三方插件拒绝 `mcos.*`、`sys.*`、`mcp.*`、`std.*`（[02 §4.3](./02-command-protocol.md)）。
3. **重复 ID 检查** —— 清单内部以及已声明依赖之间（[02 §4.4](./02-command-protocol.md)）。
4. **sideEffectClass 诚实度启发式** —— 标记可疑的不匹配：
   - 命令声明 `sideEffectClass: "read"` 但清单提及 `http`/`destructive` 标记
   - 命令声明 `write`/`destructive` 但 `bodyTemplate`/处理器的所有分支只返回 `read` 形态的产出物
   - 是警告，不是硬错误；市场的容忍策略见 [09 §5.1](./09-marketplace.md)
5. **SemVer 合规** —— 见上文 [§13.1](#131-ci-checkable-rules) 的规则。
6. **i18n 完整性** —— 每个声明的语言区域标签都存在 title/description（[§12.1](#121-locale-fallback-chain)）。
7. **密钥封闭** —— `http.bodyTemplate` 中不出现 `{{secret.*}}` 或来自 `x-mcos-secret` 字段的值（[§11.1](#111-the-http-object--field-by-field-specification)）。

校验器报告与市场返回的 JSON 形状相同，因此修复所有本地错误应当带来一次干净的提交。

---

## 14. 测试支持

```kotlin
class CameraScanTest {
    @Test
    fun scansQr() = runBlocking {
        val rt = FakeRuntime.with(CameraPlugin())
        val result = rt.executeDsl("camera.scan(format=\"qr\")")
        assertTrue(result.ok)
    }
}
```

`mcos-sdk-testing` 提供：

- 伪权限内核（PermissionKernel，自动授权 / 拒绝集合）  
- 内存版事件总线（EventBus）  
- 已记录进度的断言  

### 14.1 完整的 `mcos-sdk-testing` API

**`FakeRuntime.Builder`** —— 在每个测试前配置内存版运行时：

```kotlin
val rt = FakeRuntime.Builder()
    .with(CameraPlugin())
    .with(WeatherPlugin())
    .grants("command.camera.scan", "command.hello.world")   // auto-approve these scopes
    .deny("command.camera.delete")                           // always reject
    .clock(FakeClock(start = "2026-08-06T10:00:00Z"))        // deterministic Clock
    .config(RuntimeConfig(networkAllowList = listOf("*.example.com")))
    .build()
```

| 方法 | 用途 |
|--------|---------|
| `with(plugin: McosPlugin)` | 注册一个插件实例 |
| `grants(vararg scopes: String)` | 伪 PermissionKernel 自动批准的作用域 |
| `deny(vararg scopes: String)` | 伪 PermissionKernel 总是拒绝的作用域 |
| `clock(fake: FakeClock)` | 注入一个可控的 `Clock` 以获得确定性时间 |
| `config(cfg: RuntimeConfig)` | 覆盖默认值（网络白名单、截止时间等） |
| `secureStoreFake(entries: Map<String, ByteArray>)` | 预置伪 `SecureStore` |

**执行** —— 两个镜像生产运行时的入口：

```kotlin
suspend fun executeDsl(dsl: String): FakeResult
suspend fun invoke(commandId: String, args: JsonObject): FakeResult
```

**`FakeResult`** —— 暴露测试所关心的一切：

```kotlin
class FakeResult {
    val ok: Boolean
    val value: JsonObject                  // non-null when ok
    val error: CommandResult.Err?          // non-null when !ok
    val events: List<RuntimeEvent>         // every RuntimeEvent emitted during the run
    val progressLog: List<ProgressEntry>   // every progress() call
    val artifacts: List<Artifact>          // every emit() recorded
    val meta: JsonObject                   // the Runtime-owned meta (read-only)
}
```

**断言辅助**（`FakeResult` 上的扩展函数）：

```kotlin
result.assertEmitted<RuntimeEvent.RunStarted>()
result.assertEmitted("artifact.saved")                       // by event type tag
result.assertProgressContains("Scanning frame 3")
result.assertArtifactCount(2)
result.assertError("PERMISSION_DENIED")                      // code check
```

**内部实现。** `FakeRuntime` 将同一个 10 阶段流水线（[01 §5](./01-architecture.md)）接到内存伪实现 —— 没有 Android 依赖，没有真实网络，没有真实文件系统。它是 JVM 可运行的，适用于纯 JUnit/Kotest。

**完整示例 —— 快乐路径 + 权限拒绝：**

```kotlin
class CameraScanTest {
    @Test
    fun happyPath() = runBlocking {
        val rt = FakeRuntime.Builder()
            .with(CameraPlugin())
            .grants("command.camera.scan")
            .build()

        val result = rt.executeDsl("camera.scan(format=\"qr\")")

        assertTrue(result.ok)
        assertEquals("qr", result.value["format"]!!.jsonPrimitive.content)
        result.assertEmitted<RuntimeEvent.StepStarted>()
        result.assertArtifactCount(1)
    }

    @Test
    fun deniedWithoutScope() = runBlocking {
        val rt = FakeRuntime.Builder()
            .with(CameraPlugin())
            // no grants
            .build()

        val result = rt.invoke("camera.scan", buildJsonObject { put("format", JsonPrimitive("qr")) })

        assertFalse(result.ok)
        result.assertError("PERMISSION_DENIED")
        // 没有 StepStarted 事件，因为 Authorize 阶段在 Execute 之前就拒绝了
        result.assertProgressContains("Permission denied: command.camera.scan")
    }
}
```

---

## 15. 安全评审清单（市场）

上架插件之前：

- [ ] 清单权限与实际 API 使用一致  
- [ ] 包内无明文密钥  
- [ ] `sideEffectClass` 如实  
- [ ] 网络域名已记录  
- [ ] 已签名构建 / 尽可能可复现  
- [ ] 同步 PII 的插件提供隐私政策 URL  
- [ ] **针对 Planner 消费的输出添加提示注入标签。** 其输出文本会被 Planner 消费的插件（如返回 OCR 文本的 `camera.scan`、邮件阅读器、网页抓取器）应当用信任信号标记其结果，以便 Planner 把不受信任的内容当作数据而非指令处理。权威的标记方式和 Planner 的处理规则见 [08 §11](./08-security.md)。此类插件的作者应在插件 README 中明确说明哪些输出字段可能包含对抗性内容。

---

## 16. 示例：最小化的 Hello 插件

**plugin.json**

```json
{
  "id": "example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "minRuntimeVersion": "0.1.0",
  "entry": "com.example.hello.HelloPlugin",
  "commands": [
    {
      "id": "hello.world",
      "version": "1.0.0",
      "title": "Hello World",
      "sideEffectClass": "read",
      "idempotent": true,
      "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "name": { "type": "string", "default": "MCOS" }
        }
      },
      "outputSchema": {
        "type": "object",
        "required": ["message"],
        "properties": {
          "message": { "type": "string" }
        }
      },
      "examples": ["hello.world()", "hello.world(name=\"Tom\")"]
    }
  ]
}
```

**处理器**

```kotlin
class HelloWorldHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val name = ctx.args["name"]?.jsonPrimitive?.contentOrNull ?: "MCOS"
        return CommandResult.Ok(buildJsonObject { put("message", "Hello, $name") })
    }
}
```

---

## 17. 内置插件集（第一方）

> ✅ **Implementation status:** 前四个插件（hello / system / camera / files）已在 `plugins/` 落地并带一致性测试，同时是 marketplace 的 curated 内置集；其余仍为 spec-only。
>
> **本表是内置命令表面的单一真相源。** 所有其他文档（路线图、仓库拓扑、愿景）引用本表，而非维护独立的命令清单。若某命令出现在其他文档但不在本表中，它是未记录的——应加入本表或从引用文档中移除。`mcos.plugin.system` 插件同时拥有 `sys.*` 和 `sys.device.*` 命名空间（设备查询是系统 API 封装，归入保留的 `sys` 根而非单独的 `device` 根——`device` 不是保留命名空间，见 [02 §4.3](./02-command-protocol.md)）。

| 插件 | 命令（示意） | 目标阶段 |
|--------|-------------------------|--------------|
| `example.hello` | `hello.world` | P1（参考） |
| `mcos.plugin.system` | `sys.notify`, `sys.share`, `sys.clipboard`, `sys.openUrl`, `sys.vibrate`, `sys.device.battery`, `sys.device.wifi`, `sys.device.screen`, `sys.device.volume`, `sys.device.location`, `sys.device.brightness`, `sys.event.emit` | P1（+`sys.event.emit` P2） |
| `mcos.plugin.camera` | `camera.capture`, `camera.scan` | P1 |
| `mcos.plugin.files` | `file.list`, `file.search`, `photo.search`, `photo.compress`, `file.write`, `file.read`, `file.stat`, `file.delete` | P1 |
| `mcos.plugin.iot` | `home.*`, `iot.*` | P2 |
| `mcos.plugin.mcp` | 动态 `mcp.*` | P2 spike / P3 production |

---

## 18. SDK v0.1 的非目标

- 在无重启保证的情况下热修补原生 `.so`  
- 在生产构建中以进程内方式运行不受信任的未签名代码  
- 跨语言插件（Swift/RN）—— 未来可能出现桥接  
- 完整的操作系统提权 API  

---

## 19. 总结

SDK 将第三方能力转化为**注册表原生命令（registry-native commands）**：

- 清单声明**做什么**以及**需要什么权限**  
- 处理器实现**怎么做**  
- 运行时强制执行**是否允许**  

下一篇：将多个命令组合成可靠的图 —— [05-workflow.md](./05-workflow.md)。
