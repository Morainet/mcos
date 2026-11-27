# Android Debug embedded-host regression

[中文](../zh/14-embedded-host.md) · [Current architecture](./13-architecture-convergence.md)

`RecordExportActivity` ships in the `mcos-android` Debug APK. `RecordDebugHost` assembles a base Runtime for exercising preview, confirmation, file creation, and audit with app-owned record commands. `MainActivity` launches the user-facing Agent with the separate full-host assembly.

## Run the regression

These commands use the current checkout. `createEmbedded` is the base-host entry point in this source tree. The `v0.0.6` tag exposes the full `CompositionRoot.create` entry point.

```bash
sh gradlew :mcos-android:testDebugUnitTest :mcos-android:assembleDebug
adb install -r mcos-android/build/outputs/apk/debug/mcos-android-debug.apk
adb shell am start -n com.morainet.mcos.android/.demo.records.RecordExportActivity
```

| Action | Expected outcome |
|---|---|
| **Preview and export record** → **Export** | Export `demo-001` and display the run ID, filename, record contents, and audit count. |
| **Find latest completed record** → **Preview and export located record** → **Export** | Find and export `demo-002`. The CLI can verify the run against that record ID. |
| **Reject** or **Cancel task** in the confirmation dialog | The run reports failure or cancellation. The run has created no file before approval. |
| **Try a missing record** → **Export** | Display `Completed record not found`. The run creates no file. |

The manual regression runs offline. Each approved export creates a new private text file under `filesDir/record-exports/`. Base-host audit lives in `filesDir/embedded/audit.jsonl`. Use the [ADB guide](../../tools/mcos-device/README.md) to verify the terminal status and file by run ID. Record the APK source, Android version, and approval path for device checks.

## Embed an app-owned capability

1. Implement `McosPlugin` with a command schema, side-effect class, and handler. Refer to [`RecordExportPlugin.kt`](../../mcos-android/src/main/kotlin/com/morainet/mcos/android/demo/records/RecordExportPlugin.kt).
2. Call the suspend `CompositionRoot.createEmbedded(context, plugins)` from an Application-scoped coroutine. For Activity interactions, supply wired host services through `EmbeddedRuntime.create(hostServices, plugins, auditLog)` or use the full host.
3. Present `runtime.preview(request)` and start the run with `execute(request)`. Collect `observe(runId)`, pass decisions to `respondConfirmation`, and handle success, failure, and cancellation.
4. Use `ArtifactEmitted` and the terminal event to present the actual output. The regression Activity cancels its run when destroyed and flushes the audit after a terminal result.

See the [Debug regression screen](../../mcos-android/src/debug/kotlin/com/morainet/mcos/android/demo/records/), [EmbeddedRuntime](../../mcos-android-sdk/src/main/kotlin/com/morainet/mcos/android/EmbeddedRuntime.kt), and [host regression test](../../mcos-android/src/testDebug/kotlin/com/morainet/mcos/android/demo/records/RecordExportIntegrationTest.kt).

Base assembly uses trusted app-shipped plugins. Runtime confirmation, Android system permissions, and host authorization retain their own roles. Some early exits surface through run events, so verify persistent audit coverage for the path being tested. File writes and audit are independent. Cancellation or audit failure after a write may leave the file in place.
