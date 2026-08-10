# MCOS Android

面向用户的 Android 外壳应用，承载运行时并通过 Compose UI 提供 CLI + Chat 交互。

## 包结构

```
com.mcos.android/
```

## 文件

| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | 应用入口 Activity，承载 Compose UI |

## 依赖

- `mcos-sdk`
- `mcos-runtime`
- 内置插件：camera / system / files / hello
- Jetpack Compose（UI）
