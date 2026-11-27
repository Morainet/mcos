# MCOS Android Agent

`mcos-android` 是用户在手机上发起任务、审批和查看结果的 App，也是源码调试入口。用户描述目标后，模型从已注册命令中规划，Runtime 负责校验、权限、确认和执行。内置巡检预设可以直接运行。

## 构建与体验

需要 JDK 17 和 Android SDK Platform 35。最低运行版本为 API 26。

```bash
sh gradlew :mcos-android:assembleDebug
adb install -r mcos-android/build/outputs/apk/debug/mcos-android-debug.apk
adb shell am start -n com.morainet.mcos.android/.demo.MainActivity
```

1. App 打开即进入对话。从输入区提问或描述任务，点击发送按钮提交。首次提交时如果还未连接模型，App 会打开模型设置并保留草稿。追问会携带同一会话内近期的用户消息和已完成的文字回复。
2. 点击输入区的 **+** 打开 **Quick tasks**，或从左上角打开工作区菜单。选择 **Export the latest completed inspection** 可试用巡检示例，在确认框核对并批准后，对话会显示经过核验的文件名和记录内容。
3. 从菜单打开 **Task history** 可搜索、重命名和回看任务。历史保存在本机加密存储。有草稿或消息时，点击右上角的 **New task** 可开启新任务。等待审批或执行时需要先处理当前任务再切换，重启后中断的审批需要重新发起。
4. 菜单中的 **Model settings** 用于配置模型。**Capabilities** 管理技能、MCP 和设备权限。App 默认使用浅色，可在菜单中切换 **Dark mode**。选择会保存在本机。

使用支持图片输入的模型时，可从输入区的 **+** 打开 **Attach image**。每次可选一张图片，发送前可预览、替换或移除，留空问题会请求模型描述图片。附件保存在本机加密缓存，后续对话会沿用文字上下文。

同一 Debug APK 还包含 `RecordExportActivity`。[基础宿主回归指南](../docs/zh/14-embedded-host.md)说明如何直接打开这页，[ADB CLI](../tools/mcos-device/README.md)以这页为回归目标。

## 源码入口

| 位置 | 职责 |
|---|---|
| `McosApplication.kt`、`MainActivity.kt` | 进程级宿主装配、Compose 启动和系统闪屏接线 |
| `shell/McosApp.kt` | 页面导航、Activity 结果和权限桥、审批交互 |
| `shell/McosViewModel.kt` | 任务协调、运行状态、模型配置和能力管理 |
| `shell/TaskWorkspaceStore.kt`、`shell/AgentSuspensionStore.kt` | 加密任务历史、草稿和 Agent 暂停点 |
| `chat/` | 对话、工作区菜单与任务历史 |
| `records/InspectionTask.kt`、`records/RecordExportPlugin.kt` | 内置巡检流程和应用自有记录命令 |
| `device/AdbInspectionPlugin.kt` | Debug App 的固定 ADB 观察工具，按可调试标志注册 |
| `src/debug/.../records/RecordExportActivity.kt`、`RecordDebugHost.kt` | 独立基础 Runtime 的 Debug 回归入口 |
| `settings/`、`tools/`、`skills/`、`mcp/`、`marketplace/` | 模型设置、能力管理与开发者界面 |

源码包名是 `com.morainet.mcos.android.demo`，`applicationId` 是 `com.morainet.mcos.android`。可复用宿主适配见 [Android SDK](../mcos-android-sdk/README.md)，当前模块边界及迁移计划见[架构文档](../docs/zh/13-architecture-convergence.md)。

## 验证

```bash
sh gradlew :mcos-android:testDebugUnitTest :mcos-android:assembleDebug
```

`McosViewModelAgentTest` 覆盖任务状态、审批、历史和内置巡检。`RecordExportIntegrationTest` 用 JVM 验证基础 Runtime 的审批和文件产物。手机 UI、ADB 连接和实际文件核验按对应设备指南验证。
