# MCOS API 快速参考

## kotlinx.serialization.json 常用类型

```kotlin
// 需要精确导入的扩展属性（不能依赖通配符）
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

// 常用通配符导入
import kotlinx.serialization.json.*       // 覆盖类：Json, JsonObject, JsonElement 等
import kotlinx.serialization.Serializable  // @Serializable 注解
```

### 扩展属性用法

```kotlin
val element: JsonElement = ...

// 获取 JSON 对象字段
val obj: JsonObject = element.jsonObject
val field: JsonElement? = obj["fieldName"]

// 获取原始值
val str: String = element.jsonPrimitive.content
val int: Int = element.jsonPrimitive.int
val bool: Boolean = element.jsonPrimitive.boolean

// 获取数组
val arr: JsonArray = element.jsonArray
```

## Compose Material3 注解

```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen() {
    TopAppBar(
        title = { Text("Title") },
        colors = TopAppBarDefaults.topAppBarColors(...)
    )
}
```

## MCOS SDK 核心类型

### CommandHandler 接口

```kotlin
interface CommandHandler {
    val command: CommandDescriptor
    suspend fun handle(
        params: JsonObject,
        auth: AuthStamp?,
        progress: ProgressEmitter
    ): CommandResult
}
```

### McosPlugin 接口

```kotlin
interface McosPlugin {
    val manifest: PluginManifest
    suspend fun onLoad(host: HostServices)
    suspend fun onUnload()
    fun handlers(): Map<String, CommandHandler>   // 注意:函数,返回 Map(命令ID→handler)
}
```

### CommandResult 密封类

```kotlin
sealed class CommandResult {
    data class Ok(
        val value: JsonElement,                             // JSON 类型,不是 String
        val artifacts: List<Artifact> = emptyList()
    ) : CommandResult()
    data class Err(
        val code: String,                                   // McosErrorCode 或插件命名空间码
        val message: String,
        val retryable: Boolean = false,
        val details: JsonObject = JsonObject(emptyMap())
    ) : CommandResult()
}
```

## 模块 build.gradle.kts 模板

### 纯 Kotlin 库(mcos-sdk / mcos-security / mcos-runtime-core / mcos-llm / mcos-marketplace / mcos-runtime)

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    // 按规则 4 的拓扑方向声明;公开签名类型所在依赖用 api,其余 implementation
    api(project(":mcos-sdk"))              // 例:mcos-security
    api(libs.kotlinx.serialization.json)   // 公开 API 出现 JsonObject 时用 api
    implementation(libs.kotlinx.coroutines.core)
}
```

插件模块(`plugins/*`)只依赖 sdk,不动摇。

### Android 应用(mcos-android)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)   // Compose 编译器插件,不再手写 composeOptions 版本
    kotlin("plugin.serialization")
}

android {
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":mcos-sdk"))
    implementation(project(":mcos-runtime"))      // facade
    implementation(project(":mcos-llm"))
    implementation(project(":plugins:mcos-plugin-hello"))
    implementation(project(":plugins:mcos-plugin-system"))
    implementation(project(":plugins:mcos-plugin-camera"))
    implementation(project(":plugins:mcos-plugin-files"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
}
```

> `gradlew` 无执行位,统一用 `sh gradlew <task>`。
