# 当前实现与验证范围

[English](../en/11-implementation-status.md) · [当前架构](./13-architecture-convergence.md)

本页记录当前源码中的入口与可重复验证方式。构建、单测、模拟器、真机和已发布制品分别记录证据，发布历史见 [CHANGELOG](../../CHANGELOG.md)。

| 场景 | 当前实现 | 核对入口 |
|---|---|---|
| 手机用户在 App 发起任务 | `mcos-android` 的 Agent 首页提供模型配置、任务运行、审批、进度、结果和加密任务历史。预设巡检任务通过 Runtime 查询、导出并核验文件。 | [App 指南](../../mcos-android/README.md)，`McosViewModelAgentTest` |
| Android 应用嵌入自有能力 | `CompositionRoot.createEmbedded` 装配基础 Runtime，Debug APK 的 `RecordExportActivity` 演示预览、确认、实际文件和审计。 | [基础宿主](./14-embedded-host.md)，`RecordExportIntegrationTest` |
| 开发者 ADB 与模型联调 | 独立 CLI 驱动 Debug 页，按 runId 核验导出。ChatAnywhere 可用于有限的自然语言导出。App 内提供 `adb.inspect_mcos` 调试观察工具。 | [设备调试](../../tools/mcos-device/README.md)，Python 单测与设备验证 |
| 核心协议与执行 | DSL / Typed IR、注册、校验、确认、调度、工作流、事件、审计与记忆已接入对应模块。 | [`docs/fixtures`](../fixtures/)，`mcos-runtime-core`、`mcos-security`、`mcos-runtime` 测试和 `mcos-conformance` |
| 模型、插件和服务 | LLM 规划和会话、MCP 入站工具、市场安装、索引服务和同步服务各有实现。默认宿主与独立扩展的关系见架构依赖表。 | [当前架构](./13-architecture-convergence.md)，[索引服务](./12-index-server.md) |
| 插件进程隔离 | 完整 Android 宿主可选启用 `:mcos_plugin`。SDK 提供 Binder 真机回归套件，覆盖范围见 SDK 文档。 | [Android SDK](../../mcos-android-sdk/README.md) 的真机验证章节 |

## 核对命令

```bash
sh gradlew projects
sh gradlew build
sh gradlew :mcos-conformance:conformance
sh gradlew :mcos-android-sdk:testDebugUnitTest :mcos-android:testDebugUnitTest :mcos-android:assembleDebug
python3 -m unittest discover -s tools/mcos-device -p 'test_*.py'
```

需要确认手机系统交互时，按对应指南运行设备用例，记录设备、APK 构建来源、审批选择、运行 ID 和实际结果。设备能力可用性、CLI 线路连通和服务部署应分别核对。
