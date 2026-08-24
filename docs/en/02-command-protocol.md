# MCOS Command Protocol (RFC)

> **Status:** Draft RFC  
> **RFC Number:** MCOS-RFC-0001  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-24  
> **Normative:** Yes  
> **Depends on:** [00-vision.md](./00-vision.md), [01-architecture.md](./01-architecture.md)

---

## Abstract

This RFC specifies the **Mobile Command OS Command Protocol**: the stable vocabulary and wire/AST forms by which humans, AI planners, and runtimes describe **invocable mobile capabilities**.

If only one document in this repository becomes industry-relevant, it should be this one.

---

## 1. Motivation

Mobile automation today is fragmented across Intents, deep links, Accessibility trees, vendor IoT SDKs, and ad-hoc agent tool JSON.

MCOS requires:

1. A **stable command identity** (`home.light.on`)  
2. **Typed arguments** with validation  
3. A **textual DSL** suitable for humans and LLMs  
4. A **structured IR** suitable for engines and storage  
5. Clear rules for **versioning, errors, permissions, and side effects**

AI must generate **commands**, not opaque side effects.

---

## 2. Terminology

| Term | Definition |
|------|------------|
| **Command ID** | Namespaced identifier, e.g. `camera.capture` |
| **Command Descriptor** | Metadata registered in the Command Registry |
| **Invocation** | One call of a command with concrete arguments |
| **DSL** | Human/AI-facing textual form |
| **IR** | JSON (or equivalent) intermediate representation |
| **Run** | One execution session containing one or more invocations / workflow steps |
| **Side-effect class** | Declared impact category used by policy |

---

## 3. Design Goals

### 3.1 Goals

- **Deterministic parse** of DSL ↔ IR  
- **LLM-friendly** syntax (familiar to JS/Python call style)  
- **Strict validation** before any plugin runs  
- **Forward-compatible** versioning  
- **Auditable** — every invocation reconstructible from logs  

### 3.2 Non-Goals

- Full general-purpose programming language  
- Arbitrary code execution (`eval`, shell)  
- Binary plugin ABI in this RFC (see SDK doc)  
- Natural language understanding (belongs to Planner)

---

## 4. Command Identity

### 4.1 Syntax

```abnf
namespace    = 1*( ALPHA / DIGIT / "-" )
name         = 1*( ALPHA / DIGIT / "-" )
command-id   = namespace 1*( "." name )
```

Examples:

```text
camera.capture
photo.search
home.scene.movie
github.pr.create
sys.notify
mcp.filesystem.read
```

### 4.2 Rules

1. **Lowercase** only in canonical form.  
2. Namespaces are owned by plugins or the `sys` / `mcp` reserved roots.  
3. Maximum length: **128** characters.  
4. Command IDs are **stable public API**; renames require major version + deprecation period.  
5. Multi-segment names after namespace are allowed (`home.scene.movie`).

### 4.3 Reserved Namespaces

| Namespace | Owner |
|-----------|-------|
| `sys` | MCOS system plugins |
| `mcp` | MCP adapter |
| `mcos` | Runtime introspection / meta commands |
| `std` | Reserved for future standard library |

### 4.4 Namespace Registration & Conflict Arbitration

A plugin declares the namespaces it owns in its manifest (`namespaces: [...]`). At Registry load time the Runtime applies this priority to decide who owns a namespace when two plugins claim it:

| Priority | Source |
|----------|--------|
| 1 (highest) | Reserved roots (`sys`, `mcp`, `mcos`, `std`) — only built-in / adapter plugins may register these |
| 2 | First plugin to declare the namespace in its manifest **and** pass signature verification |
| 3 | First plugin to load (fallback when no manifest declaration; emits a `WARN` audit event) |

**Conflict detection (at load time):** if a command ID already exists in the Registry when a second plugin tries to register it, the load is rejected with `UNKNOWN_COMMAND` (the command is treated as not available to *this* plugin) and a `details` payload of `{ "conflict": "duplicate_id", "existingPlugin": "<id>", "incomingPlugin": "<id>" }`. The already-registered command is **not** evicted. This keeps the contract stable: the first verified registrant wins.

**Version coexistence:** two plugins MAY register commands under the same namespace at *different* command names (e.g. `home.light.on` from plugin A, `home.scene.movie` from plugin B) without conflict — only identical full command IDs conflict.

### 4.5 Aliases

A command MAY declare `aliases: []` in its descriptor to publish additional command IDs that resolve to the same handler:

```json
{
  "id": "sys.notify",
  "aliases": ["sys.notification.send", "notify"]
}
```

Rules:

1. Aliases follow the same syntax and reserved-namespace rules as primary IDs (§4.1–4.2).
2. An alias MUST NOT collide with another command's primary ID or alias — checked at load time, same arbitration as §4.4.
3. The canonical IR always stores the **primary** `id`, never the alias. When a user/Planner invokes an alias, the Resolve stage (§9.1 step 2) maps it to the primary ID and the audit record records both (`requestedId` + `resolvedId`).
4. The Planner catalog (§12 of [01-architecture.md](./01-architecture.md)) MAY surface aliases as completion suggestions but MUST annotate them with `aliasOf`.

---

## 5. Type System

### 5.1 Primitive Types

| Type | JSON | DSL literal examples |
|------|------|----------------------|
| `string` | string | `"Tom"`, `'office'` |
| `int` | number (integral) | `80`, `-1` |
| `float` | number | `0.8`, `3.14` |
| `bool` | boolean | `true`, `false` |
| `null` | null | `null` |
| `bytes` | base64 string w/ annotation | (prefer IR for binary) |
| `uri` | string (URI format) | `"content://..."`, `"https://..."` |
| `duration` | string ISO-8601 duration or int ms | `"PT5M"`, `5000` |
| `datetime` | string RFC 3339 | `"2026-08-06T12:00:00+08:00"` |
| `enum` | string ∈ allowed set | `"high"` |

### 5.2 Composite Types

| Type | Description |
|------|-------------|
| `object` | Map of known properties (JSON Schema object) |
| `array<T>` | Homogeneous list |
| `union` | Discriminated or simple union (schema-defined) |
| `ref` | Reference to Memory entity / device id (string with `x-mcos-ref`) |

### 5.3 Schema Representation

Command input/output schemas **SHOULD** be expressible as **JSON Schema Draft 2020-12** with MCOS extensions:

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

Vendor extensions:

| Extension | Meaning |
|-----------|---------|
| `x-mcos-semantic` | Planner hint (`date-or-relative`, `contact`, `device`, …) |
| `x-mcos-secret` | Value redacted in logs |
| `x-mcos-ref` | Resolves via Memory before execute |
| `x-mcos-default-from-memory` | Key path in Memory profile |

### 5.4 Type → JSON Schema Mapping & Validation Bounds

Each MCOS primitive maps to a concrete JSON Schema fragment with default bounds. A descriptor MAY tighten these (e.g. add `minimum`/`maximum`), but the defaults below apply when the descriptor omits them.

| MCOS type | JSON Schema fragment (defaults) | Validation failure → |
|-----------|---------------------------------|---------------------|
| `string` | `{"type": "string", "maxLength": 65536}` | `SCHEMA_VIOLATION` |
| `int` | `{"type": "integer", "minimum": -2^63, "maximum": 2^63-1}` (signed 64-bit `Long`) | `SCHEMA_VIOLATION` |
| `float` | `{"type": "number"}` (IEEE 754 double) | `SCHEMA_VIOLATION` |
| `bool` | `{"type": "boolean"}` | `SCHEMA_VIOLATION` |
| `null` | `{"type": "null"}` | `SCHEMA_VIOLATION` |
| `bytes` | `{"type": "string", "contentEncoding": "base64", "maxLength": 10485760}` (10 MiB encoded) | `SCHEMA_VIOLATION` details `reason: "base64_invalid"` or `"size_exceeded"` |
| `uri` | `{"type": "string", "format": "uri", "maxLength": 2048}` | `SCHEMA_VIOLATION` |
| `duration` | `{"oneOf": [{"type": "string", "pattern": "^-?P.*$"}, {"type": "integer", "minimum": 0}]}` — ISO-8601 duration string **or** non-negative int milliseconds | `SCHEMA_VIOLATION` details `reason: "duration_format"` |
| `datetime` | `{"type": "string", "format": "date-time"}` (RFC 3339) | `SCHEMA_VIOLATION` |
| `enum` | `{"type": "string", "enum": [...]}` — allowed values come from the schema `enum` array | `SCHEMA_VIOLATION` details `reason: "not_in_enum", allowed: [...]` |

**`duration` disambiguation:** when the literal is a bare number (e.g. `5000`), it is interpreted as **integer milliseconds**. When it is a quoted string matching `^P` (e.g. `"PT5M"`), it is an ISO-8601 duration. A quoted string that does not match ISO-8601 fails validation. Schema authors SHOULD use `x-mcos-semantic: "duration-ms"` or `"duration-iso"` to pin the expected form and avoid ambiguity for the Planner.

**`bytes` validation:** the base64 string MUST be standard (not URL-safe) base64 with or without padding. Decoding failure or size > 10 MiB → `SCHEMA_VIOLATION`.

**`ref` resolution order:** a field annotated `x-mcos-ref` is resolved in pipeline Stage 4 (Expand, see [01-architecture.md §9.2](./01-architecture.md)). The raw arg value (e.g. `"空调"`) is passed to `MemoryFacade.resolveRef()`, which returns a `ResolveResult` sealed class ([07 §5.0](./07-memory.md)) with three variants:

- `Resolved(id)` — a single concrete id (e.g. `"device:iot-ac-living-room-01"`); the stage substitutes it and continues.
- `Ambiguous(candidates)` — multiple matches found; the stage returns a `Clarify` result to the Planner, which must ask the user to disambiguate before re-submitting.
- `NotFound` — no match; the stage fails with `SCHEMA_VIOLATION` details `{ "path": "args.<field>", "reason": "ref_unresolvable", "ref": "<raw>" }`.

Schema validation (Stage 5) then validates the *resolved* value (only reached on the `Resolved` path) against the schema. See [07 §6.0](./07-memory.md) for the normative `resolveRef` algorithm and the three-state mapping.

---

## 6. Textual DSL

### 6.1 Invocation Form

```text
command.id(arg1=value1, arg2=value2)
```

Positional arguments are **not** allowed in v0.1 (reduces LLM ambiguity).  
All arguments are **named**.

Empty args:

```text
camera.scan()
weather.today()
```

### 6.2 Literals

```text
string   := "..." | '...'
number   := int | float
bool     := true | false
null     := null
array    := [ expr, expr, ... ]
object   := { key: expr, ... }
```

Nested calls in v0.1: **forbidden** in pure DSL scripts executed directly.  
Workflow IR handles chaining; Planner emits workflows, not nested call trees.

Allowed:

```text
photo.compress(quality=80, uris=["content://1", "content://2"])
```

Not allowed in v0.1:

```text
mail.send(to="Tom", attachment=photo.compress(quality=80))
```

### 6.3 Relative / Semantic Sugar (Parser-Expanded)

Optional sugar **may** be enabled for humans; canonical IR always stores expanded forms.

| Sugar | Expands toward |
|-------|----------------|
| `date="today"` | RFC 3339 date range for local TZ |
| `name="空调"` | Memory / device directory resolution |

Sugar expansion happens **before** permission checks and must be logged.

### 6.4 Comments & Multi-Statement

```text
# comment
camera.capture()
photo.compress(quality=80)
```

Multi-statement scripts are a **list of invocations** (implicit sequential workflow), not a full language.

### 6.5 DSL Version Header (Optional)

```text
# mcos-dsl: 0.1
home.light.on(id="living-room")
```

### 6.6 Lexer Token Specification

The lexer produces the following token stream. Whitespace and comments are discarded between tokens (except the version header, which is a distinct token).

| Token | Pattern (regex, after whitespace skip) | Notes |
|-------|----------------------------------------|-------|
| `HEADER` | `^#\s*mcos-dsl:\s*(\d+\.\d+)\s*$` (line-anchored, first line only) | Captures `dslVersion`. Only recognized if it is the very first non-empty line. |
| `COMMENT` | `#[^\n]*` | Discarded. Any other `#` not matching `HEADER`. |
| `IDENT` | `[a-zA-Z][a-zA-Z0-9-]*` | Command-id segments and arg names. Lowercase canonical, but lexer accepts any case (canonicalization lowercases). |
| `DOT` | `\.` | Namespace/name separator. |
| `LPAREN` | `\(` | |
| `RPAREN` | `\)` | |
| `LBRACKET` | `\[` | |
| `RBRACKET` | `\]` | |
| `LBRACE` | `\{` | |
| `RBRACE` | `\}` | |
| `COMMA` | `,` | |
| `COLON` | `:` | Object field separator. |
| `EQUALS` | `=` | Arg name/value separator. |
| `STRING` | `("([^"\\]|\\.)*")\|('([^'\\]|\\.)*')` | Escapes per §18.1. |
| `NUMBER` | `-?(0\|[1-9][0-9]*)(\.[0-9]+)?` | See §6.8 for int/float bounds. |
| `BOOL` | `true\|false` | Keyword; matched before `IDENT`. |
| `NULL` | `null` | Keyword; matched before `IDENT`. |
| `EOF` | — | End of input. |

**Whitespace:** space (`U+0020`), tab (`U+0009`), newline (`U+000A`), carriage return (`U+000D`) are whitespace and skipped. A UTF-8 BOM (`U+FEFF`) at the start of input is permitted and skipped once; a BOM elsewhere is a `PARSE_ERROR`. Vertical tab / form feed are **not** whitespace in DSL v0.1 (rejected to keep the grammar tight).

**Keyword vs identifier:** `true`, `false`, `null` are matched as `BOOL`/`NULL` only when they appear as a complete value token (not a prefix). `trueValue` is an `IDENT`, not `BOOL` + `IDENT`.

**Maximal munch:** the lexer always matches the longest valid token. `home.light` is `IDENT DOT IDENT`, not a single token.

### 6.7 Error Location Precision

Error `location: {line, column}` follows these rules so implementations produce identical diagnostics:

1. **Line and column are 1-indexed.** The first character of input is line 1, column 1.
2. **Column is counted in Unicode code points**, not UTF-16 code units and not bytes. A multi-byte character (e.g. `空`, 3 UTF-8 bytes / 1 code point) advances the column by **1**.
3. **The location points to the start of the offending token** (the first character that begins the error), not the end and not the character after. Example: in `camera.capture("front")` the positional-arg error points at column 16 — the opening `"` of `"front"`.
4. **Newline handling:** `\r\n` counts as a single line break (column resets on `\n`). A lone `\r` also resets the column.
5. **End-of-input errors** (e.g. unterminated invocation) point at the column **one past the last character** of input. Example: `camera.capture(quality=80` (22 chars) reports column 23.
6. The `message` string is human-readable and MAY vary between implementations; the `code` and `location` MUST be exact for conformance, **except** for errors whose detection point is itself ambiguous — conformance suites MUST accept a tolerance of **±1 column** for the nested-call error (the parser detects the nesting at the `(` that follows the inner identifier, which is 1 column into the nested token; both the token-start column and the inner-`(` column are acceptable). This exception applies only to the nested-invocation case; all other locations must be exact.

### 6.8 Number Literal Bounds

| Rule | Detail |
|------|--------|
| Integer range | Signed 64-bit: `−9,223,372,036,854,775,808` to `9,223,372,036,854,775,807`. Out of range → `PARSE_ERROR` details `{ "reason": "int_overflow" }`. |
| Float format | IEEE 754 double precision. Parsed as `double`; precision loss beyond ~15–17 significant digits is accepted (matches JSON). |
| Exponent notation | **Forbidden in v0.1** (`1e3`, `1.5E-2` rejected) to reduce LLM ambiguity. Use the integer or decimal form directly. Revisit in a future minor bump. |
| Leading zeros | Forbidden for non-zero integers: `007` → `PARSE_ERROR`. `0` alone is valid. `0.8` is valid (leading zero before decimal point is allowed). |
| Sign | Leading `-` is allowed (`-1`, `-0.5`). `+` prefix is **not** allowed. |
| Negative zero | `-0` is accepted and canonicalized to `0` for integers, and to `-0.0` (IEEE 754) for floats (preserved as-is, since `-0.0 == 0.0` numerically). |
| Decimal point | A float MUST have digits on both sides of the decimal point: `0.5` valid, `.5` invalid, `5.` invalid. |
| Trailing junk | `80abc` → `PARSE_ERROR` (lexer matches `80` then fails on `abc` in value position). |

**Int vs float classification:** a literal without a decimal point is an `int`; with a decimal point it is a `float`. The schema (§5.4) then determines whether the *type* is acceptable — an `int` literal passed to a `float` field is accepted (widened); a `float` literal passed to an `int` field is rejected with `SCHEMA_VIOLATION` (narrowing is never implicit).

### 6.9 String Literal Bounds

| Rule | Detail |
|------|--------|
| Max length | **65,536 code points** per string literal (64K). Longer → `PARSE_ERROR` details `{ "reason": "string_too_long", "max": 65536 }`. Binary data should use `bytes` / URIs, not giant strings. |
| Encoding | DSL source is UTF-8. Strings are normalized to **NFC** (Unicode Normalization Form C) at parse time so canonically equivalent inputs produce identical IR. |
| Empty string | `""` and `''` are valid and produce the empty string `""`. |
| Surrogate pairs | `\uXXXX` escapes encode the **BMP** only (U+0000–U+FFFF). Bare surrogate code units (`\uD800`–`\uDFFF`) followed by another `\uXXXX` (the UTF-16 surrogate-pair technique) are **forbidden** → `PARSE_ERROR`. To encode an astral character (e.g. U+1F600 😀), write the **raw character** directly in the UTF-8 source; do not use surrogate pairs. |
| Null bytes | `\u0000` is permitted (produces U+0000) but schemas SHOULD reject it for most fields via `"pattern": "^[^\u0000]*$"`. |
| Concatenation | No implicit string concatenation across lines. A string literal is single-line; an unescaped literal newline → `PARSE_ERROR` (per §18.1). |

### 6.10 Input Size Bounds (DoS Prevention)

Untrusted input (Planner output, pasted DSL, or IR received via API) is parsed off the UI thread ([01 §8](./01-architecture.md)). To bound worst-case parse time, the Runtime enforces these limits **before** parsing begins. The pre-check is O(1) (a byte count + a quick scan for nesting depth) and safe on any dispatcher.

| Dimension | Limit | Exceeds → |
|-----------|-------|-----------|
| Token count per script | 4096 | `PARSE_ERROR` details `{ "reason": "token_limit", "max": 4096 }` |
| Nesting depth (arrays / objects) | 32 | `PARSE_ERROR` details `{ "reason": "nesting_depth", "max": 32 }` |
| Statement count (multi-statement DSL) | 64 | `PARSE_ERROR` details `{ "reason": "statement_limit", "max": 64 }` |
| Total input bytes | 256 KB (262 144) | `PARSE_ERROR` details `{ "reason": "size_limit", "max": 262144 }` |

---

## 7. Intermediate Representation (IR)

Canonical IR is JSON.

### 7.1 Single Invocation

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

### 7.2 Sequential Script

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

**Output binding in sequences.** A sequence step MAY declare `"saveAs": "<name>"` to expose its result for `$ref` binding by subsequent steps. The binding uses the same `$ref` + `__steps.<id>.value.<path>` grammar as Workflow IR ([05 §6.0](./05-workflow.md)) — there is no array-index or `{{...}}` form. Example:

```json
{
  "type": "sequence",
  "steps": [
    { "type": "invoke", "id": "maps.search", "args": { "query": "office" }, "saveAs": "search" },
    { "type": "invoke", "id": "maps.navigate", "args": { "dest": { "$ref": "search.value.placeId" } } }
  ]
}
```

The `$ref` object is resolved by the Runtime at Stage 4 (Expand). If the Planner needs branching, parallelism, waits, or compensation, it MUST emit a Workflow IR ([§7.3](#73-workflow-reference)) instead — sequences are strictly ordered invokes with optional output binding.

### 7.3 Workflow Reference

Complex graphs use Workflow IR (see [05-workflow.md](./05-workflow.md)):

```json
{
  "dslVersion": "0.1",
  "type": "workflow",
  "workflowId": "wf_home_movie",
  "body": { "...": "Workflow IR" }
}
```

### 7.4 Canonicalization

Before hashing / audit:

1. Lowercase command IDs  
2. Sort object keys lexicographically  
3. Reject unknown fields unless `additionalProperties` explicitly allowed by schema  
4. Normalize numbers (`1.0` vs `1` per schema type)

### 7.5 Canonicalization Algorithm (Normative)

The four rules above are realized by this recursive procedure. It is deterministic: two semantically equivalent DSL inputs produce byte-identical canonical IR.

```text
canonicalize(node, schema?):
  # 1. Top-level invoke/sequence/workflow node
  if node.type == "invoke":
      node.id = lowercase(node.id)
      node.args = canonicalizeValue(node.args, inputSchema.properties)
      # node.meta is NOT sorted — see note below
  elif node.type == "sequence":
      for step in node.steps:
          canonicalize(step)         # each step is an invoke
  elif node.type == "workflow":
      pass                            # body is Workflow IR (see 05-workflow.md)

  # 2. Reject unknown top-level fields unless schema allows additionalProperties
  for key in node (top-level keys: dslVersion, type, id, args, meta, steps, workflowId, body):
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
      # Elements are NOT sorted — array order has semantic meaning
      return [ canonicalizeValue(elem, schema.items) for elem in value ]
  if value is NUMBER and schema.type == "integer":
      return normalizeInt(value)      # strip leading zeros, canonicalize -0 → 0
  if value is NUMBER and schema.type == "number":
      return normalizeFloat(value)    # IEEE 754 canonical, preserve -0.0
  return value                         # string/bool/null/enum/uri/datetime: unchanged
```

**Key clarifications:**

- **Object keys are sorted recursively**, including nested objects inside `args`. Sorting is by Unicode code point (lexicographic on the UTF-32 value), matching `JSON.stringify` of a sorted-keys object.
- **Array elements are NEVER sorted.** Array order is semantically meaningful (e.g. `uris=["a","b"]` ≠ `uris=["b","a"]`).
- **The `meta` field is NOT canonicalized or sorted.** It carries runtime provenance (`source`, `confidence`, `utteranceId`) that is not business data; sorting its keys is unnecessary and provenance order may be significant for audit. However, `meta` keys are still a fixed set (see §8.2) — unknown keys in `meta` are rejected.
- **Number normalization depends on the schema type**, not the literal form. `80` and `80.0` both canonicalize to `80` when the schema says `integer`; both stay as `80` / `80.0` respectively (unchanged) when the schema says `number`.
- **Hashing:** the canonical IR's UTF-8 JSON byte sequence is what audit records and pinned workflows hash (e.g. SHA-256). Two runs with the same canonical IR produce the same hash.

---

## 8. Command Descriptor (Registry Entry)

Normative fields:

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

| Class | Meaning | Default confirmation |
|-------|---------|----------------------|
| `read` | No lasting change | None |
| `write` | Creates/modifies data | First-run or policy |
| `destructive` | Deletes / irreversible | Always confirm |
| `network` | Leaves device boundary | Policy / first-run |
| `control` | Actuates device / IoT / VPN | Confirm unless trusted |

Policies may tighten; they must not loosen below user global settings.

### 8.2 `meta` Field Specification

The IR `meta` object carries **runtime provenance**, not business data. It is injected by the Planner (Stage 1, LLM source attribution) and the Runtime (Stages 5–6, distributed-tracing fields). Its keys are a fixed closed set; unknown keys are rejected at canonicalization (§7.5).

| Field | Type | Required | Injected by | Meaning |
|-------|------|----------|-------------|---------|
| `source` | `"user"` \| `"llm"` \| `"workflow"` \| `"automation"` | yes | Planner | Who/what produced this invoke |
| `confidence` | `float` ∈ [0.0, 1.0] | only when `source="llm"` | Planner | LLM self-reported confidence in the parsed intent |
| `utteranceId` | `string` (uuid) | only when `source="llm"` | Planner | Stable id of the originating utterance; groups retries/re-paraphrases |
| `correlationId` | `string` (uuid) | yes | Runtime (Stage 5) | End-to-end correlation id for this single invoke; propagates into audit, traces, plugin logs |
| `traceId` | `string` (uuid) | yes | Runtime (Stage 5) | Distributed-trace root id; nested plugin calls share the same `traceId` and emit child spans |

**Lifecycle:** `source` / `confidence` / `utteranceId` are fixed once the Planner emits the IR and **never mutated** by later stages. `correlationId` / `traceId` are stamped at the start of Stage 5 (Resolve) and propagate unchanged through audit. A Workflow run reuses the parent run's `traceId` but stamps a fresh `correlationId` per child invoke.

### 8.3 Error `details` Schema by Code

There are **two** error envelope shapes, distinguished by when the failure occurs:

**A. Parse-time error** (Stages 1–2, before any command/run identity exists). No `commandId`/`runId` is available because parsing itself failed. `location` rides at the top of `error` (not in `details`), since every parse error has one and the conformance fixtures assert it there:

```json
{ "ok": false, "error": { "code": "PARSE_ERROR", "message": "...", "location": { "line": 1, "column": 16 } } }
```

| Field | Required | Notes |
|-------|----------|-------|
| `location: {line, column}` | yes (only for `PARSE_ERROR`) | 1-indexed; see §6.7 |
| `reason: string` | optional | machine-readable sub-reason, e.g. `"leading_zero"`, `"int_overflow"`, `"unsupported_version"` |
| `token: string` | optional | the offending lexeme |
| `expected: string[]` | optional | set of token types the parser was looking for |

**B. Runtime failure** (Stages 3+, after the command is resolved and a `runId` is minted). Carries full identity and a per-code `details` object:

```json
{ "ok": false, "commandId": "...", "runId": "...", "error": { "code": "...", "message": "...", "retryable": bool, "details": { ... } } }
```

`details` shape is fixed per code. Implementations **MUST** populate the listed required fields; optional fields are omitted (not `null`) when absent.

| Code | `details` required fields | `details` optional fields |
|------|---------------------------|---------------------------|
| `UNKNOWN_COMMAND` | `requestedId: string` | `suggestions: string[]` (≤3 closest catalog ids by edit distance) |
| `SCHEMA_VIOLATION` | `path: string` (JSON-pointer, e.g. `/args/uris/0`), `expected: string` (type or keyword), `actual: any` | `schemaPath: string` |
| `PERMISSION_DENIED` | `permission: string`, `sideEffectClass: string` | `missingRole: string[]` |
| `CONFIRMATION_REQUIRED` | `sideEffectClass: string` | `prompt: string` |
| `TIMEOUT` | `timeoutMs: integer`, `elapsedMs: integer` | `stage: string` (which stage timed out) |
| `CANCELLED` | `reason: "user"` \| `"timeout"` \| `"parent"` \| `"system"` | `elapsedMs: integer` |
| `PLUGIN_ERROR` | `exitCode: string` (plugin-defined) | `pluginMessage: string`, `retryable: boolean` (default per descriptor) |
| `UNAVAILABLE` | `component: string` | `retryable: boolean` (default `true`) |
| `RATE_LIMITED` | `retryAfterMs: integer` | `bucket: string` (which limiter bucket) |
| `CONFLICT` | `reason: "duplicate_id"` → `resolvedId: string`, `requestedId: string`, `winningManifest: string`; **or** `reason: "device_locked"` → `heldDevice: string`, `requestedDevice: string`, `runId: string` | `duplicateManifest: string` (`duplicate_id` only) |
| `INTERNAL` | `component: string` (e.g. `"scheduler"`, `"executor"`) | `stackHash: string` (stable hash for de-dup; never raw stack) |

**Notes:**

- `details` objects are **extensible** — implementations may add extra keys, but conformance suites only assert the required fields. New error codes introduced in future versions follow the same rule: declare the required/optional field set in the RFC revision.
- `SCHEMA_VIOLATION.path` uses **RFC 6901 JSON-pointer** notation rooted at the invoke node (so `/args/uris/0` points to the first element of the `uris` arg).
- `INTERNAL.stackHash` is a stable short hash of the stack, used for crash de-duplication and aggregation; raw stacks are **never** serialized into `details` (security/PII).
- `PARSE_ERROR` is the **only** code that uses shape A; it never appears in a runtime envelope because parsing precedes run creation.

---

## 9. Execution Semantics

### 9.1 Validation Pipeline

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

Failure at any step before handler invocation **MUST NOT** cause side effects.

### 9.2 Timeouts

Descriptor `timeoutMs` is enforced by Executor. On timeout:

- Cancel cooperative job if supported  
- Emit `Failure(code="TIMEOUT")`  
- Do not retry unless Workflow retry policy says so  

### 9.3 Cancellation

Runs carry a cancellation token. Plugins **SHOULD** check regularly.  
Cancelled runs emit `Failure(code="CANCELLED")`.

### 9.4 Idempotency

If `idempotent: true`, Workflow retry may re-invoke safely.  
If false, retries require explicit policy or compensation steps.

### 9.5 Pipeline Cross-Reference

This RFC's 9-step validation pipeline (§9.1) is a compact view of the **10-stage execution pipeline** defined normatively in [01-architecture.md](./01-architecture.md) §9. The architecture doc splits two steps that this RFC merges; the mapping is:

| This RFC §9.1 step | Architecture §9 stage | Notes |
|--------------------|-----------------------|-------|
| 1. Lex / parse DSL → IR | **Stage 1 — Parse** | Lexer + parser produce raw IR |
| — | **Stage 2 — Canonicalize** | Sort keys, lowercase id, normalize numbers (§7.5). RFC folds this into step 1. |
| 2. Resolve command ID | **Stage 3 — Resolve** | Registry lookup, alias resolution, version selection |
| 3. Expand sugar / memory refs | **Stage 4 — Expand** | Sugar macros + `Memory` ref dereferencing |
| 4. Validate args | **Stage 5 — ValidateInput** | JSON-Schema check vs `inputSchema` |
| — | **Stage 6 — Authorize** | Permission Kernel decision (corresponds to RFC step 5) |
| 5. Permission Kernel authorize | Stage 6 | (see above) |
| 6. Scheduler enqueue | **Stage 7 — Schedule** | Dispatcher + rate limiter |
| 7. Executor invoke handler | **Stage 8 — Execute** | Plugin handler runs; produces `value` or throws |
| 8. Validate output | **Stage 9 — ValidateOutput** | `outputSchema` check (dev/strict mode) |
| 9. Audit append | **Stage 10 — Audit** | Append-only record |

**When the two docs disagree, the architecture doc's stage boundaries win** (it is the more granular specification); this RFC's 9-step list is the conceptual summary.

### 9.6 Transactional Boundary

The pipeline has a sharp transactional seam at **Stage 7 (Execute)**. Every stage before it is **side-effect-free**; from Stage 7 onward, the run may have mutated device state.

| Outcome | Stage where it failed | Cleanable? | Retry behavior |
|---------|----------------------|------------|----------------|
| `PARSE_ERROR` | 1 / 2 | ✅ clean | Caller may fix input and retry; no state touched |
| `UNKNOWN_COMMAND` | 3 | ✅ clean | Not retryable as-is (caller must change id) |
| `SCHEMA_VIOLATION` | 4 / 5 | ✅ clean | Retryable after fixing args |
| `PERMISSION_DENIED` / `CONFIRMATION_REQUIRED` | 6 | ✅ clean | Retryable after grant/confirm |
| `TIMEOUT` / `CANCELLED` | 7+ | ⚠️ **maybe partial** | Plugin may have started side effects; see below |
| `PLUGIN_ERROR` | 7+ | ⚠️ maybe partial | Plugin decides; `idempotent` governs safe retry |
| `UNAVAILABLE` / `RATE_LIMITED` | 7 (rarely later) | ✅ clean | Retryable per `retryAfterMs` |

**Rules:**

1. **Pre-execute failure is always clean.** Stages 1–6 touch no device state; any failure there leaves the system identical to before the invoke. Conformance tests assert this by checking no audit `write`/`destructive`/`control` record was emitted.
2. **Execute-stage failure is non-rollbackable by default.** MCOS provides no automatic transaction. If `camera.capture()` started the sensor and then timed out, the sensor state is whatever the plugin left it.
3. **Compensation is a Workflow concern, not a Runtime one.** A Workflow author may declare `onFailure: <compensate-invoke>`; the Runtime then runs the compensation step but does **not** guarantee atomicity — the plugin must be written to make compensation best-effort (e.g. `note.delete()` after a failed `note.create()`).
4. **Idempotent commands are the safe retry surface.** Only commands with `idempotent: true` may be auto-retried by Workflow retry policy. Non-idempotent commands require an explicit, per-occurrence policy decision or user confirmation.

---

## 10. Results & Errors

### 10.1 Success Envelope

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

### 10.2 Failure Envelope

> ✅ **Implementation status:** `retryable` and `details` are implemented — `CommandResult.Err(code, message, retryable, details: JsonObject)` lives in `mcos-sdk` and is populated by the Executor's structured error mapping. See [11-implementation-status.md](./11-implementation-status.md) §7.

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

### 10.3 Standard Error Codes

| Code | Meaning |
|------|---------|
| `PARSE_ERROR` | DSL/IR syntax invalid |
| `UNKNOWN_COMMAND` | Not in Registry |
| `SCHEMA_VIOLATION` | Args fail schema |
| `PERMISSION_DENIED` | Missing grant |
| `CONFIRMATION_REQUIRED` | Soft-stop awaiting user |
| `TIMEOUT` | Exceeded timeout |
| `CANCELLED` | User/system cancel |
| `PLUGIN_ERROR` | Handler threw / returned failure |
| `UNAVAILABLE` | Backend offline / device busy |
| `RATE_LIMITED` | Policy throttle |
| `INTERNAL` | Runtime bug |
| `CONFLICT` | Resource contention (deadlock, duplicate manifest ID) |

Plugins **SHOULD** map vendor errors into these codes plus `details`.

> **Note:** This table is the command-level subset of the full `McosErrorCode` enum. The complete enumeration (including `COMPILE_FAILED`, and the five workflow-specific codes `WORKFLOW_INVALID` / `MAX_ITERATIONS_EXCEEDED` / `COMPENSATION_FAILED` / `JOIN_FAILED` / `TRIGGER_MISFIRE`) is in [01 §15.1](./01-architecture.md).

---

## 11. Permissions Binding

Each invocation evaluates the union of:

1. Command descriptor permissions  
2. Plugin-level permissions  
3. Runtime global policy  

Grant records are keyed by:

```text
(pluginId | commandId | androidPermission | scope)
```

See [08-security.md](./08-security.md) for grant lifetime and UI flows.

**Normative rule:** IR must not contain a way to request new Android permissions silently. Permission prompts are Runtime UX.

---

## 12. Mapping From Adjacent Systems

### 12.1 MCP Tools

```text
MCP tool name:  read_file
Server id:      filesystem
Command id:     mcp.filesystem.read_file
```

Args map 1:1 where types align; adapters convert incompatibilities explicitly.

### 12.2 App Functions

```text
Package:   com.example.notes
Function:  createNote
Command:   sys.appfn.com.example.notes.createNote
```

Vendors **MAY** publish nicer aliases via plugin manifests (`note.create` → app function).

### 12.3 Intents

Represented as `sys.intent.start` with **schema-constrained** extras — not free-form maps from the model without validation.

### 12.4 MCP Tool → MCOS Schema Conversion Table

The MCP adapter converts each MCP tool's JSON Schema into an MCOS `inputSchema` field-by-field. The mapping is lossless for the common types and **fails closed** for unmappable types (the adapter refuses to register the tool rather than silently dropping arguments).

| MCP / JSON-Schema type | MCOS primitive | Notes |
|------------------------|----------------|-------|
| `string` (no `format`) | `string` | `maxLength` capped at 65536 |
| `string` + `format: "date-time"` | `datetime` | MCP's RFC 3339 == MCOS `datetime` |
| `string` + `format: "byte"` | `bytes` | base64; size cap 10 MiB |
| `string` + `enum: [...]` | `enum` | `fromSchema: [...]` |
| `integer` | `int` | signed 64-bit; range checked |
| `number` | `float` | IEEE 754 double |
| `boolean` | `bool` | — |
| `array` | `array<T>` | recurse on `items` |
| `object` (with `properties`) | `object` | recurse per property; `required` → MCOS required list |
| `null` / `const: null` | `null` | rare; allowed as a literal arg |
| `$ref` (local) | `ref` | resolved at Stage 4 Expand |
| `oneOf` / `anyOf` | **unmapped** | v0.1 does not support union types |
| `format: "uri"` | `uri` | MCOS `uri` primitive |
| `format: "duration"` | `duration` | ISO-8601 form |

**Unmapped types:** when the adapter hits an unmappable keyword (`oneOf`, `anyOf`, `patternProperties` with non-trivial patterns, `format` values outside the table), it **does not register the tool** and emits a Registry log entry with `details: { toolName, unmappedType, reason }`. The tool is hidden from the Planner until its author narrows the schema. This prevents silent argument loss.

**`description` and `examples`:** MCP `description` is copied verbatim into the descriptor's per-arg doc; MCP `examples` (if present) become descriptor `examples`.

### 12.5 App Function Package-Name Encoding

The command-id grammar (§4.1) uses `.` as the namespace separator, but Java/Kotlin package names also use `.` (e.g. `com.example.notes`). To keep command ids unambiguous, the adapter **replaces each `.` in the package name with `_`** when forming the command id:

```text
Package:   com.example.notes       (4 segments)
Function:  createNote
Command:   sys.appfn.com_example_notes.createNote
```

**Encoding rules:**

1. Each `.` in the package → `_`. So `com.example.notes` → `com_example_notes`.
2. Existing `_` in the package are preserved (no escaping needed; `_` is not a separator).
3. The function name is appended after a final `.`: `….<functionName>`.
4. The resulting command id is always of the form `sys.appfn.<encodedPackage>.<function>` — three dots minimum, never ambiguous with hand-authored `note.create` style ids (which have exactly one dot).

**Reverse lookup:** given `sys.appfn.com_example_notes.createNote`, the Runtime recovers the package by splitting on `.`, dropping the `sys.appfn` prefix and the last segment (function), then replacing `_` → `.`. This is unambiguous because step 1 is a 1:1 char swap.

Vendors **MAY** additionally publish a friendly alias (`note.create`) via a plugin manifest; the alias and the generated id both resolve to the same handler.

### 12.6 Intent Extras — Mandatory Schema

Free-form Android `Intent` extras (an untyped `Bundle`) are a historic source of LLM hallucination: the model invents extra keys the receiver does not understand. MCOS refuses this. The rule:

- **Every `sys.intent.start` invoke MUST supply an `extrasSchema`.** The descriptor's `inputSchema` requires `extras` to be an `object` whose own `properties` are declared by the caller (or by a published plugin manifest for well-known intents like `android.intent.action.SEND`).
- If the caller cannot declare a schema for `extras`, the invoke is rejected at Stage 5 (`SCHEMA_VIOLATION`, `path: /args/extras`, `reason: "extras_schema_required"`).
- A small allowlist of system intents is published with **pre-declared schemas** in the `sys` plugin; for these, callers may omit `extrasSchema` and the Runtime validates against the published one.

**Rationale:** forcing a schema turns "the model guessed an extra key" from a silent runtime misfire into a Stage-5 rejection the Planner can self-correct on the next turn.

---

## 13. Examples (Normative Illustrations)

### 13.1 Camera

```text
camera.capture()
camera.capture(facing="front")
camera.scan()
```

### 13.2 Photos

```text
photo.search(date="today")
photo.compress(quality=80)
photo.clean(olderThan="P30D", confirm=true)
```

### 13.3 Communication

```text
mail.send(to="Tom", subject="Photos", body="FYI")
```

### 13.4 Home / IoT

```text
home.light.on(id="living-room")
home.scene.movie()
iot.ac.set(name="air-condition", power=true, tempC=26)
```

### 13.5 Dev

```text
github.issue.create(repo="mcos/mcos", title="Bug")
github.pr.list(repo="mcos/mcos", state="open")
```

### 13.6 System

```text
vpn.connect(profile="office")
calendar.next()
weather.today()
sys.notify(title="MCOS", text="Done")
```

---

## 14. Versioning & Compatibility

### 14.1 Command Contract SemVer

| Change | Bump |
|--------|------|
| New optional arg | MINOR |
| New command in plugin | MINOR (plugin) |
| Remove arg / change meaning | MAJOR |
| Tighten validation rejecting previously valid args | MAJOR |
| Bugfix preserving contract | PATCH |

### 14.2 DSL Language Version (`dslVersion`)

`dslVersion` uses a **two-segment `MAJOR.MINOR` shorthand** (e.g. `"0.1"`), distinct from the three-segment SemVer used for command contracts (§14.1). It versions the *DSL grammar / IR shape*, not individual commands.

Rules:

1. Runtimes **MUST** reject `dslVersion` whose **major** part is unsupported.
2. Runtimes **MAY** accept older **minor** versions (forward-compatible: newer runtimes read older DSL).
3. A **minor** bump adds grammar/IR features backward-compatibly; a **major** bump may break parsing.
4. When omitted from a script, runtimes assume the highest minor they support for the current major.

This is why every example and fixture in this RFC carries `"dslVersion": "0.1"` (major `0`, minor `1`), while command descriptors carry full SemVer like `"1.0.0"`.

### 14.3 Deprecation

Descriptors set `deprecated: true` and `replacedBy`. Planners **SHOULD** prefer replacements. Runtime **MAY** warn in CLI.

---

## 15. Security Considerations

1. **No nested code execution** in DSL v0.1.  
2. **Schema validation** is mandatory — never pass raw model JSON to plugins.  
3. **Secret args** redacted in audit (`x-mcos-secret`).  
4. **Destructive** class always confirmable.  
5. **Network egress** observable and policy-gated.  
6. Treat Planner output as **untrusted input** equivalent to user scripts.

---

## 16. Conformance

An implementation is **Command Protocol Conformant v0.1** if it:

1. Parses the DSL grammar subset defined here  
2. Round-trips DSL ↔ IR for the golden test suite published under [`docs/fixtures/`](../fixtures/) (positives must match; negatives must be rejected — see `../fixtures/README.md`)
3. Validates with JSON Schema before invoke  
4. Emits standard error codes listed in §10.3  
5. Honors `sideEffectClass` confirmation hooks  
6. Refuses unknown command IDs

### 16.1 Structured Test Matrix

The six conformance points above are turned into an explicit test matrix below. Conformance suites **MUST** pass every row. Rows marked **fixture** have a published golden case under [`docs/fixtures/`](../fixtures/); rows marked **suggested** describe cases the suite should add (not yet published).

| # | Category | Input | Required behavior | Fixture |
|---|----------|-------|-------------------|---------|
| P1 | positive | `# mcos-dsl: 0.1`<br>`camera.capture()` | IR: `type:"invoke"`, `id:"camera.capture"`, `args:{}` | `01-empty-args` |
| P2 | positive | `hello.world(name="Tom")` | IR: single `string` arg, `args:{name:"Tom"}` | `02-named-string` |
| P3 | positive | `photo.compress(quality=80, uris=["content://1","content://2"])` | IR: `int` + `array<string>`; keys sorted → `quality, uris`; array order preserved | `03-array-and-int` |
| P4 | positive | `# comment`<br>`camera.capture()`<br>`photo.compress(quality=80)` | IR: `type:"sequence"`, 2 steps; comment ignored | `04-sequence` |
| P5 | positive | `home.light.set(id="living-room", on=true, brightness=0.8, meta=null)` | IR: `string`/`bool`/`float`/`null`; keys sorted → `brightness, id, meta, on` | `05-mixed-literals` |
| N1 | negative | `mail.send(to="Tom", body=photo.compress())` | `PARSE_ERROR`; `location` at start of nested `photo.compress` | `06-nested-call` |
| N2 | negative | `camera.capture("front")` | `PARSE_ERROR` (positional); `location` at `"front"` | `07-positional-arg` |
| N3 | negative | `camera.capture(quality=80` (unclosed) | `PARSE_ERROR`; `location` at EOF | `08-malformed` |
| S1 | suggested | `a(n=99999999999999999999)` int overflow | `PARSE_ERROR` `reason:"int_overflow"` | — |
| S2 | suggested | `a(s="caf\u00e9")` `\u` BMP escape | IR with `é`; column counting by code point | — |
| S3 | suggested | `# mcos-dsl: 0.1`<br>`# only a comment` (zero invocations) | `PARSE_ERROR` `reason:"empty_script"` — a script must yield at least one invoke or a non-empty sequence | — |
| S4 | suggested | `# mcos-dsl: 0.2`<br>`a()` header version mismatch | `PARSE_ERROR` `reason:"unsupported_version"` | — |
| S5 | suggested | `a(name="王小明")` non-ASCII arg | IR with the CJK string verbatim; column counting uses code points | — |
| S6 | suggested | `A.B()` mixed-case command id | IR with `id:"a.b"` (lowercased per §7.5) | — |
| S7 | suggested | `a(x=007)` leading zero | `PARSE_ERROR` `reason:"leading_zero"` | — |

**Conformance-suite obligations:**

- For each **positive** row: parse `input.dsl`, assert the produced canonical IR equals `expected.ir.json` byte-for-byte (after key sorting per §7.5).
- For each **negative** row: parse `input.dsl`, assert the error envelope equals `expected.error.json` including `code`, `message` (regex match allowed), and `location`.
- For **suggested** rows, the suite author publishes a fixture under `docs/fixtures/<NNN-…>/` and links it here once frozen.

> **Note on `meta` in fixture 05:** the `meta=null` there is a **business argument** named `meta` (the light device's own metadata slot), **not** the IR-level provenance `meta` of §8.2. They are unrelated — the IR-level `meta` is a sibling of `args`, not a key inside it. Conformance suites must not confuse the two.

---

## 17. Future Extensions (Non-Normative)

- Positional args with explicit schema `x-mcos-positional`  
- Pipelines: `a() | b()` as syntactic sugar for sequence + artifact binding  
- Typed variables in scripts  
- Capability tokens embedded in IR for multi-agent delegation  
- CBOR IR for on-device storage efficiency  

---

## 18. Reference Grammar (Informative)

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

### 18.1 String Literals & Escapes

A string literal is delimited by `"…"` or `'…'`. Both quoting styles accept the same escape set below; they exist so that a string containing one quote style can be written without escaping it (e.g. `"it's"` or `'say "hi"'`).

**Supported escapes** (inside either quote style):

| Escape | Meaning |
|--------|---------|
| `\"` | literal `"` |
| `\'` | literal `'` |
| `\\` | literal `\` |
| `\/` | literal `/` (optional; keeps DSL JSON-friendly) |
| `\n` | newline U+000A |
| `\r` | carriage return U+000D |
| `\t` | tab U+0009 |
| `\b` | backspace U+0008 |
| `\f` | form feed U+000C |
| `\uXXXX` | Unicode code point (4 hex digits, e.g. `\u7a7a` → 空) |

**Unicode:** DSL source is UTF-8. Raw (unescaped) non-ASCII characters **are** permitted inside string literals — e.g. `iot.ac.set(name="空调")` is valid and produces the literal string `空调`. The `\uXXXX` form is provided for tooling that prefers ASCII-only output (e.g. some LLM decoders).

**Rejected as `PARSE_ERROR`:**

- An unescaped literal newline inside a string (strings are single-line).
- A dangling backslash at end of string (`"trailing\"`).
- `\u` not followed by exactly 4 hex digits.
- Mismatched quotes (`"abc'`).

These rules keep the DSL string grammar a strict subset of JSON string semantics, so IR `args` values round-trip cleanly into JSON.

---

## 19. Summary

The Command Protocol is MCOS’s public language:

- **Stable IDs** for capabilities  
- **Typed, named arguments**  
- **DSL for humans/LLMs**, **IR for engines**  
- **Strict validation + side-effect classes** before anything touches the device  

Next: how the Runtime consumes this protocol — [03-runtime.md](./03-runtime.md).
