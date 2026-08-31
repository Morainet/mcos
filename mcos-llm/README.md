# MCOS LLM

LLM 网关与自然语言编排层——把用户话语经多 provider 规划链（原生工具调用 / 约束解码 /
自由 JSON / 延迟分层路由）编译成可执行命令，并经 `RuntimeGateway` 端口直接驱动内核。

## 模块定位

- **门面的对等客户端**：本模块不依赖 `mcos-runtime` 门面（仅 testImplementation），
  经 `mcos-runtime-core` 的 `core.api.RuntimeGateway` 直连内核——
  "llm and the facade are sibling clients of the kernel"（01-architecture §3.2）。
- 零三方 LLM SDK 依赖：HTTP transport 自带 JDK 实现，可注入替换。
- 规格：`docs/zh/06-agent.md`。

## 结构（单包 `com.morainet.mcos.llm`，main 18 文件）

| 分组 | 关键文件 | 说明 |
|------|----------|------|
| Provider 端口 | `LlmProvider.kt`、`LlmProviderRegistry.kt`、`LlmProbePolicy.kt` | chat/toolCall/constrainedChat/probe 四端点；能力协商（CHAT/PLAN/TOOL_CALL/CONSTRAINED/EMBED）与层别（ON_DEVICE/CLOUD）；健康缓存 30s、失败冷却 10s |
| Provider 实现 | `OpenAiLlmProvider.kt`、`GrammarLlmProvider.kt` | 前者任意 OpenAI 兼容端点（含 vLLM/LiteLLM）；后者真 token 级语法约束（llama.cpp `grammar`、vLLM/Outlines `guided_grammar`/`guided_json`） |
| 规划 | `LlmPlanner.kt`（775 行，核心）、`ToolCallTypes.kt`、`UtteranceClassifier.kt`、`RecipeMatcher.kt` | 系统提示 = 命令目录 + 参数 schema + Memory 用户事实；四种 PlanMode；端侧→云隐私门（§13.2）；零延迟配方直出 DSL 不经 LLM |
| 编排 / Agent | `ChatOrchestrator.kt`、`Agent.kt`（McosAgent）、`AgentBridge.kt`、`AgentSessionStore.kt` | 一次性 chat→plan→execute→事件收集；多轮循环 compile→探查(read-prefix)→重规划(≤cap)→PlanReady→用户审批→执行 |
| 安全 | `PromptInjectionDetector.kt` | 纯函数启发式链：指令覆写/提权/社工/数据外传 |
| 语法约束 | `GbnfGrammar.kt` | 从命令目录生成 GBNF，采样期即不可能输出目录外命令 ID |

注意命名区分：本模块的 `Recipe`（触发词→DSL 零延迟快路径）与 marketplace 的
`RecipeEnvelope`（签名配方工作流）是两个概念。

## 典型用法

```kotlin
// 一次性编排（摘自 ChatOrchestratorTest）
val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)
val result = orchestrator.chat("say hello")   // plan → 注入检测 → gateway.execute → ChatResult

// 多轮 Agent（摘自 AgentLoopTest）
val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)
val results = agent.runTurn("s1", "find my cat photos and enhance the best one").toList()
// [Probing(...), PlanReady(ir, needsConfirmation=true)]
agent.resume("s1", approved = true)           // → Done

// 延迟分层 + 零延迟配方（摘自 LlmPlannerLatencyTieredTest）
val plan = LlmPlanner(llm, registry, recipes = recipes)
    .plan("good morning", PlanMode.LATENCY_TIERED)   // route="recipe:morning"，0 次 LLM 调用
```

NL→IR 质量由金样回归门守护（`golden/NlIrGoldenSuiteTest` + `docs/fixtures/planner/`）：
structureAccuracy=1.0、misRefusalRate=0.0、misExecutionRate=0.0。

## 平台注记

JDK HTTP transport 在 Android 不可用（无 `java.net.http`），Android 侧注入
`AndroidLlmHttpTransport`（HttpURLConnection 版，在 mcos-android-sdk）。

## 依赖

- `mcos-sdk`、`mcos-runtime-core`（均 api：RuntimeGateway/Registry/Parser/IR/Memory/EventBus）
- `kotlinx.serialization.json`、`kotlinx.coroutines.core`（api）
- `mcos-runtime`（**仅测试**：真实门面做集成覆盖）
