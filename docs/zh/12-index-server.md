# MCOS 公共索引服务端 —— REST 契约与运营手册

> **状态：** P3 遗留收口 —— 索引托管（10-roadmap §6、09-marketplace §11.5）。
> 实现状态注（随代码落地维护）：机器可校验的契约是 `mcos-index-server/src/test` 中的可执行互操作套件
> （真服务端 + 真实 `MarketplaceIndex`/`JdkMarketplaceHttpTransport` 客户端），以及驱动共享评审门禁引擎的
> `mcos-conformance` "market" 套件（[11-implementation-status.md](./11-implementation-status.md) item 52）。
> OpenAPI 3.1 `api/openapi.yaml` 镜像（09 §11.5）推迟到端点面稳定后；JVM 互操作测试即活契约。

## 1. 目的

公共索引服务端是 P3 生态市场在服务端的落点。它承载：**索引**（可搜索的包/配方元数据）、
**发布者评审管线**（提交 → CI 门禁 1–11 → 决策）、**密钥注册表** 与 **签名黑名单**（每个客户端拉取）。

本文档是 `mcos-index-server`（本仓库，零三方运行时依赖）实现、`MarketplaceIndex`（已交付客户端，
`mcos-marketplace`）对接的契约。它镜像 [09-marketplace.md](./09-marketplace.md) §5/§6/§11/§14 的规范面，
并补齐服务端专属缺口（注册表持久化、管理 API、密钥轮换 runbook）。

> **与 blob 同步服务端的关系：** `mcos-server` 实现 *记忆同步* 端点（07 §11）。索引服务端是独立进程/角色；
> 二者共享自托管姿态（零三方依赖、`com.sun.net.httpserver`、token 鉴权、反向代理 TLS），作为独立服务分别运行。

## 2. 范围与诚实边界（MVP）

范围内：

- 只读发现索引（`/v1/plugins*`、`/v1/recipes*`、`/v1/publishers/{id}`）。
- 发布者密钥注册 / 轮换 / 紧急撤销（09 §6.1、§6.3）。
- 提交评审管线：上传 → CI 门禁 1–11（09 §5.1）→ 决策。
- 签名黑名单分发（09 §14.3）与已撤销密钥分发（`/v1/keys/revoked`）。
- 匿名、opt-in 的安装遥测聚合（09 §11.3）与滥用举报（09 §14.1）。
- 面向市场运营方的管理 API（决策、黑名单、下架）。

范围外（V1+，诚实边界 —— 不提供伪造占位端点）：

- 透明日志（`/v1/transparency/sth`，09 §6.4）—— MVP 信任运营方。
- 完整人审工作台 UI；MVP 只暴露 *决策* API 供外部评审工具使用。需要人审的提交停在
  `HUMAN_REVIEW`，等待运营决策。
- 付费插件、自助申诉（`POST /v1/appeals` 在 MVP 返回 405）。
- 自动 AV 扫描 *引擎*：服务端提供**可插拔扫描器接缝**与哈希黑名单扫描器。接入真实引擎（如 ClamAV）
  是部署动作（运营手册 §8.3）；未配置引擎时 gate 9 报 `UNSCANNED`（警告，提交转入人审，见 §5.4）。

## 3. 注册表数据模型与持久化

注册表是 `--data-dir`（默认 `data/index`）下的一组 JSON 文档。所有写操作原子化（临时文件 + rename）
并在进程内单写锁下进行。服务端绝不在内存中先行变更：每次变更先落盘再对外服务新状态。

```
{data-dir}/
  registry.json          # publishers、keys、packages、submissions（完整 facts）
  recipes.json           # 已发布工作流配方（签名信封）
  blocklist.json         # 条目 + 签名 + 文档版本
  revoked-keys.json      # GET /v1/keys/revoked 服务的 PublisherKey 列表
  telemetry.ndjson       # 追加式 opt-in 安装事件
  reports.ndjson         # 追加式滥用举报
  audit.ndjson           # 追加式运营/提交审计
```

注册表 facts 比公开的 `PackageMetadata`（渲染视图）更丰富：已批准提交保留完整 `PluginManifest`
facts（命令 id+版本、locale、副作用类、命名空间根），使门禁 5/10/11 具备 first-published 历史、
SemVer 耦合与单调性输入。

种子数据：首次以空注册表启动时，服务端初始化 `keys/` 种子目录中列出的发布者（见运营 §8.2 —
首个 Ed25519 锚如何托管：运营方将客户端已 pin 的同一公钥放入该目录）。

## 4. 鉴权模型

三类凭据，全部常量时间比较。

| 类别 | 凭据 | 服务范围 | 请求头 |
|-------|-----------|--------|--------|
| 公开 | 无 | 全部 `GET` 索引端点、`/v1/reports` | — |
| 发布者 | 发布者 token（服务端签发，存 HMAC-SHA256） | 自身密钥 + 提交端点 | `Authorization: Bearer <publisherToken>` |
| 运营 | 管理 token（`MCOS_INDEX_ADMIN_TOKEN`） | `/v1/admin/*` | `Authorization: Bearer <adminToken>` |

Token 签发是运营动作（§8.1）。服务端不存明文发布者 token，存 `sha256(token)` 并比较哈希。
封禁发布者 = 删除其注册表记录（密钥进入 `revoked-keys.json` 的 `REVOKED`）。

## 5. 规范性端点

所有响应为 JSON（UTF-8）。错误使用 [09-marketplace.md](./09-marketplace.md) §11.4 信封。
HTTP 状态与 `code` 字符串对齐该表（`SCHEMA_VIOLATION`、`UNAUTHENTICATED`、`PERMISSION_DENIED`、
`NOT_FOUND`、`ALREADY_EXISTS`、`RATE_LIMITED`、`INTERNAL`）。

### 5.1 读侧（公开）

| 方法 | 路径 | 行为 |
|--------|------|-----------|
| `GET` | `/v1/plugins` | 搜索/列表。参数按 09 §11.1：`query`、`category`（`iot`,`media`,`productivity`,`developer`,`mcp`,`home`,`system`）、`sort`（`relevance`/`safety`/`popularity`/`newest`）、`page` ≥1、`pageSize` 1–100、`minRuntimeVersion`（SemVer 过滤）。返回 `SearchResponse`。`relevance` 用 09 §9.1 复合排序（服务端镜像 `SearchRanking`）。 |
| `GET` | `/v1/plugins/{packageId}` | 最新已批准 `PackageMetadata`，否则 404 `NOT_FOUND`。 |
| `GET` | `/v1/plugins/{packageId}/versions` | `List<PackageMetadata>`，新→旧。 |
| `GET` | `/v1/plugins/{packageId}/versions/{version}` | 指定版本元数据，或 404。 |
| `GET` | `/v1/plugins/{packageId}/artifact` | 302 重定向到批准时记录的 CDN 下载 URL。 |
| `GET` | `/v1/plugins/by-command/{commandId}` | 提供该命令的 `List<PackageMetadata>`（推荐用，09 §9.2）；无则空列表，**非** 404（客户端将 404 也视为空）。 |
| `GET` | `/v1/recipes` | 搜索配方（`query`,`category`,`page`,`pageSize`）→ `RecipeSearchResponse`，仅 LISTED。 |
| `GET` | `/v1/recipes/{recipeId}` | 签名 `RecipeEnvelope` 或 404。 |
| `GET` | `/v1/blocklist` | 签名黑名单（见 §7）。`Cache-Control` TTL 1h。 |
| `GET` | `/v1/keys/revoked` | `List<PublisherKey>`（`status: REVOKED`）。 |
| `GET` | `/v1/publishers/{id}` | 发布者主页：id、名称、已发布包（各自最新元数据），不含密钥。 |

只有 `LISTED`（历史术语"published"）状态的包出现在读侧。`CI_REJECTED`/`HUMAN_REVIEW`/`UNLISTED`/
`REVOKED` 永不在此出现。

**`sort=safety`/`sort=popularity` 字段**（`safetyScore`、`downloadCount`）在批准时由服务端计算，
不来自提交（09 §11.2）。

### 5.2 写侧 —— 发布者

端点限定 `{id}`；token 必须属于该发布者，否则 `PERMISSION_DENIED`。

| 方法 | 路径 | 行为 |
|--------|------|-----------|
| `POST` | `/v1/publishers/{id}/keys` | 注册签名密钥。Body：无 `status` 的 `PublisherKey`（服务端置 `ACTIVE`）。校验：keyId 唯一、算法 ∈ {`Ed25519`, `RSA-PSS-4096`}、指纹一致（服务端重算 `publicKeyEncoded` 的 SHA-256，不一致即拒 —— 指纹不可自声明）。重复 keyId → `409 ALREADY_EXISTS`。 |
| `DELETE` | `/v1/publishers/{id}/keys/{keyId}` | **常规轮换**：旧密钥 → `REVOKED`，写入 `revoked-keys.json`（含 `rotatedFrom` 历史）；gate 8 只对 `ACTIVE` 密钥放行。旧密钥宽限期是客户端 TTL 政策（09 §6.3）。 |
| `POST` | `/v1/publishers/{id}/plugins` | 提交新版本。`multipart/form-data`：`artifact`（二进制 `.mcos`/`.aar`）、`metadata`（JSON `PackageMetadata`，不含 `downloadCount`/`safetyScore`）、`signature`（JSON `{ "signingKeyId", "algorithm", "signature" }`，base64）。同步跑 CI 门禁（09 §5.1 SLA 上限 15 分钟；MVP 远低于 1 分钟）并返回 `{ submissionId, state, submittedAt, reviewReport }`，其中 `reviewReport` 是 §5.4 形状的门禁报告。状态：`CI_REJECTED`（任一 error）、`HUMAN_REVIEW`（仅 warning/触发）、`APPROVED`（全绿无触发）。 |
| `GET` | `/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}` | 轮询提交：当前状态 + 门禁报告 + 决策时间戳。 |
| `POST` | `/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}/publish` | 将 `APPROVED` 提交发布 → `LISTED`（09 §5.0 "publisher publishes"）。非 `APPROVED` 状态返回 `409`。 |

**提交冲突规则（02 §4.4 / 09 §5.1 gate 3 + 10）：**

- 对已有**更新**已批准版本的 `packageId` 提交 → `409 ALREADY_EXISTS`，除非 SemVer 更高（更新路径）。
- 包内重复 `commandId`：解析层拒绝（gate 3）。
- 命令 id 已被其他已批准包占用：gate 10 error，first-published 胜出（除非该包声明对占位包的依赖 —
  自有命令仲裁，见 §5.4 gate 10）。

**批准语义。** `APPROVED` 尚不可读侧可见：一次 `publish` 调用使状态 `APPROVED → LISTED`
（09 §5.0）。`HUMAN_REVIEW` 等待运营 *approve*（或 *reject*）决策（§5.4）；运营亦可代发布者 publish。

### 5.3 客户端/设备写侧

| 方法 | 路径 | 行为 |
|--------|------|-----------|
| `POST` | `/v1/reports` | 滥用举报（09 §14.1）。Body 按 `PluginReportRequest`。返回 `ReportAck { reportId }`。追加存储；7 天内同一包 ≥3 次举报会标记该包（审计行 + 对运营可见）。举报对发布者保密。 |
| `POST` | `/v1/telemetry/install` | Opt-in 安装事件（09 §11.3），body 按 `InstallTelemetryEvent`。追加到 `telemetry.ndjson`；仅用于重算 `downloadCount`/热度。`anonymizedClientId` 非 SHA-256 hex 形状的事件记日志并丢弃（隐私加固）。 |

### 5.4 管理侧 —— 运营（`/v1/admin/*`）

| 方法 | 路径 | 行为 |
|--------|------|-----------|
| `POST` | `/v1/admin/plugins/{packageId}/submissions/{submissionId}/approve` | `HUMAN_REVIEW` → `APPROVED`（评审决策；记录评审人 + 备注）。对 `APPROVED` 幂等无操作。 |
| `POST` | `/v1/admin/plugins/{packageId}/submissions/{submissionId}/reject` | → `REJECTED`（人审拒绝，可申诉）。 |
| `POST` | `/v1/admin/plugins/{packageId}/publish` | 运营代发布者发布：最新 `APPROVED` 提交 → `LISTED`。 |
| `POST` | `/v1/admin/plugins/{packageId}/unlist` | → `UNLISTED`（滥用调查）；再次发布最新已批准提交可逆。 |
| `POST` | `/v1/admin/plugins/{packageId}/revoke` | → `REVOKED`，终态；追加 `BlocklistEntry(packageId, "*", PUBLISHER_BANNED | POLICY_VIOLATION | SECURITY_VULNERABILITY, …)`（原因由运营选择），重签并升版本。 |
| `POST` | `/v1/admin/blocklist/entries` | 增删 `BlocklistEntry`（`MALWARE`、`LEGAL_TAKEDOWN` 等），升文档 `version`（单调），重签并写 `blocklist.json`。 |
| `POST` | `/v1/admin/keys/{keyId}/emergency-revoke` | 紧急撤销（泄密，09 §6.3）：密钥 → `REVOKED` 并追加到 `revoked-keys.json`。 |
| `GET` | `/v1/admin/registry` | 完整注册表快照（带 facts 的包、提交、发布者）—— 备份与（未来）评审工作台用。 |
| `GET` | `/v1/admin/submissions?state=…` | 按状态过滤的提交队列（运营评审收件箱）。 |

每个运营动作写一行 `audit.ndjson`：`{ at, actor, action, target, detail }`。审计只追加、随备份保留，
运营无法改写历史。

## 6. 门禁管线（CI 门禁 1–11）

管线与 `mcos-conformance` "market" 套件驱动的是**同一引擎**（`mcos-marketplace` review 包），
因此"本地验证通过 → CI 通过"（09 §5.1）是结构性保证而非口号。

| 门禁 | 检查 | 实现 | 失败模式 |
|------|-------|----------------|--------------|
| 1 | Manifest 按 01 §10 JSON Schema 可解析 | `McosPackage.readPluginManifest`（zip `PK` 门、必填字段、sideEffectClass 枚举） | error → `CI_REJECTED` |
| 2 | 保留命名空间 `mcos.`/`sys.`/`mcp.`/`std.` | 引擎 `NamespaceEnforcer`（共享） | error → `CI_REJECTED` |
| 3 | 重复命令 id（包内 + 跨依赖） | `readPluginManifest`（包内 fail-closed）+ 依赖扫描 | error → `CI_REJECTED` |
| 4 | sideEffectClass 诚实启发式（04 §13.2 check 4） | 引擎启发式（静态 manifest facts） | **仅 warning** → 转 `HUMAN_REVIEW` |
| 5 | SemVer：正则 + 命令版本耦合 + 单调性（04 §13.1） | 引擎（`VersionRange`、注册表前版 facts） | error → `CI_REJECTED` |
| 6 | i18n 完整性（04 §12.1） | 引擎 locale 检查 | error → `CI_REJECTED` |
| 7 | 秘密包含 —— artifact 字节中无 `{{secret.*}}`/`x-mcos-secret` | 引擎 artifact 扫描 | error → `CI_REJECTED` |
| 8 | 对注册 ACTIVE 密钥的签名验证 | `ArtifactVerifier`（security） | error → `CI_REJECTED` |
| 9 | 恶意软件扫描 | 可插拔 `AvScanner`（内置哈希黑名单扫描器；引擎接缝留给真实 AV） | MALICIOUS → error + 人审标记；UNSCANNED → warning（未配置引擎） |
| 10 | 命名空间/命令仲裁 vs 已批准注册表 | 引擎（`registryState.existingCommandIds`） | error → `CI_REJECTED`（first-published 胜出） |
| 11 | `minRuntimeVersion` ≤ 当前运行时 + 对前版单调 | 引擎（注册表前版 facts） | error → `CI_REJECTED`（面向未来运行时） |

**诚实边界措辞**（item 51 交接）：门禁 1/2/3/7 已有本地 conformance 覆盖（`manifest` 套件），
gate 8 有 `trust` 套件，gate 5 的 SemVer 原语在 `VersionRange`。本工作（item 52）把
**marketplace-only** 的门禁逻辑（4/9/10/11 + 全报告组合）落为 `mcos-marketplace` 的*共享生产引擎*，
新增驱动它的 conformance `market` 套件，并交付运行同一引擎的索引服务端。

**报告形状。** 门禁输出即 [09-marketplace.md](./09-marketplace.md) §5.4 JSON：
`{ overall, checks: [{ gate, rule, status, severity, message, location }] }`。
`overall`：`CI_REJECTED`（任一 `severity: "error"`）、`HUMAN_REVIEW`（仅 warning）、`APPROVED`（全绿）。
Warning 从不阻塞但升级处理（09 §5.2）。

## 7. 黑名单签名契约

`GET /v1/blocklist` 返回的文档签名由客户端用 `TrustAnchors` 内置（指纹 pin）的知名市场公钥验证。
因此签名必须精确复现客户端验证的字节：

1. 规范载荷 = 置 `signature = null` 的 `Blocklist` 文档，按 `MarketplaceIndex.fetchBlocklist`
   验签前的方式序列化：`Json { ignoreUnknownKeys = true; explicitNulls = false }`，
   `encodeDefaults = false`（kotlinx 默认），字段序 = 声明序。`MarketplaceIndex` 构造的就是
   完全相同的序列化器实例（`blocklist.copy(signature = null)`）。
2. 服务端用运营 Ed25519（或 RSA-PSS-4096）私钥对规范字节签名，base64 写入 `signature`。
3. 文档 `version` 是单调字符串（每次变更计数递增）。
4. 每次黑名单变更（admin 增删、撤销）都重签并升版本。

更换签名密钥的运营方必须用同一规范编码例程重签；互操作测试通过真实客户端拉取黑名单并断言
`BlocklistVerifier.verify` 成功来 pin 住这一点。

## 8. 运营手册

### 8.1 运行

```bash
# dev / CI
MCOS_INDEX_ADMIN_TOKEN=ops-secret sh gradlew :mcos-index-server:run \
  --args="--port 8877 --data-dir ./data/index --keys-dir ./data/index/keys"

# production-style
sh gradlew :mcos-index-server:installDist
./mcos-index-server/build/install/mcos-index-server/bin/mcos-index-server \
  --port 8877 --data-dir /var/lib/mcos-index --keys-dir /var/lib/mcos-index/keys \
  --admin-token "$(cat /run/secrets/mcos-admin-token)"
```

| 选项 | 默认 | 说明 |
|--------|---------|-------------|
| `--port` | `8877` | HTTP 端口。 |
| `--data-dir` | `data/index` | 注册表持久化目录（不存在则创建）。 |
| `--keys-dir` | `<data-dir>/keys` | 初始发布者/运营公钥种子目录（`.pem`/`.json`）。 |
| `--admin-token` | env `MCOS_INDEX_ADMIN_TOKEN` | 运营 token；为空拒绝启动（与 `mcos-server` 一致）。 |

TLS 在反向代理（Caddy/nginx）终结；Bearer token 保护 API，TLS 保护 token 传输。

### 8.2 启动与发布者入驻

1. 运营以 `--keys-dir` 启动服务端，目录内放市场/运营 Ed25519 **公钥**（即客户端 `TrustAnchors`
   pin 的那把）。
2. 运营签发发布者 id + token（admin 动作，记审计），线下分发。
3. 发布者经 `POST /v1/publishers/{id}/keys` 注册签名密钥；其公钥是客户端将缓存的密钥（09 §6.2 步 2）。
4. 发布者先本地跑 `:mcos-conformance:conformance`，再提交。

### 8.3 AV 引擎接线（gate 9）

内置扫描器基于哈希黑名单：`{data-dir}/av-denylist.txt` 每行一个 `sha256` hex → `MALICIOUS`。
需要真实扫描的部署在 `AvScanner` 接缝后放脚本/二进制（env `MCOS_AV_SCANNER_CMD`）：扫描进程从
stdin 收 artifact 路径，打印 `CLEAN`/`MALICIOUS`。无引擎且黑名单无命中时 gate 9 报 `UNSCANNED`
（warning），提交转入人审 —— MVP 绝不假装做过未执行的扫描。

### 8.4 密钥轮换 runbook

源自 09 §6.3。两种场景：

**常规轮换（发布者发起）。** 宽限期让已装插件在其客户端撤销 TTL（7 天）内继续用缓存的旧密钥加载——
轮换本身不强制禁用任何东西。

```bash
# 1. 发布者生成新密钥对（Ed25519，PKCS#8/X.509）
# 2. 先注册新密钥（与旧密钥并存 ACTIVE）：
curl -X POST https://market.example/v1/publishers/acme/keys \
  -H "Authorization: Bearer $PUB_TOKEN" \
  -d '{"keyId":"key_2026_09","publisherId":"acme",
       "publicKeyFingerprint":"<sha256 hex>","algorithm":"Ed25519",
       "publicKeyEncoded":"<base64 X.509>","createdAt":"2026-09-04T00:00:00Z"}'
# 3. 用新密钥签下一个版本并提交。
# 4. 全部已发布版本重签（或满 90 天）后，把旧密钥轮换为 REVOKED：
curl -X DELETE https://market.example/v1/publishers/acme/keys/key_2026_01 \
  -H "Authorization: Bearer $PUB_TOKEN"
#    旧密钥现在出现在 GET /v1/keys/revoked，带 rotatedFrom 历史。
```

**紧急撤销（泄密或封禁）。** 仅运营；客户端下次轮询黑名单/revoked-keys（1h TTL）立即可见，
并强制禁用匹配插件（09 §14.4）。

```bash
curl -X POST https://market.example/v1/admin/keys/key_2026_01/emergency-revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"reason":"key compromise reported"}'
# 若直接封禁发布者：撤销其全部密钥并黑名单全部包：
curl -X POST https://market.example/v1/admin/plugins/acme.telemetry/revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"reason":"PUBLISHER_BANNED","detailUrl":"https://status.example/post-42"}'
```

运营密钥本身：Ed25519 生成后私钥离线保存（HSM/密钥库），公钥经 `--keys-dir` 发布。运营密钥轮换
= 需要所有已装客户端更新锚 —— 是协调的客户端发布，而非服务端动作。

### 8.5 备份与恢复

停机 → tar `{data-dir}` → 在恢复副本上用 `GET /v1/admin/registry` 校验。恢复是整体文件恢复；
`audit.ndjson`、`telemetry.ndjson` 随同恢复。黑名单文档是派生状态 —— 恢复后服务端按配置的运营
密钥从 `blocklist.json` 条目重新签名。

## 9. 互操作与测试矩阵

`mcos-index-server/src/test` 在临时端口启动服务端，用**真实已交付客户端**
（`MarketplaceIndex` + `JdkMarketplaceHttpTransport`）驱动：

| 领域 | 覆盖 |
|------|---------|
| 发现 | search（query/category/sort/minRuntimeVersion/分页）、by-command、包获取（含 404→null）、versions、配方搜索/获取 |
| 信任端点 | 黑名单签名经真实 `BlocklistVerifier` 通过；revoked-keys 往返 |
| 举报/遥测 | POST 接受；遥测隐私校验（非 SHA256 id 拒绝） |
| 发布 | 注册密钥（指纹不一致拒绝）、提交 → 合成包的门禁判定（全绿 → APPROVED；保留命名空间 → gate 2；重复命令 → gate 3；未来 `minRuntimeVersion` → gate 11；AV 黑名单命中 → gate 9）、轮询提交 |
| 管理 | publish 决策 → 读侧可见；unlist → 消失；revoke → 黑名单条目 + 客户端见 blocklisted |
| 鉴权 | 缺失/错误 token → 401/403；管理端点拒绝发布者 token |

`mcos-conformance` "market" 套件无服务端驱动同一引擎（作者侧），本地作者验证与 CI 共享一份门禁实现。

## 10. 关联

- 市场规范： [09-marketplace.md](./09-marketplace.md) §5/§6/§11/§14。
- 客户端模块：`mcos-marketplace`（`MarketplaceIndex`、`BlocklistVerifier`）。
- 设备信任锚：`mcos-android-sdk` 的 `TrustAnchors`。
- 实现状态：[11-implementation-status.md](./11-implementation-status.md)。
