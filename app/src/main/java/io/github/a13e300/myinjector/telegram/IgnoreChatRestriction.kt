package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.hookAllConstantIf

/**
 * 解除打开对话限制。
 *
 * 被限制的用户、群组和频道（restriction_reason 匹配 android 平台）在打开时由
 * MessagesController.checkCanOpenChat / openChatOrProfileWith 调用 getRestrictionReason，
 * 返回非空则弹出「无法显示」并阻止打开；消息列表也据此把内容替换为限制说明。
 * 让 getRestrictionReason 恒返回 null 即可正常打开并显示服务器下发的内容。
 * 「敏感内容」（sensitive）使用独立的 isSensitive 检查，不受影响。
 * MessagesController 成员在官方版本中保留名称，按名称定位。
 */
class IgnoreChatRestriction : MyDynHook("ignoreChatRestriction") {
    override fun isFeatureEnabled(): Boolean = TelegramHandler.settings.ignoreChatRestriction

    override fun onHook() {
        val hooks = findClass("org.telegram.messenger.MessagesController")
            .hookAllConstantIf("getRestrictionReason", null, ::isEnabled)
        require(hooks.isNotEmpty()) { "getRestrictionReason not found" }
    }
}
