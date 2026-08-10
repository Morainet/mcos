# Files Plugin

文件与照片管理插件。

## 命令

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `file.list` | `uri`*, `mimeType` | read | 列出目录/URI 下的文件 |
| `file.search` | `query`*, `uri` | read | 按名称搜索文件 |
| `photo.search` | `date`, `location`, `album` | read | 按日期/位置/相册搜索照片 |
| `photo.compress` | `uri`*, `quality` (1-100) | write | 压缩照片 |

## 文件

| 文件 | 说明 |
|------|------|
| `FilesPlugin.kt` | 插件入口 + 4 个处理器 |
| `FilesPluginTest.kt` | 8 个测试（F1-F8） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
