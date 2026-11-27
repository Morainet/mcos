# MCOS Android 控制原型

本地 CLI 驱动 MCOS Record Export 示例。它读取界面、定位导出按钮并启动任务，用户在手机上的确认框选择导出、拒绝或取消。CLI 用当前运行 ID、页面显示的文件名和新增文件的实际内容共同验证成功，并以 JSON 返回结果。

先安装 [MCOS Record Export Debug 示例](../../docs/zh/14-embedded-host.md)。将 Android Platform Tools 放到 PATH，连接设备并完成 USB 调试授权。

```sh
python3 tools/mcos-device/mcos_device.py devices
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL export-record
```

示例已在前台时，可以用 `observe` 查看其可见控件。ADB 若不在 PATH，可设置 `MCOS_ADB` 或传入全局参数 `--adb`。输出 JSON 在标准输出，手机上的审批提示在标准错误。退出码 0 表示成功，1 表示失败，2 表示示例已报告取消。

原型面向已授权的开发测试设备，只启动和操作示例 App。文件核验使用 Debug 包的 `run-as`。任务等待超时后，应先检查手机上的待处理任务，确认状态再重试。手机里的审批由示例 App 的 Runtime 管理。

[产品方向与设备控制边界](../../docs/zh/15-product-direction.md)
