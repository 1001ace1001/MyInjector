package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.call
import io.github.a13e300.myinjector.arch.callS
import io.github.a13e300.myinjector.arch.hookAfter
import io.github.a13e300.myinjector.arch.hookAll
import io.github.a13e300.myinjector.arch.toObfsInfo

/**
 * 浮动日期后附加具体时间。
 *
 * 聊天顶部浮动日期由 ChatActionCell.setCustomDate(date, scheduled, animated) 生成，
 * 文本来自 LocaleController.formatDateChat(date)。在 setCustomDate 执行期间给 formatDateChat
 * 的结果追加时间；原方法按小时去重（customDate / 3600），因此进入前把 customDate 清零，
 * 让同一小时内的不同消息也能刷新（文本相同时原方法自身会跳过重排）。
 * 「消息时间精确到秒」开启时时间带秒。
 */
class FloatingDateTime : MyDynHook("floatingDateTime") {
    override fun isFeatureEnabled(): Boolean = TelegramHandler.settings.floatingDateTime

    private val active = ThreadLocal<Boolean>()

    override fun onHook() {
        PreciseTime.install(classLoader)
        val creator = TelegramHandler.creator
        val setCustomDate = creator.create("chatActionCellSetCustomDate") { bridge ->
            (telegramNamedMethod(bridge, "org.telegram.ui.Cells.ChatActionCell", "setCustomDate", "int", "boolean", "boolean")
                ?: bridge.findMethod {
                    matcher {
                        usingEqStrings("MessageScheduledUntilOnline", "MessageScheduledOn")
                        paramTypes("int", "boolean", "boolean")
                    }
                }.single()).toObfsInfo()
        }
        val customDate = creator.create("chatActionCellCustomDate") { bridge ->
            val cls = bridge.getClassData(setCustomDate.className)!!
            (cls.fields.singleOrNull { it.name == "customDate" } ?: bridge.findField {
                matcher {
                    declaredClass(setCustomDate.className)
                    type("int")
                    addReadMethod(setCustomDate.descriptor)
                    addWriteMethod(setCustomDate.descriptor)
                }
            }.single()).toObfsInfo()
        }

        val kCell = findClass(setCustomDate.className)
        val customDateField = kCell.getDeclaredField(customDate.memberName).also { it.isAccessible = true }
        val kLocaleController = findClass("org.telegram.messenger.LocaleController")
        val formatDateChat = kLocaleController.declaredMethods.single {
            it.name == "formatDateChat" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType
        }

        formatDateChat.hookAfter(cond = { isEnabled() && active.get() == true }) { param ->
            val text = param.result as? String ?: return@hookAfter
            val millis = (param.args[0] as Number).toLong() * 1000L
            param.result = "$text ${timeText(kLocaleController, millis)}"
        }

        kCell.hookAll(
            setCustomDate.memberName,
            cond = ::isEnabled,
            before = { param ->
                if (param.args.size == 3 && param.args[1] == false) {
                    customDateField.setInt(param.thisObject, 0)
                    active.set(true)
                }
            },
            after = { active.remove() },
        )
    }

    private fun timeText(kLocaleController: Class<*>, millis: Long): String =
        if (TelegramHandler.settings.preciseMessageTime) PreciseTime.format(millis)
        else kLocaleController.callS("getInstance").call("getFormatterDay").call("format", millis) as String
}
