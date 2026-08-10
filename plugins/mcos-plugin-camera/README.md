# Camera Plugin

相机插件——拍照与扫码。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `camera.capture` | `quality` (string), `flash` (string), `facing` (string) | read | 拍照 |
| `camera.scan` | — | read | 扫码（QR/Barcode） |

## 文件

| 文件 | 说明 |
|------|------|
| `CameraPlugin.kt` | 插件入口 + `CaptureHandler` + `ScanHandler` |
| `CameraPluginTest.kt` | 14 个测试（M1-M14） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
