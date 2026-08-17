# MCOS SDK

SDK 接口层——定义插件与运行时之间的公共契约。

## 包结构

```
com.morainet.mcos.sdk/
```

## 核心接口

| 文件 | 类型 | 说明 |
|------|------|------|
| `McosPlugin.kt` | 接口 | 插件入口点、`CommandHandler`、`HostServices`（7 个子服务） |
| `CommandDescriptor.kt` | 数据类 | 命令元数据：ID、版本、JSON Schema、权限、超时 |
| `CommandResult.kt` | sealed | 命令执行结果：`Ok`（值 + 工件）或 `Err`（错误码 + 可重试） |
| `ExecutionContext.kt` | 数据类 | 处理器上下文：runId、参数、授权戳记、超时、进度发射器 |
| `PluginManifest.kt` | 数据类 | 插件清单：ID、命令条目、权限声明、命名空间 |
| `McosException.kt` | 异常 | 插件抛出结构化错误，Executor 直接映射为 `CommandResult.Err` |
| `SideEffectClass.kt` | 枚举 | 副作用等级：`read` / `write` / `destructive` / `network` / `control` |

## 依赖

- `kotlinx.coroutines.core`
- `kotlinx.serialization.json`
