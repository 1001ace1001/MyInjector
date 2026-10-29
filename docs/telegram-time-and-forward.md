# Telegram：精确时间、转发预览、标题与限制

本文说明 Telegram 设置中新增的 6 项功能及其实现方式。默认全部关闭。

| 设置项 | 实现 |
| --- | --- |
| 消息时间精确到秒 | `ChatMessageCell.measureTime` 执行期间，`LocaleController.getFormatterDay()` 返回带秒的 `FastDateFormat`。宽度按新文本计算，排版不错位。混淆版沿用 ShowMsgId 的 DexKit 特征定位 `measureTime`。 |
| 浮动日期附加精确时间 | `ChatActionCell.setCustomDate` 执行期间，在 `formatDateChat` 结果后追加时间。原方法按小时去重，进入前把 `customDate` 清零。定时消息不处理。 |
| 转发时保留链接预览 | `processForwardFromMyName` / `sendMessage(ArrayList, ...)` 执行期间记录源消息。若 `SendMessageParams.webPage` 就是源消息的预览，则补上 `mediaWebPage` 和 `invert_media`，客户端会以 `inputMediaWebPage` 原样发送（保留链接、大小图、位置）。 |
| 自定义对话列表标题 | `DialogsActivity.createView` 后记录主列表的 ActionBar 和原默认标题，改写 `actionBarDefaultTitle`。之后该 ActionBar 的 `setTitle*` 文本等于默认标题时替换为自定义文本，文件夹标签名不受影响。依赖类/字段名称，仅对未混淆客户端（exteraGram、AyuGram 等）生效。 |
| 解除打开对话限制 | `MessagesController.getRestrictionReason` 恒返回 `null`，被地区/平台限制的对话可以打开并显示内容。敏感内容使用独立的 `isSensitive`，不受影响。 |
| 加入/订阅时间精确到秒 | `LocaleController.formatJoined` / `formatGroupMemberJoined` 执行期间同样替换 `getFormatterDay()`。 |

共用逻辑在 `telegram/PreciseTime.kt`：带秒格式器的类型取自 `getFormatterDay()` 的返回类型，12/24 小时制读取 `LocaleController.is24HourFormat`。
