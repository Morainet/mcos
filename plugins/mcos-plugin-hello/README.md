# Hello Plugin

参考示例插件——展示如何用 MCOS SDK 编写插件的最小样板。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `hello.world` | `name`（可选，默认 `"World"`） | read | 返回 `"Hello, $name!"` |

## 文件

| 文件 | 说明 |
|------|------|
| `HelloPlugin.kt` | 插件入口 + Handler |
| `HelloPluginTest.kt` | 6 个测试（H1-H6，含 manifest 注册发现） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
