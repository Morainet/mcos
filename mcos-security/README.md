# MCOS Security

安全内核库——权限授权（AuthStamp 铸造与签名）、限流、出网策略、企业策略、插件信任门、
崩溃隔离、审计流水与 JSON Schema 校验。每个控制点都是"接口 + 生产实现 + 具名空对象"，
**关闭安全永远是显式、可 grep 的选择，绝不 fail-open**。

## 模块定位

- 纯 Kotlin/JVM 17 库（无 Android 依赖），JVM 运行时与 Android 宿主共用。
- 在依赖图中只依赖 `mcos-sdk`（`AuthStamp` 定义在 SDK 的 `ExecutionContext` 中，
  本模块负责签名与校验）。
- 规格：`docs/zh/08-security.md`。

## 包结构

```
com.morainet.mcos.security/
├── SecurityConfig.kt          # 7 个控制点总装 + defaults()/permissive() 空对象族
├── AuthStampSigner.kt         # 授权印章 HMAC 签名/校验
├── PluginTrustGate.kt         # 加载期信任矩阵（BUILTIN/签名/debug-sideload）
├── TrustLevel.kt              # 信任等级枚举（运行时推导，插件不可自证）
├── ArtifactVerifier.kt        # Ed25519 / RSA-PSS-4096 工件验签（7 步 fail-closed）
├── PublisherKey.kt            # 发布者密钥与状态（ACTIVE/REVOKED）
├── VerificationCache.kt       # 验签缓存（离线复用已验证插件）
├── RateLimiter.kt             # 令牌桶限流（每分钟调用 + 每小时 destructive）
├── NetworkEgressPolicy.kt     # 出网四步流水线（kill switch→HTTPS→scope glob→企业）
├── DomainGlob.kt              # host 提取与 scope-glob 匹配的唯一事实源（含 IDN 归一化）
├── CrashQuarantine.kt         # 滑动窗口崩溃隔离
├── EnterprisePolicy.kt        # fail-closed 企业策略 + FAIL_CLOSED 兜底
├── EnterprisePolicySource.kt  # 策略源（fixed/None/文件热重载）
├── SecretResolver.kt          # {{secret.key}} 模板解析（秘密永不落盘/落审计）
├── SnapshotFile.kt            # 原子重写 + HMAC 防篡改快照原语
├── permission/                # 权限内核（授权决策 + stamp 铸造 + GrantStore 持久化）
├── audit/                     # 审计流水（InMemory + FileAuditLog JSONL 持久化）
└── validate/                  # JSON Schema Draft 2020-12 子集校验
```

## 核心控制点

| 控制点 | 接口 | 生产实现 | 关闭安全的空对象 |
|--------|------|----------|------------------|
| 权限内核 | `PermissionKernel` | `DefaultPermissionKernel` | `PermissivePermissionKernel` |
| 限流 | `RateLimiter` | `TokenBucketRateLimiter`（默认 60/min、5 destructive/h） | `UnlimitedRateLimiter` |
| 出网 | `NetworkEgressPolicy` | `ScopeBasedEgressPolicy` | `AllowAllEgressPolicy` / `DenyAllEgressPolicy` |
| stamp 签名 | `AuthStampSigner` | `HmacAuthStampSigner` | `TrustingAuthStampSigner` |
| 崩溃隔离 | `CrashQuarantine` | `SlidingWindowCrashQuarantine` | `NoopCrashQuarantine` |
| 审计 | `AuditLog` | `InMemoryAuditLog` / `FileAuditLog` | `NullAuditLog` |

`SecurityConfig.defaults()` 给出生产姿态；`SecurityConfig.permissive()` 把每个控制点换成
上述具名空对象。审计方式：`grep -rn "permissive()" src/` 与
`grep -rn "Permissive\|AllowAll\|Trusting\|Unlimited\|Noop\|DenyAll" src/`。

值得注意的细节：

- **AuthStamp 防提权**：`HmacAuthStampSigner` 对
  `(runId|commandId|pluginId|grantsUsed|issuedAt|expiresAt)` 做 HMAC-SHA256 常量时间比较，
  空签名的 stamp 一律视为不可信；生产应从设备 keystore 派生 key。
- **源感知授权**：`authorize(descriptor, policy, source)` 对后台源（EVENT/SCHEDULE）的
  network/destructive 强制降级为 `ConfirmationNeeded`（08 §4.0/§4.1）。
- **DomainGlob 双执行点一致**（08 §8.2）：命令参数树的 URL 检查（Executor Stage 6.5）与
  handler 内 NetService 调用的 stamp 域门共享同一 host 匹配语义，不会漂移；
  含 IDN/Punycode 归一化（Unicode 同形域防护）。
- **企业策略 fail-closed**：坏 JSON 抛异常，调用方必须回退 `EnterprisePolicy.FAIL_CLOSED`。
- **审计脱敏**：password/token/secret/apikey/credential/authorization/bearer/cookie 键与
  `x-mcos-secret` 标记对象在落盘前脱敏；export 可附 HMAC 签名行。
- **持久化防篡改**：`FileGrantStore`（授权表）、`FileAuditLog`（审计）、`SnapshotFile`
  （通用原语）均走 HMAC 验证，验不过视为不存在。

## 典型用法

```kotlin
// 授权 + 确认矩阵（摘自 PermissionKernelTest）
kernel.grant("example.sys", "android.permission.CAMERA")
val result = kernel.authorize(descriptor)   // read 级 → Authorized(stamp)

// stamp 签名与篡改拒绝（摘自 AuthStampSignerTest）
val signer = HmacAuthStampSigner()
val signed = signer.sign(AuthStamp("run-1", "cmd.test", "test.plugin",
    setOf("network.read"), 1000, 60000))
assertTrue(signer.verify(signed.copy(grantsUsed = setOf("network.write"))).not())

// 出网策略（摘自 NetworkEgressPolicyTest）
val policy = ScopeBasedEgressPolicy()
policy.decideEgress("https://api.example.com/path", stampWithScopes("network.api.example.com"),
                    globalKillSwitch = false)   // Allow
```

## 依赖

- `mcos-sdk`（api：公开签名引用 `CommandDescriptor`/`SideEffectClass`/`AuthStamp`）
- `kotlinx.coroutines.core`（implementation）
- `kotlinx.serialization.json`（api：`SchemaValidator` 公开 `JsonObject`）
