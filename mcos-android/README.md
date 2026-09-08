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
  → shell/McosApp.kt MCOSApp（NavigationBar 5 tab + 页面路由 + 四个 AlertDialog）
```

## 源码结构（子包，包名统一 `com.morainet.mcos.android.demo`）

```
demo/
├── MainActivity.kt · McosApplication.kt · McosTheme.kt   # 入口与共享主题（留根）
├── shell/       McosApp.kt（导航 + 对话框）· McosViewModel.kt（架构核心）
├── chat/        McosChatPage.kt
├── skills/      SkillsPage（McosSkillsPage.kt）· SkillStore.kt（导入/持久化）
├── mcp/         McpPage.kt（server 管理 + JSON 导入 + 逐工具勾选）
├── tools/       McosShellCards.kt（StatusBar / DslInputCard / OutputLog 等）
├── settings/    McosSettingsPage.kt · McosVendors.kt
└── marketplace/ MarketplaceViewModel.kt · McosMarketplaceUi.kt
```

> 子包统一保留根包名 `…android.demo`，`PackageBoundariesTest` 按最长前缀匹配自动归属
> 本模块,无需改健身测试。

## UI 结构（NavigationBar 5 tab）

| Tab | 页面文件 | 演示能力 |
|-----|----------|----------|
| **Chat** | chat/McosChatPage.kt | API key 管理（SecureStore）、provider 探活、NL→DSL 规划、Agent 模式;对话 + 事件日志 |
| **Skills** | skills/McosSkillsPage.kt | Claude 风格 skill 包导入(SKILL.md / JSON 粘贴)、启用开关、删除、指令预览 —— 注入 planner 系统提示 |
| **MCP** | mcp/McpPage.kt | server 增/删/开关/重连、**粘贴 `mcp.json` 批量导入**、展开后**逐个工具勾选** |
| **Tools** | tools/McosShellCards.kt · marketplace/McosMarketplaceUi.kt | 插件状态 + 命令面板、marketplace 搜索/安装/卸载/Recipe、DSL 输入 + 实时 preview、事件流控制台 |
| **Settings** | settings/McosSettingsPage.kt | LLM 厂商切换与凭据 |
| 四个 AlertDialog | shell/McosApp.kt | 运行确认（08 §5）/ Agent 计划审批（06 §11）/ 安装权限预览 / Recipe 向导 + 更新权限 diff |

## ViewModel（架构核心）

- `McosViewModel`：`attach(deps)` 随 Activity onCreate 重绑；`run()`（preview→execute→observe）、
  `chat()`（ChatOrchestrator + PromptInjectionDetector）、`agentTurn()/resumeAgentTurn()/
  cancelAgentTurn()`（多轮 Agent）、`respondConfirmation()`；MCP 块只把 `McpServerController`
  结果映射到 UI(含 `importMcpJson()`、逐工具 `setMcpToolEnabled()`)。`chat()/agentTurn()`
  构建 planner 时懒读 `SkillStore.enabled()` 传入 `skills=`,skill 列表变更后经版本号使
  agent bridge 重建。
- `SkillStore`（skills/）：SecureStore key `skills` 存 JSON 列表(含 enabled);
  `SkillParser` 识别 SKILL.md(YAML frontmatter + 正文)或 JSON,不引额外依赖。
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
