# Lawnchair 划卡强杀进程

在 Lawnchair（`app.lawnchair`、`app.lawnchair.nightly`、`app.lawnchair.debug`）的最近任务中划走单张卡片时，同时强行停止该应用。默认关闭，在模块首页的 Lawnchair 入口打开设置，开关键为 `swipeKill`，保存在宿主数据目录的 `my_injector_settings`（`LawnchairSettings` protobuf）。

## 实现

基于 16.Dev (#5374) 反编译确认，Lawnchair 的 Quickstep 代码未混淆：

- 单卡移除：新的弹簧动画（`RecentsDismissUtils.handleGroupTaskRemoval`）和旧的动画（`RecentsView` 匿名动画监听的 `onEnd`）最终都调用 `RecentsView.removeGroupTaskInternal(GroupTask)`，再由它在 `UI_HELPER_EXECUTOR` 上对每个任务执行 `ActivityManagerWrapper.removeTask(key.id)`。
- 全部清除：`RecentsView.dismissAllTasks` 走 `removeAllRecentTasks`，不经过上面的方法。

模块在 `removeGroupTaskInternal` 前读取 `GroupTask.getTasks()` 中每个 `Task.key` 的 `getPackageName()` 与 `userId`，在独立线程中强停，结果用 Toast 提示。分屏组会停止两个应用；桌面窗口模式的 `DesktopTask`、Lawnchair 自身均跳过。`dismissAllTasks` 之后 3 秒内不处理，避免全部清除时误触发。

## 权限与限制

强停由 Lawnchair 进程自己发起，不经过 system_server：

- Lawnchair 持有 `FORCE_STOP_PACKAGES`（作为特权应用并获得 privapp 白名单）时，直接调用 `IActivityManager.forceStopPackage`。
- 否则通过 Lawnchair 进程内常驻的 `su` shell 执行 `am force-stop --user <userId> <包名>`。首次划卡时 Magisk/KernelSU 会向 Lawnchair 请求 root，需要手动授予；之后复用同一 shell，每条命令以带退出码的结束标记判断结果。包名执行前按 `[A-Za-z0-9_.]` 校验。拒绝授权或 shell 退出时 Toast 提示失败，下次划卡重新尝试。

Lawnchair 不是系统最近任务提供者时，手势进入的是系统桌面的最近任务，本功能不生效。
