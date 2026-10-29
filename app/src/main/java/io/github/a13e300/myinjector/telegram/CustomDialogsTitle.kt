package io.github.a13e300.myinjector.telegram

import android.graphics.drawable.Drawable
import io.github.a13e300.myinjector.arch.hookAll
import io.github.a13e300.myinjector.arch.hookAllAfter
import io.github.a13e300.myinjector.bridge.HookParam
import io.github.a13e300.myinjector.logE
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 自定义对话列表标题。
 *
 * 主页 DialogsActivity 的标题在 createView、切换文件夹标签、账号/会员状态变化时多次重新设置，
 * 来源分别是 R.string.AppName（官方）或 LocaleUtils.getActionBarTitle（exteraGram 系）。
 * createView 结束后，若当前页是主列表（folderId == 0、非选择模式、非搜索、非社群），记录其 ActionBar，
 * 并把当时的标题视为「默认标题」；之后对该 ActionBar 的 setTitle / setTitleAnimated* 调用中，
 * 文本等于默认标题时替换为自定义文本。文件夹标签名等其他标题保持不变。
 * 同时改写 actionBarDefaultTitle 字段，切换回「全部」标签时也显示自定义文本。
 */
class CustomDialogsTitle : MyDynHook("customDialogsTitle") {
    override fun isFeatureEnabled(): Boolean =
        TelegramHandler.settings.customDialogsTitle && TelegramHandler.settings.customDialogsTitleText.isNotEmpty()

    /** 主列表 ActionBar -> 原始默认标题。 */
    private val bars: MutableMap<Any, String> = Collections.synchronizedMap(WeakHashMap())

    private fun custom(): String = TelegramHandler.settings.customDialogsTitleText

    override fun onHook() {
        val kDialogs = findClass("org.telegram.ui.DialogsActivity")
        val kActionBar = findClass("org.telegram.ui.ActionBar.ActionBar")
        val actionBarField = findField(kDialogs) { it.type == kActionBar }
            ?: error("BaseFragment.actionBar not found")
        fun intField(name: String) = runCatching {
            kDialogs.getDeclaredField(name).also { it.isAccessible = true }
        }.getOrNull()
        val folderId = intField("folderId")
        val onlySelect = intField("onlySelect")
        val initialDialogsType = intField("initialDialogsType")
        val searchString = intField("searchString")
        val communityId = intField("communityId")
        val defaultTitle = intField("actionBarDefaultTitle")
        val statusDrawable = intField("statusDrawable")

        kDialogs.hookAllAfter("createView", cond = ::isEnabled) { param ->
            val fragment = param.thisObject
            if ((folderId?.getInt(fragment) ?: 0) != 0) return@hookAllAfter
            if (onlySelect?.getBoolean(fragment) == true) return@hookAllAfter
            if ((initialDialogsType?.getInt(fragment) ?: 0) != 0) return@hookAllAfter
            if (searchString?.get(fragment) != null) return@hookAllAfter
            if ((communityId?.getLong(fragment) ?: 0L) != 0L) return@hookAllAfter
            val bar = actionBarField.get(fragment) ?: return@hookAllAfter
            val original = (defaultTitle?.get(fragment) as? CharSequence)?.toString()
                ?: (bar.javaClass.getMethod("getTitle").invoke(bar) as? CharSequence)?.toString()
                ?: return@hookAllAfter
            if (original == custom()) return@hookAllAfter
            bars[bar] = original
            if (defaultTitle != null && defaultTitle.type.isAssignableFrom(String::class.java)) {
                defaultTitle.set(fragment, custom())
            }
            // 重新设置一次标题，保留 Premium 状态图标。
            replaceCurrent(bar, statusDrawable?.get(fragment))
        }

        val replace: (HookParam) -> Unit = replace@{ param ->
            val original = bars[param.thisObject] ?: return@replace
            val idx = param.args.indexOfFirst { it is CharSequence }
            if (idx < 0) return@replace
            val text = param.args[idx].toString()
            // exteraGram 在账号切换后会重新计算默认标题，此时同步更新记录。
            if (text == original || text == custom()) {
                param.args[idx] = custom()
            }
        }
        for (name in listOf("setTitle", "setTitleAnimated", "setTitleAnimatedX")) {
            kActionBar.hookAll(name, cond = ::isEnabled, before = replace)
        }
    }

    private fun replaceCurrent(bar: Any, drawable: Any?) {
        runCatching {
            if (drawable is Drawable) {
                bar.javaClass.getMethod("setTitle", CharSequence::class.java, Drawable::class.java)
                    .invoke(bar, custom(), drawable)
            } else {
                bar.javaClass.getMethod("setTitle", CharSequence::class.java).invoke(bar, custom())
            }
        }.onFailure { logE("customDialogsTitle setTitle", it) }
    }

    private fun findField(cls: Class<*>, predicate: (Field) -> Boolean): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull(predicate)?.let { return it.also { f -> f.isAccessible = true } }
            c = c.superclass
        }
        return null
    }
}
