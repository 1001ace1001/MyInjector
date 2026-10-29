package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.hookAll
import io.github.a13e300.myinjector.arch.toObfsInfo

/**
 * 消息时间精确到秒。
 *
 * ChatMessageCell.measureTime 内部用 LocaleController.getFormatterDay() 生成时间文本并据此计算宽度；
 * 在 measureTime 执行期间让 getFormatterDay() 返回带秒格式器，宽度会随文本一起正确计算。
 * 混淆版本沿用 ShowMsgId 的特征（measureTime 引用 MessageScheduledRepeatMonthlyMany）定位。
 */
class PreciseMessageTime : MyDynHook("preciseMessageTime") {
    override fun isFeatureEnabled(): Boolean = TelegramHandler.settings.preciseMessageTime

    override fun onHook() {
        PreciseTime.install(classLoader)
        val info = TelegramHandler.creator.create("chatMessageCellMeasureTime") { bridge ->
            (telegramNamedMethod(bridge, "org.telegram.ui.Cells.ChatMessageCell", "measureTime", "org.telegram.messenger.MessageObject")
                ?: bridge.findMethod {
                    matcher { usingEqStrings("MessageScheduledRepeatMonthlyMany") }
                }.single()).toObfsInfo()
        }
        findClass(info.className).hookAll(
            info.memberName,
            cond = ::isEnabled,
            before = PreciseTime.enterHook,
            after = PreciseTime.exitHook,
        )
    }
}
