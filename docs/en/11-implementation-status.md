# Current implementation and verification scope

[中文](../zh/11-implementation-status.md) · [Current architecture](./13-architecture-convergence.md)

This page maps current source entry points to repeatable checks. Build, unit-test, emulator, device, and published-artifact evidence are tracked independently. See the [changelog](../../CHANGELOG.md) for release history.

| Scenario | Current implementation | Where to verify |
|---|---|---|
| Start a task in the Android app | The `mcos-android` Agent workspace provides model configuration, execution, approval, progress, results, and encrypted task history. The inspection preset queries, exports, and verifies a file through the Runtime. | [App guide](../../mcos-android/README.md), `McosViewModelAgentTest` |
| Embed app-owned capabilities | `CompositionRoot.createEmbedded` assembles the base Runtime. The Debug APK's `RecordExportActivity` exercises preview, confirmation, file creation, and audit. | [Embedded host](./14-embedded-host.md), `RecordExportIntegrationTest` |
| Developer ADB and model testing | The local CLI drives the Debug screen and verifies export by run ID. ChatAnywhere supports the scoped natural-language export flow. The app has an `adb.inspect_mcos` Debug observation tool. | [Device guide](../../tools/mcos-device/README.md), Python unit tests and device checks |
| Command protocol and execution | DSL and typed IR, registration, validation, confirmation, scheduling, workflows, events, audit, and memory are wired in their respective modules. | [`docs/fixtures`](../fixtures/), the `mcos-runtime-core`, `mcos-security`, and `mcos-runtime` suites, plus `mcos-conformance` |
| Models, plugins, and services | Planning, sessions, inbound MCP tools, marketplace installation, index service, and sync service have implementations. The architecture page records how they are wired. | [Current architecture](./13-architecture-convergence.md), [index service](./12-index-server.md) |
| Plugin process isolation | The full Android host can opt into `:mcos_plugin`. The SDK guide documents the Binder device suite and its coverage. | [Android SDK](../../mcos-android-sdk/README.md), device verification section |

## Verification commands

```bash
sh gradlew projects
sh gradlew build
sh gradlew :mcos-conformance:conformance
sh gradlew :mcos-android-sdk:testDebugUnitTest :mcos-android:testDebugUnitTest :mcos-android:assembleDebug
python3 -m unittest discover -s tools/mcos-device -p 'test_*.py'
```

For Android system interactions, use the device flow in the relevant guide and record the device, APK source, approval choice, run ID, and outcome. Verify device capability, model connectivity, and service deployment independently.
