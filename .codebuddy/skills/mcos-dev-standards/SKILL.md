---
name: mcos-dev-standards
description: |
  MCOS (Mobile Command OS) 项目开发规范技能。编写或修改任何 MCOS 模块代码时必须遵循此规范。
  触发场景：修改 mcos-sdk / mcos-runtime / plugins / mcos-android 下的 Kotlin 源码、
  修改 Gradle 构建脚本、新增模块或依赖。目的是在编码阶段就避免常见的编译错误，确保代码
  一次提交通过。
---

# MCOS 开发规范

## 概述

此技能定义 MCOS 项目的所有编码规范和常见错误避免规则。编写任何模块代码时，必须检查
以下每一项规则。如果在开发过程中产生编译错误，确保在提交前修复。

## 项目环境

| 项目 | 版本 |
|------|------|
| JDK | 17 |
| Kotlin | 2.0.21 |
| AGP | 8.7.3 |
| Android compileSdk | 35 |
| Android minSdk | 26 |
| Compose BOM | 2024.12.01 |
| Gradle | 8.10 |
| kotlinx.serialization | 1.7.3 |

## 模块拓扑

```
mcos-sdk          ← 叶子模块，无内部依赖
mcos-runtime      ← 依赖 mcos-sdk
plugins/*         ← 各插件模块，依赖 mcos-sdk
mcos-android      ← 依赖所有其他模块
```

**构建顺序规则：** mcos-sdk 必须先于其他模块编译通过，因为所有模块都依赖它。

**包命名规则：**
- `com.mcos.sdk` — SDK 契约层
- `com.mcos.runtime` — 运行时 (runtime + parse + ir + error)
- `com.mcos.plugin.<name>` — 插件模块
- `com.mcos.android` — Android 宿主

## 规则 1: kotlinx.serialization.json 扩展属性导入

### 问题

`jsonObject`、`jsonPrimitive`、`jsonArray` 是 `JsonElement` 的 Kotlin 扩展属性，
定义在 `kotlinx.serialization.json` 包下。通配符导入 `kotlinx.serialization.json.*`
**不一定**能覆盖扩展属性（取决于 Kotlin 编译器版本）。

### 规则

在使用 `jsonObject` / `jsonPrimitive` / `jsonArray` 的源文件中，**必须显式添加**以下导入：

```kotlin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
```

### 检查清单

- [ ] 搜索代码中是否有 `.jsonObject` 调用
- [ ] 搜索代码中是否有 `.jsonPrimitive` 调用
- [ ] 确保对应的精确 import 语句存在
- [ ] 不要依赖 `import kotlinx.serialization.json.*` 来覆盖扩展属性

### 受影响文件

`mcos-runtime` 和所有 `plugins/*` 模块都可能用到这些扩展属性。每次修改这些模块代码时
都要检查。

## 规则 2: Material3 实验性 API 注解

### 问题

在 Compose BOM 2024.12.01 中，`TopAppBar`、`TopAppBarDefaults` 等组件标记为
`@ExperimentalMaterial3Api`，未经 `@OptIn` 直接使用会导致编译错误。

### 规则

在 `mcos-android` 模块中使用 Material3 组件时：
- 检查是否使用了标记为 `@ExperimentalMaterial3Api` 的 API
- 在使用位置添加 `@OptIn(ExperimentalMaterial3Api::class)` 注解
- 同时需要导入：`import androidx.compose.material3.ExperimentalMaterial3Api`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen() {
    TopAppBar(...)
}
```

### 检查清单

- [ ] 搜索代码中是否有 `TopAppBar`、`TopAppBarDefaults`、`Scaffold` 等 Material3 组件
- [ ] 确保使用这些组件的函数上有 `@OptIn(ExperimentalMaterial3Api::class)`
- [ ] 确保导入了 `androidx.compose.material3.ExperimentalMaterial3Api`

## 规则 3: 未使用导入清理

### 规则

提交代码前，移除所有未使用的导入语句。特别关注：
- `@ExperimentalSerializationApi` — 如果不再需要
- `JsonNamingStrategy` — 如果已迁移到 `@Serializable` 上的 `@SerialName`
- 任何 IDE 灰色标记的 import

使用 IDE 的 "Optimize Imports" 功能，或手动检查。

## 规则 4: 模块间依赖

### 规则

- `mcos-sdk` 不能依赖任何 MCOS 内部模块
- `mcos-runtime` 只能依赖 `mcos-sdk`
- `plugins/*` 只能依赖 `mcos-sdk`（以及 kotlinx.serialization）
- `mcos-android` 可以依赖所有模块

在 `build.gradle.kts` 中添加新依赖时，必须遵守这些约束。

### 依赖声明模板

```kotlin
// mcos-runtime/build.gradle.kts
dependencies {
    implementation(project(":mcos-sdk"))
    // ... external dependencies
}

// plugins/mcos-plugin-xxx/build.gradle.kts
dependencies {
    implementation(project(":mcos-sdk"))
    // ... external dependencies
}
```

## 规则 5: Kotlin 代码风格

### 规则

项目使用 `kotlin.code.style=official`。遵循：

- 4 空格缩进（非 Tab）
- 类名 PascalCase，函数名 camelCase
- 常量 UPPER_SNAKE_CASE
- 每行最大 120 字符（建议）
- 逗号后空格
- 冒号前空格（类型声明）：`val x: Int = 0`

### 包结构

- 接口和数据类放在包根目录
- 子包用于组织相关实现：`.parse`、`.ir`、`.error`
- 测试代码镜像源码包结构：`src/test/kotlin/` 对应 `src/main/kotlin/`

## 规则 6: 命名规范

### 模块命名
- `mcos-plugin-<name>` — 插件模块目录名
- `:plugins:mcos-plugin-<name>` — Gradle 模块路径

### 类命名
- 数据类：名词，如 `CommandDescriptor`, `PluginManifest`
- 接口：名词/名词短语，如 `McosPlugin`, `CommandHandler`, `HostServices`
- 枚举：名词，如 `SideEffectClass`, `McosErrorCode`
- 密封类：名词，如 `CommandResult`, `ExecutionIr`, `ParseResult`

### 命令命名
- 格式：`<domain>.<action>`（小写，点分隔）
- 示例：`sys.notify`, `camera.capture`, `file.list`

## 规则 7: Git 提交前检查

### 必须执行的命令

每次提交前，在项目根目录依次运行：

```powershell
# 1. 编译所有模块
./gradlew compileKotlin

# 2. 如有 mcos-android 修改，额外检查
./gradlew :mcos-android:compileDebugKotlin

# 3. 运行测试（如果有修改运行时逻辑）
./gradlew :mcos-runtime:test
```

### 提交信息规范

遵循项目已有的提交风格，使用中文或英文均可，格式：
```
<模块>: <简短描述>

<详细说明（可选）>
```

示例：
```
mcos-sdk + mcos-runtime: 实现 DSL 解析器和命令协议类型

- 添加 Token/Lexer/Parser/Canonicalizer
- 添加 CommandDescriptor/CommandResult/PluginManifest
- 包含 17 个测试用例覆盖所有 golden fixture
```

## 常见错误速查表

| 错误信息关键词 | 原因 | 解决 |
|---|---|---|
| `Unresolved reference 'jsonObject'` | 缺少扩展属性显式导入 | 添加 `import kotlinx.serialization.json.jsonObject` |
| `Unresolved reference 'jsonPrimitive'` | 同上 | 添加 `import kotlinx.serialization.json.jsonPrimitive` |
| `Unresolved reference 'jsonArray'` | 同上 | 添加 `import kotlinx.serialization.json.jsonArray` |
| `This material API is experimental` | 缺少 Material3 OptIn 注解 | 添加 `@OptIn(ExperimentalMaterial3Api::class)` |
| `Unresolved reference 'ExperimentalSerializationApi'` | 未使用的实验性 API 导入 | 移除该 import |
| `Unable to find JDK 17` | 本地 JDK 版本过低 | 安装 JDK 17 |

## 新模块添加流程

当需要添加新模块时，按以下步骤操作：

1. 在 `settings.gradle.kts` 中声明模块 `include(":xxx")`
2. 创建 `xxx/build.gradle.kts`，声明正确的依赖关系
3. 确保包名遵循 `com.mcos.xxx` 规范
4. 编译验证：`./gradlew :xxx:compileKotlin`
5. 如有 Android 集成需求，在 `mcos-android/build.gradle.kts` 中添加依赖
