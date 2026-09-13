# System Plugin

系统级命令插件——通知、分享、剪贴板、URL、Intent、振动、事件发布、设备查询与控制。

## 命令（13 个）

### 基础命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `sys.notify` | `title`*, `text`* | write | 发送系统通知 |
| `sys.share` | `text` / `uri` 至少其一 | write | 通过系统分享面板分享内容 |
| `sys.clipboard` | `text`（带=写，省略=读） | read | 读写剪贴板；读取结果带 `"untrusted": true` 标记（注入防御） |
| `sys.openUrl` | `url`* | network | 在浏览器中打开 URL |
| `sys.intent.start` | `action`*, `dataUri`, `package`, `categories`, `extras`, `extrasSchema` | write | 启动 Android Intent；`extras` 必须由 `extrasSchema` 声明（02 §12.6）。**别名 `intent.start`** |
| `sys.vibrate` | `duration`（0-5000ms，默认 500） | control | 设备振动 |
| `sys.event.emit` | `type`*（`payload` 可选对象） | write | 向系统事件总线发布事件（触发器源） |

### 设备查询与控制命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `sys.device.battery` | — | read | 电池电量/充电状态/温度 |
| `sys.device.wifi` | — | read | Wi-Fi SSID/信号强度/频率（无定位权限时诚实降级 null） |
| `sys.device.screen` | — | read | 屏幕分辨率/密度/方向 |
| `sys.device.volume` | — | read | 媒体/铃声/闹钟/通知音量 |
| `sys.device.location` | — | read | GPS 经纬度/精度（无定位返回 `status:"no_fix"` 而非报错） |
| `sys.device.brightness` | `level`（0-255，省略=查询） | control | 查询/设置屏幕亮度（设置走 WRITE_SETTINGS 深链） |

插件级 permissions：VIBRATE / POST_NOTIFICATIONS / ACCESS_FINE_LOCATION / WRITE_SETTINGS。

设计纪律：无能力一律 `UNAVAILABLE`，绝不假成功；剪贴板读取结果按不可信输入处理。

## `sys.intent.start` 与 02 §12.6

Intent 命令由**本插件**提供（02 §12.3：「以 `sys.intent.start` 表示」），§12.6 要求的预声明 extras 允许清单也发布在此插件（`WellKnownIntents`：VIEW/MAIN/DIAL/SENDTO/WEB_SEARCH/SEND，均 `additionalProperties: false`）。规则：

- 除允许清单内的 action 外，每次 invoke **必须**携带 `extrasSchema`；缺失 → `SCHEMA_VIOLATION`，`details.path = /args/extras`、`details.reason = extras_schema_required`（与该节写明的 Stage-5 拒绝同码/同 reason）。
- `ExtrasSchema` 只支持可安全校验的 JSON-Schema 子集；`oneOf`/`anyOf`/`$ref`/`pattern`/`format` 等关键字一律 **fail-closed**（`extras_schema_unsupported`），绝不半校验。
- 10-roadmap §5.4 的 `intent.start` 是**别名**，不是第二份实现——同一协议规则只有一处实现。
- **宿主能力**：宿主提供 `HostServices.intents` 时，整包请求（typed extras + categories）送达宿主；未提供时保留历史 `ui.startActivityForResult` 路径（仅 action/dataUri/package），若该 invoke 需要 extras 或 categories 则如实报 `UNAVAILABLE`——绝不静默丢弃，因为静默丢弃正是 §12.6 要消灭的危险。
- `§12.6` 的拒绝发生在处理器内而非 Executor 的 Stage-5：治理 schema 逐次供给，静态 `inputSchema` 表达不了它（错误码/path/reason 逐字对齐规范）。

## 文件

| 文件 | 说明 |
|------|------|
| `SystemPlugin.kt` | 插件入口 + 13 个处理器 |
| `ExtrasSchema.kt` | §12.6 extras schema 校验器（fail-closed） |
| `WellKnownIntents.kt` | §12.6 预声明 schema 允许清单 |
| `SystemPluginTest.kt` | 81 个测试（S1-S63；S50-S63 覆盖 §12.6） |
| `ExtrasSchemaTest.kt` | 16 个测试（ES1-ES16；含 fail-closed 形状） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
