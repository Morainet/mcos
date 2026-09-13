# Intent Plugin

通往其他 App 表面的桥——深链与 App Functions（02 §12）。

## 命令（2 个）

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `deeplink.open` | `uri`（必填，须带 scheme）、`package?` | write | 以 `ACTION_VIEW` 打开深链；payload 走 URI，故 §12.6 的 extras 危险面不适用 |
| `appfn.invoke` | `package`（必填）、`function`（必填，单段）、`args?` | write | 调用其他 App 包发布的 App Function（02 §12.2/§12.5） |

```text
deeplink.open(uri="myapp://profile/42")
appfn.invoke(package="com.example.notes", function="createNote", args={title: "Hi"})
```

## 为什么这里没有 `intent.start`

02 §12.3 把 Intent 表示为 **`sys.intent.start`**，§12.6 又要求预声明 extras 允许清单「在 `sys` 插件中发布」——因此 Intent 命令本身归 `mcos.plugin.system`（`SystemPlugin`，含 `ExtrasSchema` 与 `WellKnownIntents`）。10-roadmap §5.4 对该能力的命名 `intent.start` 作为**别名**解析到那里，本插件不再携带第二份 §12.6 实现（同一协议规则只有一处实现）。

```text
sys.intent.start(action="android.intent.action.SEND", extras={...}, extrasSchema={...})
intent.start(...)        # 别名，解析到同一个处理器
```

## 规范对齐

- **02 §12.5（App Function id）**：`AppFunctionIds` 实现 `sys.appfn.<encodedPackage>.<function>`。**诚实边界**：§12.5 称反向查找「无歧义」，但只对不含 `_` 的包名成立——`com.my_app` 与 `com.my.app` 编码同为 `com_my_app`。歧义在**编码**侧，故 `encode` 对含 `_` 的包名 fail-closed（这类包用 `appfn.invoke(package, function, args)` 参数形式寻址），`decode` 按规范算法还原。
- **无假成功**：宿主未提供 `HostServices.intents` / `HostServices.appFunctions` 时命令报 `UNAVAILABLE`；Intent 无接收方时返回 `status="not_handled"`（诚实结果，不是错误）。

## 诚实边界

- `appfn.invoke` 无法得知目标函数的实际影响，声明 `write` 作为下限（运行时仍要求 write 授权并展示 DSL 预览）；目标函数为 destructive 的发布者应以更强副作用的包装描述符发布。
- 命名空间张力：§12.2/§12.5 把生成的 App Function id 定为 `sys.appfn.*`，而 `sys.*` 是 `mcos.plugin.system` 拥有的保留命名空间（04 §9/§14）、第三方不得注册；本插件把 §12.5 编码落为**工具函数**（用于审计关联），不注册任何 `sys.*` 描述符，因此不触碰该规则。
- 本次为纯 JVM 交付：Android 侧 `AndroidHostServices` 的 `intents`/`appFunctions` 实现与隔离进程 wire op 为后续工作；在此之前 Android 与隔离插件上如实 `UNAVAILABLE`，绝不假成功。

## 文件

| 文件 | 说明 |
|------|------|
| `IntentPlugin.kt` | 插件入口 + 2 个处理器 |
| `AppFunctionIds.kt` | §12.5 命令 id 编解码 |
| `IntentPluginTest.kt` | 14 个测试（P1-P3、P15-P25；P4-P14 已随 `intent.start` 迁至 `SystemPluginTest` S50-S63） |
| `AppFunctionIdsTest.kt` | 5 个测试（AF1-AF5） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
- `kotlinx.coroutines.core`
