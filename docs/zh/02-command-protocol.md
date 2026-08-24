# MCOS 命令协议（RFC）

> **语言:** [English](../en/02-command-protocol.md) · 中文（当前）

> **Status:** Draft RFC  
> **RFC Number:** MCOS-RFC-0001  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-24  
> **Normative:** Yes  
> **Depends on:** [00-vision.md](./00-vision.md), [01-architecture.md](./01-architecture.md)

---

## 摘要

本 RFC 规定 **Mobile Command OS 命令协议（Command Protocol）**：人类、AI 规划器（Planner）和运行时用来描述**可调用的移动能力**的稳定词汇表，以及对应的 wire/AST 形式。

如果本仓库中只能有一份文档在业界产生影响，那应当是这一份。

---

## 1. 动机

今天的移动自动化碎片化地散落在 Intent、Deep Link、Accessibility 树、厂商 IoT SDK 以及临时 Agent 工具 JSON 之间。

MCOS 需要：

1. 一个**稳定的命令标识**（`home.light.on`）  
2. 带校验的**类型化参数**  
3. 一套面向人类与 LLM 的**文本 DSL**  
4. 一套面向引擎与存储的**结构化 IR**  
5. 关于**版本管理、错误、权限与副作用**的清晰规则

AI 必须生成**命令**，而不是不透明的副作用。

---

## 2. 术语

| 术语 | 定义 |
|------|------------|
| **Command ID**（命令 ID） | 带命名空间的标识符，例如 `camera.capture` |
| **Command Descriptor**（命令描述符） | 在命令注册表（Command Registry）中注册的元数据 |
| **Invocation**（调用） | 对一个命令的一次带具体参数的调用 |
| **DSL** | 面向人类/AI 的文本形式 |
| **IR** | JSON（或等价形式）的中间表示 |
| **Run**（运行） | 一次执行会话，包含一次或多次调用 / 工作流步骤 |
| **Side-effect class**（副作用类别） | 供策略使用的、已声明的影响类别 |

---

## 3. 设计目标

### 3.1 目标

- DSL ↔ IR 的**确定性解析**  
- **对 LLM 友好**的语法（贴近 JS/Python 调用风格）  
- 在任何插件运行之前进行**严格校验**  
- **向前兼容**的版本管理  
- **可审计** —— 每一次调用都能从日志中重建

### 3.2 非目标

- 完整的通用编程语言  
- 任意代码执行（`eval`、shell）  
- 本 RFC 不涉及二进制插件 ABI（参见 SDK 文档）  
- 自然语言理解（属于 Planner 的职责）

---

## 4. 命令标识

### 4.1 语法

```abnf
namespace    = 1*( ALPHA / DIGIT / "-" )
name         = 1*( ALPHA / DIGIT / "-" )
command-id   = namespace 1*( "." name )
```

示例：

```text
camera.capture
photo.search
home.scene.movie
github.pr.create
sys.notify
mcp.filesystem.read
```

### 4.2 规则

1. 规范形式仅允许**小写**。  
2. 命名空间由插件或 `sys` / `mcp` 保留根拥有。  
3. 最大长度：**128** 个字符。  
4. Command ID 是**稳定的公共 API**；重命名需要提升主版本号 + 废弃期。  
5. 命名空间之后允许使用多段名称（`home.scene.movie`）。

### 4.3 保留命名空间

| Namespace | 归属 |
|-----------|-------|
| `sys` | MCOS 系统插件 |
| `mcp` | MCP 适配器 |
| `mcos` | 运行时内省 / 元命令 |
| `std` | 保留给未来的标准库 |

### 4.4 命名空间注册与冲突仲裁

插件在其清单（manifest）中声明它所拥有的命名空间（`namespaces: [...]`）。在注册表加载时，运行时会按照以下优先级来决定两个插件同时声称拥有同一命名空间时的归属：

| 优先级 | 来源 |
|----------|--------|
| 1（最高） | 保留根（`sys`、`mcp`、`mcos`、`std`）—— 只有内置 / 适配器插件可以注册这些 |
| 2 | 在清单中第一个声明该命名空间 **且** 通过签名校验的插件 |
| 3 | 第一个加载的插件（当没有清单声明时的兜底；会发出一条 `WARN` 审计事件） |

**冲突检测（在加载时）：** 如果当一个第二个插件尝试注册某个命令 ID 时，该命令 ID 已经存在于注册表中，则这次加载会被以 `UNKNOWN_COMMAND` 拒绝（该命令被视为对*此*插件不可用），并附带一个 `details` 载荷 `{ "conflict": "duplicate_id", "existingPlugin": "<id>", "incomingPlugin": "<id>" }`。已经注册的命令**不会**被驱逐。这保持了契约的稳定：第一个通过校验的注册者获胜。

**版本共存：** 两个插件**可以**在同一个命名空间下、以*不同*的命令名注册命令（例如插件 A 注册 `home.light.on`，插件 B 注册 `home.scene.movie`）而不发生冲突 —— 只有完全相同的完整命令 ID 才会冲突。

### 4.5 别名

命令**可以**在其描述符中声明 `aliases: []`，以发布解析到同一处理器的额外命令 ID：

```json
{
  "id": "sys.notify",
  "aliases": ["sys.notification.send", "notify"]
}
```

规则：

1. 别名遵循与主 ID 相同的语法与保留命名空间规则（§4.1–4.2）。
2. 别名**MUST NOT**与另一命令的主 ID 或别名发生冲突 —— 在加载时检查，仲裁方式同 §4.4。
3. 规范 IR 始终存储**主** `id`，绝不存储别名。当用户/Planner 调用一个别名时，Resolve 阶段（§9.1 第 2 步）会将其映射到主 ID，并且审计记录会同时记录两者（`requestedId` + `resolvedId`）。
4. Planner 目录（[01-architecture.md](./01-architecture.md) 的 §12）**可以**把别名作为补全建议展示，但**MUST**用 `aliasOf` 对其进行标注。

---

## 5. 类型系统

### 5.1 基础类型

| Type | JSON | DSL 字面量示例 |
|------|------|----------------------|
| `string` | string | `"Tom"`, `'office'` |
| `int` | number（整数） | `80`, `-1` |
| `float` | number | `0.8`, `3.14` |
| `bool` | boolean | `true`, `false` |
| `null` | null | `null` |
| `bytes` | 带标注的 base64 字符串 | （二进制建议使用 IR） |
| `uri` | string（URI 格式） | `"content://..."`, `"https://..."` |
| `duration` | ISO-8601 时长字符串或 int 毫秒 | `"PT5M"`, `5000` |
| `datetime` | RFC 3339 字符串 | `"2026-08-06T12:00:00+08:00"` |
| `enum` | 取值属于允许集合的 string | `"high"` |

### 5.2 复合类型

| Type | 描述 |
|------|-------------|
| `object` | 由已知属性组成的 Map（JSON Schema 对象） |
| `array<T>` | 同构列表 |
| `union` | 判别式或简单联合（由 schema 定义） |
| `ref` | 对记忆（Memory）实体 / 设备 id 的引用（带 `x-mcos-ref` 的字符串） |

### 5.3 Schema 表示

命令的输入/输出 schema **SHOULD** 能用 **JSON Schema Draft 2020-12** 加上 MCOS 扩展来表达：

```json
{
  "$id": "mcos:command:photo.compress/input",
  "type": "object",
  "additionalProperties": false,
  "required": ["quality"],
  "properties": {
    "quality": { "type": "integer", "minimum": 1, "maximum": 100 },
    "uris": {
      "type": "array",
      "items": { "type": "string", "format": "uri" }
    },
    "date": { "type": "string", "x-mcos-semantic": "date-or-relative" }
  }
}
```

厂商扩展：

| Extension | 含义 |
|-----------|---------|
| `x-mcos-semantic` | 规划器提示（`date-or-relative`、`contact`、`device`、…） |
| `x-mcos-secret` | 该值在日志中被脱敏 |
| `x-mcos-ref` | 在执行前通过记忆解析 |
| `x-mcos-default-from-memory` | 记忆档案中的键路径 |

### 5.4 类型 → JSON Schema 映射与校验边界

每个 MCOS 基础类型都映射到一个具体的 JSON Schema 片段，并带有默认边界。描述符**可以**收紧这些边界（例如添加 `minimum`/`maximum`），但当描述符省略它们时，以下默认值生效。

| MCOS 类型 | JSON Schema 片段（默认值） | 校验失败 → |
|-----------|---------------------------------|---------------------|
| `string` | `{"type": "string", "maxLength": 65536}` | `SCHEMA_VIOLATION` |
| `int` | `{"type": "integer", "minimum": -2^63, "maximum": 2^63-1}`（有符号 64 位 `Long`） | `SCHEMA_VIOLATION` |
| `float` | `{"type": "number"}`（IEEE 754 双精度） | `SCHEMA_VIOLATION` |
| `bool` | `{"type": "boolean"}` | `SCHEMA_VIOLATION` |
| `null` | `{"type": "null"}` | `SCHEMA_VIOLATION` |
| `bytes` | `{"type": "string", "contentEncoding": "base64", "maxLength": 10485760}`（编码后 10 MiB） | `SCHEMA_VIOLATION` details `reason: "base64_invalid"` 或 `"size_exceeded"` |
| `uri` | `{"type": "string", "format": "uri", "maxLength": 2048}` | `SCHEMA_VIOLATION` |
| `duration` | `{"oneOf": [{"type": "string", "pattern": "^-?P.*$"}, {"type": "integer", "minimum": 0}]}` —— ISO-8601 时长字符串 **或** 非负整数毫秒 | `SCHEMA_VIOLATION` details `reason: "duration_format"` |
| `datetime` | `{"type": "string", "format": "date-time"}`（RFC 3339） | `SCHEMA_VIOLATION` |
| `enum` | `{"type": "string", "enum": [...]}` —— 允许的取值来自 schema 的 `enum` 数组 | `SCHEMA_VIOLATION` details `reason: "not_in_enum", allowed: [...]` |

**`duration` 消歧：** 当字面量是一个裸数字（例如 `5000`）时，它被解释为**整数毫秒**。当它是匹配 `^P` 的带引号字符串（例如 `"PT5M"`）时，它是一个 ISO-8601 时长。不匹配 ISO-8601 的带引号字符串会校验失败。Schema 作者**SHOULD**使用 `x-mcos-semantic: "duration-ms"` 或 `"duration-iso"` 来固定期望的形式，避免给 Planner 造成歧义。

**`bytes` 校验：** base64 字符串**MUST**是标准（而非 URL 安全的）base64，可以带或不带填充。解码失败或大小超过 10 MiB → `SCHEMA_VIOLATION`。

**`ref` 解析顺序：** 标注了 `x-mcos-ref` 的字段在管线第 4 阶段（Expand，见 [01-architecture.md §9.2](./01-architecture.md)）解析。原始参数值（例如 `"空调"`）会传给 `MemoryFacade.resolveRef()`，后者返回 `ResolveResult` 密封类（sealed class，[07 §5.0](./07-memory.md)）的三种变体：

- `Resolved(id)` —— 找到单个具体 id（例如 `"device:iot-ac-living-room-01"`）；该阶段替换并继续。
- `Ambiguous(candidates)` —— 发现多个匹配；该阶段向规划器返回 `Clarify` 结果，规划器必须请用户消歧后再重新提交。
- `NotFound` —— 无匹配；该阶段以 `SCHEMA_VIOLATION` 失败，details 为 `{ "path": "args.<field>", "reason": "ref_unresolvable", "ref": "<raw>" }`。

随后 schema 校验（第 5 阶段）对*解析后的*值（仅在 `Resolved` 路径上到达）进行校验。见 [07 §6.0](./07-memory.md) 规范性的 `resolveRef` 算法与三态映射。

---

## 6. 文本 DSL

### 6.1 调用形式

```text
command.id(arg1=value1, arg2=value2)
```

在 v0.1 中**不允许**位置参数（positional arguments，可降低 LLM 的歧义）。  
所有参数都是**命名参数**。

空参数：

```text
camera.scan()
weather.today()
```

### 6.2 字面量

```text
string   := "..." | '...'
number   := int | float
bool     := true | false
null     := null
array    := [ expr, expr, ... ]
object   := { key: expr, ... }
```

在 v0.1 中，直接执行的纯 DSL 脚本里**禁止**嵌套调用。  
工作流 IR 负责处理链式调用；Planner 产出的是工作流，而不是嵌套调用树。

允许：

```text
photo.compress(quality=80, uris=["content://1", "content://2"])
```

在 v0.1 中不允许：

```text
mail.send(to="Tom", attachment=photo.compress(quality=80))
```

### 6.3 相对 / 语义语法糖（由解析器展开）

可选的语法糖**可以**为人类启用；规范 IR 始终存储展开后的形式。

| 语法糖 | 展开方向 |
|-------|----------------|
| `date="today"` | 本地时区下对应 RFC 3339 日期范围 |
| `name="空调"` | 通过记忆 / 设备目录解析 |

语法糖的展开发生在权限检查**之前**，且必须被记录到日志。

### 6.4 注释与多语句

```text
# comment
camera.capture()
photo.compress(quality=80)
```

多语句脚本是**一个调用列表**（隐式的顺序工作流），而不是一门完整的语言。

### 6.5 DSL 版本头（可选）

```text
# mcos-dsl: 0.1
home.light.on(id="living-room")
```

### 6.6 词法器 Token 规范

词法器产生如下 token 流。token 之间的空白与注释会被丢弃（版本头除外，它本身就是一个独立的 token）。

| Token | 模式（正则，在跳过空白之后） | 说明 |
|-------|----------------------------------------|-------|
| `HEADER` | `^#\s*mcos-dsl:\s*(\d+\.\d+)\s*$`（行锚定，仅首行） | 捕获 `dslVersion`。仅当它是输入的第一个非空行时才被识别。 |
| `COMMENT` | `#[^\n]*` | 丢弃。任何不匹配 `HEADER` 的其它 `#`。 |
| `IDENT` | `[a-zA-Z][a-zA-Z0-9-]*` | 命令 ID 段与参数名。规范形式为小写，但词法器接受任意大小写（规范化时转为小写）。 |
| `DOT` | `\.` | 命名空间/名称分隔符。 |
| `LPAREN` | `\(` | |
| `RPAREN` | `\)` | |
| `LBRACKET` | `\[` | |
| `RBRACKET` | `\]` | |
| `LBRACE` | `\{` | |
| `RBRACE` | `\}` | |
| `COMMA` | `,` | |
| `COLON` | `:` | 对象字段分隔符。 |
| `EQUALS` | `=` | 参数名/值分隔符。 |
| `STRING` | `("([^"\\]|\\.)*")\|('([^'\\]|\\.)*')` | 转义见 §18.1。 |
| `NUMBER` | `-?(0\|[1-9][0-9]*)(\.[0-9]+)?` | int/float 边界见 §6.8。 |
| `BOOL` | `true\|false` | 关键字；在 `IDENT` 之前匹配。 |
| `NULL` | `null` | 关键字；在 `IDENT` 之前匹配。 |
| `EOF` | — | 输入结束。 |

**空白：** 空格（`U+0020`）、制表符（`U+0009`）、换行（`U+000A`）、回车（`U+000D`）都是空白并被跳过。输入开头的 UTF-8 BOM（`U+FEFF`）被允许并跳过一次；出现在其它位置的 BOM 会触发 `PARSE_ERROR`。垂直制表符 / 换页符在 DSL v0.1 中**不是**空白（被拒绝，以保持语法紧凑）。

**关键字与标识符：** `true`、`false`、`null` 只有作为完整的值 token（而非前缀）出现时才被匹配为 `BOOL`/`NULL`。`trueValue` 是一个 `IDENT`，而不是 `BOOL` + `IDENT`。

**最长匹配：** 词法器总是匹配最长的合法 token。`home.light` 是 `IDENT DOT IDENT`，而不是单个 token。

### 6.7 错误位置精度

错误 `location: {line, column}` 遵循以下规则，以确保各实现产生一致的诊断：

1. **行与列从 1 开始计数。** 输入的第一个字符是第 1 行第 1 列。
2. **列按 Unicode 码点计数**，而不是按 UTF-16 码元或字节。一个多字节字符（例如 `空`，3 个 UTF-8 字节 / 1 个码点）使列前进 **1**。
3. **位置指向出错 token 的起始处**（即开始该错误的第一个字符），而非末尾，也非出错字符之后。示例：在 `camera.capture("front")` 中，位置参数错误的报错点指向第 16 列 —— `"front"` 的起始 `"`。
4. **换行处理：** `\r\n` 算作一次换行（列在 `\n` 处重置）。单独的 `\r` 也会重置列。
5. **输入结束错误**（例如未闭合的调用）指向输入最后一个字符**之后一列**。示例：`camera.capture(quality=80`（22 个字符）报第 23 列。
6. `message` 字符串是人类可读的，**可以**因实现而异；`code` 与 `location` **MUST**精确一致以满足一致性要求，**例外**：当错误的检测点本身就存在歧义时 —— 一致性套件对嵌套调用错误**MUST**接受 **±1 列**的容差（解析器在内部标识符之后的 `(` 处检测到嵌套，该位置位于嵌套 token 内部第 1 列；token 起始列与内部 `(` 列二者均可接受）。此例外仅适用于嵌套调用情形；所有其它位置必须精确。

### 6.8 数字字面量边界

| 规则 | 细节 |
|------|--------|
| 整数范围 | 有符号 64 位：`−9,223,372,036,854,775,808` 到 `9,223,372,036,854,775,807`。越界 → `PARSE_ERROR` details `{ "reason": "int_overflow" }`。 |
| 浮点格式 | IEEE 754 双精度。按 `double` 解析；超出约 15–17 位有效数字的精度损失被接受（与 JSON 一致）。 |
| 指数表示法 | **在 v0.1 中禁止**（`1e3`、`1.5E-2` 被拒绝），以降低 LLM 歧义。请直接使用整数或十进制形式。留待未来一次 minor 提升时重新考虑。 |
| 前导零 | 非零整数禁止前导零：`007` → `PARSE_ERROR`。单独的 `0` 合法。`0.8` 合法（小数点前的前导零允许）。 |
| 正负号 | 允许前导 `-`（`-1`、`-0.5`）。**不允许** `+` 前缀。 |
| 负零 | `-0` 被接受：整数规范为 `0`，浮点规范为 `-0.0`（IEEE 754，原样保留，因为 `-0.0 == 0.0` 数值上相等）。 |
| 小数点 | 浮点数**MUST**在小数点两侧都有数字：`0.5` 合法，`.5` 非法，`5.` 非法。 |
| 尾部垃圾 | `80abc` → `PARSE_ERROR`（词法器先匹配 `80`，然后在值位置上对 `abc` 失败）。 |

**int 与 float 的区分：** 不含小数点的字面量是 `int`；含小数点的是 `float`。随后由 schema（§5.4）决定该*类型*是否可接受 —— 一个 `int` 字面量传给 `float` 字段被接受（拓宽）；一个 `float` 字面量传给 `int` 字段被以 `SCHEMA_VIOLATION` 拒绝（绝不隐式收窄）。

### 6.9 字符串字面量边界

| 规则 | 细节 |
|------|--------|
| 最大长度 | 每个字符串字面量最多 **65,536 个码点**（64K）。更长 → `PARSE_ERROR` details `{ "reason": "string_too_long", "max": 65536 }`。二进制数据应使用 `bytes` / URI，而非巨型字符串。 |
| 编码 | DSL 源码为 UTF-8。字符串在解析时被规范化为 **NFC**（Unicode 规范化形式 C），从而让规范等价的输入产生相同的 IR。 |
| 空字符串 | `""` 与 `''` 合法，产生空字符串 `""`。 |
| 代理对 | `\uXXXX` 转义只能编码 **BMP**（U+0000–U+FFFF）。裸代理码元（`\uD800`–`\uDFFF`）后跟另一个 `\uXXXX`（UTF-16 代理对技术）被**禁止** → `PARSE_ERROR`。要编码一个星平面字符（例如 U+1F600 😀），请在 UTF-8 源码中直接写出**原始字符**；不要使用代理对。 |
| 空字节 | `\u0000` 被允许（产生 U+0000），但 schema **SHOULD**通过 `"pattern": "^[^\u0000]*$"` 对大多数字段拒绝它。 |
| 拼接 | 不存在跨行的隐式字符串拼接。字符串字面量是单行的；未转义的原始换行 → `PARSE_ERROR`（见 §18.1）。 |

### 6.10 输入规模上限（DoS 防护）

不可信输入（Planner 输出、粘贴的 DSL、或通过 API 接收的 IR）在 UI 线程之外解析（[01 §8](./01-architecture.md)）。为限制最坏情况下的解析时间，运行时在解析**开始之前**强制执行以下上限。预检查为 O(1)（字节计数 + 嵌套深度的快速扫描），在任何 dispatcher 上都安全。

| 维度 | 上限 | 超出 → |
|------|------|--------|
| 每脚本 token 数 | 4096 | `PARSE_ERROR` details `{ "reason": "token_limit", "max": 4096 }` |
| 嵌套深度（数组/对象） | 32 | `PARSE_ERROR` details `{ "reason": "nesting_depth", "max": 32 }` |
| 语句数（多语句 DSL） | 64 | `PARSE_ERROR` details `{ "reason": "statement_limit", "max": 64 }` |
| 输入总字节数 | 256 KB（262 144） | `PARSE_ERROR` details `{ "reason": "size_limit", "max": 262144 }` |

---

## 7. 中间表示（IR）

规范 IR 为 JSON。

### 7.1 单次调用

```json
{
  "dslVersion": "0.1",
  "type": "invoke",
  "id": "photo.compress",
  "args": {
    "quality": 80,
    "uris": ["content://media/1"]
  },
  "meta": {
    "source": "planner",
    "confidence": 0.86,
    "utteranceId": "u_123"
  }
}
```

### 7.2 顺序脚本

```json
{
  "dslVersion": "0.1",
  "type": "sequence",
  "steps": [
    { "type": "invoke", "id": "camera.capture", "args": {} },
    { "type": "invoke", "id": "photo.compress", "args": { "quality": 80 } }
  ]
}
```

**序列中的输出绑定。** 序列步骤可以声明 `"saveAs": "<name>"` 来暴露其结果，供后续步骤通过 `$ref` 绑定引用。绑定使用与工作流 IR 相同的 `$ref` + `__steps.<id>.value.<path>` 语法（[05 §6.0](./05-workflow.md)）——不存在数组下标或 `{{...}}` 形式。示例：

```json
{
  "type": "sequence",
  "steps": [
    { "type": "invoke", "id": "maps.search", "args": { "query": "office" }, "saveAs": "search" },
    { "type": "invoke", "id": "maps.navigate", "args": { "dest": { "$ref": "search.value.placeId" } } }
  ]
}
```

`$ref` 对象由运行时在阶段 4（Expand）解析。如果 Planner 需要分支、并行、等待或补偿，它必须改为发出工作流 IR（[§7.3](#73-workflow-reference)）——序列严格为有序调用，可选地附带输出绑定。

### 7.3 工作流引用

复杂图使用工作流 IR（见 [05-workflow.md](./05-workflow.md)）：

```json
{
  "dslVersion": "0.1",
  "type": "workflow",
  "workflowId": "wf_home_movie",
  "body": { "...": "Workflow IR" }
}
```

### 7.4 规范化

在哈希 / 审计之前：

1. 将命令 ID 转为小写  
2. 按字典序对对象键排序  
3. 除非 schema 显式允许 `additionalProperties`，否则拒绝未知字段  
4. 依据 schema 类型规范化数字（`1.0` 与 `1`）

### 7.5 规范化算法（规范性）

上述四条规则由以下递归过程实现。它是确定性的：两个语义等价的 DSL 输入会产生逐字节相同的规范 IR。

```text
canonicalize(node, schema?):
  # 1. 顶层 invoke/sequence/workflow 节点
  if node.type == "invoke":
      node.id = lowercase(node.id)
      node.args = canonicalizeValue(node.args, inputSchema.properties)
      # node.meta 不排序 —— 见下方说明
  elif node.type == "sequence":
      for step in node.steps:
          canonicalize(step)         # 每个 step 是一个 invoke
  elif node.type == "workflow":
      pass                            # body 是工作流 IR（见 05-workflow.md）

  # 2. 拒绝未知的顶层字段，除非 schema 允许 additionalProperties
  for key in node (顶层键：dslVersion, type, id, args, meta, steps, workflowId, body):
      if key not in ALLOWED_TOP_KEYS and not schema.additionalProperties:
          error PARSE_ERROR { reason: "unknown_field", field: key }

  return node

canonicalizeValue(value, schema):
  if value is OBJECT:
      sorted = {}
      for key in sorted(value.keys, lexicographic by Unicode code point):
          childSchema = schema.properties[key] if exists else null
          sorted[key] = canonicalizeValue(value[key], childSchema)
      return sorted
  if value is ARRAY:
      # 元素不排序 —— 数组顺序具有语义含义
      return [ canonicalizeValue(elem, schema.items) for elem in value ]
  if value is NUMBER and schema.type == "integer":
      return normalizeInt(value)      # 去掉前导零，把 -0 规范为 0
  if value is NUMBER and schema.type == "number":
      return normalizeFloat(value)    # IEEE 754 规范形式，保留 -0.0
  return value                         # string/bool/null/enum/uri/datetime：保持不变
```

**关键澄清：**

- **对象键被递归排序**，包括 `args` 内部的嵌套对象。排序按 Unicode 码点（按 UTF-32 值的字典序），与对排序后键的对象做 `JSON.stringify` 一致。
- **数组元素绝不排序。** 数组顺序具有语义含义（例如 `uris=["a","b"]` ≠ `uris=["b","a"]`）。
- **`meta` 字段不被规范化或排序。** 它携带的是运行时来源信息（`source`、`confidence`、`utteranceId`），不属于业务数据；对其键排序无意义，且来源信息的顺序对审计可能很重要。不过，`meta` 的键仍是固定的封闭集合（见 §8.2）—— `meta` 中的未知键会被拒绝。
- **数字规范化取决于 schema 类型**，而非字面量形式。当 schema 为 `integer` 时，`80` 与 `80.0` 都规范化为 `80`；当 schema 为 `number` 时，两者分别保持 `80` / `80.0` 不变。
- **哈希：** 规范 IR 的 UTF-8 JSON 字节序列正是审计记录与固定（pinned）工作流所哈希的内容（例如 SHA-256）。两次运行只要规范 IR 相同，就产生相同的哈希。

---

## 8. 命令描述符（注册表条目）

规范字段：

```json
{
  "id": "camera.capture",
  "version": "1.0.0",
  "pluginId": "mcos.plugin.camera",
  "title": "Capture photo",
  "description": "Takes a photo using the default rear camera.",
  "inputSchema": { "$ref": "..." },
  "outputSchema": { "$ref": "..." },
  "permissions": [
    { "type": "android", "name": "android.permission.CAMERA" },
    { "type": "mcos", "name": "plugin.camera.execute" }
  ],
  "sideEffectClass": "write",
  "idempotent": false,
  "timeoutMs": 60000,
  "tags": ["media", "camera"],
  "examples": [
    "camera.capture()",
    "camera.capture(facing=\"front\")"
  ],
  "deprecated": false,
  "replacedBy": null
}
```

### 8.1 `sideEffectClass`

| Class | 含义 | 默认确认 |
|-------|---------|----------------------|
| `read` | 无持久变化 | 无 |
| `write` | 创建 / 修改数据 | 首次运行或按策略 |
| `destructive` | 删除 / 不可逆 | 总是确认 |
| `network` | 离开设备边界 | 按策略 / 首次运行 |
| `control` | 驱动设备 / IoT / VPN | 除非已信任，否则确认 |

策略可以收紧规则；但不得低于用户全局设置而放宽。

### 8.2 `meta` 字段规范

IR 的 `meta` 对象携带的是**运行时来源信息**，而非业务数据。它由 Planner 注入（第 1 阶段，LLM 来源归属），以及由运行时注入（第 5–6 阶段，分布式链路追踪字段）。它的键是一个固定的封闭集合；未知键会在规范化时被拒绝（§7.5）。

| 字段 | 类型 | 必需 | 注入方 | 含义 |
|-------|------|----------|-------------|---------|
| `source` | `"user"` \| `"llm"` \| `"workflow"` \| `"automation"` | 是 | Planner | 谁/什么产生了这次调用 |
| `confidence` | `float` ∈ [0.0, 1.0] | 仅当 `source="llm"` | Planner | LLM 对解析出的意图自报的置信度 |
| `utteranceId` | `string`（uuid） | 仅当 `source="llm"` | Planner | 源话语的稳定 id；用于把多次重试/换词归组 |
| `correlationId` | `string`（uuid） | 是 | 运行时（第 5 阶段） | 单次调用的端到端关联 id；会传播到审计、链路、插件日志 |
| `traceId` | `string`（uuid） | 是 | 运行时（第 5 阶段） | 分布式链路根 id；嵌套的插件调用共享同一个 `traceId` 并发出子 span |

**生命周期：** `source` / `confidence` / `utteranceId` 一旦由 Planner 发出 IR 就固定不变，后续阶段**绝不**修改。`correlationId` / `traceId` 在第 5 阶段（Resolve）开始时加盖，并贯穿审计保持不变。一次工作流运行会复用父运行的 `traceId`，但为每个子调用加盖一个新的 `correlationId`。

### 8.3 错误 `details` 各错误码的 Schema

存在**两种**错误信封形状，按失败发生的时机区分：

**A. 解析期错误**（第 1–2 阶段，尚不存在任何命令/运行身份）。由于解析本身就失败了，因此没有 `commandId`/`runId`。`location` 直接位于 `error` 顶层（而非 `details` 内），因为每个解析错误都带有一个 location，且一致性 fixture 在此处断言它：

```json
{ "ok": false, "error": { "code": "PARSE_ERROR", "message": "...", "location": { "line": 1, "column": 16 } } }
```

| 字段 | 必填 | 说明 |
|-------|----------|-------|
| `location: {line, column}` | 是（仅 `PARSE_ERROR`） | 1 起始索引；见 §6.7 |
| `reason: string` | 可选 | 机器可读的子原因，例如 `"leading_zero"`、`"int_overflow"`、`"unsupported_version"` |
| `token: string` | 可选 | 违规的词法单元 |
| `expected: string[]` | 可选 | 解析器当时期望的 token 类型集合 |

**B. 运行期失败**（第 3 阶段及以后，命令已解析且已生成 `runId`）。携带完整身份与一个按错误码固定的 `details` 对象：

```json
{ "ok": false, "commandId": "...", "runId": "...", "error": { "code": "...", "message": "...", "retryable": bool, "details": { ... } } }
```

`details` 的形状按错误码固定。各实现**MUST**填入列出的必填字段；缺省的可选字段应省略（而不是 `null`）。

| Code | `details` 必填字段 | `details` 可选字段 |
|------|---------------------------|---------------------------|
| `UNKNOWN_COMMAND` | `requestedId: string` | `suggestions: string[]`（按编辑距离最近的 ≤3 个目录 id） |
| `SCHEMA_VIOLATION` | `path: string`（JSON-pointer，例如 `/args/uris/0`）、`expected: string`（类型或关键字）、`actual: any` | `schemaPath: string` |
| `PERMISSION_DENIED` | `permission: string`、`sideEffectClass: string` | `missingRole: string[]` |
| `CONFIRMATION_REQUIRED` | `sideEffectClass: string` | `prompt: string` |
| `TIMEOUT` | `timeoutMs: integer`、`elapsedMs: integer` | `stage: string`（哪个阶段超时） |
| `CANCELLED` | `reason: "user"` \| `"timeout"` \| `"parent"` \| `"system"` | `elapsedMs: integer` |
| `PLUGIN_ERROR` | `exitCode: string`（插件自定义） | `pluginMessage: string`、`retryable: boolean`（默认按描述符） |
| `UNAVAILABLE` | `component: string` | `retryable: boolean`（默认 `true`） |
| `RATE_LIMITED` | `retryAfterMs: integer` | `bucket: string`（哪个限流桶） |
| `CONFLICT` | `reason: "duplicate_id"` → `resolvedId: string`、`requestedId: string`、`winningManifest: string`；**或** `reason: "device_locked"` → `heldDevice: string`、`requestedDevice: string`、`runId: string` | `duplicateManifest: string`（仅 `duplicate_id`） |
| `INTERNAL` | `component: string`（例如 `"scheduler"`、`"executor"`） | `stackHash: string`（用于去重的稳定哈希；绝不使用原始栈） |

**说明：**

- `details` 对象是**可扩展的** —— 实现可以添加额外键，但一致性套件只断言必填字段。未来版本引入的新错误码遵循同样规则：在 RFC 修订中声明其必填/可选字段集合。
- `SCHEMA_VIOLATION.path` 使用 **RFC 6901 JSON-pointer** 表示法，根锚定在调用节点（因此 `/args/uris/0` 指向 `uris` 参数的第一个元素）。
- `INTERNAL.stackHash` 是栈的一个稳定短哈希，用于崩溃去重与聚合；原始栈**绝不**被序列化进 `details`（出于安全 / PII 考虑）。
- `PARSE_ERROR` 是**唯一**使用形状 A 的错误码；它绝不会出现在运行期信封中，因为解析发生在运行创建之前。

---

## 9. 执行语义

### 9.1 校验管线

```text
1. Lex / parse DSL → IR
2. Resolve command ID in Registry (exact version policy)
3. Expand sugar / memory refs
4. Validate args against inputSchema
5. Permission Kernel authorize
6. Scheduler enqueue
7. Executor invoke handler
8. Validate output against outputSchema (dev/strict mode)
9. Audit append
```

在处理器被调用之前，任何步骤的失败都**MUST NOT**引发副作用。

### 9.2 超时

描述符中的 `timeoutMs` 由执行器强制执行。发生超时时：

- 若支持则协作式地取消任务  
- 发出 `Failure(code="TIMEOUT")`  
- 除非工作流重试策略另有规定，否则不重试  

### 9.3 取消

运行携带一个取消令牌。插件 **SHOULD** 定期检查它。  
被取消的运行发出 `Failure(code="CANCELLED")`。

### 9.4 幂等性

若 `idempotent: true`，工作流重试可以安全地再次调用。  
若为 false，则重试需要显式策略或补偿步骤。

### 9.5 管线交叉引用

本 RFC 的 9 步校验管线（§9.1）是 [01-architecture.md](./01-architecture.md) §9 中定义的 **10 阶段执行管线**的紧凑视图。架构文档拆分了本 RFC 合并的两步；其映射关系为：

| 本 RFC §9.1 步骤 | 架构 §9 阶段 | 说明 |
|--------------------|-----------------------|-------|
| 1. Lex / parse DSL → IR | **Stage 1 — Parse** | 词法器 + 解析器产出原始 IR |
| — | **Stage 2 — Canonicalize** | 键排序、id 转小写、数字规范化（§7.5）。RFC 将其折叠进第 1 步。 |
| 2. Resolve command ID | **Stage 3 — Resolve** | 注册表查找、别名解析、版本选择 |
| 3. Expand sugar / memory refs | **Stage 4 — Expand** | 语法糖宏 + `Memory` 引用解引用 |
| 4. Validate args | **Stage 5 — ValidateInput** | 对照 `inputSchema` 进行 JSON-Schema 检查 |
| — | **Stage 6 — Authorize** | 权限内核决策（对应 RFC 第 5 步） |
| 5. Permission Kernel authorize | Stage 6 | （见上） |
| 6. Scheduler enqueue | **Stage 7 — Schedule** | 调度器 + 限流器 |
| 7. Executor invoke handler | **Stage 8 — Execute** | 插件处理器运行；产出 `value` 或抛出 |
| 8. Validate output | **Stage 9 — ValidateOutput** | `outputSchema` 检查（dev/strict 模式） |
| 9. Audit append | **Stage 10 — Audit** | 只追加记录 |

**当两份文档不一致时，以架构文档的阶段边界为准**（它是更细粒度的规范）；本 RFC 的 9 步列表是概念性摘要。

### 9.6 事务性边界

管线在 **Stage 7（Execute）** 处有一道清晰的事务性接缝。它之前的每个阶段都是**无副作用**的；从 Stage 7 起，运行可能已经修改了设备状态。

| 结果 | 失败所在阶段 | 可清理？ | 重试行为 |
|---------|----------------------|------------|----------------|
| `PARSE_ERROR` | 1 / 2 | ✅ 干净 | 调用方可修正输入后重试；未触碰任何状态 |
| `UNKNOWN_COMMAND` | 3 | ✅ 干净 | 不能原样重试（调用方必须修改 id） |
| `SCHEMA_VIOLATION` | 4 / 5 | ✅ 干净 | 修正参数后可重试 |
| `PERMISSION_DENIED` / `CONFIRMATION_REQUIRED` | 6 | ✅ 干净 | 授权/确认后可重试 |
| `TIMEOUT` / `CANCELLED` | 7+ | ⚠️ **可能已部分执行** | 插件可能已开始副作用；见下 |
| `PLUGIN_ERROR` | 7+ | ⚠️ 可能已部分执行 | 由插件决定；`idempotent` 决定是否可安全重试 |
| `UNAVAILABLE` / `RATE_LIMITED` | 7（偶尔更晚） | ✅ 干净 | 按 `retryAfterMs` 重试 |

**规则：**

1. **执行前的失败总是干净的。** 阶段 1–6 不触碰任何设备状态；任何在此区间的失败都使系统与调用前完全一致。一致性测试通过检查没有发出审计 `write`/`destructive`/`control` 记录来断言这一点。
2. **执行阶段的失败默认不可回滚。** MCOS 不提供自动事务。如果 `camera.capture()` 已启动传感器随后超时，传感器状态就是插件留下的样子。
3. **补偿是工作流层面的关注点，而非运行时。** 工作流作者**可以**声明 `onFailure: <compensate-invoke>`；运行时会运行该补偿步骤，但**不**保证原子性 —— 插件必须被编写为尽力补偿（例如在失败的 `note.create()` 之后执行 `note.delete()`）。
4. **幂等命令是安全的重试面。** 只有 `idempotent: true` 的命令可被工作流重试策略自动重试。非幂等命令需要显式的、按次的策略决策或用户确认。

---

## 10. 结果与错误

### 10.1 成功信封

```json
{
  "ok": true,
  "commandId": "weather.today",
  "runId": "run_abc",
  "value": {
    "summary": "晴",
    "tempC": 31
  },
  "artifacts": [],
  "durationMs": 142
}
```

### 10.2 失败信封

> ✅ **实现状态：** `retryable` 与 `details` 已实现——`CommandResult.Err(code, message, retryable, details: JsonObject)` 位于 `mcos-sdk`，由 Executor 的结构化错误映射填充。见 [11-implementation-status.md](./11-implementation-status.md) §7。

```json
{
  "ok": false,
  "commandId": "camera.capture",
  "runId": "run_abc",
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "CAMERA permission not granted",
    "retryable": false,
    "details": {}
  }
}
```

### 10.3 标准错误码

| Code | 含义 |
|------|---------|
| `PARSE_ERROR` | DSL/IR 语法非法 |
| `UNKNOWN_COMMAND` | 不在注册表中 |
| `SCHEMA_VIOLATION` | 参数未通过 schema 校验 |
| `PERMISSION_DENIED` | 缺少授权 |
| `CONFIRMATION_REQUIRED` | 软停止，等待用户 |
| `TIMEOUT` | 超出超时时间 |
| `CANCELLED` | 用户 / 系统取消 |
| `PLUGIN_ERROR` | 处理器抛出异常 / 返回失败 |
| `UNAVAILABLE` | 后端离线 / 设备忙碌 |
| `RATE_LIMITED` | 策略限流 |
| `INTERNAL` | 运行时 bug |
| `CONFLICT` | 资源争用（死锁、清单 ID 重复） |

插件 **SHOULD** 把厂商错误映射为这些错误码，并附加 `details`。

> **注：** 本表是完整 `McosErrorCode` 枚举的命令级子集。完整枚举（包括 `COMPILE_FAILED` 和五个工作流专用码 `WORKFLOW_INVALID` / `MAX_ITERATIONS_EXCEEDED` / `COMPENSATION_FAILED` / `JOIN_FAILED` / `TRIGGER_MISFIRE`）见 [01 §15.1](./01-architecture.md)。

---

## 11. 权限绑定

每一次调用都会评估以下各项的并集：

1. 命令描述符权限  
2. 插件级权限  
3. 运行时全局策略  

授权记录按如下方式建立键：

```text
(pluginId | commandId | androidPermission | scope)
```

关于授权的生存期与 UI 流程，见 [08-security.md](./08-security.md)。

**规范规则：** IR 不得包含任何能够静默申请新 Android 权限的方式。权限提示属于运行时的 UX。

---

## 12. 从相邻系统映射

### 12.1 MCP 工具

```text
MCP tool name:  read_file
Server id:      filesystem
Command id:     mcp.filesystem.read_file
```

在类型对齐的地方，参数按 1:1 映射；适配器会显式地转换不兼容之处。

### 12.2 应用函数（App Functions）

```text
Package:   com.example.notes
Function:  createNote
Command:   sys.appfn.com.example.notes.createNote
```

厂商 **MAY** 通过插件清单发布更友好的别名（`note.create` → 应用函数）。

### 12.3 Intent

以 `sys.intent.start` 表示，并带有**受 schema 约束**的 extras —— 而不是来自模型、未经校验的自由形式 Map。

### 12.4 MCP 工具 → MCOS Schema 转换表

MCP 适配器把每个 MCP 工具的 JSON Schema 逐字段转换为一个 MCOS `inputSchema`。该映射对常见类型是无损的，并且对不可映射的类型**失败即关闭**（适配器会拒绝注册该工具，而不是静默丢弃参数）。

| MCP / JSON-Schema 类型 | MCOS 基础类型 | 说明 |
|------------------------|----------------|-------|
| `string`（无 `format`） | `string` | `maxLength` 上限为 65536 |
| `string` + `format: "date-time"` | `datetime` | MCP 的 RFC 3339 == MCOS `datetime` |
| `string` + `format: "byte"` | `bytes` | base64；大小上限 10 MiB |
| `string` + `enum: [...]` | `enum` | `fromSchema: [...]` |
| `integer` | `int` | 有符号 64 位；范围检查 |
| `number` | `float` | IEEE 754 双精度 |
| `boolean` | `bool` | — |
| `array` | `array<T>` | 对 `items` 递归 |
| `object`（含 `properties`） | `object` | 按属性递归；`required` → MCOS required 列表 |
| `null` / `const: null` | `null` | 罕见；允许作为字面量参数 |
| `$ref`（本地） | `ref` | 在第 4 阶段 Expand 解析 |
| `oneOf` / `anyOf` | **不可映射** | v0.1 不支持联合类型 |
| `format: "uri"` | `uri` | MCOS `uri` 基础类型 |
| `format: "duration"` | `duration` | ISO-8601 形式 |

**不可映射类型：** 当适配器命中一个不可映射的关键字（`oneOf`、`anyOf`、带非平凡模式的 `patternProperties`、表中以外的 `format` 值）时，它会**不注册该工具**，并发出一条注册表日志，带 `details: { toolName, unmappedType, reason }`。该工具会被对 Planner 隐藏，直到其作者收紧 schema。这可防止参数被静默丢失。

**`description` 与 `examples`：** MCP 的 `description` 原样复制到描述符中每个参数的文档里；MCP 的 `examples`（如果存在）成为描述符的 `examples`。

### 12.5 应用函数包名编码

命令 ID 文法（§4.1）使用 `.` 作为命名空间分隔符，但 Java/Kotlin 包名也使用 `.`（例如 `com.example.notes`）。为保持命令 id 无歧义，适配器在生成命令 id 时**把包名中的每个 `.` 替换为 `_`**：

```text
Package:   com.example.notes       (4 segments)
Function:  createNote
Command:   sys.appfn.com_example_notes.createNote
```

**编码规则：**

1. 包名中的每个 `.` → `_`。所以 `com.example.notes` → `com_example_notes`。
2. 包名中原有的 `_` 被保留（无需转义；`_` 不是分隔符）。
3. 函数名追加在最后一个 `.` 之后：`….<functionName>`。
4. 生成的命令 id 始终形如 `sys.appfn.<encodedPackage>.<function>` —— 至少有三个点，绝不会与手工编写的 `note.create` 风格 id（只有一个点）混淆。

**反向查找：** 给定 `sys.appfn.com_example_notes.createNote`，运行时按 `.` 切分、去掉 `sys.appfn` 前缀与最后一段（函数）、再把 `_` → `.` 来还原包名。这是无歧义的，因为第 1 步是 1:1 的字符替换。

厂商**可以**额外通过插件清单发布一个友好的别名（`note.create`）；别名与生成的 id 都解析到同一处理器。

### 12.6 Intent Extras —— 强制 Schema

自由形式的 Android `Intent` extras（一个无类型的 `Bundle`）历史上是 LLM 幻觉的来源：模型会臆造出接收方无法理解的 extra 键。MCOS 拒绝这样做。规则是：

- **每个 `sys.intent.start` 调用 MUST 提供 `extrasSchema`。** 描述符的 `inputSchema` 要求 `extras` 是一个 `object`，其自身的 `properties` 由调用方（或由已发布插件清单针对知名 intent，例如 `android.intent.action.SEND`）声明。
- 如果调用方无法为 `extras` 声明 schema，该调用会在第 5 阶段被拒绝（`SCHEMA_VIOLATION`，`path: /args/extras`，`reason: "extras_schema_required"`）。
- 一小部分系统 intent 的允许清单在 `sys` 插件中以**预声明 schema**发布；对于这些，调用方可以省略 `extrasSchema`，运行时会对照已发布的 schema 进行校验。

**理由：** 强制要求 schema 把"模型猜错了一个 extra 键"从一个静默的运行时故障，变成一个 Planner 可以在下一轮自我纠正的第 5 阶段拒绝。

---

## 13. 示例（规范性示例）

### 13.1 相机

```text
camera.capture()
camera.capture(facing="front")
camera.scan()
```

### 13.2 照片

```text
photo.search(date="today")
photo.compress(quality=80)
photo.clean(olderThan="P30D", confirm=true)
```

### 13.3 通信

```text
mail.send(to="Tom", subject="Photos", body="FYI")
```

### 13.4 家庭 / IoT

```text
home.light.on(id="living-room")
home.scene.movie()
iot.ac.set(name="air-condition", power=true, tempC=26)
```

### 13.5 开发

```text
github.issue.create(repo="mcos/mcos", title="Bug")
github.pr.list(repo="mcos/mcos", state="open")
```

### 13.6 系统

```text
vpn.connect(profile="office")
calendar.next()
weather.today()
sys.notify(title="MCOS", text="Done")
```

---

## 14. 版本管理与兼容性

### 14.1 命令契约 SemVer

| 变更 | 版本提升 |
|--------|------|
| 新增可选参数 | MINOR |
| 插件中新增命令 | MINOR（插件） |
| 移除参数 / 改变含义 | MAJOR |
| 收紧校验，使此前合法的参数被拒绝 | MAJOR |
| 保持契约不变的 bug 修复 | PATCH |

### 14.2 DSL 语言版本（`dslVersion`）

`dslVersion` 使用**两段的 `MAJOR.MINOR` 简写**（例如 `"0.1"`），与用于命令契约的三段 SemVer（§14.1）不同。它版本化的是 *DSL 语法 / IR 形态*，而不是单个命令。

规则：

1. 运行时 **MUST** 拒绝其 **major** 部分不被支持的 `dslVersion`。  
2. 运行时 **MAY** 接受更旧的 **minor** 版本（向前兼容：较新的运行时可以读取较旧的 DSL）。  
3. **minor** 的提升以向后兼容的方式增加语法 / IR 特性；**major** 的提升可能会破坏解析。  
4. 当脚本中省略时，运行时假定采用其针对当前 major 所支持的最高 minor。

这就是为什么本 RFC 中的每一个示例与 fixture 都带有 `"dslVersion": "0.1"`（major 为 `0`，minor 为 `1`），而命令描述符则带有完整的 SemVer，如 `"1.0.0"`。

### 14.3 废弃

描述符设置 `deprecated: true` 与 `replacedBy`。Planner **SHOULD** 优先使用替代项。运行时 **MAY** 在 CLI 中给出告警。

---

## 15. 安全考量

1. 在 DSL v0.1 中**没有嵌套代码执行**。  
2. **schema 校验**是强制性的 —— 永远不要把原始模型 JSON 直接传给插件。  
3. **敏感参数**在审计中被脱敏（`x-mcos-secret`）。  
4. **destructive** 类别总是可要求确认。  
5. **网络出站**可观测，且受策略门控。  
6. 把 Planner 的输出当作**不可信输入**对待，等同于用户脚本。

---

## 16. 一致性

如果一个实现满足以下条件，则它是 **Command Protocol Conformant v0.1**（命令协议一致 v0.1）的：

1. 能够解析此处定义的 DSL 语法子集  
2. 能够针对发布在 [`docs/fixtures/`](../fixtures/) 下的黄金测试套件进行 DSL ↔ IR 的往返转换（正向用例必须匹配；负向用例必须被拒绝 —— 见 `../fixtures/README.md`）  
3. 在调用之前使用 JSON Schema 校验  
4. 发出 §10.3 中列出的标准错误码  
5. 遵守 `sideEffectClass` 的确认钩子  
6. 拒绝未知的命令 ID  

### 16.1 结构化测试矩阵

上述六条一致性要求被展开为下面这张显式的测试矩阵。一致性套件**MUST**通过每一行。标记为 **fixture** 的行在 [`docs/fixtures/`](../fixtures/) 下有已发布的黄金用例；标记为 **suggested** 的行描述套件应当补充（尚未发布）的用例。

| # | 类别 | 输入 | 必需行为 | Fixture |
|---|----------|-------|-------------------|---------|
| P1 | 正向 | `# mcos-dsl: 0.1`<br>`camera.capture()` | IR：`type:"invoke"`、`id:"camera.capture"`、`args:{}` | `01-empty-args` |
| P2 | 正向 | `hello.world(name="Tom")` | IR：单个 `string` 参数，`args:{name:"Tom"}` | `02-named-string` |
| P3 | 正向 | `photo.compress(quality=80, uris=["content://1","content://2"])` | IR：`int` + `array<string>`；键排序 → `quality, uris`；数组顺序保留 | `03-array-and-int` |
| P4 | 正向 | `# comment`<br>`camera.capture()`<br>`photo.compress(quality=80)` | IR：`type:"sequence"`，2 个步骤；注释被忽略 | `04-sequence` |
| P5 | 正向 | `home.light.set(id="living-room", on=true, brightness=0.8, meta=null)` | IR：`string`/`bool`/`float`/`null`；键排序 → `brightness, id, meta, on` | `05-mixed-literals` |
| N1 | 负向 | `mail.send(to="Tom", body=photo.compress())` | `PARSE_ERROR`；`location` 指向嵌套 `photo.compress` 的起始 | `06-nested-call` |
| N2 | 负向 | `camera.capture("front")` | `PARSE_ERROR`（位置参数）；`location` 指向 `"front"` | `07-positional-arg` |
| N3 | 负向 | `camera.capture(quality=80`（未闭合） | `PARSE_ERROR`；`location` 指向 EOF | `08-malformed` |
| S1 | 建议 | `a(n=99999999999999999999)` 整数溢出 | `PARSE_ERROR` `reason:"int_overflow"` | — |
| S2 | 建议 | `a(s="caf\u00e9")` `\u` BMP 转义 | 带 `é` 的 IR；列计数按码点 | — |
| S3 | 建议 | `# mcos-dsl: 0.1`<br>`# only a comment`（零次调用） | `PARSE_ERROR` `reason:"empty_script"` —— 一个脚本必须产出至少一次调用或一个非空 sequence | — |
| S4 | 建议 | `# mcos-dsl: 0.2`<br>`a()` 头版本不匹配 | `PARSE_ERROR` `reason:"unsupported_version"` | — |
| S5 | 建议 | `a(name="王小明")` 非 ASCII 参数 | 带原样 CJK 字符串的 IR；列计数使用码点 | — |
| S6 | 建议 | `A.B()` 混合大小写命令 id | 带 `id:"a.b"` 的 IR（按 §7.5 转为小写） | — |
| S7 | 建议 | `a(x=007)` 前导零 | `PARSE_ERROR` `reason:"leading_zero"` | — |

**一致性套件义务：**

- 对每个**正向**行：解析 `input.dsl`，断言产出的规范 IR 与 `expected.ir.json` 逐字节相等（在按 §7.5 键排序之后）。
- 对每个**负向**行：解析 `input.dsl`，断言错误信封与 `expected.error.json` 相等，包括 `code`、`message`（允许正则匹配）与 `location`。
- 对**建议**行，套件作者在 `docs/fixtures/<NNN-…>/` 下发布一个 fixture，并在固化后于此处链接。

> **关于 fixture 05 中 `meta` 的说明：** 这里的 `meta=null` 是一个名为 `meta` 的**业务参数**（灯具设备自身的元数据槽），**不是** §8.2 中 IR 层面的来源 `meta`。二者无关 —— IR 层面的 `meta` 是 `args` 的兄弟字段，而非 `args` 内部的一个键。一致性套件不可将二者混淆。

---

## 17. 未来扩展（非规范性）

- 带有显式 schema `x-mcos-positional` 的位置参数  
- 管道：`a() | b()` 作为顺序 + artifact 绑定的语法糖  
- 脚本中的类型化变量  
- 内嵌于 IR 中、用于多 Agent 委托的能力令牌  
- 用于提升设备端存储效率的 CBOR IR  

---

## 18. 参考语法（参考性）

```ebnf
script       ::= { statement }
statement    ::= invoke | comment
comment      ::= "#" { any-except-newline } newline
invoke       ::= command-id "(" [ args ] ")"
args         ::= arg { "," arg }
arg          ::= ident "=" value
value        ::= string | number | bool | null | array | object
array        ::= "[" [ value { "," value } ] "]"
object       ::= "{" [ field { "," field } ] "}"
field        ::= ident ":" value
command-id   ::= ident { "." ident }
ident        ::= letter { letter | digit | "-" }
```

### 18.1 字符串字面量与转义

字符串字面量由 `"…"` 或 `'…'` 界定。两种引号风格都接受下面同一套转义集合；它们的存在是为了让包含某一种引号的字符串可以不必对该引号进行转义就能写出（例如 `"it's"` 或 `'say "hi"'`）。

**支持的转义**（在任一引号风格中都适用）：

| Escape | 含义 |
|--------|---------|
| `\"` | 字面量 `"` |
| `\'` | 字面量 `'` |
| `\\` | 字面量 `\` |
| `\/` | 字面量 `/`（可选；用于保持 DSL 对 JSON 友好） |
| `\n` | 换行 U+000A |
| `\r` | 回车 U+000D |
| `\t` | 制表符 U+0009 |
| `\b` | 退格 U+0008 |
| `\f` | 换页 U+000C |
| `\uXXXX` | Unicode 码点（4 位十六进制，例如 `\u7a7a` → 空） |

**Unicode：** DSL 源码为 UTF-8。原始（未转义的）非 ASCII 字符在字符串字面量中**是**允许的 —— 例如 `iot.ac.set(name="空调")` 是合法的，并产生字面字符串 `空调`。提供 `\uXXXX` 形式是为了方便那些偏好纯 ASCII 输出的工具（例如某些 LLM 解码器）。

**会被作为 `PARSE_ERROR` 拒绝的情况：**

- 字符串中出现未转义的原始换行（字符串必须是单行）。  
- 字符串末尾悬空的反斜杠（`"trailing\"`）。  
- `\u` 之后没有恰好跟随 4 位十六进制数字。  
- 引号不匹配（`"abc'`）。

这些规则使 DSL 的字符串语法成为 JSON 字符串语义的严格子集，因此 IR 中的 `args` 值可以干净地往返转换为 JSON。

---

## 19. 总结

命令协议是 MCOS 的公共语言：

- 面向能力的**稳定 ID**  
- **类型化的命名参数**  
- **面向人类/LLM 的 DSL**，**面向引擎的 IR**  
- 在任何东西触及设备之前进行**严格校验 + 副作用类别**  

下一步：运行时如何消费本协议 —— 见 [03-runtime.md](./03-runtime.md)。
