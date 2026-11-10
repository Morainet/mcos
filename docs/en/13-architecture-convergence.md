# Architecture convergence and development boundaries

> Date: 2026-11-10. Near-term product scope and implementation sequence; existing RFCs continue to govern command, security, and scheduling behavior.

## 1. Product scope

MCOS is a secure command runtime embedded in Android applications. Its demo app demonstrates that natural language or preset tasks can reliably invoke phone capabilities.

The primary users are Android developers and AI product teams. Hosts register commands; the runtime owns argument validation, authorization, confirmation, execution, cancellation, and audit. Natural-language planning is optional; preset tasks execute directly.

Near-term development focuses on minimal host integration, complete task outcomes, and diagnosable failures. Existing marketplace, sync, and public-index implementations remain extensions whose priority follows host use cases.

## 2. Main path and responsibilities

```text
Host app → optional natural-language planner → command request
Host app → preset task / DSL                 → command request
Command request → Runtime → plugin → Android host capability → result and audit
```

| Boundary | Owns | Change entry point |
|---|---|---|
| Plugin contract | Capability descriptions, argument schemas, handlers, host interfaces | mcos-sdk |
| Execution and security | Validation, authorization, confirmation, scheduling, workflows, audit | mcos-security / mcos-runtime-core |
| Runtime facade | Request entry, run lifecycle, subsystem coordination | mcos-runtime |
| Planning | Language to existing commands, clarification, read-only probes | mcos-llm |
| Android host | System APIs, permission bridges, processes, startup recovery | mcos-android-sdk |
| Demo application | Integration, plan, consent, progress, result presentation | mcos-android |
| Distribution and sync | Installation, review, indexing, sync transport | mcos-marketplace / mcos-index-server / mcos-server |

These are responsibility boundaries. The current Gradle graph still has runtime → marketplace and android-sdk → llm/marketplace/built-in plugins edges. Those dependencies have not been removed; the target boundaries are not a claim of completed modularization.

## 3. Implemented in this iteration

`RuntimeRequestCompiler` owns DSL, IR JSON, and named-workflow parsing, plus previews without execution side effects. `McosRuntime.execute()` and `preview()` share it; the facade retains lifecycle, scheduling, confirmation, and execution coordination. Registry and workflow-store reads remain per request so runtime installation and updates stay visible.

The component is internal to the module, with no new public API or Gradle module. Existing parse locations, unknown-command warnings, preview null representation, workflow estimates, and missing-workflow behavior are preserved.

## 4. Convergence sequence

| Order | Work | Acceptance |
|---|---|---|
| 1 | This iteration: separate request compilation from execution coordination | Existing public API regressions and conformance gate pass |
| 2 | Separate base Android runtime assembly from optional extensions | Minimal host assembles its commands; kernel, signer, registry, and grant authority remain shared |
| 3 | Separate demo task execution, model settings, and MCP management state | ViewModel delegates use cases; pages do not construct the runtime |
| 4 | Complete two golden scenarios | New host registers and executes a custom command; file/photo task presents consent, actual artifacts, and explicit failure |
| 5 | Evaluate dependency reduction against those scenarios | Preset commands run without model/marketplace configuration; existing integrations have migration guidance |

Items 2–5 are pending. Marketplace decoupling affects public Builder/installer types and requires a compatibility review before migration.

## 5. Iteration rules

- Identify the host use case, inputs, user outcome, and failure outcome for each change.
- Put domain capabilities in plugins, Android APIs in host adapters, and UI state in the demo app.
- Route command schemas, permission decisions, and execution through the same runtime path.
- Preserve security, conformance, and versioning constraints; verify observable behavior when separating responsibilities.
- Distinguish implemented, verified, and planned work; specification coverage alone does not establish a usable scenario.
