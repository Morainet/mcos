# System Plugin

系统级命令插件——通知、分享、剪贴板、URL、Intent、振动、设备查询。

## 命令

### 基础命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `sys.notify` | `title`*, `text`* | write | 发送系统通知 |
| `sys.share` | `text`, `uri`, `title` | write | 通过系统分享面板分享内容 |
| `sys.clipboard` | `text` | read | 读写剪贴板 |
| `sys.openUrl` | `url`* | network | 在浏览器中打开 URL |
| `sys.intent.start` | `action`*, `dataUri`, `package` | write | 启动 Android Intent |
| `sys.vibrate` | `duration` (0-5000ms) | control | 设备振动 |

### 设备查询命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `sys.device.battery` | — | read | 电池电量/充电状态/温度 |
| `sys.device.wifi` | — | read | Wi-Fi SSID/信号强度/频率 |
| `sys.device.screen` | — | read | 屏幕分辨率/密度/方向 |
| `sys.device.volume` | — | read | 媒体/铃声/闹钟/通知音量 |
| `sys.device.location` | — | read | GPS 经纬度/精度/提供商 |
| `sys.device.brightness` | `level` (0-255) | control | 查询/设置屏幕亮度 |

## 文件

| 文件 | 说明 |
|------|------|
| `SystemPlugin.kt` | 插件入口 + 12 个处理器 |
| `SystemPluginTest.kt` | 38 个测试（S1-S38） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
