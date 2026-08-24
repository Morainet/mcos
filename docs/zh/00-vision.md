# MCOS 愿景

> **语言:** [English](../en/00-vision.md) · 中文（当前）

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-24  
> **Audience:** 贡献者、合作伙伴，以及任何正在评估 Mobile Command OS 作为基础设施的人

---

## 1. 一句话定义

**Mobile Command OS (MCOS)** 把手机通过合作 API 暴露的能力——系统 API、合作应用（通过 App Functions / Intent）、IoT 设备和远程工具——都转化成统一的、AI 可调用的**命令（Command）**。

```text
Natural language  →  Command DSL  →  Runtime  →  Plugins / System / MCP
```

MCOS 不是带快捷方式的聊天机器人。  
它是一条**移动命令总线（Mobile Command Bus）**：填补 LLM 规划器（Planner）与真实移动世界之间缺失的那一层。

---

## 2. 为什么需要它

### 2.1 当前的工具总线格局

| 层 | 例子 | 它统一的是什么 |
|-------|---------|-----------------|
| 代码工具 | Claude Code, Gemini CLI | 开发者机器动作 |
| 工具协议 | MCP (Model Context Protocol) | 桌面 / 服务器工具服务器 |
| 应用能力 | Android App Functions | 应用内可调用函数 |
| 移动命令（OS 集成） | Google App Functions + Gemini；Apple App Intents + Apple Intelligence | OS 级命令总线——但各自锁定自身生态 |
| 移动命令（开放标准） | **MCOS（本项目）** | 开放协议 + 可换模型 + 跨厂商插件生态 |

业界已经拥有：

- **Claude Code** ≈ 代码命令总线  
- **Gemini CLI** ≈ AI 命令总线  
- **MCP** ≈ 工具总线  
- **Android App Functions** ≈ 应用能力总线  
- **Google（App Functions + Gemini）**/ **Apple（App Intents + Apple Intelligence）** ≈ OS 集成的移动命令总线——但封闭在各自平台内

仍然缺失的是：

> **一个开放的、模型无关的移动命令总线标准**——一个稳定的协议与运行时，使 AI 能够安全地驱动*任意*手机（而非某一厂商的生态），模型可替换、插件生态跨平台，就像 MCP 标准化了桌面 / 服务端工具那样。

**诚实承认：** Google 与 Apple 正在建 OS 级命令总线，拥有 MCOS 无法匹敌的结构性优势——预装分发、OS 级权限、以及强制应用厂商合作的能力。MCOS 是一个沙箱内的第三方应用。押注不是"我们能在 OS 厂商的主场赢过他们"；押注是**一个开放的、跨平台、模型可替换的标准**是与锁定生态不同的价值主张——正如开放 Web 与原生平台 API 共存。如果这个押注错了，MCOS 的生态论点无论代码质量多高都会失败（见 [10 §10](./10-roadmap.md) 风险登记表）。

### 2.2 "直接调用应用"的问题

直接方案在规模化时必然失败：

1. **Intent / Deep Link（深度链接）的混乱**——每个应用都自造一套 URL scheme 与 extras。  
2. **无障碍服务（Accessibility）抓取**——脆弱、侵犯隐私、难以版本化。  
3. **一次性的 Agent 演示**——每个产品都重新实现规划器 + 权限 + 工具。  
4. **没有共享词汇表**——模型学不到一个稳定的移动命令面。

MCOS 的押注：

> **先标准化命令面。让 LLM 可替换。**

护城河不是某个特定模型。护城河是**命令协议（Command Protocol）+ 运行时（Runtime）+ 插件（Plugin）生态**。

---

## 3. 产品定位

### 3.1 MCOS 是什么

```text
                    Mobile Command OS

                    Natural Language
                           │
                ┌──────────┴──────────┐
                │                     │
              Voice                Command CLI
                │                     │
                └──────────┬──────────┘
                           │
                     LLM Planner
                           │
                  Command Compiler
                           │
               Command Bus (Runtime)
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
 Android System      Plugin SDK           MCP / Cloud
        │                  │                  │
 Intent            App Plugin           Remote Tools
 Accessibility     IoT Plugin
 App Functions     HTTP Plugin
 Deep Link
```

MCOS 是：

- 一种**协议**——把移动能力描述为命令  
- 一个**运行时**——解析、规划、调度、执行并审计这些命令  
- 一个**插件 SDK**——让第三方无需 fork OS 即可暴露能力  
- 一个**可选的 AI 规划器**——把语言编译为 DSL，从不编译为临时副作用  

MCOS **不是**：

- Android 上的传统 Unix 终端  
- 单一的纵向 AI 聊天应用  
- Android 权限系统的替代品  
- 只能配合某一家 LLM 厂商的封闭 Agent  

类比（有意强烈）：

> **手机端的 Kubernetes + MCP + Claude Code Runtime**——把编排、工具协议和 Agent 执行循环适配到移动约束（权限、电量、离线、UI 上下文）之下。

### 3.2 核心用户承诺

| 角色 | 承诺 |
|---------|---------|
| 终端用户 | 说出口或打出一个目标；MCOS 把它编译成可审计的命令，并在明确同意下运行。 |
| 高级用户 | 使用稳定的 CLI/DSL（`home.scene.movie`、`camera.scan`），无需等待 UI 点击。 |
| 插件开发者 | 提交一个 `plugin.json` + 处理器；无需触碰 Runtime 即可出现在命令注册中心。 |
| 平台 / OEM | 托管一条命令总线，可整合系统、OEM 应用和合作方 IoT。 |
| AI 产品团队 | 随时更换规划器/Provider，保持相同的命令面和安全模型。 |

### 3.3 示例交互

```text
> camera scan
> home movie
> github pr
> photo clean
```

自然语言等价形式：

```text
帮我打开空调
导航回公司
把今天拍的照片压缩一下发给 Tom
Wi‑Fi 连上公司网络后自动开 VPN
```

以上全部编译为**Command DSL**，再经由 Runtime 执行。

---

## 4. 设计原则

> **术语说明：** 以下原则编号为 **Principle 1–8**——MCOS 设计理念的概念层次。这与本仓库其他地方使用的实现**阶段** P1（=MVP）/ P2（=V1）/ P3（=V2）不同（见 [10-roadmap §2.1](./10-roadmap.md)）。`P1`/`P2`/`P3` 记号专属路线图阶段，以避免歧义。

### Principle 1 — 协议优先

在打磨 UI 之前先定义**命令协议**。  
HTTP 统一了 Web；SQL 统一了数据访问；Git 统一了版本管理。  
MCOS 志在为**移动能力**扮演同样的角色。

### Principle 2 — AI 与副作用之间隔着 DSL

绝不让模型直接戳 Intent / Accessibility / Bluetooth。

```text
User utterance → Planner → Command DSL → Runtime Executor → Plugin
```

收益：可审计、可重放、权限校验、可测试，以及模型可移植性。

### Principle 3 — 运行时掌管安全

权限、速率限制、确认策略与沙箱边界都归于 Runtime——不靠每个插件自觉。

### Principle 4 — 插件掌管领域逻辑

相机、Home Assistant、GitHub、微信桥等都是插件。  
Runtime 保持精简；能力在边缘生长。

### Principle 5 — AI 是一个 Provider

```kotlin
interface AIProvider {
    suspend fun plan(...): Plan
    suspend fun chat(...): ChatResult
    suspend fun toolCall(...): ToolCallResult
    suspend fun embed(...): Embedding
}
```

OpenAI、Gemini、Qwen、DeepSeek、Claude、端侧 MLC-LLM——全部可互换。

### Principle 6 — 事件是一等公民

移动端的优势是持续上下文：电量、位置、通知、连接状态。  
工作流可由事件触发，而不仅仅由聊天触发。

### Principle 7 — 开放生态

Apache 式的开放：开放协议、开放 SDK、开放市场 API。  
宁要众多插件，不要一个超级应用。

### Principle 8 — 离线 / 端侧现实

假设网络时断时续、电量有限、隐私期望高。  
本地执行与本地记忆为默认；云端是同步/增强。

---

## 5. 仓库拓扑

> ✅ **实现状态：** P1（MVP）与 P2 的大部分已交付——下述拓扑是仓库的**实际**布局（12 个 Gradle 源码模块，含 `mcos-server`），而非目标。阶段历史见 [10-roadmap.md](./10-roadmap.md)；逐子系统状态见 [11-implementation-status.md](./11-implementation-status.md) §3。

```text
mcos/
├── mcos-android          # Jetpack Compose client (CLI + Chat + Store + Settings)
├── mcos-runtime          # Parser, Registry, Executor, Workflow, Memory, EventBus, Audit
├── mcos-sdk              # Plugin SDK (manifest, command handlers, permission declarations)
├── plugins/
│   ├── mcos-plugin-hello     # Reference sample (hello.world)               ← P1
│   ├── mcos-plugin-system    # System / Intent / Notification plugins       ← P1
│   ├── mcos-plugin-camera    # Camera plugin                                ← P1
│   ├── mcos-plugin-files     # Files / media plugin                         ← P1
│   ├── mcos-plugin-iot       # Home Assistant / Tuya / Matter bridges       ← P2
│   └── mcos-plugin-mcp       # MCP client adapter                           ← P2 spike / P3 production
├── mcos-server           # Sync, marketplace, config (Spring Boot or Go)    ← P3
└── docs                  # This architecture & RFC set (exists today)
```

> 各模块的依赖图、包名与构建坐标见 [REPOSITORIES.md](./REPOSITORIES.md)。实现阶段划分见 [11-implementation-status.md](./11-implementation-status.md)。

逻辑栈：

```text
App  →  Runtime  →  Plugin SDK  →  Plugins  →  (optional) Cloud
```

---

## 6. 非目标（v1）

为了保持项目诚实：

1. **不是**完整的 Android OS fork 或自定义 ROM 的要求。  
2. **不是**包装成"通用 RPA"的无限制 Accessibility 自动化。  
3. **不保证**在没有第三方应用合作的情况下能驱动其每一个功能。  
4. **不是**针对 LLM 或市场的单一云锁定。  
5. **不是**忽视移动权限 UX 的桌面优先 MCP 克隆。

我们优先选择合作的插件和官方的 App Functions / Intent 桥，而非脆弱的 UI 自动化。基于 Accessibility 的桥（若有）是可选的、严格受控的，并被明确标注为脆弱。

---

## 7. 成功指标

### 7.1 技术

| 指标 | 目标（V1） |
|--------|-------------|
| 核心注册中心中稳定的命令 ID | ≥ 50 个有文档的命令 |
| 端到端 DSL 执行延迟（本地插件，P50） | < 200 ms（不含设备 I/O） |
| 权限拒绝正确性 | 授权缺失时 100% 阻断 |
| 规划器 → DSL 在 golden set 上的解析成功率 | ≥ 90% |
| 无需重启 Runtime 即可加载/卸载插件 | 必须支持 |

### 7.2 生态

| 指标 | 目标 |
|--------|--------|
| 外部插件可通过 SDK 发布 | 是 |
| MCP 工具可用作命令 | 是（适配器） |
| 多 LLM Provider 支持 | ≥ 3 个 Provider |

### 7.3 用户价值

| 场景 | 完成定义 |
|----------|--------------------|
| CLI 高级用法 | 用户通过 DSL 完成多步家庭/媒体/文件任务，无需在 UI 中翻找 |
| 语音目标 | 一句话 → 已确认的计划 → 已执行的工作流 |
| 事件自动化 | 至少 3 个事件→工作流配方（Wi‑Fi、电量、通知） |

---

## 8. 与相邻标准的关系

| 标准 | 关系 |
|----------|----------------|
| **MCP** | 一等公民适配器：MCP 工具映射进命令注册中心。 |
| **Android App Functions** | 合作应用优先选用的高质量桥。 |
| **Intents / Deep Links** | 通过系统插件支持，被包装为命令。 |
| **Matter / Home APIs / 厂商 IoT SDK** | 通过 IoT 插件暴露，不硬编码进 Runtime。 |
| **OpenAI tool calling / 等** | 在 AI Provider 内使用；输出被约束为 Command DSL。 |

MCOS 不替代这些标准，而是在一条命令总线之下**组合**它们。

---

## 9. 命名

| 术语 | 含义 |
|------|---------|
| **MCOS** | Mobile Command OS——本项目 |
| **Command** | 一个带版本号、带命名空间的能力 ID，附带类型化参数 |
| **Command DSL** | 人类与 AI 用来调用命令的文本 / AST 形式 |
| **Runtime** | 负责校验、授权、调度与执行的进程 |
| **Plugin** | 打包好的一组命令 + 处理器 + 权限 |
| **Workflow** | 由步骤组成的图（顺序 / 并行 / 条件 / 重试） |
| **Planner** | 把目标编译成 DSL / 工作流的 AI（或规则） |
| **Memory** | 为规划服务的持久化用户 / 设备 / 偏好上下文 |
| **Marketplace** | 签名插件的分发渠道 |

---

## 10. 文档地图

本愿景是第 `00` 章。完整设计集：

| 文档 | 标题 |
|-----|-------|
| [00-vision.md](./00-vision.md) | 项目愿景（本文件） |
| [01-architecture.md](./01-architecture.md) | 系统架构 |
| [02-command-protocol.md](./02-command-protocol.md) | 命令协议 RFC |
| [03-runtime.md](./03-runtime.md) | 运行时设计 |
| [04-plugin-sdk.md](./04-plugin-sdk.md) | 插件 SDK |
| [05-workflow.md](./05-workflow.md) | 工作流引擎 |
| [06-agent.md](./06-agent.md) | AI 规划器 / Agent |
| [07-memory.md](./07-memory.md) | 记忆系统 |
| [08-security.md](./08-security.md) | 权限与安全 |
| [09-marketplace.md](./09-marketplace.md) | 插件市场 |
| [10-roadmap.md](./10-roadmap.md) | MVP → V1 路线图 |

---

## 11. 致贡献者

如果你关心的是**协议**、**运行时**、**SDK** 或**安全**，请从这里开始——而不是再做一层聊天皮肤。

优先贡献方向：

1. 命令协议的 schema 与 golden 测试集  
2. 运行时执行器与权限内核  
3. 第一方插件（系统、相机、文件）  
4. MCP 适配器保真度  
5. 端侧 / 隐私友好的规划器路径  

---

## 12. 总结

**MCOS = 移动命令总线。**

- AI 生成的是**命令**，而非不透明的副作用。  
- 运行时**执行并约束**这些命令。  
- 插件在不 fork 核心的前提下**扩展**命令面。  
- 长期资产是**命令协议**以及讲这门语言的生态。

> 让手机上的每一个合作能力都成为一条 AI 可以调用的命令——安全、可审计、可互换。
