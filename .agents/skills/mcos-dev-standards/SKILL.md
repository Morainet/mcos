---
name: mcos-dev-standards
description: |
  MCOS (Mobile Command OS) 项目开发规范。编写或修改任何 MCOS 模块代码、Gradle 构建脚本、
  新增模块或依赖时必须遵循。触发场景:改动 mcos-sdk / mcos-security / mcos-runtime-core /
  mcos-llm / mcos-marketplace / mcos-runtime / plugins/* / mcos-android / mcos-server 下的
  Kotlin 源码,调整模块间依赖,或排查编译错误。目的是在编码阶段避免常见编译错误,
  确保代码一次通过全量验证。
---

# MCOS 开发规范

## 概述

本技能定义 MCOS 项目的编码规范与常见错误避免规则。修改任何模块代码前,逐条检查以下规则。
本文件是**唯一权威版本**,`.zcode/skills/`、`.codebuddy/skills/` 等平台目录下的同名技能
均为指向本目录的符号链接,不要直接编辑那些副本。

## 项目环境

| 项目 | 版本 |
|------|------|
| JDK | 17 (jvmToolchain 17) |
| Kotlin | 2.0.21 |
| AGP | 8.7.3 |
| Android compileSdk / minSdk | 35 / 26 |
| Compose BOM | 2024.12.01 |
| Gradle | 8.10 |
| kotlinx.serialization | 1.7.3 |
| kotlinx.coroutines | 1.9.0 |

注意:本仓库的 `gradlew` 无执行位,统一用 `sh gradlew <task>`。

## 模块拓扑(按当前 build 实测)

```
mcos-sdk              叶子模块,无内部依赖
 └─ mcos-security         依赖 sdk
     └─ mcos-runtime-core     依赖 sdk、security
         ├─ mcos-marketplace      依赖 sdk、security、core
         ├─ mcos-runtime (facade) 依赖 sdk、security、core、marketplace
         ├─ mcos-llm             依赖 sdk、core(经 core.api 的 RuntimeGateway 端口驱动内核,不依赖门面)
         └─ plugins/*                各插件只依赖 sdk
mcos-android-sdk      依赖 sdk、security、core、facade、llm、marketplace、内置插件（无 UI 库,禁止 Compose/ViewModel 依赖）
mcos-android          依赖 android-sdk + sdk、security、core、facade、llm、marketplace、plugin-mcp（Compose 演示壳,包 …android.demo）
mcos-server           依赖 core（仅 test）
```

**包命名规则(模块一一对齐,由 PackageBoundariesTest 强制):**
- `com.morainet.mcos.sdk` — SDK 契约层
- `com.morainet.mcos.security.*` — 安全内核、限流、出网策略、审计、权限、校验（mcos-security）
- `com.morainet.mcos.runtime.core.*` — core 子系统 parse/ir/error/events/registry/plugin/memory/executor/workflow,及 `core.api`（RuntimeTypes、StubHostServices,mcos-runtime-core）
- `com.morainet.mcos.runtime` / `com.morainet.mcos.runtime.api` — 仅门面（McosRuntime,mcos-runtime）
- `com.morainet.mcos.llm.*` — LLM 网关与编排
- `com.morainet.mcos.marketplace.*` — 配方商店、遥测
- `com.morainet.mcos.plugin.<name>` — 插件模块
- `com.morainet.mcos.android`（含 `host`、`host.isolation` 隔离 RPC 纯层）— Android 宿主 SDK（mcos-android-sdk）
- `com.morainet.mcos.android.demo` — Compose 演示壳（mcos-android）
- `com.morainet.mcos.server` — blob/索引服务
- 跨模块 split-package 禁止;`runtime.` 前缀仅属运行时内核两模块;新模块必须显式注册根包

## 规则 1: kotlinx.serialization.json 扩展属性导入

`jsonObject` / `jsonPrimitive` / `jsonArray` 是 `JsonElement` 的扩展属性,通配符导入
`kotlinx.serialization.json.*` 不保证覆盖。使用处必须显式导入:

```kotlin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
```

## 规则 2: Material3 实验性 API 注解

Compose BOM 2024.12.01 中 `TopAppBar`、`TopAppBarDefaults` 等标记为
`@ExperimentalMaterial3Api`。使用处需 `@OptIn(ExperimentalMaterial3Api::class)` 并导入
`androidx.compose.material3.ExperimentalMaterial3Api`。

## 规则 3: 未使用导入清理

提交前移除所有未使用导入(尤其 `@ExperimentalSerializationApi`、迁移后遗留的
`JsonNamingStrategy`)。IDE "Optimize Imports" 或手动检查。

## 规则 4: 模块依赖与 api/implementation

依赖方向必须与上方拓扑一致,不允许新增环(尤其 llm→facade 是唯一合法反向边)。
公开签名(公开类/函数/属性的类型、公开函数返回类型)出现某依赖的类型才用 `api`,
否则 `implementation`。模板:

```kotlin
// mcos-runtime-core/build.gradle.kts
dependencies {
    api(project(":mcos-sdk"))
    api(project(":mcos-security"))
    api(libs.kotlinx.coroutines.core)      // Flow 出现在公开 API
    api(libs.kotlinx.serialization.json)   // JsonObject 出现在公开 API
}
```

## 规则 5: Kotlin 代码风格

`kotlin.code.style=official`;4 空格缩进;类名 PascalCase、函数名 camelCase、
常量 UPPER_SNAKE_CASE;行宽 120;冒号前空格(`val x: Int = 0`)。
测试代码镜像源码包结构(`src/test/kotlin/` 对应 `src/main/kotlin/`)。

## 规则 6: 命名规范

- 命令 ID:`<domain>.<action>` 小写点分隔,如 `sys.notify`、`camera.capture`、`photo.compress`。
  **文档、示例、默认 DSL 中引用的命令 ID 必须与插件 `CommandDescriptor` 实际注册的一致**
  (例:是 `sys.clipboard` 不是 `sys.clipboard.copy`;`sys.notify` 必填 `text` 参数,没有 `body`)。
- 关闭安全特性的空对象命名可 grep:`Permissive*` / `AllowAll*` / `DenyAll*` / `Trusting*` /
  `Noop*` / `Null*` 前缀(如 `SecurityConfig.permissive()` 的各成员)。
- 安全豁免必须显式写出 `SecurityConfig.permissive()`,禁止裸构造绕过。

## 规则 7: 提交前验证命令

```bash
# 各模块单测
sh gradlew :mcos-sdk:test :mcos-security:test :mcos-runtime-core:test \
          :mcos-llm:test :mcos-marketplace:test :mcos-runtime:test

# Android 改动
sh gradlew :mcos-android-sdk:testDebugUnitTest :mcos-android:testDebugUnitTest :mcos-android:assembleDebug

# 全量门禁
sh gradlew build
```

## 规则 8: internal 可见性是模块级

Gradle 拆分后 `internal` 从"包内可见"变为"模块内可见"。跨模块引用 internal 成员会编译失败;
确需跨模块时上移为 public,或把成员移入同模块。

## 规则 9: EventBus 每运行隔离通道

`TypedEventBus.observe(runId)` 返回**该 run 独立**的流:terminal 事件
(`RunSucceeded`/`RunFailed`/`RunCancelled`)后流完成。实现要点:

- 用 `transformWhile { emit(it); !isTerminal(it) }`,**不是** `takeWhile`(后者丢边界事件);
- 已结束且被逐出的 run 返回 `emptyFlow()`(tombstone),未知 runId 用 `computeIfAbsent`
  等首个事件(execute() 返回 handle 早于 RunStarted 发布,早订阅者必须能等到事件);
- 回放上限 RUN_REPLAY=512,已完成 run 保留 128 个,被逐出 runId tombstone 保留 512 个。

## 规则 10: 引用类必须先 import,禁止调用处内联全限定名

使用其他包的类(含伴生对象成员、嵌套类、顶层函数)时,必须先 `import` 再用短名:

```kotlin
// ✗ 禁止:调用处写全限定名
val r = com.morainet.mcos.sdk.ResolveResult.NotFound("cmd")

// ✓ 正确
import com.morainet.mcos.sdk.ResolveResult
val r = ResolveResult.NotFound("cmd")
```

- 适用所有模块 Kotlin 源码(main + test),含 `java.*`/`javax.*`/`kotlinx.*`/`androidx.*`
  (例:`java.security.MessageDigest.getInstance` 必须先 import)。
- 同名遮蔽先用显式 import 解决:Kotlin 显式 import 优先级**高于本包声明**,只有
  通配符 import 才会被本包同名类遮蔽(即把 `import x.y.Z` 写全即可覆盖同包 `Z`)。
- 同文件确需**两个同短名**类时,用 import 别名(`import a.b.C as AliasedC`),
  两侧各用各的短名,同样不留全限定(例:`CommandRegistryTest` 的
  `SdkResolveResult`、`AndroidHostServices` 的 `PlatformWifiInfo`)。
- 别名仍不可行时(三个以上同短名、或别名撞本地声明)才允许保留全限定引用,
  并加行内注释说明缘由——目前仓库应为 **0 处**。
- 新增 import 后同步清理未使用导入(规则 3)。

## 常见错误速查表

| 错误信息关键词 | 原因 | 解决 |
|---|---|---|
| `Unresolved reference 'jsonObject'` | 缺扩展属性显式导入 | `import kotlinx.serialization.json.jsonObject` |
| `This material API is experimental` | 缺 OptIn 注解 | `@OptIn(ExperimentalMaterial3Api::class)` |
| `Unresolved reference` + internal 成员 | 跨模块访问 internal | 上移 public 或移入同模块(规则 8) |
| 观察者收不到事件/挂死 | observe 时机或 terminal 语义 | 检查规则 9;订阅应在 execute() 返回后立即进行 |
| `Cannot access 'X': it is internal` | api/implementation 错配 | 公开签名类型所在依赖改 `api`(规则 4) |
| 测试超时 (CI 挂死类) | Flow 未完成 | 确认 terminal 事件会完成流,勿用 takeWhile |

## 新模块添加流程

1. `settings.gradle.kts` 中 `include(":xxx")`;
2. 创建 `xxx/build.gradle.kts`,kotlin-jvm + jvmToolchain(17),依赖遵守规则 4 方向;
3. 包名 `com.mcos.<xxx>`,测试镜像包结构;
4. 编译验证:`sh gradlew :xxx:compileKotlin`;
5. Android/Server 集成:在各自 `build.gradle.kts` 加依赖(通常只指 facade);
6. 若新增"关闭安全"路径,必须落在可 grep 的空对象命名上(规则 6)。

## 权威参考

- `references/api_reference.md` — kotlinx.serialization / Material3 / SDK 核心类型速查
- `docs/zh/01-architecture.md` §3 — 包→模块对照与模块边界规则
- `docs/zh/03-runtime.md` §4.2 — 运行时事件通道保证
- `.github/workflows/ci.yml` — CI 分片与门禁命令
