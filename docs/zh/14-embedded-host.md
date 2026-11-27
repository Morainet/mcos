# 独立宿主：导出应用记录

本示例面向给现有 Android 应用接入命令能力的开发者。宿主将自身业务能力注册为 `record.export`，用户通过预设任务完成计划预览、确认、实际文件生成、结果展示和审计。模型规划可在此链路上另行接入。

## 运行

```bash
sh gradlew :mcos-sample-embedded:testDebugUnitTest :mcos-sample-embedded:assembleDebug
adb install -r mcos-sample-embedded/build/outputs/apk/debug/mcos-sample-embedded-debug.apk
adb shell am start -n com.morainet.mcos.sample.embedded/.MainActivity
```

安装包名称为 MCOS Record Export，独立于完整演示壳。无需模型密钥、服务器地址、账号或媒体权限。示例移除了 Android SDK manifest 合并带来的权限和组件，仅写应用私有目录。

## 操作与结果

| 操作 | 预期结果 |
|---|---|
| Preview and export record → Export | 生成私有 txt 文件，显示文件名、记录正文、runId 与审计数量 |
| Preview and export record → Reject | 明确失败，不创建文件 |
| Try a missing record → Export | 显示 Record not found，不创建文件 |
| 确认弹窗 → Cancel task | 取消当前 run，避免遗留等待确认的任务 |

导出源是宿主内置的固定业务记录 `demo-001`；输出在 `filesDir/record-exports/`。每次生成新文件，避免覆盖先前产物。文件 URI 是本应用私有引用，不用于跨应用分享。审计落在 `filesDir/embedded/audit.jsonl`，由现有设备密钥机制加密并提供 HMAC 导出签名。

## 最小接入路径

1. 实现 `McosPlugin`：声明命令 schema、`write` 副作用类别与处理器；参见示例 `RecordExportPlugin.kt`。
2. 在 Application 的进程级协程中调用 `CompositionRoot.createEmbedded(context, plugins)`。
3. 用 `runtime.preview(request)` 展示计划，再调用 `execute(request)`。
4. 订阅 `observe(runId)`，处理 `ConfirmationNeeded` 并调用 `respondConfirmation`；分别展示成功、失败、取消。
5. 使用 `ArtifactEmitted` 展示实际产物，在终态后等待审计 `flush()`。Activity 销毁时取消该页面发起的任务。

实现位置：

- [独立宿主源码](../../mcos-sample-embedded/src/main/kotlin/com/morainet/mcos/sample/embedded/)
- [基础装配](../../mcos-android-sdk/src/main/kotlin/com/morainet/mcos/android/EmbeddedRuntime.kt)
- [端到端 JVM 验证](../../mcos-sample-embedded/src/test/kotlin/com/morainet/mcos/sample/embedded/RecordExportIntegrationTest.kt)

## 能力边界与验证

基础装配只接收随宿主发布的可信插件；宿主提供它们的声明权限，执行副作用仍需确认。它不接收任意第三方代码，不初始化市场、规划器、定时唤醒或后台恢复。Android 系统权限仍由系统控制。

需要相机、系统选择器等 Activity 交互时，通过 `EmbeddedRuntime.create(hostServices, plugins, auditLog)` 提供已绑定 UI 桥的宿主服务；便捷入口 `createEmbedded` 的 UI 桥未绑定，不能将它当成完整演示壳的等价替代。

目前是**运行装配收敛**：Android SDK 的 Gradle 制品仍传递依赖 LLM、市场与内置插件，runtime 仍依赖 marketplace。包体裁剪和发布制品拆分仍待处理。本示例不宣称实现了这些构建解耦。

JVM 回归驱动真实 Runtime、同一基础装配和导出插件，校验审批前零写入、审批后文件内容和审计、拒绝、缺失记录及参数校验；Android 窗口与系统生命周期仍需按上表在设备上验证。

审批拒绝、参数校验失败和审批前取消通过运行事件反馈；当前内核对这些提前退出路径不保证持久审计记录。导出文件与审计不构成事务，执行后取消或审计失败不代表文件已回滚。

## 本轮验证记录（2026-11-27）

- 完整 `sh gradlew build` 通过；一致性门 78/78。
- 新增 5 条宿主端到端、3 条基础装配和 1 条同步审批响应回归，均通过。
- TYH612M / Android 10 / API 29：实际安装并通过 UI 操作，审批导出生成正确文件；拒绝、缺失记录和审批前取消不新增文件。
- 真机私有审计文件存在，未包含明文命令/记录；包信息确认未请求 Android 系统权限。
- 真机验证限于上述固定记录场景，未验证任意 App 控制或第三方插件隔离。
