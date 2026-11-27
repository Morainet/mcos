# Product direction and acceptance criteria

[中文](../zh/15-product-direction.md) · [Architecture and migration design](./13-architecture-convergence.md)

## Near-term scope

- The open-source Android Agent lets users describe a goal on the phone, review planning and progress, confirm sensitive operations, and get a result.
- The embeddable secure command Runtime lets developers register existing Android app capabilities as commands with validation, permissions, and audit. Models and presets share the execution path.

The first verifiable scenario is inspection-record export. The in-app preset finds the latest completed record, requests approval, creates a file, and verifies its contents. Host integration and ADB use the [Debug regression screen](./14-embedded-host.md) in the same APK. The [ADB CLI](../../tools/mcos-device/README.md) can start execution, wait for approval on the phone, and verify the result in separate calls tied to one run ID.

## Iteration priorities

| Priority | Goal | Acceptance |
|---|---|---|
| 1 | Consolidate docs, entry points, and ownership | A new developer can find the single app, embedded-host entry point, execution flow, and verification commands. |
| 2 | Separate host Runtime assembly from extensions | A host capability runs without a model or remote service. The full host, approval, audit, and recovery retain regression coverage. |
| 3 | Close the app task loop | Users can start a goal or preset, confirm it, cancel it, understand failures, and revisit the actual result. |
| 4 | Improve reliability of authorized device tooling | Each step has observable before-and-after state, ambiguous UI or timeouts are diagnosable, and approval and artifacts have independent checks. |

Marketplace, MCP, and sync keep their existing extension boundaries. Additional platforms, public marketplace operations, and inbound control of the phone depend on validated use cases, permission scopes, and approval interfaces. Keep source, device evidence, and publication records distinct when reporting availability.
