# MCOS 文档

[English](../en/README.md)

## 先从这里开始

| 你要做什么 | 阅读入口 |
|---|---|
| 了解当前模块边界、依赖和重设计方案 | [当前架构与开发边界](./13-architecture-convergence.md) |
| 构建和调试 Android Agent | [Android App 指南](../../mcos-android/README.md) |
| 给自己的 Android App 接入命令 | [Android SDK](../../mcos-android-sdk/README.md) · [基础宿主回归](./14-embedded-host.md) |
| 验证授权设备与导出文件 | [ADB 调试指南](../../tools/mcos-device/README.md) |
| 确定实现、验证范围与优先级 | [当前实现](./11-implementation-status.md) · [产品方向](./15-product-direction.md) |
| 提交代码或文档 | [贡献指南](../../CONTRIBUTING.md) |

## 技术参考

| 主题 | 文档 |
|---|---|
| 命令与插件 | [命令协议](./02-command-protocol.md) · [插件 SDK](./04-plugin-sdk.md) · [黄金用例](../fixtures/README.md) |
| 运行时与安全 | [运行时](./03-runtime.md) · [安全](./08-security.md) · [工作流](./05-workflow.md) |
| Agent 与记忆 | [Agent](./06-agent.md) · [记忆](./07-memory.md) |
| 可选扩展 | [市场](./09-marketplace.md) · [索引服务](./12-index-server.md) |

技术参考保留 RFC 的设计约定。查找实际类名、模块边、命令可用性或测试时，从当前架构、Gradle 配置、源码和 fixture 核对。早期决策见 [愿景草案](./00-vision.md)、[架构设计参考](./01-architecture.md)和[历史路线图](./10-roadmap.md)。发布记录见 [CHANGELOG](../../CHANGELOG.md)。
