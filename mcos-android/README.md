# MCOS Android（演示壳）

基于 `mcos-android-sdk` 的 Compose Material3 演示 App——终端风外壳，把宿主 SDK 的全部
能力（DSL 执行、确认弹窗、Agent 循环、MCP 桥、marketplace 安装/Recipe 向导）以
"纯渲染 Composable + 纯 JVM 可测 ViewModel" 的架构演示出来，供集成方照抄接线方式。

## 模块定位

- `applicationId com.morainet.mcos.android`，包名 `com.morainet.mcos.android.demo`
  （保持 applicationId 不变以维持安装身份；宿主面全部由 SDK manifest merge 进来，
  本模块 manifest 刻意极简）。
- **可整体替换**：它只是参考 UI，宿主 SDK 不依赖它。

## 入口链

```
McosApplication（实现 McosHostApp：CompositionRoot.create + RuntimeBootstrap.ensureRehydrated）
  → MainActivity（唯一职责 setContent { MCOSApp(deps) }）
  → MCOSApp（TopAppBar + 卡片流 + 四个 AlertDialog）
```

## UI 结构

| 组件 | 文件 | 演示能力 |
|------|------|----------|
| `StatusBar` | McosShellCards.kt | 插件加载状态 + 命令面板开关 |
| `MarketplaceCard` | McosMarketplaceUi.kt | 索引搜索、安装进度流、卸载、Recipe 搜索 |
| `AiChatCard` | McosShellCards.kt | API key 管理（SecureStore）、provider 探活、NL→DSL 规划、Agent 模式开关 |
| `McpServerCard` | McosShellCards.kt | MCP 服务器增/删/开关/重连 |
| `DslInputCard` | McosShellCards.kt | DSL 输入 + 实时 preview（每键取消重发） |
| `OutputLog` | McosShellCards.kt | 事件流控制台（上限 1000 行） |
| 四个 AlertDialog | McosApp.kt | 运行确认（08 §5）/ Agent 计划审批（06 §11）/ 安装权限预览 / Recipe 向导 + 更新权限 diff |

## ViewModel（架构核心）

- `McosViewModel`：`attach(deps)` 随 Activity onCreate 重绑；`run()`（preview→execute→observe）、
  `chat()`（ChatOrchestrator + PromptInjectionDetector）、`agentTurn()/resumeAgentTurn()/
  cancelAgentTurn()`（多轮 Agent）、`respondConfirmation()`；MCP 块只把 `McpServerController`
  结果映射到 UI。
- `MarketplaceViewModel`：search/install/uninstall/searchRecipes/prepareRecipe/submitRecipe/
  confirmUpdate；`registryRevision` 单调计数驱动命令面板刷新。
- 测试全部纯 JVM（`McosViewModelAgentTest` 有 agentBridgeOverride 测试缝）。

## 集成方必抄的接线

```kotlin
// Activity 桥（摘自 McosApp.kt）
val launcher = rememberLauncherForActivityResult(deps.resultBridge.contract) { ... }
deps.resultBridge.attach(launcher)
val permLauncher = rememberLauncherForActivityResult(RequestPermission()) { ... }
deps.permissionBridge.onResult = permLauncher::launch

// MCP 桥（摘自 McosViewModel.kt DemoMcpBridge）
McpAdapter.discover(deps.hostServices.net, McpServerConfig(id, endpoint, secretKey),
    secretLookup = { key -> deps.hostServices.secureStore.get(key) })
```

## 依赖

- `mcos-android-sdk`（核心）+ 显式 `mcos-sdk`/`mcos-runtime`/`mcos-llm`/`mcos-marketplace`/
  `mcos-runtime-core`/`mcos-security` + `plugins:mcos-plugin-mcp`
- Compose BOM + Material3 + lifecycle-viewmodel-compose
