# Hello Plugin

参考示例插件——展示如何用 MCOS SDK 编写插件。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `hello.world` | `name` | read | 返回问候语 |

## 文件

| 文件 | 说明 |
|------|------|
| `HelloPlugin.kt` | 插件入口 + Handler |
| `HelloPluginTest.kt` | 5 个测试（H1-H5） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
