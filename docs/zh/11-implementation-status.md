# MCOS 实现状态

> **语言:** [English](../en/11-implementation-status.md) · 中文（当前）

> **Status:** Living document  
> **Last Updated:** 2026-08-24  
> **Audience:** 需要了解**什么是仅规范、什么仍待构建**的贡献者与评估者。

MCOS 已交付 **P1 MVP 与大部分 P2**：命令协议、运行时、插件 SDK 与外壳已用 Kotlin 实现，分布在 `mcos-sdk`、`mcos-runtime`、`mcos-android` 与四个插件中。下表将每个子系统标记为 **已实现 / 部分 / 未开始**，并引用落地代码的 commit；仍为纯规范的行就是剩余工作。

---

## 0. 黄金法则

> **文档即规范。实现向文档对齐——而不是反过来。**
>
> 当实现与文档不一致时，默认以文档为准（除非文档自身内部自相矛盾）。不要"修"文档去迁就实现。相反，在此处登记差距，并朝文档去实现。

---

## 1. 当前仓库状态

本仓库包含**文档与可工作的多模块实现**：

```text
mcos/
├── docs/                 # 12 RFCs (00–11) + fixtures + REPOSITORIES.md
├── doc/                  # Early Chinese brainstorm notes
├── mcos-sdk/             # 插件契约（McosPlugin、CommandHandler、…）+ Memory/ResolveResult 类型
├── mcos-security/        # Publisher 密钥、ArtifactVerifier、PluginTrustGate、SecretResolver、CrashQuarantine、AuditLog（InMemory + FileAuditLog 持久化、可选 HMAC 导出签名）、EnterprisePolicy
├── mcos-runtime-core/    # Parser → IR、Registry、Executor、Permission、Workflow、EventBus、Memory、PluginLoader、core.api 管线类型
├── mcos-runtime/         # McosRuntime 门面（api）
├── mcos-llm/             # Planner 桥（LlmPlanner、providers、grammar、探活）
├── mcos-marketplace/     # MarketplaceIndex、PluginInstaller、配方商店、SearchRanking
├── mcos-android/         # Jetpack Compose CLI / Chat 外壳 + Android host services + 市场 UI
├── mcos-server/          # 独立自托管同步端点（SyncBlobTransport REST 契约 + Bearer 认证，仅存不透明 blob）
├── plugins/              # hello、system、camera、files
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **源代码模块：** ✅ 12 个（sdk、security、runtime-core、runtime、llm、marketplace、android、server + 4 插件），见 §2
- **构建系统：** ✅ Gradle Kotlin DSL 多模块（JDK 17、Kotlin 2.0.21、AGP 8.7.3、minSdk 26）
- **Golden fixture：** ✅ 8 个用例（[`docs/fixtures/`](../fixtures/) 下 5 正向 + 3 负向）——由 `DslParserTest` 执行并通过

---

## 2. 目标模块拓扑

以下是实现将引入的模块，按依赖顺序排列。完整参考卡片见 [`REPOSITORIES.md`](./REPOSITORIES.md)。

**P1 首批模块**（6 个——定义于 [REPOSITORIES.md](./REPOSITORIES.md) §2）：

| Module | 角色 | 目标阶段 |
|--------|------|--------------|
| `mcos-sdk` | 插件契约（`McosPlugin`、`CommandHandler`、`CommandDescriptor`、…） | P1（叶子） |
| `mcos-runtime` | Parser → IR、Registry、Permission Kernel、Scheduler、Executor、Audit | P1 |
| `mcos-android` | Jetpack Compose 客户端外壳（CLI / Chat） | P1 |
| `plugins:mcos-plugin-hello` | 参考示例插件 | P1 |
| `plugins:mcos-plugin-system` | `sys.notify`、`sys.share`、`sys.clipboard`、`sys.openUrl`、`sys.intent.start`、`sys.vibrate`、`sys.device.*` 六条（battery/wifi/screen/volume/location/brightness，04 §17 单一真相源） | P1 |
| `plugins:mcos-plugin-camera` | `camera.capture`、`camera.scan` | P1 |

**计划模块**（后续阶段——定义于 [REPOSITORIES.md](./REPOSITORIES.md) §3）：

| Module | 角色 | 目标阶段 |
|--------|------|--------------|
| `mcos-plugin-files` | `file.*`、`photo.search`、`photo.compress` | P1 |
| `mcos-plugin-iot` | `home.*`、`iot.*`（Home Assistant / Tuya / Matter） | P2 |
| `mcos-plugin-mcp` | MCP 客户端适配器 → `mcp.*` | P2 spike / P3 production |

`mcos-server` 已不再是计划模块——它已作为 `mcos-server/` 落地（§3 Memory 行），覆盖 P3「同步」职责；市场索引**客户端**已随 `mcos-marketplace/` 落地（应用内搜索/安装已接入 `mcos-android`），索引服务端部署与远程策略下发仍为 P3 剩余项。

---

## 3. 子系统实现矩阵

状态图例：✅ 已实现 · 🟡 部分 · ⬜ 仅规范（未开始）。落地 commit 按行引用（2026-08-12，`main` 分支）。

| Subsystem | 规范文档 | 目标阶段 | 状态 |
|-----------|----------|--------------|------|
| **Parser**（DSL → IR） | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1（最先） | ✅ `parse/`（Lexer、Parser、DslParser、Canonicalizer）；fixture 全绿 |
| **IR 类型** | [02](./02-command-protocol.md) §7 | P1 | ✅ `ir/IrTypes`（ExecutionIr + Step、payload 信封） |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 | ✅ `registry/CommandRegistry`（register/resolve/unregister，id=name） |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 | ✅ `permission/PermissionKernel.decideConfirmation`（NORMAL/ELEVATED）+ 命令级硬权限门（`DefaultPermissionKernel.authorize`：manifest/命令 `permissions` 未授予即 Denied）；Android 确认对话框已接线（`respondConfirmation`），内置插件经 `PluginPermissionBootstrap` 在注册时授予声明权限（demo 简化——marketplace 路径是安装时 `permissionsPreview` 同意后 `install()` 授予）；**授权持久化 ✅**——`GrantStore`/`FileGrantStore`（构造回灌 + 逐变更写穿、快照 tmp+rename 原子重写、可选 HMAC 防篡改；坏文件/签名不符 fail-closed 重启为空表即拒绝；session 授权绝不落盘），Android 经设备种子派生密钥接线 `filesDir/permissions/grants.json` |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 | 🟡 `McosRuntime` 内进程内 FIFO 队列；尚无优先级通道 |
| **Executor** | [03](./03-runtime.md) §9 | P1 | ✅ `executor/Executor`（步骤、产物、确认、取消、限流） |
| **Audit 日志** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1（基础） | ✅ `audit/`——`InMemoryAuditLog`（单写者、`x-mcos-secret` 脱敏、30d/10k 逐出）+ `FileAuditLog`（JSONL 落盘、重启重放、畸形行容错、逐出原子重写 tmp+rename、导出可选 HMAC-SHA256 签名行）；`McosRuntime.Builder.withAuditLog`（SecurityConfig + WorkflowEngine 两路）接线；Android 演示外壳 `filesDir/audit/audit.jsonl` + SecureStore 种子派生签名密钥。**未做**：静态加密（SQLCipher）、`auditFailClosed` 传播 |
| **Planner 桥** | [06](./06-agent.md) | P1（单一 provider） | ✅ `llm/` LlmPlanner + OpenAiLlmProvider + ChatOrchestrator，通过可插拔 `LlmHttpTransport`（JDK `HttpClient` 默认 + Android `HttpURLConnection`）接入 Android 聊天外壳；API key 经 `AndroidSecureStore` 持久化；四种 PlanMode——NATIVE_TOOL_CALL / FREEFORM_JSON / CONSTRAINED / LATENCY_TIERED |
| **Planner（多 provider）** | [06](./06-agent.md) §17 V1 | P2 | ✅ `llm/LlmProviderRegistry`——能力模型（`Capability`：CHAT/PLAN/TOOL_CALL/EMBED）、健康探测（`probe()`）、`LlmPlanner` 按优先级排序的回退链（可重试错误 → 下一 provider；§18.1 端侧→云端回退）、**PlanMode `NATIVE_TOOL_CALL`**（`ToolCall`/`ToolDescriptor`/`TokenUsage`，按 provider 选择模式，OpenAI `tools` 协议）、**端侧→云端隐私闸门**（`ProviderTier`，"允许云端 planner" 显式开关，§13.2）、**探活策略**（`LlmProbePolicy` TTL 缓存 + 失败冷却 + 探测超时 + 并发探测，`ProviderHealth` 快照，`healthSnapshot()`/`probeAll()`，Android 健康状态行 UI） |
| **Agent 循环（多轮）** | [06](./06-agent.md) §11 | P2 | ✅ `llm/McosAgent` 实现流式 `AgentBridge`（`runTurn` Flow / `resume(sessionId, approved)` / `cancel`；`PlanReady`/`Probing`/`Clarify`/`Refuse`/`Done`/`Declined`）——经新端口 `RuntimeGateway.executeProbe` 的读前缀探查（内核侧 fail-closed：任一非 `read` 或不可解析步骤整体拒绝整批；审计来源 `AGENT_PROBE`），观察折叠进 replan `extraContext`，每回合 `AgentCaps`（maxProbeSteps 3 / maxReplanRounds 2 / maxWallClockMs 30s，跨重规划持续消耗 → `Refuse(QUOTA)`），§14.1 重规划漂移防护（新引入 destructive/network 命令 → 强制 Clarify），收敛检测，`AgentSessionStore` 每会话状态，共享 EventBus 上的 `agent.*` 生命周期事件；最终执行经 `Payload.IrJson`（审计 `CHAT`）；Android 外壳已接线（Agent 开关、探查进度 + CANCEL、允许/拒绝计划对话框） |
| **Network Egress 策略** | [08](./08-security.md) §12（`decideEgress`） | P1 | ✅ `security/NetworkEgressPolicy.decideEgress` |
| **Prompt Injection 检测** | [08](./08-security.md) §11 | P1（编译器侧） | ✅ `llm/PromptInjectionDetector` |
| **Rate Limiting** | [08](./08-security.md) §10 | P1（每插件/分钟） | ✅ `security/RateLimiter`（每插件/分钟） |
| **Secret 管理**（`{{secret}}` 模板） | [08](./08-security.md) §9 | P1 | ✅ `security/SecretResolver` + `Executor` NetService 装饰器（解析值绝不写回 args；未知 key 保持惰性模板；审计 `x-mcos-secret` 脱敏） |
| **Crash-loop 隔离** | [08](./08-security.md) §15.3 | P1 | ✅ `security/CrashQuarantine`（60 秒内 3 次崩溃 → 隔离 + 注销 + 审计 `plugin.quarantined`；成功调用重置窗口；仅显式重新启用可恢复） |
| **进程隔离** | [08](./08-security.md) §8 | P1（尽力而为）→ P3（第三方默认） | 🟡 仅进程内尽力而为的并发控制 |
| **Workflow 引擎** | [05](./05-workflow.md) | P2 | ✅ `workflow/WorkflowEngine` — sequential/parallel/if/loop/retry/try/confirm，命名 store + JSON 解码；已接入 `McosRuntime.runWorkflow`（`d533c05`） |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 | ✅ `events/EventBus` — 类型化信封、前缀 + where 过滤、订阅者隔离、丢最旧背压 + 审计（`22ba52b`） |
| **Memory** | [07](./07-memory.md) | P2 | ✅ `memory/MemoryStore` — TTL、标签、模糊 resolveRef + 置信度、CREATED/UPDATED/CONFLICT 写入语义、superseded 历史（`d549236`）+ ✅ `memory/EpisodicMemory` — run 摘要、时间衰减召回（§8.1）、50→5 自动摘要 + 90 天保留（§8.2）+ ✅ `memory/RunSummarizer` — §9.4 run 完成钩子：命令/工作流记录 `EpisodicRecord`（实体取自 `namespace.path` 参数，摘要取自 DSL 文本）+ ✅ **`memory/MemorySync` — §11 设备间同步：`VectorClock`（tick / 严格支配 `isAfter` / `isConcurrentWith` / 分量取最大 `merge`，§11.1）、per-entry `syncable` 标记（§11.0：仅 `syncable=true` 条目可离开设备）、`SyncEntry` 快照导出/导入 + LWW 表（本地/远端支配 → 静默；并发 → 呈现 `SyncConflict` → `KEEP_LOCAL`/`KEEP_REMOTE`/`KEEP_BOTH`）、`SyncPolicy`（§11.3：`enabled` = disableCloudMemorySync、`allowedCategories` = allowedSyncCategories）违规记 AuditLog** + ✅ **`memory/MemoryBlobCrypto` — §11.0 端到端加密 blob：AES-256-GCM + 随机 12 字节 IV（同明文两次加密密文不同）、`HkdfSha256`（RFC 5869 HKDF-SHA256，JDK 无内置实现）从设备本地账户密钥（`AccountKeyProvider`，§11.0：同账户多设备可派生同一密钥，Android 端 Keystore 包裹）派生用途特定子密钥（§10.1：高熵主密钥，无 PBKDF2/Argon2）、blob 版本绑定 GCM AAD——密文/IV/版本任何篡改 → `BlobIntegrityException`；wire 格式 `EncryptedBlob{version, iv, ciphertext}` Base64 JSON** + ✅ **`memory/MemorySyncClient` + `SyncBlobTransport`（§11.0 服务器仅存不透明 blob）：`push()` 导出 syncable 快照 → 加密 → 上传（返回 blobId）、`pull()` 下载 → 本地解密 → `importSnapshot`（LWW + 企业策略端到端生效）；`JdkSyncBlobTransport`（`java.net.http.HttpClient`，`PUT|GET|DELETE /blobs/{id}`，404 → 非重试 `SyncBlobException("NOT_FOUND")`，可选 `token` 注入 `Authorization: Bearer`；Android 无该模块，注入 `HttpURLConnection` 实现）** + ✅ **E2E：内嵌 JDK `HttpServer` 参考服务器 + 设备 A→B 全链路（E1-E9：往返、服务器只见密文、LWW、并发冲突、`local_only` 不出设备、`disableCloudMemorySync`、404、幂等）** + ✅ **独立 `mcos-server` 部署（`mcos-server/`，P3 同步）：零第三方依赖 JDK `HttpServer` 实现 `SyncBlobTransport` REST 契约（`PUT|GET|DELETE /blobs/{id}`，`/healthz` 无认证健康检查），强制 Bearer token（恒定时间比较、401 + `WWW-Authenticate` 挑战、启动无 token 即拒绝），blobId 白名单 `[A-Za-z0-9_-]{1,128}` 防路径穿越，磁盘持久化 + 原子写入（重启存活），16 MiB 上限 → 413；`main()` CLI（`--port`/`--data-dir`/`--token`/`MCOS_SERVER_TOKEN` 环境变量回退）；E2E 用真实设备侧 `JdkSyncBlobTransport` 对接活实例（S1-S13：往返、不透明字节不变、幂等删除、401/404/405/400、healthz、重启持久化、路径穿越加固）** |
| **企业策略** | [08](./08-security.md) §13 | P3 | ✅ `security/EnterprisePolicy`（§13.1 命令/网络 allow-deny 名单 + forceConfirm + disableAllPluginNetwork + auditFailClosed，§13.3 fail-closed 解析）+ `security/EnterprisePolicySource`（静态源 / 文件热加载源，mtime 轮询、解析失败→`FAIL_CLOSED` + 事件、读取失败→缓存回退）+ 已接入 `PermissionKernel.authorize`（§13.2 命令名单、§4.3 forceConfirm 升级）与 `NetworkEgressPolicy.decideEgress`（§12.0 步骤 4 网络名单、§13.2 企业 kill switch）+ `Executor`/`McosRuntime.Builder.withEnterprisePolicySource` 装配 |
| **Marketplace** | [09](./09-marketplace.md) | P3 | ✅ `security/TrustLevel`（§7.0 BUILTIN/MARKETPLACE_VERIFIED/SIDELOAD_DEBUG/UNTRUSTED）+ `security/ArtifactVerifier`（§6.2 六步管线：SHA-256 完整性 → 取公钥 → key 状态 → 签名验证（Ed25519 / RSA-PSS-4096）→ blocklist → 缓存）+ `security/VerificationCache`（§16.2，7 天 TTL）+ `security/PluginTrustGate`（§6.5/§7.1 决策矩阵 + `disableSideload` 落地）+ `CommandRegistry.register(plugin, trustLevel)`（UNTRUSTED 拒绝）+ ✅ **`marketplace/MarketplaceIndex` — §4 索引客户端（`search` 支持 `query`/`category`/`sort`（relevance/safety/popularity/newest）/`minRuntimeVersion`/分页，`byCommand`（§11.1 `/v1/plugins/by-command/{commandId}`，§9.2 推荐来源，404 → 空列表），`getPackage`/`fetchBlocklist`/`fetchRevokedKeys` + 发布者公钥注册表引导 §6.3，24h 包缓存 / 15m blocklist 缓存）+ **§8 配方商店客户端：`searchRecipes`（`query`/`category`/分页，24h 缓存）+ `getRecipe`（§11.1 `GET /v1/recipes` / `/v1/recipes/{recipeId}`，404 → null 供安装向导处理）+ **§8.3 安装向导：`RecipeInstaller.prepare`（经 `RecipeDependencyResolver` 解析依赖 + `fromMemory` 播种的 `PlaceholderPrompt` 列表）→ `submit`（必填校验 → 默认值/空值回退 → 递归 `{{placeholder.key}}` IR token 替换，未声明 token → `SCHEMA_VIOLATION`）→ `CompiledRecipe`（无残留 token，保留触发语）** + `RecipeEnvelope`/`RecipePlaceholder`/`RecipeTriggerPreview` wire 模型（§14.1 原始 `workflow` IR 以 `JsonObject` 保留）+ **§14.1 用户举报：`reportPlugin` → `POST /v1/reports`（`ReportReason` wire 枚举，可选 description / 经同意的 `anonymizedInfo`，返回追踪 `reportId`，429 → `RATE_LIMITED`）+ §11.3 opt-in 遥测：`recordInstallTelemetry` → `POST /v1/telemetry/install`（fire-and-forget 202，5xx 可重试；`MarketplaceHttpTransport.postJson`）** + §14.3 blocklist 签名验证：`BlocklistVerifier`（对去掉 `signature` 的规范载荷做 Ed25519 / RSA-PSS-4096，缺签/畸形/验签失败一律 fail-closed）——签名无效 → `BLOCKLIST_SIGNATURE_INVALID` 且保留先前已接受的 blocklist（刷新失败时 stale-ok 缓存回退）** + ✅ **`marketplace/SearchRanking` — §9.1 客户端复合排序：`computeSafetyWeight`（destructive 0.15 / elevated 0.05 / normal 0.01 罚分，0.3 下限——安全降权但绝不隐藏插件），`rank` = (textScore×0.5 + categoryBonus×0.2 + popularity×0.3) × safetyWeight，`commandsPreview` 命令 ID 精确命中得 1.0（精确命中排在摘要提及之上）+ §9.2 隐私保守 `recommendPlugins`（缺失命令 ID → 用户未安装的 provider，按安全分 + 0.1 同发布者熟悉度加成排序，top-N）** + `marketplace/MarketplaceHttpTransport`（`getJson` + 二进制 `getBytes` §7.1 下载）+ ✅ **`marketplace/PluginInstaller` — §7 端到端安装流程：`InstallState` 状态机（§7.0 DOWNLOADING→VERIFYING→STAGING→LOADING→INSTALLED + UPDATE_AVAILABLE/DISABLED/UNINSTALLING/FAILED + `InstallProgress` 事件），安装管线（下载 → SHA-256+签名验证 → 落盘 staging → `PluginLoader` 注册，失败清理），§7.2 更新含 `PermissionDiff`（`computePermissionDiff`：增/删/改，tier 升级 → `consentRequired` 静默-vs-征询决策），§7.3 卸载（注销 + 工件清理 + host 钩子），**§14.4 blocklist 强制禁用：`PluginInstaller.applyBlocklist`（命中条目 → 排空描述符 + `InstallState.DISABLED`，工件保留在盘，`ForceDisabled{packageId, version, reason}` 供用户通知 + `plugin.force_disabled` 审计，`SECURITY_VULNERABILITY` 修复版重装自动解除）+ `VersionRange` SemVer 范围匹配（`*`/精确/`>=`/`>`/`<=`/`<`/`=` 边界 / `^` caret（同 major，`^0.y` 同 minor，`^0.0.z` 同 patch，缺段归一化）/ `~` tilde（同 minor，`~x` 同 major）/ 空白分隔合取，畸形 spec 兜底）+ `Blocklist.isBlocklisted` + `asSecurityBlocklist()` 桥接验签器谓词 + `updateBlocklist` 下载前闸门（已知恶工件绝不下载）** + ✅ **`marketplace/RecipeDependencyResolver` — §7.4 `requiredPlugins` 解析：`pluginId@semverRange` 解析（裸 id → `*`），已安装版本检查 → 市场查找 → `Resolved` / `Unresolved(missing)`（商店可满足范围时带 `suggestedVersion`，否则 `not_in_marketplace`）；不可解析 spec/range → `SCHEMA_VIOLATION`**，经 `McosRuntime.Builder.withPluginInstaller` 装配** + ✅ **Android 应用内接线（`mcos-android`）**——市场卡片（搜索 → 安装确认对话框（`permissionsPreview` 按 riskTier 着色）→ 实时 `InstallProgress` → 卸载）：`CompositionRoot` 为 `PluginInstaller` 与 `PluginLoader`/`PluginTrustGate` 装配**同一个** `ArtifactVerifier`（修复 facade 默认 loader `verifier = null` → 签名工件被 `verifier_not_configured` 误拒）+ `AndroidMarketplaceHttpTransport`（`HttpURLConnection` 实现接口全部三方法——Android 无 `java.net.http`；超时 → `MARKETPLACE_TIMEOUT`）；`MarketplaceViewModel`（索引 baseUrl 持久化 SecureStore、按次搜索现构 `MarketplaceIndex`、`registryRevision` 递增驱动 DSL 命令面板刷新、fresh-chain 异步再水合、安装成功后 `permissionsPreview` 同意 → `PluginPermissionBootstrap.grantAll` 授予声明权限并入内核持久化）；`MarketplacePluginFactory` 双分派工厂（curated 4 个内置插件 id → 本地实现；其余 id 经 `DynamicPluginLoader` 动态加载）；**动态 `.mcos` 加载 ✅（回填）**——`McosPackage`（zip 解包：manifest.json 声明 id/version/entry + classes.dex + 可选资源；`readManifest` 从已验签字节解析）+ `DexPluginLoader`（`DexClassLoader` 加载声明的 entry 类并实例化为 `McosPlugin`，上下文 `codeCacheDir` 作 optimized 目录；Android 独有——JVM 无 Dex），`CompositionRoot` 以 `MarketplacePluginFactory(dynamicLoader = DexPluginLoader(activity))` 装配；动态工厂断言 manifest id 与请求 id 一致（签名工件不得冒名）；**信任锚 ✅（回填）**——`TrustAnchors`（§6.3 冷启动种子：结构有效的占位 Ed25519 公钥 + 指纹——真实 marketplace 运营方密钥发布前 fail-closed，验签不会误通过；`bundled()` 于组合期播种发布者密钥库，与安装钉住密钥、轮换刷新幂等叠加）；**更新流 UI ✅（回填）**——`MarketplaceViewModel.requestUpdate`（§7.2：拉新版本 → `computePermissionDiff` → 无权限变化静默装 / 有变化 `NeedsConsent` 呈现 `PermissionDiff`）+ `UpdateConsentDialog`（增/删/改权限逐项呈现，tier 升级标红，同意/拒绝两路）；**安装跨重启持久化 ✅**——`InstallRecordStore`（终态记录：版本/状态/信任级别/工件名 + **钉住发布者公钥与签名信封**，HMAC 防篡改，坏文件 fail-closed）+ `PluginInstaller.rehydrateInstalled` 重启再水合（逐条回种钉住公钥 → 对 staged 工件**重新全量验签** → 注册——篡改即丢记录零注册；DISABLED 保持卸载；工厂缺失保留记录待后续构建）；纯 JVM 测试（fake 索引传输 + 真实 Ed25519 签名/验签链 + 原始套接字 HTTP 夹具）；公共索引服务端部署仍为 P3（宿主侧）

> **已完成：** `DslParser`（最高杠杆的第一步）与其余 P1 流水线一同交付。P1 安全底线——`decideConfirmation`、`decideEgress`、prompt-injection 检查、rate limiting、crash 隔离（`CrashQuarantine`）与 `{{secret}}` 模板（`SecretResolver`）——均已实现（见上表各行）。
>
> **测试基线（2026-08-24）：** 全模块 968 个测试（含 Android debug/release 双变体共 1017 次执行）——parser fixture、executor、permission（含授权表持久化 G1-G6：跨实例往返、撤销持久化、auto-approve/always-confirm 往返 + destructive 不变量保持、session 授权不落盘、坏文件/HMAC 不符 fail-closed 空表启动）、audit（含 `x-mcos-secret` 脱敏 + `FileAuditLog` 持久化 8 个：跨实例重放、畸形行跳过、逐出原子重写、HMAC 导出验签、P0-C5 flush 守卫、落盘前脱敏、load 时 TTL 压实）、workflow（W1-W6）、event bus（8）、memory（M1-M33 + 情景 E1-E19（含 §8.3 命名实体合并/模糊引用 E15-E19）+ 摘要 S1-S11 + **同步 V1-V4 向量时钟语义：tick / 严格支配 `isAfter` / 并发 / 分量取最大 `merge` + S1-S15 同步流：仅 syncable 导出、新路径应用、本地/远端支配 LWW、并发→`SyncConflict` 呈现、幂等重复导入、`allowedSyncCategories` 过滤、`disableCloudMemorySync` 中止 + AuditLog 记录、KEEP_LOCAL/REMOTE/BOTH 解决、时钟 merge 单调性、快照载荷** + **B1-B3 HKDF-SHA256（RFC 5869 §A.1 官方 PRK/OKM 测试向量 + 长度边界）+ C1-C7 `MemoryBlobCrypto`（往返、同明文异密文、密文/IV 篡改 → `BlobIntegrityException`、版本门控、不同账户密钥不可互解、wire 不透明）** + **E1-E9 加密同步 E2E（A 推送 → HTTP → 参考服务器 → B 拉取解密：往返、服务器只见密文无明文、远端 LWW 覆盖、本地新写保留、并发 → `SyncConflict`、`local_only` 不出设备、`disableCloudMemorySync` + AuditLog、404 → `NOT_FOUND`、幂等重复拉取）** + **mcos-server S1-S13（§3 详述：REST 契约 + Bearer 认证互操作、不透明字节、幂等删除、401/404/405/400、healthz、重启持久化、路径穿越加固）**）、secret 解析器、crash 隔离、插件、多 provider（R1-R8 registry + F1-F6 回退链 + T1-T8 原生工具调用 + O1-O10 本地隐私闸门 + T-transport 7 + **C1-C16 CONSTRAINED：模式选择（TOOL_CALL > CONSTRAINED）、IR `invoke`/`sequence`/`clarify`/`refuse` 解析、畸形→`LLM_PARSE_ERROR`、可重试回退 + 不可重试终止、语法注入（GBNF / JSON Schema 选择）、`parseIrJson` 单元测试** + **G1-G12 GBNF 语法生成：root 枚举目录命令、args 约束（键名/类型/enum/const/嵌套）、step 规则、空目录、共享 JSON 规则、转义** + **P1-P10 GrammarLlmProvider：llama.cpp `grammar` / vLLM `guided_grammar`/`guided_json` 注入、格式不匹配→`CAPABILITY_EXCEEDED`、传输错误映射** + **U1-U13 话语分类（§13.1 路由启发式：EXACT_CLI/KNOWN_RECIPE/PRIVACY_SENSITIVE/COMPLEX/SIMPLE、优先级 EXACT_CLI > KNOWN_RECIPE > PRIVACY > COMPLEX > SIMPLE）** + **R1-R8 RecipeMatcher（精确/归一化/包含匹配、短触发语安全、首中者胜）** + **L1-L11 LATENCY_TIERED 分层路由：EXACT_CLI 解析器直通（零 LLM）、KNOW_RECIPE 本地配方（零 LLM）、SIMPLE 端侧优先（即使 cloud 为主）、COMPLEX 云端优先（opt-in 时）、PRIVACY 强制端侧、快路径失败回落 LLM 链、latencyMs/route 遥测、隐私闸门保留、默认模式向后兼容** + **Q1-Q8 探活策略（§17 V1）：健康结果 TTL 缓存、失败冷却期、探测超时→不健康、并发 vs 串行探测、`healthSnapshot` 快照、`probeAll` 强制刷新**）、Android、**NL→IR 评测套件 3（golden fixtures 良构 + 套件级 100% 结构准确率/0 误执行/0 误拒绝 + 逐用例结构匹配，§16）** + **企业策略（E1-E11 §13 解析/fail-closed/命令与网络名单 glob/forceConfirm + F1-F8b 文件热加载：首载、mtime 重载、解析失败→`FAIL_CLOSED` + SHA-256 事件、版本不支持 fail-closed、缺文件→缓存回退、listener、刷新节流/过期 + N14-N18b egress 企业名单集成 + P21-P26 permission 企业名单集成）** + **信任与签名验证（§7/§6：V1-V16 `ArtifactVerifier` 真实 Ed25519/RSA-PSS-4096 密钥对——通过、篡改→`hash_mismatch`、未知/吊销 key、算法不匹配、错误 key 签名、畸形 base64、blocklist、缓存命中/未命中/拒绝不提升/过期重验 + T1-T12 `PluginTrustGate` 决策矩阵——内置放行、有效签名→`MARKETPLACE_VERIFIED`、无效签名/吊销/blocklist 拒绝、无验证器拒绝、调试侧载放行、生产侧载拒绝、`disableSideload` 阻断侧载但不阻已验证插件、缓存备注 + R22-R24 注册信任级别记录/默认 BUILTIN/UNTRUSTED 拒绝）** + **市场索引与配方商店：T1-T18 索引客户端（search 参数/`sort`/`minRuntimeVersion`/缓存，`byCommand` 404 → 空列表，blocklist 签名验证 + stale-ok 回退）+ T19-T22 配方搜索（参数透传、独立查询缓存、完整信封解码含 workflow IR/占位符/`triggerPreview`，404 → null）+ T23-T26 用户举报与遥测（wire 载荷 + 追踪 id、429 → `RATE_LIMITED`、opt-in 安装事件、5xx 可重试）+ R1-R10 `SearchRanking` + V1-V7 `BlocklistVerifier` + D1-D12 `RecipeDependencyResolver`（已安装满足 / 裸 id `*` / 市场给出 `suggestedVersion` / `not_in_marketplace` / 市场最新版超范围 / 混合 / `SCHEMA_VIOLATION` / 空列表）+ R1-R4 `RecipeEnvelope` wire 模型 + caret/tilde `VersionRange` 语义（同 major/minor/patch 边界、缺段归一化、`isValid`）+ I1-I14 `RecipeInstaller` 向导（空计划、缺失依赖呈现、记忆播种提示、默认回退、嵌套对象/数组 token 替换、未声明 token → `SCHEMA_VIOLATION`、依赖门控 submit、prepare→submit 全流程，**签名门：有效签名安装、篡改信封 → `SignatureRejected("invalid_signature")`、无签名信封 → `SignatureRejected("missing_signature")` fail-closed**）+ V1-V10 `RecipeSignatureVerifier`（Ed25519 + RSA-PSS-4096 真实密钥对往返，缺失/篡改/未知 key/算法不匹配/吊销/畸形 base64 拒绝，canonical 载荷确定性 + 签名字段排除）** + **T9-T14 安装记录持久化与再水合（记录跨实例回灌、重启再水合全链真 Ed25519 验签 + 命令重注册、篡改制品 fail-closed 零注册且丢记录、卸载跨重启删 staged 工件、DISABLED 保持卸载态、坏/HMAC 不符记录文件 fail-closed 空启动）** + **Android 市场接线：A1-A10 `MarketplaceViewModel`（持久化 baseUrl 一次加载、fresh-chain 重置陈旧记录并异步再水合（再水合管线行为在 T9-T14 覆盖）、缺 baseUrl 报错、搜索结果/URL 持久化、索引错误上浮、真实 Ed25519 安装 → `MARKETPLACE_VERIFIED` + 命令可解析、篡改字节 fail-closed 且零注册、无本地工厂快速失败、卸载清记录 + 修订号递增、安装成功授予声明权限（camera 声明权限经内核全过 Stage-6 硬门——A10））+ T1-T7 `AndroidMarketplaceHttpTransport`（原始套接字 HTTP 夹具：getJson 状态/正文、404 透传、二进制工件往返、postJson 正文 + Content-Type、读超时 → `MARKETPLACE_TIMEOUT` 可重试、连接拒绝原样传播）+ P1-P3 内置插件权限 bootstrap（manifest/命令权限并集去重、按 manifest id 授予、真实 `camera.capture` 过 Stage-6 硬权限门回归）** + **宿主能力面（本切片）：SystemPlugin 设备/剪贴板/震动真接线（S13/S19/S20 改造为记录式 Fake 真调断言；新增 S14-1 剪贴板读 `untrusted:true` 标注、S14-2 空剪贴板 UNAVAILABLE、S19-1 无 haptics UNAVAILABLE、S39-S46 六设备查询 + 亮度设置真调往返与 no_fix/null 语义）+ Executor E31（可选能力经 `secretResolvingServices` 门面委派防回归——匿名包装器漏 override 即静默置 null）** + **Android 动态加载与更新流（回填另一会话，debug 46 中含 20 个新测：`DynamicPluginLoadingTest` 9——McosPackage 解包/DexClassLoader 入口类加载/manifest id 冒名断言/坏包拒绝；`MarketplaceRecipeUpdateTest` 7——requestUpdate 静默更新 vs `NeedsConsent` + `PermissionDiff` 呈现、同意/拒绝两路；`TrustAnchorsKeyBootstrapTest` 4——冷启动种子、幂等叠加、占位密钥 fail-closed）** + **多轮 Agent 循环（06 §11）：P1-P5 `executeProbe` 端口（非 read 或未知步骤整批拒绝且零执行、read 批次返回完整 `Ok` 值、审计来源 `AGENT_PROBE` 与 `CHAT` 区分）+ C17-C18 规划器接缝（clarify/refuse IR 结果填充 `LlmPlan` 扁平字段、`extraContext` 折叠进 `[Probe observations]` 头 / null 保持纯目标）+ A1-A16 + A5b `McosAgent` 循环（仅读前缀自动执行、观察折叠抵达第二次 compile、Probing→PlanReady 流式形态且恰一个终态、批准→Done / 拒绝→Declined / 无暂存 Declined、三种上限均 QUOTA 拒绝、探查中 cancel 优先、不可解析命令视作非 read、§14.1 重规划漂移防护强制 Clarify、会话隔离 + 观察跨回合保留、`agent.*` 事件）+ UI1-UI3 Android 外壳（探查进度 + 暂存计划预览与审批提示、允许→Done 总结、拒绝→Declined）**。

---

## 4. Fixture 覆盖

[`docs/fixtures/`](../fixtures/) 下的 golden 用例。全部 8 个均由 `DslParserTest` 执行并通过。

### 4.1 正向（DSL → IR 往返）

| Case | 覆盖 | Protocol § |
|------|--------|------------|
| `01-empty-args` | 空参数 + `# mcos-dsl:` 头 | §6.1, §6.5 |
| `02-named-string` | 具名字符串参数 | §6.1 |
| `03-array-and-int` | int + 字符串数组 | §6.2 |
| `04-sequence` | 多语句 + 注释 → `sequence` | §6.4 |
| `05-mixed-literals` | bool / float / null，键已排序 | §6.2, §7.4 |

### 4.2 负向（必须拒绝）

| Case | 非法输入 | 预期错误 | Protocol § |
|------|---------------|----------------|------------|
| `06-nested-call` | 参数中的嵌套调用 | `PARSE_ERROR` | §6.2, §15.1 |
| `07-positional-arg` | 位置参数 | `PARSE_ERROR` | §6.1 |
| `08-malformed` | 括号不平衡 | `PARSE_ERROR` | §18 |

---

## 5. 全局特性矩阵（P0 → P3）

聚合自 [05](./05-workflow.md) §15、[06](./06-agent.md) §17、[07](./07-memory.md) §16、[08](./08-security.md) §17、[09](./09-marketplace.md) §15 中的 "MVP vs V1" 阶段表。P0 列为**历史基线**（规范完整、代码未动）；截至 2026-08-24，P1 已交付、P2 大部分落地、P3 客户端侧已交付。阶段术语：P1 = MVP、P2 = V1、P3 = V2（[10](./10-roadmap.md) §2.1）。

| Subsystem | P0（仅规范） | P1 MVP | P2 | P3 |
|-----------|----------------|--------|----|----|
| Parser + IR | 规范完成 | ✅ **已实现** 完整 DSL↔IR | — | — |
| Registry + Executor | 规范完成 | ✅ **已实现** | — | — |
| Permission Kernel（`decideConfirmation`） | 规范完成 | ✅ runtime 已实现 + Android 确认对话框已接线 + 内置插件 bootstrap 授权 + 安装时授权 | ✅ 授权持久化（`FileGrantStore` 快照 + HMAC 防篡改 + session 不落盘） | — |
| ConfirmationPrompt | 规范完成 | ✅ **已实现** NORMAL/ELEVATED | ✅ 破坏性 typed-ack | — |
| Network Egress（`decideEgress`） | 规范完成 | ✅ **已实现** | — | — |
| Prompt Injection 检测 | 规范完成 | ✅ **已实现** 编译器侧 | — | + 自适应模型侧 |
| Rate Limiting | 规范完成 | ✅ **已实现** 每插件/分钟 | ✅ + 每 recipe/小时 | + 自适应 |
| Audit | 规范完成 | ✅ **已实现** 基础 + FileAuditLog 持久化（重放/逐出/脱敏/JSONL 导出 + 可选 HMAC 签名） | 🟡 部分实现：导出 ✅；静态加密 ❌（SQLCipher 为后续切片）；`auditFailClosed` 传播未接 | 远程证明 |
| Workflow | 规范完成 | ✅ 顺序 | ✅ **已实现** 并行 / 条件 / 循环 / 重试 / try / 确认 | — |
| Event Bus | 规范完成 | ✅ run 事件通道 | ✅ **已实现** 完整（信封、过滤、隔离、背压） | — |
| Memory | 规范完成 | ✅ profile + remember | ✅ 模糊引用 + 冲突检测 + **情景层（§8）**：`EpisodicMemory` 召回 + **§8.3 命名实体合并/模糊引用（`EntityMatcher`：叶节点匹配 + 别名注册，中英混合查询分词，0.75 §6.0 阈值）** + **§11 同步层（向量时钟 LWW + 策略）已完成** + **§11.0 端到端加密 blob（`MemoryBlobCrypto`：AES-256-GCM + HKDF 派生 + 版本绑定 AAD）** | ✅ **独立 `mcos-server` 部署完成（§6 步骤 22）**：`mcos-server/` 零第三方依赖 JDK `HttpServer` 实现 `SyncBlobTransport` REST 契约 + 强制 Bearer 认证（恒定时间比较）、磁盘持久化原子写入、blobId 白名单防路径穿越、16 MiB 上限、`/healthz`；`JdkSyncBlobTransport` 支持 token；真实 transport 互操作 E2E S1-S13 |
| Planner | 规范完成 | ✅ 1 个 provider，chat→DSL | ✅ 多 provider registry + **探活策略（§17）**：TTL 缓存、失败冷却、探测超时、并发探测、`healthSnapshot`/`probeAll`、Android UI 健康状态行（[06 §17](./06-agent.md)） | 🟡 延迟分层路由（[§13.1](./06-agent.md) 分类器 + 零延迟路径 + 分层链）已实现 |
| Plugins | 规范完成 | ✅ hello + system + camera + files（20+ 命令，[10 §4.3.1](./10-roadmap.md)） | ⬜ IoT + Intent | MCP spike (P2) / MCP 生产 + 市场 (P3) |
| Marketplace | 规范完成 | — | ✅ 信任级别 + 签名验证基础设施（`TrustLevel`/`ArtifactVerifier`/`VerificationCache`/`PluginTrustGate`，[09 §6.2/§6.5](./09-marketplace.md)）；调试侧载（`SIDELOAD_DEBUG`）已落地 | 🟡 客户端侧安装流程已交付（`mcos-marketplace` + Android 应用内搜索/安装/卸载/更新 + 安装记录落盘与重启再水合 + 动态 `.mcos` 加载（`DexClassLoader`）+ `TrustAnchors` 冷启动种子（占位密钥））；公共索引服务端部署与真实运营密钥仍待 |
| 进程隔离 | 规范完成 | 🟡 尽力而为（进程内） | — | 第三方默认 |
| 企业策略 | 规范完成 | — | — | ✅ **已实现** 允许/拒绝名单（[08 §13](./08-security.md)）：命令/网络名单 + forceConfirm + 企业 kill switch + fail-closed 解析 + 文件热加载 |
| Crash-loop 隔离 | 规范完成 | ✅ | ✅ | ✅ |

---

## 6. 推荐开发路径

步骤 1–7 与 10 已实现（2026-08-12）；8–9 已通过 `AndroidHostServices` 部分接线。

1. ✅ **Gradle 多模块构建**——`mcos-sdk`、`mcos-runtime`、`mcos-android`、4 个插件，按 [REPOSITORIES.md](./REPOSITORIES.md)。
2. ✅ **`DslParser`**——按 [02](./02-command-protocol.md) §6 + §18；§4 中全部 fixture 通过。
3. ✅ **`CommandRegistry`**——从插件加载 `CommandDescriptor`；按 ID 解析。按 [03](./03-runtime.md) §6。
4. ✅ **`Executor`**——用校验过的参数调用 `CommandHandler`；异常映射为 `PLUGIN_ERROR`。按 [03](./03-runtime.md) §9。
5. ✅ **Schema 校验**——执行前按 `inputSchema` 校验参数。按 [02](./02-command-protocol.md) §9.1。
6. ✅ **`PermissionKernel`**——按 `sideEffectClass` 实现 grant/deny/confirm 流程。按 [08](./08-security.md) §4。
7. ✅ **Audit（基础 + 持久化）**——`InMemoryAuditLog`（单写者协程、`x-mcos-secret` 脱敏、30d/10k 逐出）+ `FileAuditLog`（JSONL 追加落盘、逐行 flush、重启重放、畸形行容错、逐出时 tmp+rename 原子重写、导出可选 HMAC-SHA256 签名行——密钥由设备种子经 `deriveAuditHmacKey` 派生）；`McosRuntime.Builder.withAuditLog` + Android `filesDir/audit/audit.jsonl` 接线（种子存 SecureStore）。按 [03](./03-runtime.md) §13。
8. ✅ **真实插件处理器**——`camera.capture` / `sys.notify` 已通过 `AndroidHostServices` 接 Android API；交互式确认 UI（`RuntimeEvent.ConfirmationNeeded` → Material 3 `AlertDialog` → `respondConfirmation`，按 [08](./08-security.md) §5）。按 [04](./04-plugin-sdk.md) §7。
9. ✅ **`files` 插件**——`photo.search` / `photo.compress` 已实现并接入真实 Android 媒体库：`FileService.searchPhotos(mimeType, afterMs, beforeMs, limit)` 下推原生 `MediaStore` `DATE_ADDED` selection，新到旧排序 + `LIMIT`（API 31+）；`photo.search` 将 `date`（`today`/`yesterday`/`this_week`/`this_month`）与 ISO-8601 `after`/`before` 解析为本地时区毫秒后再查询；`file.search` 默认根目录为 `media://images`。`location` 在 EXIF/GPS 索引落地（P3）前保持提示性。按 [10](./10-roadmap.md) §4.3。
10. ✅ **多 provider Planner**——`LlmProvider` 能力模型（`Capability`：CHAT/PLAN/TOOL_CALL/EMBED）、`LlmProviderRegistry`（注册、能力路由、健康探测）以及 `LlmPlanner` 中按优先级排序的回退链（retryable 错误 → 下一 provider；§18.1 本地→云端回退）。按 [06](./06-agent.md) §17 V1。
11. ✅ **PlanMode `NATIVE_TOOL_CALL`**——按 provider 选择模式（TOOL_CALL → 原生工具调用，否则 FREEFORM_JSON）、`ToolCall`/`ToolDescriptor`/`TokenUsage` 类型、registry 命令投影（含尽力而为的示例解析）以及 `OpenAiLlmProvider` 中的 OpenAI `tools` 协议支持。按 [06](./06-agent.md) §3.2/§17 V1。
12. ✅ **本地→云端回退与隐私闸门**——`LlmProvider` 上的 `ProviderTier`（ON_DEVICE/CLOUD）、`LlmPlanner.cloudFallbackEnabled`（"允许云端 planner" 显式开关，06 §13.2）以及隐私闸门：一旦 ON_DEVICE provider 失败，升级到 CLOUD 需要显式开启——否则失败以 `CLOUD_FALLBACK_DISABLED` 拒绝呈现，且数据不出设备。标准错误码 `CAPABILITY_EXCEEDED`/`CLOUD_FALLBACK_DISABLED`；`LlmProviderRegistry.onDeviceProviders()`/`cloudProviders()` 分层过滤。按 [06](./06-agent.md) §13.0/§13.2/§17 V2。
13. ✅ **Android 聊天外壳（Planner 接入应用）**——可插拔 `LlmHttpTransport`（`llm/` 中的 `LlmHttpTransport`/`HttpTransportResponse`/`LlmTransportException`；JDK `HttpClient` 默认实现保持 JVM 测试绿色；`AndroidLlmHttpTransport` 使用 `HttpURLConnection`——Android 无 `java.net.http` 模块）、`OpenAiLlmProvider(transport=…)` 注入、Manifest 中的 `INTERNET` 权限，以及 `MainActivity` 中的 **AI Chat 卡片**（自然语言输入 → `ChatOrchestrator` → 计划/DSL 预填到 DSL 编辑器 → 执行事件记录；OpenAI API key 经 `AndroidSecureStore` 持久化）。按 [06](./06-agent.md) §17。
14. ✅ **PlanMode `CONSTRAINED`（语法约束解码）**——`Capability.CONSTRAINED` + `PlanMode.CONSTRAINED`、`LlmProvider.constrainedChat(messages, grammar: LlmGrammar)`（默认 `CAPABILITY_EXCEEDED`，不可重试）、模式选择 `NATIVE_TOOL_CALL > CONSTRAINED > FREEFORM_JSON`、`buildIrJsonSchema()`（MCOS IR JSON Schema：`invoke`/`sequence`/`clarify`/`refuse`）以及 `parseIrJson()`——模型回复是单个 IR JSON 对象；畸形输出产生可重试的 `LLM_PARSE_ERROR`，回退链继续。`OpenAiLlmProvider` 用 OpenAI `response_format: json_object` + 将 schema 追加到 system 消息实现 constrainedChat（API 侧的近似实现；真实解码级语法见 #16）。CONSTRAINED 的 system prompt 只列出命令/记忆（无 DSL 格式段）。按 [06](./06-agent.md) §3.2 V2 / §17 V2。
15. ✅ **Memory 设备间同步（§11）**——`memory/VectorClock`（§11.1：`tick`/严格支配 `isAfter`/`isConcurrentWith`/分量取最大 `merge`；刻意不用 CRDT——事实型 KV 记忆"最新正确值"即所需语义）、`MemoryEntry` 增 `syncable`/`vectorClock`/`writerDeviceId`（§11.0：仅 `syncable=true` 条目可离开设备；本地 `put` 自动 tick 本设备时钟）、`memory/MemorySync`——`exportSnapshot()`（只含 syncable 条目）+ `importSnapshot()`（LWW 表：本地/远端支配→静默保留/覆盖，并发→呈现 `SyncConflict`："Keep local, remote, or both?"）+ `resolveConflict()`（`KEEP_LOCAL`/`KEEP_REMOTE`/`KEEP_BOTH`，后者将本地旧值软删入历史保留两者）以及 `SyncPolicy`（§11.3：`enabled` = disableCloudMemorySync 全局禁用、`allowedCategories` = allowedSyncCategories 类别限制），违规中止并记 AuditLog（`source=MEMORY_SYNC`）。`MemoryStore.applySyncEntry` 合并远端时钟使本设备单调追平（LWW 单调性）。Server（Phase 3）仅存加密 blob——本实现聚焦设备侧载荷与决策。按 [07](./07-memory.md) §11。
16. ✅ **CONSTRAINED 语法注入（GBNF / Outlines 后端）**——`LlmGrammar`/`GrammarFormat`（`GBNF` 与 `JSON_SCHEMA`）、`LlmProvider.grammarFormats` 广告语法能力（planner 按最高保真选择：GBNF > JSON_SCHEMA）、`llm/GbnfGrammar`——从命令目录生成 llama.cpp GBNF 语法：`root` 枚举目录内命令 + 终态（invoke/sequence/clarify/refuse）、每命令 `args-<id>` 规则（键名/值类型/enum/const/嵌套 object/array，llama.cpp 官方 json.gbnf 风格——对象内成员顺序自由、可重复，required 语义由 `parseIrJson` 与 executor schema 校验兜底）、`step-<id>` 约束 sequence 内命令、共享 JSON 规则（`ws`/`string`/`number`/`boolean`/`value`/`object`/`array`）、规则名消毒（`.` → `_`）与 GBNF 字符串转义；以及 `GrammarLlmProvider`——OpenAI 兼容 + 真实解码级约束：llama.cpp `llama-server` `{"grammar": "<gbnf>"}`、vLLM/Outlines `guided_grammar`（GBNF）与 `guided_json`（JSON Schema）注入字段（`GrammarInjection`），格式不匹配 → `CAPABILITY_EXCEEDED`（非重试，不触网）；`OpenAiLlmProvider` 收到非 JSON_SCHEMA 语法时同样拒绝。按 [06](./06-agent.md) §3.2 V2。
17. ✅ **PlanMode `LATENCY_TIERED`（延迟分层路由）**——06 §13.1 路由策略 + §15.1 性能预算：`llm/UtteranceClassifier`（`UtteranceClass`：EXACT_CLI / KNOWN_RECIPE / PRIVACY_SENSITIVE / COMPLEX / SIMPLE；关键词 + 启发式，优先级 EXACT_CLI > KNOWN_RECIPE > PRIVACY_SENSITIVE > COMPLEX > SIMPLE；嵌入向量相似度层留作后续里程碑）、`llm/Recipe` + `llm/RecipeMatcher`（§13.1 FAQ / 已知配方 → 本地匹配器：归一化精确/包含匹配，零延迟）；`LlmPlanner.plan(naturalLanguage, mode)` 支持显式 `LATENCY_TIERED`——先走零延迟路径（EXACT_CLI → 解析器直通 `direct-parser`；KNOWN_RECIPE → 配方 DSL `recipe:<id>`，均不触网），再按延迟分层排序 LLM 链（ON_DEVICE p95 ≤ 800ms 优先，CLOUD p95 ≤ 3000ms 殿后；COMPLEX 意图在云端 opt-in 时反转优先；PRIVACY_SENSITIVE 强制端侧优先），§13.2 隐私闸门在分层链中完整保留；`LlmPlan` 新增遥测 `utteranceClass` / `latencyMs` / `route`（§15.0）；`ChatOrchestrator.chat` 透传 `mode`。按 [06](./06-agent.md) §13.1/§13.2/§15.1。
18. ✅ **Memory 云端同步（Phase 3 端到端加密 blob）**——07 §11.0"服务器仅存不透明 blob"：`memory/HkdfSha256`（RFC 5869 HKDF-SHA256，JDK 无内置原语）、`memory/MemoryBlobCrypto`（AES-256-GCM + 随机 12 字节 IV，同明文两次加密密文不同；密钥由设备本地**账户密钥**（`AccountKeyProvider`，§11.0：同账户多设备共享派生，非设备 keystore 密钥；Android 由 Keystore 包裹）经 HKDF 派生用途特定子密钥（§10.1：高熵主密钥 → 无 PBKDF2/Argon2）；blob 版本绑定 GCM AAD——密文/IV/版本任何篡改 → `BlobIntegrityException`；wire 格式 `EncryptedBlob{version, iv, ciphertext}` 全 Base64）、`memory/MemorySyncClient`（`push()`：exportSnapshot → 加密 → `transport.upload` → 返回 blobId；`pull()`：下载 → 本地解密 → `importSnapshot`——LWW + `SyncPolicy` 端到端生效）+ `SyncBlobTransport`（可插拔：JVM 默认 `JdkSyncBlobTransport`（`java.net.http.HttpClient`，`PUT|GET|DELETE /blobs/{id}`，404 → 非重试 `SyncBlobException("NOT_FOUND")`；Android 无该模块，按 `LlmHttpTransport` 同模式注入 `HttpURLConnection` 实现）。E2E 测试用内嵌 JDK `HttpServer` 参考服务器（只存不透明 blob、绝不解析）验证全链路：A 推送 → 加密 → HTTP → 服务器 → B 拉取 → 解密 → LWW 导入；服务器只见密文（无明文路径/值）、`local_only` 条目不出设备、`disableCloudMemorySync` 阻断并记 AuditLog。按 [07](./07-memory.md) §10.1/§11.0/§11.3。
19. ✅ **NL→IR 评测套件（§16）**——`docs/fixtures/planner/` 下 7 个黄金用例：invoke（camera/notes/navigation×memory 消歧）、sequence（压缩→发邮件，`$ref:memory` 绑定）、clarify（歧义）、refuse（离题 + 空目录）、destructive invoke（confirm 属 executor 层）；`NlIrEvaluation` 把每个 fixture 经真实 `LlmPlanner` 流水线编译——`GoldLlmProvider` 按 `mode`（constrained/freeform）路由到 `constrainedChat`/`chat` 并返回 fixture 的 stub 回复——再做**结构断言**（命令 ID、步骤顺序、arg 键存在；`$ref:` 绑定仅要求非空，不断言精确值，§16.0），聚合 §16.1 指标（编译准确率、误拒绝率、误执行率、澄清正确率、平均延迟）；`NlIrGoldenSuiteTest` 作为 §16.2 回归门控：fixture 良构校验 + 套件级 100% 结构准确率 + 0 误执行/0 误拒绝 + 逐用例匹配。按 [06](./06-agent.md) §16。
20. ✅ **Planner 探活策略与 UI 呈现（§17 V1）**——`LlmProbePolicy`（TTL 缓存 `cacheTtlMs`、失败冷却 `failureCooldownMs`、探测超时 `probeTimeoutMs`、并发开关 `concurrent`）+ `ProviderHealth` 快照（tier/capabilities/healthy/lastProbeAtMs/consecutiveFailures/errorCode/errorMessage）；`LlmProviderRegistry` 升级：`healthyProviders(policy)` 命中缓存不再触网、失败在冷却期内不再重复探测、单次探测超时→`LLM_PROBE_TIMEOUT` 视为不健康、默认并发探测（`coroutineScope` + `async`），新增 `healthSnapshot()`（零网络，供诊断/UI）与 `probeAll()`（清缓存强制刷新）；`healthyProviders()`/`primaryHealthy()` 默认策略向后兼容；Android `MainActivity` AI Chat 卡片新增 provider 健康状态行（● 就绪 / ○ down + 错误码）与 PROBE 手动刷新按钮（key 输入防抖 500ms 自动探测）。按 [06](./06-agent.md) §17 V1。
21. ✅ **情景层 §8.3 命名实体合并/模糊引用**——`EntityMatcher`（07-memory §8.3）：实体以记忆路径存储（`people.tom`），查询以自然语言引用（"Tom"/"跟上次一样发照片给Tom"）；三路召回信号取最大值：整路径 fuzzyScore（回退）、叶节点匹配（`people.tom` → `tom`，大小写不敏感）、注册别名合并（label/昵称 → 规范路径）；查询按汉字串/ASCII 字母数字串分词（`\p{IsHan}+|[a-zA-Z0-9]+`）使混合语言查询命中内嵌实体名；弱信号（< 0.75，§6.0 Step 3 同款阈值）丢弃避免误召回；`EpisodicMemory.search` 接入（textScore 与 entityScore 取 max）。测试 E15-E19。按 [07](./07-memory.md) §8.3。

22. ✅ **独立 `mcos-server` 部署（§11.0 REST 契约 + 认证）**——`mcos-server/` 独立 JVM 应用（零第三方运行时依赖，JDK `com.sun.net.httpserver`）：REST 契约 `PUT|GET|DELETE /blobs/{id}`（204/200/404/405/400/413）+ `/healthz` 无认证健康检查；**强制 Bearer token 认证**（`Authorization: Bearer <token>`，恒定时间比较 `MessageDigest.isEqual`，401 带 `WWW-Authenticate: Bearer` 挑战；`--token` 或 `MCOS_SERVER_TOKEN` 环境变量，未配置则拒绝启动）；blob 不透明存储（绝不解析内容）：磁盘持久化 + tmp/rename 原子写入（重启存活）、blobId 白名单 `[A-Za-z0-9_-]{1,128}` 防路径穿越（编码点段 `..%2F` → 400）、16 MiB 上限 → 413；设备侧 `JdkSyncBlobTransport` 新增可选 `token` 参数注入 Bearer 头（`SyncBlobTransport` 接口不变，Android 注入式实现不受影响）。E2E S1-S13：用真实设备侧 transport 对接活服务器（认证往返、服务器只见不透明字节、幂等删除、错/缺 token 401、404 非重试、405/404、healthz 免认证、重启持久化、路径穿越加固）。按 [07](./07-memory.md) §11.0。

23. ✅ **企业策略允许/拒绝名单（§13）**——`security/EnterprisePolicy`（§13.1 类型：`allowCommands`/`denyCommands`/`forceConfirm`/`networkAllow`/`networkDeny`/`disableSideload`/`disableCloudMemorySync`/`auditFailClosed`/`disableAllPluginNetwork`/`secretTtlDays`/`version`/`issuedAt`/`issuedBy`；命令 ID glob（`*` 全匹配、`prefix.*` 前缀、精确）与域名 glob（`*`、`*.suffix`、精确）同 §12.1 语义）；**fail-closed 解析**（§13.3：畸形 JSON / 缺字段 / 版本不支持 → `FAIL_CLOSED`——硬编码安全集命令、全类别 forceConfirm、侧载/云同步/插件网络全禁、审计 fail-closed）；`security/EnterprisePolicySource`——静态源（`EnterprisePolicySource.fixed`）与 `FileEnterprisePolicySource`（mtime 轮询热加载，§13.3：解析失败 → `FAIL_CLOSED` + `PolicyParseFailed`（含 SHA-256 文档指纹）事件、读取失败 → 缓存回退或 `FAIL_CLOSED` + `PolicyFetchFailed`、成功 → `PolicyUpdated` 事件、listener 订阅、刷新节流）；**接线**：`PermissionKernel.authorize(descriptor, enterprisePolicy)`——命令 deny 名单无条件拒绝、非空 allow 名单为上限、`forceConfirm` 将 auto-approve 命令升级为 `ConfirmationNeeded`（§4.3，只升不降）；`NetworkEgressPolicy.decideEgress(url, authStamp, kill, debugMode, enterprisePolicy)`——`disableAllPluginNetwork` 并入步骤 1 kill switch（`enterprise_kill_switch_active`）、步骤 4 网络 deny 名单优先、非空 allow 名单为上限（`enterprise_network_deny` / `enterprise_network_allowlist_miss`）；`Executor` 新增 `enterprisePolicySource` 构造参数（Stage 6 / Stage 6.5 每次取 `current()`，热加载即时生效）；`McosRuntime.Builder.withEnterprisePolicySource(...)`。测试 E1-E11、F1-F8b、N14-N18b、P21-P26（36 个）。按 [08](./08-security.md) §12/§13/§4.3。

24. ✅ **插件信任级别与签名验证（§7/§6）**——`security/TrustLevel`（08 §7.0：`BUILTIN` / `MARKETPLACE_VERIFIED` / `SIDELOAD_DEBUG` / `UNTRUSTED`，由运行时按工件签名状态派生，插件不得自证）；`security/PublisherKey`（09 §6.0：keyId/publisherId/fingerprint/algorithm/`publicKeyEncoded`（X.509 DER base64）/`rotatedFrom`/`status`（ACTIVE/REVOKED））+ `InMemoryPublisherKeyStore`；`security/ArtifactSignature`（§4.0 `ArtifactRef` 签名信封：`payloadSha256`/`signature`/`signingKeyId`/`algorithm`/`signedAt`）；`security/ArtifactVerifier`（§6.2 六步管线，fail-closed：① SHA-256 完整性 ② 取公钥 ③ key 状态（REVOKED → `key_revoked`）④ 缓存快路径（离线加载）⑤ 签名验证（Ed25519 首选 / RSA-PSS-4096 传统，JCA 算法名映射）⑥ blocklist（`packageId@version`）→ 成功缓存 `(keyId, hash)`）；`security/VerificationCache`（03 §16.2：`(keyId, payloadSha256) → VerifyCacheEntry(verifiedAt, trusted)`，默认 TTL 7 天对齐撤销 TTL，惰性过期，并发安全）；`security/PluginTrustGate`（§6.5/§7.1 决策矩阵：内置放行、有效签名→`MARKETPLACE_VERIFIED`、签名无效/吊销/blocklist→拒绝、无验证器→拒绝、调试构建无签名→`SIDELOAD_DEBUG`、生产构建无签名→拒绝、`disableSideload` 企业策略→拒绝（§13.2，但不阻已验证市场插件））；**接线**：`CommandRegistry.register(plugin, trustLevel = BUILTIN)` 记录信任级别到 `RegistryEntry.trustLevel`，`UNTRUSTED` → 新增 `RegisterResult.Rejected`（插件在注册前被拦截）。测试 V1-V16、T1-T12、R22-R24（30 个）。按 [08](./08-security.md) §7、[09](./09-marketplace.md) §6、[03](./03-runtime.md) §16.2。

25. ✅ **持久化收口（授权表 + 安装记录，复用 FileAuditLog 范式）**——`security/SnapshotFile`（共享持久化原语：单文档快照 tmp+rename 原子重写 + 可选 HMAC-SHA256 防篡改行 + `deriveHmacKey` 种子派生）；**授权表**：`permission/GrantStore`（接口 + `NullGrantStore` no-op 默认 + `FileGrantStore`），`DefaultPermissionKernel(store)` 构造回灌 grants/autoApprove/alwaysConfirm、8 个变更点写穿，**session 授权按 `pluginId:permission` 键过滤绝不落盘**，destructive auto-approve 异常在写穿前抛出（不变量保持），坏文件/HMAC 不符 → 空表启动（fail-closed = 拒绝方向）；**安装记录**：`marketplace/InstallRecordStore`（终态 `PersistedInstallRecord`：版本/状态/信任级别/工件名/**钉住的发布者公钥 + 签名信封**——密钥库每启动为空，不钉住则重启后无法重新验签），`PluginInstaller` 构造回灌 `states`/`installedVersions`，安装成功/卸载/blocklist 禁用/mark* 全部写穿（瞬态永不落盘），`rehydrateInstalled(factory, seedKey)` 重启再水合——逐条**重新全量验签** staged 工件后才注册（持久记录是主张不是证据；篡改 → 丢记录零注册），DISABLED 保持卸载，工厂缺失保留记录；**Android 接线**：`filesDir/permissions/grants.json` + `marketplace/install-records.json`，HMAC 密钥由 SecureStore `state_hmac_seed`（与审计种子分域）派生；`MarketplaceViewModel.attach` 异步再水合（Ed25519 验签不阻塞冷启动，恢复后 onLoad + 面板刷新），`install()` 成功后经 `PluginPermissionBootstrap.grantAll` 授予声明权限（安装确认对话框即同意时刻）——授权与安装两条持久化在此合流，重启后市场安装的插件可直接运行。测试 G1-G6、T9-T14、Android +1。按 [08](./08-security.md) §5.1、[09](./09-marketplace.md) §7。

26. ✅ **宿主能力面扩容（设备信息 + 剪贴板 + 震动 + toast）**——`sys.device.*` 六条 UNAVAILABLE 与 `sys.vibrate`/`sys.clipboard` 假成功全部变真。**SDK**：`HostServices` 增三个可选能力（`deviceInfo`/`clipboard`/`haptics`，接口默认 null——纯 JVM 宿主不覆写即诚实降级）+ `DeviceInfoService`（battery/wifi/screen/volume/location/brightness/setBrightness）等接口与数据类 + `UiService.toast` 默认抛 UNAVAILABLE（04 §6.3 规范已补 §6.7-6.10）；**Executor**：`secretResolvingServices` 匿名包装器补齐三项委派（陷阱：漏 override 时接口默认 null 会静默剥夺所有已执行命令的能力，E31 防回归）；**SystemPlugin**：9 个 handler 真接线——P2-F3 假数据史终章（曾硬编码 battery=85%/ssid="MCOS-Network"/坐标 22.5431 并报 Ok），现映射真实 `DeviceInfoService`，取不到的数据项输出 null/`no_fix` 绝不猜测；`sys.vibrate` 假成功移除（真震动或 UNAVAILABLE）；`sys.clipboard` 读结果恒带 `untrusted:true`（08 §11.1 剪贴板是不可信输入源）；brightness set 保持先 schema 校验后能力检查（S37 语义）；**Android**：`AndroidDeviceInfoService`（sticky 广播电量、ConnectivityManager 连接态 + 定位门控 SSID/RSSI、R+ display 分支、三流音量、getLastKnownLocation、Settings 亮度读写 + `canWrite` 检查）/`AndroidClipboardService`/`AndroidHapticsService`（VibratorManager S+ 兼容）+ 主线程 Handler toast；Manifest 补 VIBRATE/ACCESS_FINE_LOCATION(+COARSE 配对)/WRITE_SETTINGS/ACCESS_NETWORK_STATE（lint 强制）。**🟡 已知缺口（如实）**：无应用内 `requestPermissions` 引导流——运行时权限未授予时命令报可操作的 `PERMISSION_DENIED` 并指向系统设置；Android 三个实现类为框架 API 直调、无 JVM 测试（项目无 Robolectric 惯例），**待真机验证**；`FileService` 沙箱读写（04 §6.1）仍无命令消费（单独切片）；`NetService`/`SecureStore`/`Clock` 与 §6 全量签名对齐仍缺。测试 37→48（+11）+ Executor +1。按 [04](./04-plugin-sdk.md) §6.3/§6.7-6.10/§17。

**下一步（建议）：** 市场运营侧——公共索引服务端部署、真实签名密钥引导与轮换（`TrustAnchors` 骨架已就位但持占位密钥，发布即换运营方公钥）；宿主能力补全——应用内运行时权限引导流（替代"去系统设置"的手工指引）、`FileService` 沙箱读写切片（04 §6.1 规范在等待命令消费方）、§6 全量签名对齐（NetService/SecureStore/Clock）。

---

## 7. SDK API 目标（规范契约）

完整的插件契约在 [04-plugin-sdk.md](./04-plugin-sdk.md) §5–6 中规定。实现已提供以下全部内容（四个插件均基于其编译）：

- `McosPlugin`——`id`、`version`、`commands()`、`handler(commandId)`，以及按规范的生命周期（`onLoad`/`onUnload`）。
- `CommandDescriptor`——全部规范字段，包括 `permissions`、`inputSchema`、`outputSchema`、`sideEffectClass`。
- `CommandResult`——`Ok(value, artifacts)` 与 `Err(code, message, retryable, details)`，其中 `details: JsonObject` 携带按错误码的结构化上下文（[04 §5.2](./04-plugin-sdk.md)、[02 §8.3](./02-command-protocol.md)）。
- `McosException`——插件声明的错误通道，用于区别于 `CommandResult.Err` 的可恢复失败（[04 §9.5](./04-plugin-sdk.md)）。
- `ExecutionContext`——`runId`、`commandId`、`args`、`stepId`、`auth`、`deadline`、`progress`、`services`。
- `HostServices` 门面：`files`、`net`、`ui`、`secureStore`、`clock`、`json`、`memory`——统一的插件侧门面（[04 §6](./04-plugin-sdk.md)、[01 §11.1](./01-architecture.md)）。历史名称 `PluginHost` 已废弃。

权威的 Kotlin IDL 见 [04-plugin-sdk.md](./04-plugin-sdk.md) §5。

---

## 8. 如何更新本文件

- **当代码落地：** 把相关行从"仅规范"移到"已实现"，并引用对应的 PR/commit。
- **当规范变化：** 升级 RFC 版本号，并更新 §4 fixture + §3/§5 矩阵。
- **不要**删除行——把它们标记为 superseded，以保留意图的历史。

本文件变化很快；把它当作一份清单，而非纪念碑。
