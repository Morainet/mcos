# MCOS Record Export

Independent Android host demonstrating one app-owned capability, `record.export`.
It uses `CompositionRoot.createEmbedded`, requires no model key or marketplace
configuration, and installs separately from the full demo.

```bash
sh gradlew :mcos-sample-embedded:testDebugUnitTest :mcos-sample-embedded:assembleDebug
adb install -r mcos-sample-embedded/build/outputs/apk/debug/mcos-sample-embedded-debug.apk
adb shell am start -n com.morainet.mcos.sample.embedded/.MainActivity
```

Preview → approve/reject/cancel → real private file → result and audit.
The app is a reference integration and is not published to Maven Central.

[Integration guide](../docs/en/14-embedded-host.md) · [中文接入指南](../docs/zh/14-embedded-host.md)
