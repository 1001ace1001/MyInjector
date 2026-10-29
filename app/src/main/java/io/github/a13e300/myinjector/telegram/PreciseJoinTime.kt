package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.hookAll

/**
 * 精确显示订阅/加入时间。
 *
 * 成员列表、邀请链接详情和资料页中的「加入于 / 订阅于」由
 * LocaleController.formatJoined / formatGroupMemberJoined 生成，时间部分来自 getFormatterDay()。
 * 在这两个方法执行期间让 getFormatterDay() 返回带秒格式器。
 * LocaleController 成员在官方版本中保留名称，按名称定位。
 */
class PreciseJoinTime : MyDynHook("preciseJoinTime") {
    override fun isFeatureEnabled(): Boolean = TelegramHandler.settings.preciseJoinTime

    override fun onHook() {
        PreciseTime.install(classLoader)
        val kLocaleController = findClass("org.telegram.messenger.LocaleController")
        var hooked = 0
        for (name in listOf("formatJoined", "formatGroupMemberJoined")) {
            hooked += kLocaleController.hookAll(
                name,
                cond = ::isEnabled,
                before = PreciseTime.enterHook,
                after = PreciseTime.exitHook,
            ).size
        }
        require(hooked > 0) { "formatJoined not found" }
    }
}
