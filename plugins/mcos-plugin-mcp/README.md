# MCP Plugin

MCP（Model Context Protocol）桥适配器——把任意 MCP 服务器的 `tools/list` 动态合成为
MCOS 插件命令，每个工具映射为 `mcp.<serverId>.<toolName>`。

## 模块定位

- **动态合成插件，无静态命令**：不能静态注册，由宿主（`McpServerController`）在 enable
  时经 `McpAdapter.discover()` 发现工具并 `runtime.loadPlugin(builtin=true)` 装载。
- 每个服务器一个插件实例（pluginId = `mcos.plugin.mcp.<serverId>`），一个
  `McpCircuitBreaker` 由该服务器全部 `mcp.<server>.*` handler 共享。
- 只依赖 `mcos-sdk`。

## 命令（运行时合成）

| 命令模式 | 副作用 | 说明 |
|----------|--------|------|
| `mcp.<serverId>.<toolName>` | 下限 network；`destructiveHint` 升级为 destructive | schema 经 `McpSchemaConverter` 转换，不可映射的参数**丢弃并记入 skipped**（fail-closed，绝不静默放宽） |

## 关键组件

| 文件 | 说明 |
|------|------|
| `McpAdapterPlugin.kt` | `McpServerConfig(id, endpoint, token?, secretKey?)` + `McpAdapter.discover()`：`tools/list` → 合成 `CommandDescriptor`（含 inputSchema/别名清洗） |
| `McpClient.kt` | 单 POST JSON-RPC 2.0，经宿主 `NetService` 走统一出网策略；连接级故障指数退避重试（服务器已应答则不重试） |
| `McpCircuitBreaker.kt` | 连续 3 次可重试失败开路 30s，半开探测 |
| `McpSchemaConverter.kt` | JSON Schema → MCOS 参数 schema，安全默认值 |

secret 的正路是 `secretKey`：token 只以 SecureStore key 名义存在，handler 携带
`{{secret.<key>}}` 模板，由 Executor Stage-4 逐调用解析——token 永不进插件记录。

## 典型用法

```kotlin
// 摘自 McpAdapterTest
val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
assertEquals(listOf("mcp.demo.echo"), discovery.plugin.manifest.commands.map { it.id })

// 宿主侧接线（演示壳 DemoMcpBridge）
McpAdapter.discover(deps.hostServices.net,
    McpServerConfig(id, endpoint, secretKey = key),
    secretLookup = { k -> deps.hostServices.secureStore.get(k) })
```

## 测试

61 个测试：`McpAdapterTest`(19) · `McpSchemaConverterTest`(28) · `McpEndToEndTest`(5) ·
`McpCircuitBreakerTest`(5) · `McpClientReconnectTest`(4)，含参考 MCP 服务器
（`ReferenceMcpServer.kt`）的真协议往返。

## 依赖

- `mcos-sdk`、`kotlinx.serialization.json`
