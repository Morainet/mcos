# Product direction and proposal decisions (2026-11-27)

## 1. Direction and near-term promise

The long-term direction is an open AI capability execution runtime and plugin ecosystem: embeddable, extensible and interoperable. Initial users are developers exposing existing application capabilities to AI or preset tasks. Android is the first acceptance host; pure JVM contracts and platform adapter boundaries remain.

The near-term promise is a host-owned capability flowing through preview, consent, execution, actual output and failure feedback. The record-export sample is a reference integration, not evidence of external adoption or an established plugin ecosystem.

## 2. Decisions on the broader proposal

| Proposal | Decision and current state |
|---|---|
| Core / Adapter / Extension / Services | Accept as responsibility groups; retain artifact names |
| No model/marketplace runtime requirement | Base host initializes neither; artifact dependency reduction is pending |
| Platform-independent protocol | Preserve existing DSL/typed IR, errors and version rules; a new JSON envelope needs an RFC and migration |
| New manifest and lifecycle | Map existing PluginManifest, PluginLoader, install states and onLoad/onUnload first; avoid competing authorities |
| Optional Workflow / Memory | Accept the goal; review RuntimeGateway, authorization, expansion and recovery dependencies before extraction |
| Plugin Testkit / CLI | Reuse conformance and host regression first; add tooling based on external integration friction |
| Bidirectional MCP | Keep inbound tool adaptation; design caller identity, scopes, execution handles and consent before exposing commands |
| More platforms and marketplace growth | Follow independent host and external plugin validation |

Illustrative identifiers and fields such as `system.get_battery`, `device.camera.capture`, `protocolVersion` and `capabilities` are not current APIs. Actual descriptors remain authoritative, including `sys.device.battery` and `camera.capture`. Documentation edits must not silently change contracts.

## 3. Next deliveries and acceptance

1. Verify this host on a device, recording artifacts, rejection and cancellation separately from JVM evidence.
2. Reduce base-host artifact dependencies, document migration from CompositionRoot/AppDeps, and retain the working sample.
3. Provide a custom-plugin example buildable outside the main repository using manifest/conformance and publication checks.
4. Connect business run IDs to audit records and define persistent diagnostics for early rejection/validation failures.
5. Validate a second independent business host before stabilizing outward MCP and plugin lifecycle APIs.

Five-minute integration, thirty-minute plugin authoring, two independent apps and three external-author plugins are validation targets, not achieved metrics. Releases follow actual compatibility and release gates rather than a fixed week-based promise of 1.0.

## 4. Security and compatibility boundaries

The base host loads trusted app-shipped plugins only. Android same-UID processes provide crash and memory separation, not a complete OS sandbox for hostile code. Application authorization, OS permissions and runtime approval remain distinct controls.

Existing protocol fixtures and security gates constrain the changes. New build modules do not imply a new protocol; breaking public contracts requires migration guidance and an available path for existing artifacts.

## 5. Confirmed next stage: ADB device-control prototype

On 2026-11-27 the user confirmed: close the current host task flow first, then prioritize an agent observing, operating and verifying Android tasks through ADB. Dependency reduction remains engineering backlog; the ADB prototype is the next iteration.

Scope: explicit device selection, connection state and timeouts, screenshots and UI trees, observation-guided tap/type/swipe/back/app launch, and post-action observation with verification results. Start with a local developer CLI before choosing MCP integration. Authorized ADB is required; Companion and other platforms follow later evaluation.

Use the record-export host as the acceptance task: observe → locate export → handle consent → verify completion and actual file. Successful input delivery is not business success; screenshot, UI state and artifacts provide separate evidence.

Device adaptation stays at the platform edge; common runtime owns command validation, execution state and audit. Native host capability access remains available without changing existing command/artifact contracts.

The local ADB CLI now observes, launches and taps the reference app and verifies its output artifact. The host runtime handles on-device consent. General gestures, screenshots and MCP integration remain next-stage work. See the [ADB prototype](../../tools/mcos-device/README.md).
