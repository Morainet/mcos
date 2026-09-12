# Intent Plugin

Intent / Deep Link / App Functions 桥接插件——把「启动另一个 App 的能力」变成受 schema 约束的命令。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `intent.start` | `action`（必填）、`dataUri?`、`package?`、`categories?`、`extras?`、`extrasSchema?` | write | 启动一个 Intent。`extras` **必须**由逐次声明的 `extrasSchema` 约束（02 §12.6）；action 属 well-known 允许清单时可省略 schema |
| `deeplink.open` | `uri`（必填，须带 scheme）、`package?` | write | 以 `ACTION_VIEW` 打开深链；payload 走 URI，故 §12.6 的 extras 危险面不适用 |
| `appfn.invoke` | `package`（必填）、`function`（必填，单段）、`args?` | write | 调用其他 App 包发布的 App Function（02 §12.2/§12.5） |

```text
intent.start(action="android.intent.action.VIEW", dataUri="https://example.com")
intent.start(action="com.example.app.OPEN", extrasSchema={type:"object"}, extras={})
deeplink.open(uri="myapp://profile/42")
appfn.invoke(package="com.example.notes", function="createNote", args={title: "Hi"})
```

## 规范对齐

- **02 §12.6（extras 必须带 schema）**：`intent.start` 执行该规则——缺 `extrasSchema` 且 action 不在允许清单内 → `SCHEMA_VIOLATION`，`details.path = /args/extras`、`details.reason = extras_schema_required`（与该节写明的 Stage-5 拒绝同码/同 reason）。校验器只支持可安全校验的子集（`type`/`properties`/`required`/`additionalProperties:false`/`enum`/`const`/`items`/数值与长度边界），遇 `oneOf`/`anyOf`/`$ref`/`pattern`/`format`、schema 值型 `additionalProperties`、元组 `items[]` 等关键字一律 **fail-closed**（`extras_schema_unsupported`），绝不半校验——与 MCP 转换器对不可映射工具的姿态一致（02 §12.4）。
- **Well-known 允许清单**：`WellKnownIntents` 发布 6 条系统 action 的预声明 schema（VIEW/MAIN/DIAL/SENDTO/WEB_SEARCH/SEND），全部 `additionalProperties: false`，使「模型臆造 extra key」从静默 misfire 变成可自纠的拒绝。
- **02 §12.5（App Function id）**：`AppFunctionIds` 实现 `sys.appfn.<encodedPackage>.<function>`。**诚实边界**：§12.5 称反向查找「无歧义」，但只对不含 `_` 的包名成立——`com.my_app` 与 `com.my.app` 编码同为 `com_my_app`。歧义在**编码**侧，故 `encode` 对含 `_` 的包名 fail-closed（这类包用 `appfn.invoke(package, function, args)` 参数形式寻址），`decode` 按规范算法还原。
- **无假成功**：宿主未提供 `HostServices.intents` / `HostServices.appFunctions` 时命令报 `UNAVAILABLE`；Intent 无接收方时返回 `status="not_handled"`（诚实结果，不是错误）。

## 诚实边界

- `appfn.invoke` 无法得知目标函数的实际影响，声明 `write` 作为下限（运行时仍要求 write 授权并展示 DSL 预览）；目标函数为 destructive 的发布者应以更强副作用的包装描述符发布。
- extras schema 由调用方**逐次**声明，因此 §12.6 的拒绝发生在处理器内而非 Executor 的 Stage-5（静态 `inputSchema` 表达不了逐次 schema）；错误码/path/reason 逐字对齐规范。
- 本次为纯 JVM 交付：Android 侧 `AndroidHostServices` 的 `intents`/`appFunctions` 实现与隔离进程 wire op 为后续工作；在此之前 Android 上两者如实 `UNAVAILABLE`。隔离（独立进程）插件同样保持 `UNAVAILABLE`，绝不假成功。
- **命名空间张力（规范内部不一致）**：02 §12.2/§12.5 把生成的 App Function id 定为 `sys.appfn.*`，而 `sys.*` 属保留命名空间、归 `mcos.plugin.system`（04 §9/§14），第三方插件不得注册；路线图 §5.4 又把该桥命名为独立命令 `appfn.invoke`。本插件采用路线图命名（`appfn.invoke`）并把 §12.5 编码落为**工具函数**（`AppFunctionIds`，用于审计关联），不注册任何 `sys.*` 描述符，因此不触碰保留命名空间规则；未来若要让适配器真正注册 `sys.appfn.<pkg>.<fn>` 逐函数描述符，需要规范明确其归属。

## 文件

| 文件 | 说明 |
|------|------|
| `IntentPlugin.kt` | 插件入口 + 三个 Handler |
| `ExtrasSchema.kt` | §12.6 extras schema 校验器（fail-closed） |
| `WellKnownIntents.kt` | §12.6 预声明 schema 允许清单 |
| `AppFunctionIds.kt` | §12.5 命令 id 编解码 |
| `IntentPluginTest.kt` / `ExtrasSchemaTest.kt` / `AppFunctionIdsTest.kt` | 46 个测试（P1-P25、ES1-ES16、AF1-AF5） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
- `kotlinx.coroutines.core`
