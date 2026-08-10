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
    val handlers: List<CommandHandler>
}
```

### CommandResult 密封类

```kotlin
sealed class CommandResult {
    data class Ok(val value: String, val artifacts: List<Artifact>) : CommandResult()
    data class Err(
        val code: McosErrorCode,
        val message: String,
        val retryable: Boolean,
        val details: JsonObject? = null
    ) : CommandResult()
}
```

## 模块 build.gradle.kts 模板

### 纯 Kotlin 库（mcos-sdk, plugins）

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // mcos-runtime 额外需要：
    // implementation(project(":mcos-sdk"))
}
```

### Android 应用（mcos-android）

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}

android {
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    composeOptions { kotlinCompilerExtensionVersion = "5.2.0" }
}

dependencies {
    implementation(project(":mcos-sdk"))
    implementation(project(":mcos-runtime"))
    implementation(project(":plugins:mcos-plugin-hello"))
    implementation(project(":plugins:mcos-plugin-system"))
    implementation(project(":plugins:mcos-plugin-camera"))
    implementation(project(":plugins:mcos-plugin-files"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}
```
