# MCOS Marketplace

配方与插件商店客户端——`MarketplaceIndex` 提供带缓存/签名校验的商店 API，
`PluginInstaller` 执行"下载→验签→落盘→注册"的规范安装管线（支持 `.mcos` 全清单
manifest-only 注册），`RecipeInstaller` 负责配方依赖解析、占位符向导与签名门下的
工作流编译。

## 模块定位

- 纯 Kotlin/JVM 17 库；签名用 JDK 原生 JCA（Ed25519 / RSA-PSS-4096），零三方加密库。
- 被 `mcos-runtime` 门面 `api` re-export（宿主经门面使用），也被 `mcos-android-sdk`
  直接依赖。
- 规格：`docs/zh/09-marketplace.md`。

## 结构（单包 `com.morainet.mcos.marketplace`，main 14 文件）

| 分组 | 关键文件 | 说明 |
|------|----------|------|
| 线缆类型 | `MarketplaceTypes.kt`、`RecipeEnvelope.kt` | `@Serializable` 元数据/黑名单/举报/遥测/签名信封 |
| 商店客户端 | `MarketplaceIndex.kt`、`MarketplaceHttpTransport.kt` | `/v1/plugins`、`/v1/recipes`、`/v1/reports`、`/v1/telemetry/install`、`/v1/blocklist`、`/v1/keys/revoked`；24h 搜索缓存 / 1h 黑名单 stale-ok |
| 安装管线 | `PluginInstaller.kt`（610 行，核心）、`InstallState.kt`、`InstallRecordStore.kt`、`BlocklistVerifier.kt` | 状态机 NOT_INSTALLED→DOWNLOADING→VERIFYING→STAGING→LOADING→INSTALLED；重启 `rehydrateInstalled` 重跑完整验签 |
| 配方 / 治理 | `RecipeInstaller.kt`、`RecipeDependencyResolver.kt`、`RecipeSignatureVerifier.kt`、`PermissionDiff.kt`、`SearchRanking.kt`、`VersionRange.kt` | `pluginId@semverRange` 依赖解析；`{{placeholder.*}}` 编译；权限 diff 决定静默更新 vs 重新授权；本地复算安全加权排序 |

## 关键安全语义

- **manifest-only 安装接缝**（08 §8，item 45）：构造参数 `manifestDecoder`——有 decoder 时
  LOADING 步走 `PluginLoader.loadManifest`，主进程不实例化任何插件代码，分发经隔离宿主；
  decode 失败 fail-closed（`decode_failed`）并清理落盘。
- **占位 pinned 信任**：`InstallRecordStore` 持久化"已验证事实"（记录 + pinned 发布者
  密钥 + 签名信封，HMAC 防篡改）；重启恢复时重验签。
- **签名黑名单**：`fetchBlocklist()` 验签失败 → `BLOCKLIST_SIGNATURE_INVALID` 并保留旧表；
  `applyBlocklist` 强制禁用已装包。
- **更新治理**：新增 elevated/destructive 权限或 riskTier 升级 → `NeedsConsent(diff)`。
- **隐私**：遥测 opt-in，`anonymizedClientId` 为设备 ID 不可逆 SHA-256；
  推荐只上送命令 ID，不上送使用历史。

## 典型用法

```kotlin
// 安装管线（摘自 PluginInstallerTest）
val inst = PluginInstaller(transport, ArtifactVerifier(keyStore), keyStore,
    loader, registry, downloadDir, installRecordStore = recordStore,
    manifestDecoder = manifestDecoder)          // 可选：manifest-only
val result = inst.installPackage(meta) { bytes -> createPlugin(meta.packageId, bytes) }

// 配方向导：prepare（依赖 + Memory 建议值）→ submit（验签门 → 编译）
val plan = RecipeInstaller().prepare(recipe, installedVersion, marketplaceLookup, memoryLookup)
val outcome = RecipeInstaller(RecipeSignatureVerifier(marketplaceKey))
    .submit(recipe, plan, bindings = mapOf("target" to "MyOfficeNet"))
```

## 平台注记

JDK HTTP transport 在 Android 不可用；Android 侧注入
`AndroidMarketplaceHttpTransport`（在 mcos-android-sdk）。

## 依赖

- `mcos-sdk`（McosPlugin/PluginManifest）、`mcos-security`（ArtifactVerifier/PublisherKey/
  TrustLevel）、`mcos-runtime-core`（CommandRegistry/PluginLoader）——均 api
- `kotlinx.serialization.json`（api）；coroutines 仅 implementation
