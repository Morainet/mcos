# MCOS 实现状态

> **语言:** [English](../en/11-implementation-status.md) · 中文（当前）

> **Status:** Living document  
> **Last Updated:** 2026-08-15  
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
├── mcos-runtime/         # Parser → IR、Registry、Executor、Permission、Audit、Workflow、EventBus、Memory、LLM
├── mcos-android/         # Jetpack Compose CLI / Chat 外壳 + Android host services
├── mcos-server/          # 独立自托管同步端点（SyncBlobTransport REST 契约 + Bearer 认证，仅存不透明 blob）
├── plugins/              # hello、system、camera、files
├── README.md / README.zh-CN.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE
```

- **源代码模块：** ✅ 7 个（sdk、runtime、android、server + 4 插件），见 §2
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
| `plugins:mcos-plugin-system` | `sys.notify`、`sys.share`、`sys.intent.start` | P1 |
| `plugins:mcos-plugin-camera` | `camera.capture`、`camera.scan` | P1 |

**计划模块**（后续阶段——定义于 [REPOSITORIES.md](./REPOSITORIES.md) §3）：

| Module | 角色 | 目标阶段 |
|--------|------|--------------|
| `mcos-plugin-files` | `file.*`、`photo.search`、`photo.compress` | P1 |
| `mcos-plugin-iot` | `home.*`、`iot.*`（Home Assistant / Tuya / Matter） | P2 |
| `mcos-plugin-mcp` | MCP 客户端适配器 → `mcp.*` | P2 spike / P3 production |

`mcos-server` 已不再是计划模块——它已作为 `mcos-server/` 落地（§3 Memory 行），覆盖 P3「同步」职责；市场索引与远程策略仍为 P3 剩余项。

---

## 3. 子系统实现矩阵

状态图例：✅ 已实现 · 🟡 部分 · ⬜ 仅规范（未开始）。落地 commit 按行引用（2026-08-12，`main` 分支）。

| Subsystem | 规范文档 | 目标阶段 | 状态 |
|-----------|----------|--------------|------|
| **Parser**（DSL → IR） | [02](./02-command-protocol.md) §6, [03](./03-runtime.md) §5 | P1（最先） | ✅ `parse/`（Lexer、Parser、DslParser、Canonicalizer）；fixture 全绿 |
| **IR 类型** | [02](./02-command-protocol.md) §7 | P1 | ✅ `ir/IrTypes`（ExecutionIr + Step、payload 信封） |
| **Command Registry** | [03](./03-runtime.md) §6 | P1 | ✅ `registry/CommandRegistry`（register/resolve/unregister，id=name） |
| **Permission Kernel** | [08](./08-security.md) §4, [03](./03-runtime.md) §7 | P1 | ✅ `permission/PermissionKernel.decideConfirmation`（NORMAL/ELEVATED） |
| **Scheduler** | [03](./03-runtime.md) §8 | P1 | 🟡 `McosRuntime` 内进程内 FIFO 队列；尚无优先级通道 |
| **Executor** | [03](./03-runtime.md) §9 | P1 | ✅ `executor/Executor`（步骤、产物、确认、取消、限流） |
| **Audit 日志** | [03](./03-runtime.md) §13, [08](./08-security.md) §14 | P1（基础） | ✅ `audit/AuditLog`（append、filter、rotate、sha256 + HMAC 链） |
| **Planner 桥** | [06](./06-agent.md) | P1（单一 provider） | ✅ `llm/` LlmPlanner + OpenAiLlmProvider + ChatOrchestrator，通过可插拔 `LlmHttpTransport`（JDK `HttpClient` 默认 + Android `HttpURLConnection`）接入 Android 聊天外壳；API key 经 `AndroidSecureStore` 持久化；四种 PlanMode——NATIVE_TOOL_CALL / FREEFORM_JSON / CONSTRAINED / LATENCY_TIERED |
| **Network Egress 策略** | [08](./08-security.md) §12（`decideEgress`） | P1 | ✅ `security/NetworkEgressPolicy.decideEgress` |
| **Prompt Injection 检测** | [08](./08-security.md) §11 | P1（编译器侧） | ✅ `llm/PromptInjectionDetector` |
| **Rate Limiting** | [08](./08-security.md) §10 | P1（每插件/分钟） | ✅ `security/RateLimiter`（每插件/分钟） |
| **Secret 管理**（`{{secret}}` 模板） | [08](./08-security.md) §9 | P1 | ⬜ 未实现 |
| **Crash-loop 隔离** | [08](./08-security.md) §15.3 | P1 | ⬜ 未实现 |
| **进程隔离** | [08](./08-security.md) §8 | P1（尽力而为）→ P3（第三方默认） | 🟡 仅进程内尽力而为的并发控制 |
| **Workflow 引擎** | [05](./05-workflow.md) | P2 | ✅ `workflow/WorkflowEngine` — sequential/parallel/if/loop/retry/try/confirm，命名 store + JSON 解码；已接入 `McosRuntime.runWorkflow`（`d533c05`） |
| **Event Bus** | [03](./03-runtime.md) §11 | P2 | ✅ `events/EventBus` — 类型化信封、前缀 + where 过滤、订阅者隔离、丢最旧背压 + 审计（`22ba52b`） |
| **Memory** | [07](./07-memory.md) | P2 | ✅ `memory/MemoryStore` — TTL、标签、模糊 resolveRef + 置信度、CREATED/UPDATED/CONFLICT 写入语义、superseded 历史（`d549236`）+ ✅ `memory/EpisodicMemory` — run 摘要、时间衰减召回（§8.1）、50→5 自动摘要 + 90 天保留（§8.2）+ ✅ `memory/RunSummarizer` — §9.4 run 完成钩子：命令/工作流记录 `EpisodicRecord`（实体取自 `namespace.path` 参数，摘要取自 DSL 文本）+ ✅ **`memory/MemorySync` — §11 设备间同步：`VectorClock`（tick / 严格支配 `isAfter` / `isConcurrentWith` / 分量取最大 `merge`，§11.1）、per-entry `syncable` 标记（§11.0：仅 `syncable=true` 条目可离开设备）、`SyncEntry` 快照导出/导入 + LWW 表（本地/远端支配 → 静默；并发 → 呈现 `SyncConflict` → `KEEP_LOCAL`/`KEEP_REMOTE`/`KEEP_BOTH`）、`SyncPolicy`（§11.3：`enabled` = disableCloudMemorySync、`allowedCategories` = allowedSyncCategories）违规记 AuditLog** + ✅ **`memory/MemoryBlobCrypto` — §11.0 端到端加密 blob：AES-256-GCM + 随机 12 字节 IV（同明文两次加密密文不同）、`HkdfSha256`（RFC 5869 HKDF-SHA256，JDK 无内置实现）从设备本地账户密钥（`AccountKeyProvider`，§11.0：同账户多设备可派生同一密钥，Android 端 Keystore 包裹）派生用途特定子密钥（§10.1：高熵主密钥，无 PBKDF2/Argon2）、blob 版本绑定 GCM AAD——密文/IV/版本任何篡改 → `BlobIntegrityException`；wire 格式 `EncryptedBlob{version, iv, ciphertext}` Base64 JSON** + ✅ **`memory/MemorySyncClient` + `SyncBlobTransport`（§11.0 服务器仅存不透明 blob）：`push()` 导出 syncable 快照 → 加密 → 上传（返回 blobId）、`pull()` 下载 → 本地解密 → `importSnapshot`（LWW + 企业策略端到端生效）；`JdkSyncBlobTransport`（`java.net.http.HttpClient`，`PUT|GET|DELETE /blobs/{id}`，404 → 非重试 `SyncBlobException("NOT_FOUND")`，可选 `token` 注入 `Authorization: Bearer`；Android 无该模块，注入 `HttpURLConnection` 实现）** + ✅ **E2E：内嵌 JDK `HttpServer` 参考服务器 + 设备 A→B 全链路（E1-E9：往返、服务器只见密文、LWW、并发冲突、`local_only` 不出设备、`disableCloudMemorySync`、404、幂等）** + ✅ **独立 `mcos-server` 部署（`mcos-server/`，P3 同步）：零第三方依赖 JDK `HttpServer` 实现 `SyncBlobTransport` REST 契约（`PUT|GET|DELETE /blobs/{id}`，`/healthz` 无认证健康检查），强制 Bearer token（恒定时间比较、401 + `WWW-Authenticate` 挑战、启动无 token 即拒绝），blobId 白名单 `[A-Za-z0-9_-]{1,128}` 防路径穿越，磁盘持久化 + 原子写入（重启存活），16 MiB 上限 → 413；`main()` CLI（`--port`/`--data-dir`/`--token`/`MCOS_SERVER_TOKEN` 环境变量回退）；E2E 用真实设备侧 `JdkSyncBlobTransport` 对接活实例（S1-S13：往返、不透明字节不变、幂等删除、401/404/405/400、healthz、重启持久化、路径穿越加固）** |
| **企业策略** | [08](./08-security.md) §13 | P3 | ✅ `security/EnterprisePolicy`（§13.1 命令/网络 allow-deny 名单 + forceConfirm + disableAllPluginNetwork + auditFailClosed，§13.3 fail-closed 解析）+ `security/EnterprisePolicySource`（静态源 / 文件热加载源，mtime 轮询、解析失败→`FAIL_CLOSED` + 事件、读取失败→缓存回退）+ 已接入 `PermissionKernel.authorize`（§13.2 命令名单、§4.3 forceConfirm 升级）与 `NetworkEgressPolicy.decideEgress`（§12.0 步骤 4 网络名单、§13.2 企业 kill switch）+ `Executor`/`McosRuntime.Builder.withEnterprisePolicySource` 装配 |
| **Marketplace** | [09](./09-marketplace.md) | P3 | 🟡 `security/TrustLevel`（§7.0 BUILTIN/MARKETPLACE_VERIFIED/SIDELOAD_DEBUG/UNTRUSTED）+ `security/ArtifactVerifier`（§6.2 六步管线：SHA-256 完整性 → 取公钥 → key 状态 → 签名验证（Ed25519 / RSA-PSS-4096）→ blocklist → 缓存）+ `security/VerificationCache`（§16.2，7 天 TTL）+ `security/PluginTrustGate`（§6.5/§7.1 决策矩阵 + `disableSideload` 落地）+ `CommandRegistry.register(plugin, trustLevel)`（UNTRUSTED 拒绝）；公共索引与端到端安装流程仍为 P3 剩余 |

> **已完成：** `DslParser`（最高杠杆的第一步）与其余 P1 流水线一同交付。P1 安全底线中 `decideConfirmation`、`decideEgress`、prompt-injection 检查与 rate limiting 均已实现；**crash 隔离与 `{{secret}}` 模板仍为空白**。
>
> **测试基线（2026-08-15）：** 全模块 704 个测试——parser fixture、executor、permission、audit（含 `x-mcos-secret` 脱敏）、workflow（W1-W6）、event bus（8）、memory（M1-M33 + 情景 E1-E19（含 §8.3 命名实体合并/模糊引用 E15-E19）+ 摘要 S1-S11 + **同步 V1-V4 向量时钟语义：tick / 严格支配 `isAfter` / 并发 / 分量取最大 `merge` + S1-S15 同步流：仅 syncable 导出、新路径应用、本地/远端支配 LWW、并发→`SyncConflict` 呈现、幂等重复导入、`allowedSyncCategories` 过滤、`disableCloudMemorySync` 中止 + AuditLog 记录、KEEP_LOCAL/REMOTE/BOTH 解决、时钟 merge 单调性、快照载荷** + **B1-B3 HKDF-SHA256（RFC 5869 §A.1 官方 PRK/OKM 测试向量 + 长度边界）+ C1-C7 `MemoryBlobCrypto`（往返、同明文异密文、密文/IV 篡改 → `BlobIntegrityException`、版本门控、不同账户密钥不可互解、wire 不透明）** + **E1-E9 加密同步 E2E（A 推送 → HTTP → 参考服务器 → B 拉取解密：往返、服务器只见密文无明文、远端 LWW 覆盖、本地新写保留、并发 → `SyncConflict`、`local_only` 不出设备、`disableCloudMemorySync` + AuditLog、404 → `NOT_FOUND`、幂等重复拉取）** + **mcos-server S1-S13（§3 详述：REST 契约 + Bearer 认证互操作、不透明字节、幂等删除、401/404/405/400、healthz、重启持久化、路径穿越加固）**）、secret 解析器、crash 隔离、插件、多 provider（R1-R8 registry + F1-F6 回退链 + T1-T8 原生工具调用 + O1-O10 本地隐私闸门 + T-transport 7 + **C1-C16 CONSTRAINED：模式选择（TOOL_CALL > CONSTRAINED）、IR `invoke`/`sequence`/`clarify`/`refuse` 解析、畸形→`LLM_PARSE_ERROR`、可重试回退 + 不可重试终止、语法注入（GBNF / JSON Schema 选择）、`parseIrJson` 单元测试** + **G1-G12 GBNF 语法生成：root 枚举目录命令、args 约束（键名/类型/enum/const/嵌套）、step 规则、空目录、共享 JSON 规则、转义** + **P1-P10 GrammarLlmProvider：llama.cpp `grammar` / vLLM `guided_grammar`/`guided_json` 注入、格式不匹配→`CAPABILITY_EXCEEDED`、传输错误映射** + **U1-U13 话语分类（§13.1 路由启发式：EXACT_CLI/KNOWN_RECIPE/PRIVACY_SENSITIVE/COMPLEX/SIMPLE、优先级 EXACT_CLI > KNOWN_RECIPE > PRIVACY > COMPLEX > SIMPLE）** + **R1-R8 RecipeMatcher（精确/归一化/包含匹配、短触发语安全、首中者胜）** + **L1-L11 LATENCY_TIERED 分层路由：EXACT_CLI 解析器直通（零 LLM）、KNOW_RECIPE 本地配方（零 LLM）、SIMPLE 端侧优先（即使 cloud 为主）、COMPLEX 云端优先（opt-in 时）、PRIVACY 强制端侧、快路径失败回落 LLM 链、latencyMs/route 遥测、隐私闸门保留、默认模式向后兼容** + **Q1-Q8 探活策略（§17 V1）：健康结果 TTL 缓存、失败冷却期、探测超时→不健康、并发 vs 串行探测、`healthSnapshot` 快照、`probeAll` 强制刷新**）、Android、**NL→IR 评测套件 3（golden fixtures 良构 + 套件级 100% 结构准确率/0 误执行/0 误拒绝 + 逐用例结构匹配，§16）** + **企业策略（E1-E11 §13 解析/fail-closed/命令与网络名单 glob/forceConfirm + F1-F8b 文件热加载：首载、mtime 重载、解析失败→`FAIL_CLOSED` + SHA-256 事件、版本不支持 fail-closed、缺文件→缓存回退、listener、刷新节流/过期 + N14-N18b egress 企业名单集成 + P21-P26 permission 企业名单集成）** + **信任与签名验证（§7/§6：V1-V16 `ArtifactVerifier` 真实 Ed25519/RSA-PSS-4096 密钥对——通过、篡改→`hash_mismatch`、未知/吊销 key、算法不匹配、错误 key 签名、畸形 base64、blocklist、缓存命中/未命中/拒绝不提升/过期重验 + T1-T12 `PluginTrustGate` 决策矩阵——内置放行、有效签名→`MARKETPLACE_VERIFIED`、无效签名/吊销/blocklist 拒绝、无验证器拒绝、调试侧载放行、生产侧载拒绝、`disableSideload` 阻断侧载但不阻已验证插件、缓存备注 + R22-R24 注册信任级别记录/默认 BUILTIN/UNTRUSTED 拒绝）**。

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

聚合自 [05](./05-workflow.md) §15、[06](./06-agent.md) §17、[07](./07-memory.md) §16、[08](./08-security.md) §17、[09](./09-marketplace.md) §15 中的 "MVP vs V1" 阶段表。P0（现在）是**规范完整、代码缺失**。阶段术语：P1 = MVP、P2 = V1、P3 = V2（[10](./10-roadmap.md) §2.1）。

| Subsystem | P0（仅规范） | P1 MVP | P2 | P3 |
|-----------|----------------|--------|----|----|
| Parser + IR | 规范完成 | ✅ **已实现** 完整 DSL↔IR | — | — |
| Registry + Executor | 规范完成 | ✅ **已实现** | — | — |
| Permission Kernel（`decideConfirmation`） | 规范完成 | 🟡 runtime 已实现；Android 确认 UI 未接线 | — | — |
| ConfirmationPrompt | 规范完成 | ✅ **已实现** NORMAL/ELEVATED | ✅ 破坏性 typed-ack | — |
| Network Egress（`decideEgress`） | 规范完成 | ✅ **已实现** | — | — |
| Prompt Injection 检测 | 规范完成 | ✅ **已实现** 编译器侧 | — | + 自适应模型侧 |
| Rate Limiting | 规范完成 | ✅ **已实现** 每插件/分钟 | ✅ + 每 recipe/小时 | + 自适应 |
| Audit | 规范完成 | ✅ **已实现** 基础 | ✅ **已实现** 加密 + 导出（HMAC 链） | 远程证明 |
| Workflow | 规范完成 | ✅ 顺序 | ✅ **已实现** 并行 / 条件 / 循环 / 重试 / try / 确认 | — |
| Event Bus | 规范完成 | ✅ run 事件通道 | ✅ **已实现** 完整（信封、过滤、隔离、背压） | — |
| Memory | 规范完成 | ✅ profile + remember | ✅ 模糊引用 + 冲突检测 + **情景层（§8）**：`EpisodicMemory` 召回 + **§8.3 命名实体合并/模糊引用（`EntityMatcher`：叶节点匹配 + 别名注册，中英混合查询分词，0.75 §6.0 阈值）** + **§11 同步层（向量时钟 LWW + 策略）已完成** + **§11.0 端到端加密 blob（`MemoryBlobCrypto`：AES-256-GCM + HKDF 派生 + 版本绑定 AAD）** | ✅ **独立 `mcos-server` 部署完成（§6 步骤 22）**：`mcos-server/` 零第三方依赖 JDK `HttpServer` 实现 `SyncBlobTransport` REST 契约 + 强制 Bearer 认证（恒定时间比较）、磁盘持久化原子写入、blobId 白名单防路径穿越、16 MiB 上限、`/healthz`；`JdkSyncBlobTransport` 支持 token；真实 transport 互操作 E2E S1-S13 |
| Planner | 规范完成 | ✅ 1 个 provider，chat→DSL | ✅ 多 provider registry + **探活策略（§17）**：TTL 缓存、失败冷却、探测超时、并发探测、`healthSnapshot`/`probeAll`、Android UI 健康状态行（[06 §17](./06-agent.md)） | 🟡 延迟分层路由（[§13.1](./06-agent.md) 分类器 + 零延迟路径 + 分层链）已实现 |
| Plugins | 规范完成 | ✅ hello + system + camera + files（20+ 命令，[10 §4.3.1](./10-roadmap.md)） | ⬜ IoT + Intent | MCP spike (P2) / MCP 生产 + 市场 (P3) |
| Marketplace | 规范完成 | — | ✅ 信任级别 + 签名验证基础设施（`TrustLevel`/`ArtifactVerifier`/`VerificationCache`/`PluginTrustGate`，[09 §6.2/§6.5](./09-marketplace.md)）；调试侧载（`SIDELOAD_DEBUG`）已落地 | 公共索引 + 端到端安装流程（[09 §15](./09-marketplace.md)） |
| 进程隔离 | 规范完成 | 🟡 尽力而为（进程内） | — | 第三方默认 |
| 企业策略 | 规范完成 | — | — | ✅ **已实现** 允许/拒绝名单（[08 §13](./08-security.md)）：命令/网络名单 + forceConfirm + 企业 kill switch + fail-closed 解析 + 文件热加载 |
| Crash-loop 隔离 | 规范完成 | ⬜ **未实现** | ✅ | ✅ |

---

## 6. 推荐开发路径

步骤 1–7 与 10 已实现（2026-08-12）；8–9 已通过 `AndroidHostServices` 部分接线。

1. ✅ **Gradle 多模块构建**——`mcos-sdk`、`mcos-runtime`、`mcos-android`、4 个插件，按 [REPOSITORIES.md](./REPOSITORIES.md)。
2. ✅ **`DslParser`**——按 [02](./02-command-protocol.md) §6 + §18；§4 中全部 fixture 通过。
3. ✅ **`CommandRegistry`**——从插件加载 `CommandDescriptor`；按 ID 解析。按 [03](./03-runtime.md) §6。
4. ✅ **`Executor`**——用校验过的参数调用 `CommandHandler`；异常映射为 `PLUGIN_ERROR`。按 [03](./03-runtime.md) §9。
5. ✅ **Schema 校验**——执行前按 `inputSchema` 校验参数。按 [02](./02-command-protocol.md) §9.1。
6. ✅ **`PermissionKernel`**——按 `sideEffectClass` 实现 grant/deny/confirm 流程。按 [08](./08-security.md) §4。
7. ✅ **Audit（基础 + HMAC 链）**——本地追加 run 记录。按 [03](./03-runtime.md) §13。
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

**下一步（建议）：** 插件加载器与市场客户端（[09 §15](./09-marketplace.md)）——`PluginLoader` 动态加载 `.mcos` 包（先过 `PluginTrustGate` 再入注册表）、市场索引客户端（`MarketplaceIndex`：包元数据 + 公钥注册表拉取与缓存）、公共签名密钥引导与轮换（§6.3）、Android 侧 `disableSideload` 在安装流程落地。

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
