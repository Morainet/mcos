# mcos-plugin-iot

IoT / 智能家居插件 —— `home.*` + `iot.*` 命令面（[04-plugin-sdk.md §9](../../docs/en/04-plugin-sdk.md)）。

通过 **Home Assistant REST API** 控制设备：HA 是规范点名的开放、本地优先集成面，
无需任何厂商 SDK；Tuya/Matter 桥接可经 HA 侧接入，插件协议不变。

## 命令面（8 个）

| 命令 | 类别 | 说明 |
|---|---|---|
| `home.device.list` | read | 只读发现：`GET /api/states` 归一化为 `{id, domain, state, name}`，可按 `domain` 过滤 |
| `home.light.on(id)` | write | `light/turn_on` |
| `home.light.off(id)` | write | `light/turn_off` |
| `home.light.set(id, on, brightness, meta)` | write | golden fixture 签名；`brightness` 0..1 → hub 0..255；`meta` 收下即忽略 |
| `home.scene.apply(name)` | write | 激活 `scene.<name>` |
| `home.scene.movie` / `home.scene.sleep` | write | 文档点名的便捷命令（等价 apply） |
| `iot.ac.set(name, power, tempC)` | write | `climate/turn_on` + `set_temperature`（tempC 16..30 校验） |

## 架构约束

- **出网只走 `HostServices.net`** —— 每次调用过内核逐调用出网作用域检查（08 §12）；
  manifest 按配置声明**具体的** `network.<hub-host>` 作用域，未配置 = 零授权。
- **凭证不落配置**：token 存 `SecureStore`（`tokenSecretKey`），请求头携带
  `Bearer {{secret.<key>}}` 引用，由 executor Stage-4 逐调用解析（08 §9.2，与 MCP
  适配器同模式）—— 原始 token 不进配置、IR 或审计。
- **错误诚实映射**：hub 401/403 → `PERMISSION_DENIED`（不可重试）；404 → `UNAVAILABLE`
  （查 baseUrl）；5xx → `UNAVAILABLE`（可重试）；参数越界 → `SCHEMA_VIOLATION`，
  且校验失败**绝不触网**。
- **未配置 hub**：插件可加载，但所有命令如实地返回 `UNAVAILABLE`，不伪造成功。
- 不在 android-sdk 默认内置集里（与 plugin-mcp 同为宿主自选注入），因此也不进
  发布制品集 / BOM。

## 接入

```kotlin
val iot = IotPlugin(
    HomeAssistantConfig(
        baseUrl = "https://ha.example.com",
        tokenSecretKey = "mcos.iot.ha.token",   // SecureStore 键
    ),
)
// runtime 注册：McosRuntime.Builder 的 plugin 列表加入 iot
```

本地无认证 HA 可省略 `tokenSecretKey`。

## 测试

`IotPluginTest`（18 个，I1-I18）：manifest/作用域、`/api/states` 归一化与域名过滤、
401/503 错误映射、light/scene/climate 的确切 URL+body 断言、0.8→204 亮度换算、
`on=false` 短路、tempC 越界、UTF-8 实体名（`空调`）透传、未配置零出网。
