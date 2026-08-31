# Files Plugin

文件与照片管理插件——8 个命令，其中 4 个沙箱命令经插件命名空间沙箱
（`ctx.services.sandbox`，即 `<pluginId>/` 前缀视图）执行。

## 命令（8 个）

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `file.list` | `path`*, `limit`（默认 100） | read | MediaStore/URI 列表 |
| `file.search` | `pattern`*（`?`/`*` glob，双指针实现防 ReDoS） | read | 按名称搜索文件 |
| `photo.search` | `date`（today/yesterday/this_week/this_month）或 ISO `after`/`before`，`limit`≤200 | read | 日期解析为 epoch ms 推给宿主端筛选 |
| `photo.compress` | `uris[]`（空=最新一张）、`quality`（默认 80）、`maxWidth`/`maxHeight` | write | 宿主 media.compress，产出 image artifacts |
| `file.write` | `path`*, `text`*, `append` | write | **沙箱**写入，1 MiB 上限（`files.too_large`） |
| `file.read` | `path`* | read | **沙箱**读取；stat 先查大小防 OOM（`files.not_found`/`files.too_large`） |
| `file.stat` | `path`* | read | **沙箱** stat：exists/isDir/size |
| `file.delete` | `path`* | write | **沙箱**删除文件或空目录 |

namespaces：`file`、`photo`。插件级 permissions：READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE。

> 沙箱命令刻意经 `ctx.services.sandbox`（执行期注入的命名空间视图）而非 onLoad 捕获的
> 宿主全局面——见 `FilesPlugin.kt` file.write handler 注释。

## 文件

| 文件 | 说明 |
|------|------|
| `FilesPlugin.kt` | 插件入口 + 8 个处理器 |
| `FilesPluginTest.kt` | 25 个测试（F1-F25） |

## 依赖

- `mcos-sdk`
- `kotlinx.serialization.json`
