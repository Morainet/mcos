# Android Debug 基础宿主回归

[English](../en/14-embedded-host.md) · [当前架构](./13-architecture-convergence.md)

`RecordExportActivity` 随 `mcos-android` Debug APK 打包。它由 `RecordDebugHost` 装配基础 Runtime，用真实的应用记录命令验证预览、确认、文件和审计。手机用户的 Agent 首页由 `MainActivity` 启动，使用另一套全量宿主装配。

## 运行回归

下列命令基于当前仓库源码。`createEmbedded` 属于源码中的基础宿主入口，`v0.0.6` 标签使用全量 `CompositionRoot.create`。

```bash
sh gradlew :mcos-android:testDebugUnitTest :mcos-android:assembleDebug
adb install -r mcos-android/build/outputs/apk/debug/mcos-android-debug.apk
adb shell am start -n com.morainet.mcos.android/.demo.records.RecordExportActivity
```

| 操作 | 预期结果 |
|---|---|
| **Preview and export record** → **Export** | 导出 `demo-001`，展示运行 ID、文件名、记录正文和审计数量。 |
| **Find latest completed record** → **Preview and export located record** → **Export** | 查找并导出 `demo-002`。CLI 续验可按此记录 ID 校验。 |
| 确认框选择 **Reject** 或 **Cancel task** | 出现失败或取消终态，当前 run 在确认前未创建文件。 |
| **Try a missing record** → **Export** | 显示 `Completed record not found`，当前 run 未创建文件。 |

手动回归可以在离线状态下完成。导出生成新的应用私有 txt 文件，位置为 `filesDir/record-exports/`；基础宿主审计位于 `filesDir/embedded/audit.jsonl`。使用 [ADB 指南](../../tools/mcos-device/README.md) 按 runId 核验终态和文件内容。设备验证应记录 APK 来源、系统版本及审批路径。

## 接入自有能力

1. 实现 `McosPlugin`，声明命令 schema、副作用类别和处理器。参照 [`RecordExportPlugin.kt`](../../mcos-android/src/main/kotlin/com/morainet/mcos/android/demo/records/RecordExportPlugin.kt)。
2. 在 Application 的进程级协程调用 suspend `CompositionRoot.createEmbedded(context, plugins)`。需要 Activity 交互时，通过 `EmbeddedRuntime.create(hostServices, plugins, auditLog)` 注入宿主已接线的服务，或使用全量宿主。
3. 用 `runtime.preview(request)` 展示计划，用 `execute(request)` 启动运行。订阅 `observe(runId)`，将审批传给 `respondConfirmation`，并处理成功、失败与取消事件。
4. 根据 `ArtifactEmitted` 和终态展示实际产物。回归页在 Activity 销毁时取消本页的运行，在终态后刷新审计。

实现入口为 [Debug 回归页](../../mcos-android/src/debug/kotlin/com/morainet/mcos/android/demo/records/)、[EmbeddedRuntime](../../mcos-android-sdk/src/main/kotlin/com/morainet/mcos/android/EmbeddedRuntime.kt) 和 [宿主回归测试](../../mcos-android/src/testDebug/kotlin/com/morainet/mcos/android/demo/records/RecordExportIntegrationTest.kt)。

基础装配加载随宿主发布的可信插件。运行时确认、Android 系统权限和宿主授权各自生效。审批前的部分退出路径通过 run 事件报告，持久审计覆盖需按路径核对。文件写入和审计独立执行，写入后的取消或审计失败不保证文件回滚。
