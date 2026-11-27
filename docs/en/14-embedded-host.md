# Independent host: exporting an application record

This sample targets developers adding command execution to an existing Android app. The host registers `record.export`; a preset task previews the plan, requests confirmation, writes an actual file, displays the result, and records an audit trail. A model planner can be added above this path separately.

## Run

```bash
sh gradlew :mcos-sample-embedded:testDebugUnitTest :mcos-sample-embedded:assembleDebug
adb install -r mcos-sample-embedded/build/outputs/apk/debug/mcos-sample-embedded-debug.apk
adb shell am start -n com.morainet.mcos.sample.embedded/.MainActivity
```

MCOS Record Export installs separately from the full demo shell. No model key, server, account, or media permission is needed. The sample removes inherited SDK permissions/components and writes only to app-private storage.

## Actions and outcomes

| Action | Expected outcome |
|---|---|
| Preview and export record → Export | Private txt file, displayed filename/content, run ID and audit count |
| Preview and export record → Reject | Explicit failure and no file |
| Try a missing record → Export | Record not found and no file |
| Confirmation dialog → Cancel task | Cancel the run so approval cannot remain orphaned |

The source is the host's fixed business record `demo-001`. Each run creates a new file under `filesDir/record-exports/`. File URIs are private references, not cross-app sharing URLs. Audits persist under `filesDir/embedded/audit.jsonl`, encrypted using existing device-key handling, with HMAC export signatures.

## Minimal integration path

1. Implement `McosPlugin`: command schema, write side-effect classification, and handler; see `RecordExportPlugin.kt`.
2. Call `CompositionRoot.createEmbedded(context, plugins)` from a process-lifetime Application coroutine.
3. Present `runtime.preview(request)`, then call `execute(request)`.
4. Collect `observe(runId)`, answer `ConfirmationNeeded` through `respondConfirmation`, and distinguish success, failure, and cancellation.
5. Present actual `ArtifactEmitted` output and flush the audit after termination. Cancel the page's run when its Activity is destroyed.

Implementation:

- [Independent host source](../../mcos-sample-embedded/src/main/kotlin/com/morainet/mcos/sample/embedded/)
- [Base assembly](../../mcos-android-sdk/src/main/kotlin/com/morainet/mcos/android/EmbeddedRuntime.kt)
- [JVM end-to-end verification](../../mcos-sample-embedded/src/test/kotlin/com/morainet/mcos/sample/embedded/RecordExportIntegrationTest.kt)

## Boundaries and verification

Base assembly accepts trusted app-shipped plugins only. The host grants their declared permissions; side-effect confirmations still apply. It does not accept arbitrary third-party code or initialize the marketplace, planner, scheduled wakes, or background recovery. Android permissions remain OS-controlled.

For camera/picker interactions, supply host services with attached UI bridges through `EmbeddedRuntime.create(hostServices, plugins, auditLog)`. The convenience `createEmbedded` entry has unattached UI bridges and does not replace the full host's interactive wiring.

This is runtime assembly convergence: the Android SDK artifact still transitively depends on LLM, marketplace and built-ins, and runtime still depends on marketplace. Artifact/dependency reduction is pending.

JVM regression drives the real runtime, the same assembly and export plugin: no writes before approval, correct file contents/audit after approval, rejection, missing records, and argument validation. Android windows and system lifecycle still require the device checks above.

Approval rejection, argument validation failure and pre-approval cancellation are reported as run events; the current kernel does not guarantee persistent audit records for these early exits. File output and audit are not transactional: cancellation after execution or an audit failure does not imply rollback.

## Verification record (2026-11-27)

- Full `sh gradlew build` passed; conformance 78/78.
- Five host integration, three base assembly and one immediate-consent-response regression passed.
- TYH612M / Android 10 / API 29: installed and operated through the UI. Approval created the correct file; rejection, missing-record failure and pre-approval cancellation created no additional file.
- Private audit storage existed without plaintext command/record content; package inspection showed no requested Android system permissions.
- Device evidence covers this fixed-record scenario, not arbitrary app control or third-party isolation.
