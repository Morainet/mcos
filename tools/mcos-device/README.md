# MCOS Android 设备调试

本目录服务于获授权的开发测试设备。`mcos_adb_bridge.py` 给 Debug App 的 Agent 提供固定观察，`mcos_device.py` 驱动同一 APK 的专用巡检回归页。面向用户的入口见 [Android Agent](../../mcos-android/README.md)。

## 给 Agent 接入 ADB

MCOS 用户在 App 的 Agent 首页发起任务和审批。需要验证 ADB 工具的开发者，可以在已授权的 USB 调试设备上运行以下宿主。它经 ADB 轮询 Debug App 的私有目录，执行固定的界面观察，返回已知界面状态。

```sh
python3 tools/mcos-device/mcos_adb_bridge.py --serial DEVICE_SERIAL
```

保持宿主运行，在手机上打开 MCOS 并点击巡检示例。Agent 会先通过 `adb.inspect_mcos` 观察当前界面，再查找和导出巡检记录。这个 ADB 工具只返回界面状态枚举，注册范围是 Debug 包。

## 开发者 CLI 回归

本地 CLI 驱动 `mcos-android` 的巡检记录调试页面。它读取界面、定位导出按钮并启动任务，用户在手机上的确认框选择导出、拒绝或取消。CLI 用当前运行 ID、页面显示的文件名和新增文件的实际内容共同验证成功，并以 JSON 返回结果。

先安装 [mcos-android Debug 包](../../docs/zh/14-embedded-host.md)。将 Android Platform Tools 放到 PATH，连接设备并完成 USB 调试授权。

```sh
python3 tools/mcos-device/mcos_device.py devices
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL launch
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL export-record --record-id demo-001
```

`export-record --record-id demo-001` 会打开新版示例、填写或复用同一个记录 ID，并发起导出。手机上的确认完成后，CLI 核验本次生成的文件与内容。输入框已有其他内容时，CLI 会停止并保留原值。省略 `--record-id` 时，走原来的固定示例入口。固定入口导出 `demo-001`。最新已完成记录是 `demo-002`，可通过页面查询或自然语言流程导出。

Agent 可以把发起操作和续验拆成两次调用。加上 `--until-approval` 后，CLI 观察到本次运行的确认框就会退出，返回 `status: approval_required`、`runId` 和 `verified: false`。退出码是 3。收到此状态后，请让用户在手机上处理审批，并保留运行 ID 用于续验。

```sh
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL \
  export-record --record-id demo-001 --until-approval
```

收到 `Run:` 提示后，可以用同一个 ID 续验。导出等待超时的 JSON 也会包含已观察到的 `runId`。审批框会显示运行 ID。

```sh
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL \
  verify-export --run-id RUN_ID --wait 30
```

续验优先读取示例 App 保存在私有目录里的运行结果。终态写入后，即使离开页面或关闭 App，也能用原运行 ID 核对文件内容。App 尚未写入终态时，CLI 会继续观察当前界面。旧版示例只能通过界面核验。续验不会重新发起导出，关掉 App 也不会让尚未完成的任务继续运行。

续验的 `--wait` 用完时，如果最后仍能确认是本次运行在等待手机审批，CLI 返回 `approval_required`（退出码 3）和原运行 ID。用户操作后可以用同一个 ID 再次续验。已保存的终态优先于界面上的审批状态。

### 自然语言导出

在终端把本地密钥载入 `CHATANYWHERE_API_KEY`，然后运行以下命令。可以用 `CHATANYWHERE_MODEL` 更换模型，默认使用 `gpt-4o-mini`。模型请求走 [ChatAnywhere 的聊天接口](https://docs.chatanywhere.tech/api-92222076)，发送任务文字、规划提示和最新记录查询返回的示例 ID。默认线路为 `api.chatanywhere.org`。如连接超时，可将 `CHATANYWHERE_ENDPOINT` 设为 `https://api.chatanywhere.tech/v1/chat/completions`。

```sh
read -rs CHATANYWHERE_API_KEY
export CHATANYWHERE_API_KEY
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL \
  agent-export --task "把最新一条已完成的巡检记录导出成文件"
```

Agent 先调用 `record.latest_completed` 读取手机里的最新已完成记录，再根据得到的 ID 规划 `record.export`。显式导出 `demo-001` 也可以直接发起。返回 `approval_required` 后，请在手机上核对记录 ID 并确认，再执行 `verify-export --run-id RUN_ID --record-id RECORD_ID`。加上 `--wait-for-result` 可让第一条命令继续等待审批和文件核验。含糊的导出请求返回 `clarification_required`（退出码 4）。

确认框无人响应直到运行时超时时，结果为 `Confirmation timed out`。用户点「Reject」时，结果为 `Confirmation rejected`。两种情况都不会导出文件。`--wait` 只控制 CLI 的观察时长。

`launch` 打开 Debug 的 `RecordExportActivity` 并核对 `Export an app record`。可用 `--expect` 指定其他预期文字。成功后再调用 `observe` 或 `tap`。

示例已在前台时，可以用 `observe` 查看其可见控件。使用 `observe --screenshot /tmp/mcos-screen.png` 可保存手机屏幕 PNG，JSON 同时返回控件和截图路径。目标文件须为新文件。截图与控件树分别采集，采集前后若检测到界面变化，命令会报错。ADB 若不在 PATH，可设置 `MCOS_ADB` 或传入全局参数 `--adb`。输出 JSON 在标准输出，手机上的审批提示在标准错误。退出码 0 表示成功，1 表示失败，2 表示示例已报告取消，3 表示等待手机审批。

`tap` 按完整控件文字匹配，忽略大小写。示例已在前台时，可让 CLI 点击导出入口并验证手机已显示确认框。

```sh
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL tap \
  --text "Preview and export record" --expect "Export this record?"
```

`tap` 验证的是新出现的界面文字。要完成并核验文件导出，使用 `export-record`。遇到同名控件或确认对话框时，`tap` 会停止。点击后未观察到预期文字时，CLI 返回失败并提示检查手机。

`swipe` 只在观察到的可滚动区域内执行一次竖向手势，再核对新出现的文字。方向指手指移动方向，`up` 用来查看下方内容。区域有多个时，用 `observe` 返回的 `resource-id` 选择目标。

```sh
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL swipe \
  --direction up --expect "Cancel running task"
```

上例适用于按钮尚未出现在屏幕上且页面可滚动的情况。内容已全部显示时，无需滑动。确认框显示时，手势交给手机用户。

`type` 使用 `observe` 返回的资源 ID 定位空输入框，确认焦点后输入，再核对同一输入框的完整内容。先安装包含记录 ID 输入框的新版示例。

```sh
python3 tools/mcos-device/mcos_device.py --serial DEVICE_SERIAL type \
  --resource-id com.morainet.mcos.android:id/record_id --text demo-001
```

当前支持 1–120 个 ASCII 字母、数字、内部空格和 `.,_@-`。中文和其他字符会直接报错。已填内容、密码框以及等待审批的界面会阻止输入。输入成功后，可以点击「Preview and export entered record」发起导出，审批仍在手机上完成。输入验证失败时不会自动重试。

原型面向已授权的开发测试设备，只启动和操作示例 App。文件核验使用 Debug 包的 `run-as`。任务等待超时后，应先检查手机上的待处理任务，确认状态再重试。手机里的审批由示例 App 的 Runtime 管理。

[产品方向与设备控制边界](../../docs/zh/15-product-direction.md)
