# Golden DSL ↔ IR fixtures (Command Protocol v0.1)

Two kinds of cases live here:

- **Positive** — valid DSL that a conformant Runtime must round-trip to the exact IR.
- **Negative** — invalid DSL that a conformant Runtime must reject with the listed error.

## Layout

Positive cases carry an `expected.ir.json`; negative cases carry an `expected.error.json`.

```text
docs/fixtures/<case-id>/
  input.dsl
  expected.ir.json        # positive: canonical IR the parser must produce
  expected.error.json     # negative: error envelope the parser must emit
```

## Positive (round-trip DSL → IR)

| Case | Covers |
|------|--------|
| `01-empty-args` | Empty argument list + optional `# mcos-dsl:` header |
| `02-named-string` | Named string arg |
| `03-array-and-int` | Int + string array |
| `04-sequence` | Multi-statement sequential script + comment |
| `05-mixed-literals` | bool / float / null (args keys sorted in expected IR) |

## Negative (must reject)

| Case | Invalid input | Expected error | Protocol § |
|------|---------------|----------------|------------|
| `06-nested-call` | nested invocation in an argument | `PARSE_ERROR` | §6.2, §15.1 |
| `07-positional-arg` | positional argument | `PARSE_ERROR` | §6.1 |
| `08-malformed` | unbalanced parenthesis | `PARSE_ERROR` | §18 |

## Conformance

A Runtime is protocol-conformant (v0.1) when it:

1. Round-trips every **positive** case (`input.dsl` ↔ `expected.ir.json`).
2. Rejects every **negative** case with the matching error code (`expected.error.json`).

See [02-command-protocol.md](../02-command-protocol.md) §16.
