# Lawnchair 划卡强杀进程

在 Lawnchair（`app.lawnchair`、`app.lawnchair.nightly`、`app.lawnchair.debug`）的最近任务中划走单张卡片时，同时强行停止该应用。默认关闭，在模块首页的 Lawnchair 入口打开设置，开关键为 `swipeKill`，保存在宿主数据目录的 `my_injector_settings`（`LawnchairSettings` protobuf）。

## 实现

基于 16.Dev (#5374) 反编译确认，Lawnchair 的 Quickstep 代码未混淆：

- 单卡移除：新的弹簧动画（`RecentsDismissUtils.handleGroupTaskRemoval`）和旧的动画（`RecentsView` 匿名动画监听的 `onEnd`）最终都调用 `RecentsView.removeGroupTaskInternal(GroupTask)`，再由它在 `UI_HELPER_EXECUTOR` 上对每个任务执行 `ActivityManagerWrapper.removeTask(key.id)`。
- 全部清除：`RecentsView.dismissAllTasks` 走 `removeAllRecentTasks`，不经过上面的方法。

模块在 `removeGroupTaskInternal` 前读取 `GroupTask.getTasks()` 中每个 `Task.key` 的 `getPackageName()` 与 `userId`，在独立线程调用 `IActivityManager.forceStopPackage`，结果用 Toast 提示。分屏组会停止两个应用；桌面窗口模式的 `DesktopTask`、Lawnchair 自身均跳过。`dismissAllTasks` 之后 3 秒内不处理，避免全部清除时误触发。

## 权限与限制

强停直接在 Lawnchair 进程中调用，不经过 system_server。Lawnchair 清单声明了 `FORCE_STOP_PACKAGES`、`REMOVE_TASKS`，这两个权限只有作为特权应用（如通过 QuickSwitch 安装到 `/system/priv-app` 并获得 privapp 白名单）时才会授予，这也是它成为 Quickstep 最近任务提供者的前提。未授予时调用失败，日志记录 `SecurityException` 并 Toast 提示。

Lawnchair 不是系统最近任务提供者时，手势进入的是系统桌面的最近任务，本功能不生效。
