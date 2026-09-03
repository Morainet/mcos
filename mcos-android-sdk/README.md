# MCOS Android SDK

无 UI 的 Android 宿主运行时库——任何 App 依赖它即可获得完整的 `HostServices` 实现、
进程常驻调度（精确闹钟 + 开机重整备）、marketplace 安装链、MCP 服务器管理，以及
**opt-in 的 `:mcos_plugin` 进程隔离执行边界**。演示壳 `mcos-android` 只是它的第一个消费者。

## 模块定位

- `compileSdk 35 / minSdk 26`，包名 `com.morainet.mcos.android`（含 `host`、`host.isolation`）。
- **设计约束：无 UI 库**——不依赖 Compose/ViewModel，全部测试纯 JVM（无 Robolectric）。
- 依赖 `mcos-sdk`、`mcos-security`、`mcos-runtime-core`、`mcos-runtime`、`mcos-llm`、
  `mcos-marketplace` 与四个内置插件（hello/system/camera/files）。

## 包结构

```
com.morainet.mcos.android/
├── CompositionRoot.kt           # 组合根：AppDeps 装配（审计/信任锚/权限/签名器/隔离）
├── McosHostApp.kt               # Application 接口（receiver 反取 deps）
├── RuntimeBootstrap.kt          # 进程级一次性 rehydrate（恢复插件 + 重整备调度）
├── ScheduleAlarmReceiver.kt     # 精确闹钟接收 → driveScheduleTick()
├── BootReceiver.kt              # BOOT_COMPLETED 重整备（goAsync 拖住进程）
├── McosPackage.kt               # .mcos 清单读取器（readPluginManifest 全 schema，fail-closed）
├── DynamicPluginLoader.kt       # DexClassLoader 隔离加载
├── MarketplacePluginFactory.kt  # curated id → 本地类；其余走 dex
├── MarketplaceTrust.kt          # 吊销密钥刷新
├── TrustAnchors.kt              # 内置 Ed25519 信任锚（运营方真钥，私钥离线保管、从不入仓）
├── PluginPermissionBootstrap.kt # 内置插件权限预授
├── McpServerController.kt       # MCP 服务器列表/生命周期管理
├── TriggerMaintenance.kt        # 触发器卫生清扫
└── host/
    ├── AndroidHostServices.kt        # HostServices 全量 Android 实现（13 个 facade + 沙箱）
    ├── ActivityResultBridge.kt       # Compose launcher ↔ suspend 桥
    ├── RuntimePermissionBridge.kt    # 应用内运行时权限弹窗桥（headless 诚实返回 null）
    ├── AlarmManagerWakeScheduler.kt  # WakeScheduler 的 AlarmManager 实现
    ├── AndroidLlmHttpTransport.kt    # HttpURLConnection 版 LLM transport
    ├── AndroidMarketplaceHttpTransport.kt
    └── isolation/                    # 进程隔离 RPC 全家桶（08 §8.1-§8.3）
```

## 集成方式（三行起步）

```kotlin
class MyApplication : Application(), McosHostApp {
    override lateinit var deps: AppDeps
    override fun onCreate() {
        super.onCreate()
        deps = CompositionRoot.create(this)       // processIsolation = true 开启隔离
        RuntimeBootstrap.ensureRehydrated(deps)   // 恢复插件 + 重整备持久化调度
    }
}
```

宿主 App 经 manifest merge 免费获得：8 项 uses-permission、`ScheduleAlarmReceiver`、
`BootReceiver`、`IsolatedPluginProcessService`（`:mcos_plugin` 进程）、FileProvider。
Activity 结果/权限弹窗经 `deps.resultBridge` / `deps.permissionBridge` 与宿主 UI 桥接
（演示壳 `mcos-android` 有完整接线示例）。

## 进程隔离链路（opt-in，`processIsolation = true`）

```
Executor(主进程)
  → BinderIsolationHost            # 每插件 bind 一次 + linkToDeath 重绑
  → PipeIsolationChannel(BinderWirePipe)      # 帧协议 {"op","payload"} 单 Parcel 字符串
  → :mcos_plugin 进程 InvokeBinderEndpoint
  → IsolatedPluginRunner           # id 伪装防护 + stamp 三匹配 + 本地 deadline
  → handler + IsolatedHostServicesProxy
  → FacadeBinderEndpoint           # CODE_FACADE 反向调用
  → IsolatedFacadeServer           # UID 身份门 + §8.2 stamp 门 + <pluginId>/ 命名空间沙箱
  → 真实 HostServices
```

配套的 manifest-only 注册（item 45）：`processIsolation=true` 时主进程只凭 `.mcos` 内的
wire `plugin.json` 注册（`McosPackage.readPluginManifest`，未知 `sideEffectClass` 整包
拒绝安装），插件 dex 只在 `:mcos_plugin` 加载。隔离纯层（`host.isolation`）全部 JVM
可测，`IsolationBinder.kt` 薄壳是唯一设备验证层 — **item 50 已经在真机上端到端
验证**（见下）。

## 真机端到端验证（`androidTest`，可选）

> 在写好一段 JVM-side chain 之后，唯一能证明 Binder 内核本身 OK 的办法就是把
> 整个链路放在一台真机上跑 —— 这就是 `BinderIsolationDeviceTest` 的任务。

- **位置**：`mcos-android-sdk/src/androidTest/`
- **目标套件**：`com.morainet.mcos.android.host.isolation.BinderIsolationDeviceTest`
  —— 五个 case BD1-BD6，分别钉死：manifest-only 注册、`:mcos_plugin` 真进程、§8.3
  命名空间沙箱、运行中杀进程只影响本次、`linkToDeath` 透明重绑、损坏 staged
  artifact 时如实失败。
- **Fixture 插件**：`plugins:mcos-plugin-devicefixture` —— 一个最小 `McosPlugin`，
  仅发布 `echo` / `park` 两条 `read`-class 命令。dex 在 build 时由
  `deviceFixtureDex` Gradle 任务用 build-tools `d8` 现做（**不**提交二进制 dex），
  结果放进 `generated/deviceFixtureAssets/device-fixture.dex` 并被注入
  `androidTest` 的 assets。
- **本地运行**：CI 不带真机，所以 CI 仅编译（`assembleDebugAndroidTest`）。要
  真跑必须挂一台 Android ≥ API 29 且 JCA 注册了 Ed25519 或 RSA-PSS-4096
  算法名的设备（其他机型直接走 `Signature` 服务清单 fail-fast 帮助排错）：

```bash
adb devices                                # 确认设备挂上
sh gradlew :mcos-android-sdk:connectedDebugAndroidTest
```

- **诚实边界**（也写在 `docs/en/11-implementation-status.md` 的 item 50）：
  same-UID only（foreign-uid 拒绝仍靠 JVM `BinderIdentityPolicyTest` 罩着）、
  fixture 类在 instrumentation classloader 上可见但**执行 + sandbox + 崩溃隔离
  确实在独立插件进程**（主进程 dex-exclusivity 由 manifest-only 注册路径强制）、
  单设备验证非 emulator 矩阵。

## 平台细节（README 亮点）

- 相机拍照刻意不加 `FLAG_ACTIVITY_NEW_TASK`（NEW_TASK 会导致结果立即 RESULT_CANCELED）。
- `location()` 缺权限经 `RuntimePermissionBridge` 应用内弹窗；无 Activity（headless）诚实
  返回 PERMISSION_DENIED 并说明真实阻塞。
- `setBrightness()` 走 `ACTION_MANAGE_WRITE_SETTINGS` 深链特殊权限。
- WiFi SSID 无定位权限时诚实降级 null。
- `TrustAnchors` 携带运营方真实签名钥（私钥离线保管，从未入库）；指纹与公钥由
  `TrustAnchorsConsistencyTest` 钉死，发布守卫拒绝占位钥出仓。
