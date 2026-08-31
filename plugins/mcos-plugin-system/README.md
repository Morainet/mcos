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
| `sys.intent.start` | `action`*, `dataUri`, `package` | write | 启动 Android Intent |
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

## 文件

| 文件 | 说明 |
|------|------|
| `SystemPlugin.kt` | 插件入口 + 13 个处理器 |
| `SystemPluginTest.kt` | 51 个测试（S1-S49 标注） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
