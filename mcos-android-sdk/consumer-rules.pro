# Consumer rules for io.github.morainet:mcos-android-sdk.
# Merged into the app's ProGuard/R8 config when minification is enabled.

# 1. java.net.http is JDK 11+ and only complete on Android from API 34.
#    The JDK transports (JdkLlmHttpTransport / JdkMarketplaceHttpTransport)
#    live in the pure-JVM artifacts and are lazily loaded — on Android the
#    host injects AndroidLlmHttpTransport / its own marketplace transport
#    instead, so these classes are never touched. Silence the warnings.
-dontwarn java.net.http.**

# 2. The runtime is reflection-light by design, but this is the initial
#    release: keep the whole MCOS surface until shrinker rules are proven
#    on real apps, then narrow to the api/ + bridge packages.
-keep class com.morainet.mcos.** { *; }

# 3. Third-party plugins loaded via DexClassLoader are referenced by class
#    name from their manifests; consumer rules cannot know their packages,
#    so plugin authors must ship their own consumer rules.
