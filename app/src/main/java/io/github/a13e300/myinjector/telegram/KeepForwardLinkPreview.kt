package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.getObj
import io.github.a13e300.myinjector.arch.hookAll
import io.github.a13e300.myinjector.arch.hookAllBefore
import io.github.a13e300.myinjector.arch.hook

/**
 * 转发时保留链接预览。
 *
 * 「隐藏发送者」转发（processForwardFromMyName / sendMessage(ArrayList...) 的本地消息分支）
 * 会把原消息文字重新发送，只传入 webPage，服务器按文本重新生成预览，
 * 原预览的 URL、大小图和位置设置因此丢失。
 * 这里在转发调用期间记录源消息，若 SendMessageParams.webPage 正是源消息的预览，
 * 则补上 mediaWebPage（TL_messageMediaWebPage）与 invert_media，使客户端以 inputMediaWebPage 原样发送。
 * SendMessagesHelper 及其成员在官方版本中保留名称，按名称定位。
 */
class KeepForwardLinkPreview : MyDynHook("keepForwardLinkPreview") {
    override fun isFeatureEnabled(): Boolean = TelegramHandler.settings.keepForwardLinkPreview

    private val sources = ThreadLocal<ArrayList<Any?>>()

    private fun push(v: Any?) {
        (sources.get() ?: ArrayList<Any?>().also { sources.set(it) }).add(v)
    }

    private fun pop() {
        val list = sources.get() ?: return
        if (list.isNotEmpty()) list.removeAt(list.size - 1)
        if (list.isEmpty()) sources.remove()
    }

    private fun candidates(): Sequence<Any> = sources.get().orEmpty().asSequence().flatMap {
        when (it) {
            is Collection<*> -> it.asSequence().filterNotNull()
            null -> emptySequence()
            else -> sequenceOf(it)
        }
    }

    override fun onHook() {
        val helper = findClass("org.telegram.messenger.SendMessagesHelper")
        val kParams = findClass("org.telegram.messenger.SendMessagesHelper\$SendMessageParams")
        val fMediaWebPage = kParams.getDeclaredField("mediaWebPage").also { it.isAccessible = true }
        val fWebPage = kParams.getDeclaredField("webPage").also { it.isAccessible = true }
        val fInvert = runCatching { kParams.getDeclaredField("invert_media").also { it.isAccessible = true } }.getOrNull()
        val kMediaWebPage = fMediaWebPage.type

        // 记录源消息；不受开关条件影响，保证 push/pop 成对。
        helper.hookAll(
            "processForwardFromMyName",
            before = { push(it.args.getOrNull(0)) },
            after = { pop() },
        )
        helper.declaredMethods.filter {
            it.name == "sendMessage" && it.parameterTypes.firstOrNull() == ArrayList::class.java
        }.forEach { m ->
            m.hook(before = { push(it.args[0]) }, after = { pop() })
        }

        helper.hookAllBefore("sendMessage", cond = ::isEnabled) { param ->
            val params = param.args.singleOrNull()?.takeIf { kParams.isInstance(it) } ?: return@hookAllBefore
            if (fMediaWebPage.get(params) != null) return@hookAllBefore
            val webPage = fWebPage.get(params) ?: return@hookAllBefore
            for (msg in candidates()) {
                val owner = runCatching { msg.getObj("messageOwner") }.getOrNull() ?: continue
                val media = owner.getObj("media") ?: continue
                if (!kMediaWebPage.isInstance(media) || media.getObj("webpage") !== webPage) continue
                fMediaWebPage.set(params, media)
                fInvert?.let { f ->
                    runCatching { f.setBoolean(params, owner.getObj("invert_media") as Boolean) }
                }
                break
            }
        }
    }
}
