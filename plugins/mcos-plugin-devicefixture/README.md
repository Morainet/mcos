# mcos-plugin-devicefixture

> **注意：这是测试 fixture 插件，** **绝不** **发布、** **绝不** **进默认插件集。**
> 它的存在只是为了给 `mcos-android-sdk` 的 `BinderIsolationDeviceTest` 提供一个
> 真的、能跨进程执行的命令集（dex 经 d8 进入 androidTest assets）。任何生产主机
> 不应包含此插件。

## 它是什么

08-security.md §8 的真机端到端验证夹具（item 50）——把一个最小化的 `McosPlugin` 通过
build-tools `d8` 转 dex、随 `assembleDebugAndroidTest` 打进 androidTest 的 assets，
测试时再 `BinderIsolationDeviceTest` 自己组装一份签过名的 `.mcos`，经生产
`PluginInstaller` 装入，从而触发 `:mcos_plugin` split + Binder 全链。

两个 `read`-class 的命令刻意设计成不弹确认、不出网 —— 此 fixture 只验证 §8 的
**传输与隔离**维度，不跑任何策略层。

## 命令面（2 个）

| 命令 | 参数 | 副作用 | 说明 |
|------|------|--------|------|
| `mcos.plugin.devicefixture.echo` | `message` | read | 回显 message，外加 handler 所在进程 pid：测试据此断言 echo 不在主进程执行 |
| `mcos.plugin.devicefixture.park` | `seconds` | read | **先**经 `ctx.services.sandbox`（§8.3 命名空间门面）写 marker 文件，再 sleep `seconds` 秒；marker 写入是「kill-mid-run」测试的同步点 |

`park` 的 marker 命名固定为常量 `PARK_MARKER = "park-entered.txt"`，落点在
`filesDir/plugin-sandbox/<id>/` 下 —— 这是生产 §8.3 `NamespacedSandbox` 在
`:mcos_plugin` 进程里经反向 Binder 写到主进程 fs 的真实路径。

## 在测试里怎么用

不需要你手动集成 —— `:mcos-android-sdk` 自带的 `deviceFixtureDex` Gradle 任务在
每次 build 时把这个模块的 jar 用 d8 转 dex，命名为 `device-fixture.dex`，放在
`generated/deviceFixtureAssets/`，再被注入 `androidTest` 的 assets。
`BinderIsolationDeviceTest` 从 `context.assets.open("device-fixture.dex")` 读出来
直接打包为带签名的 `.mcos`。

如果只是 JVM 层验证（不需要设备），跑：

```bash
sh gradlew :plugins:mcos-plugin-devicefixture:test
```

要在真机上跑完整隔离验证（需要有挂载设备 + 该设备支持 Ed25519 或 RSA-PSS-4096
的 JCA 算法名 —— 见下「诚实边界」）：

```bash
sh gradlew :mcos-android-sdk:connectedDebugAndroidTest
```

## 架构约束

- **不发布**：`build.gradle.kts` 仅有 `kotlin-jvm` 插件，无任何 publication 块；
  BOM 也未收录。CI 在 `android-build` job 中至多走到 `assembleDebugAndroidTest`
  编译，不连真机。
- **`compileSdk` 无关**：fixture 只引用 `mcos-sdk` 的纯 API + `kotlinx-serialization`
  拼 JSON schema；d8 转 dex 时设 `--min-api 26` 以匹配 `mcos-android-sdk` 的
  `minSdk`。
- **真实 pid**：`selfPidOrNull()` 走 `/proc/self.stat` —— 在 Android 真机为
  `:mcos_plugin` 进程的真实 pid，桌面 JVM 上为 null（测试用 `JsonNull` 如实表达）。
- **`mcos-plugin-devicefixture` 自身的 dex 不进生产**：`androidTest` 的 manifest
  单独 enable `usesCleartextTraffic` 仅此 APK 与回环 HTTP 需要；该 manifest 仅
  合并到 androidTest APK，不进任何宿主 App 的合并 manifest。

## 测试

- **JVM（DF1-DF4，纯 JVM、不需设备）**：`DeviceIsolatedPluginTest` —— manifest 声明、
  echo round-trip（pid 在桌面 JVM 上为 `JsonNull`，诚实表达）、park marker 顺序、
  无 sandbox 能力时 park 仍 `Ok`（优雅降级）。
- **真机（BD1-BD6，仅在挂载真机时跑）**：`BinderIsolationDeviceTest`（在
  `mcos-android-sdk` 的 `androidTest`）—— 生产 `CompositionRoot` + 真
  `:mcos_plugin` split + 真 `PluginInstaller`，五条独立场景钉死 §8.1-§8.3 链。

## 诚实边界（也写在 `docs/en/11-implementation-status.md` 的 item 50）

1. **Same-UID only**：fixture 进程就是宿主 app 自己的 `:mcos_plugin` split，
   所以「foreign-uid 拒绝」只能由 JVM `BinderIdentityPolicyTest` 这个 oracle 罩着，
   需要第二个 APK 才能在真机上具体跑。
2. **Fixture 类在 instrumentation classloader 上可见**：androidTest 的事实。套件
   证明的是**执行**、sandbox 写入、崩溃隔离发生在独立插件进程 —— 主进程 dex-exclusivity
   由 manifest-only 注册路径强制（item 45），不在此处重证。
3. **签名算法的 JCA 提供者依赖**：测试运行时探测 JCA（首选 Ed25519，回退
   RSA-PSS-4096），都不支持时直接打印当前设备的 Signature 服务清单并 fail-fast；
   这也是为什么 `ArtifactVerifier.rsaPssSignature()` 顺带加上了对
   `SHA256withRSA/PSS` 别名的支持（本切片同时落地的真机兼容补丁）。
4. **单设备**：只在一台 Android 10 / API 29 真机上验证过，不是 emulator 矩阵；
   CI emulator 没有覆盖。
