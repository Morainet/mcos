# MCOS Android Agent

`mcos-android` 是用户在手机上发起任务、审批和查看结果的 App，也是源码调试入口。用户描述目标后，模型从已注册命令中规划，Runtime 负责校验、权限、确认和执行。内置巡检预设可以直接运行。

## 构建与体验

需要 JDK 17 和 Android SDK Platform 35。最低运行版本为 API 26。

```bash
sh gradlew :mcos-android:assembleDebug
adb install -r mcos-android/build/outputs/apk/debug/mcos-android-debug.apk
adb shell am start -n com.morainet.mcos.android/.demo.MainActivity
```

1. 在 Agent 首页点击 **Export the latest completed inspection**，在确认框核对并批准。任务结果包含经过核验的文件名和记录内容。
2. 要运行自己的自然语言任务，通过 **Connect model** 配置模型。
3. 从右上角的 **Task history** 搜索、重命名和回看任务。历史保存在本机加密存储。等待审批或执行时需要先处理当前任务再切换，重启后中断的审批需要重新发起。

同一 Debug APK 还包含 `RecordExportActivity`。[基础宿主回归指南](../docs/zh/14-embedded-host.md)说明如何直接打开这页，[ADB CLI](../tools/mcos-device/README.md)以这页为回归目标。

## 源码入口

| 位置 | 职责 |
|---|---|
| `McosApplication.kt`、`MainActivity.kt` | 进程级宿主装配、Compose 启动和系统闪屏接线 |
| `shell/McosApp.kt` | 页面导航、Activity 结果和权限桥、审批交互 |
| `shell/McosViewModel.kt` | 任务协调、运行状态、模型配置和能力管理 |
| `shell/TaskWorkspaceStore.kt`、`shell/AgentSuspensionStore.kt` | 加密任务历史、草稿和 Agent 暂停点 |
| `chat/McosChatPage.kt`、`chat/McosTaskLibrary.kt` | 任务工作台与历史 |
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
