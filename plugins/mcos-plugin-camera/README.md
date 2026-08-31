# Camera Plugin

相机插件——拍照与扫码。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `camera.capture` | `facing`（rear\|front）、`flash`（auto\|on\|off）、`quality`（1-100，默认 90）——均可选 | write | 经 `ui.startActivityForResult(ACTION_IMAGE_CAPTURE)` 拍照，返回 image artifact；用户取消 → `CANCELLED`；timeoutMs 30000 |
| `camera.scan` | `format`（auto\|qr\|barcode\|ean13\|ean8\|code128\|datamatrix，可选） | read | 扫码；当前主进程实现返回 null（P2：ML Kit 接入） |

插件级 permission：CAMERA。

> 平台注记：Android 宿主拍照刻意不加 `FLAG_ACTIVITY_NEW_TASK`（会导致结果立即
> RESULT_CANCELED，见 `AndroidHostServices` 注释）。

## 文件

| 文件 | 说明 |
|------|------|
| `CameraPlugin.kt` | 插件入口 + `CaptureHandler` + `ScanHandler` |
| `CameraPluginTest.kt` | 14 个测试（M1-M14） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
