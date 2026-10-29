package io.github.a13e300.myinjector.telegram

import io.github.a13e300.myinjector.arch.hookBefore
import io.github.a13e300.myinjector.bridge.HookParam
import java.lang.reflect.Method
import java.util.Locale

/**
 * 精确时间相关功能共用工具。
 *
 * 带秒格式器的类型取自 LocaleController.getFormatterDay() 的返回类型（FastDateFormat），
 * 即使该类被混淆也能构造出同类型对象。功能在目标方法执行期间调用 [enter]/[exit]，
 * 期间当前线程的 getFormatterDay() 会返回带秒格式器，从而不必重算排版宽度。
 */
internal object PreciseTime {
    private val depth = ThreadLocal<IntArray>()
    private var installed = false
    private lateinit var kLocaleController: Class<*>
    private lateinit var getInstance: Method
    private lateinit var formatLong: Method
    private var cachedKey: String? = null
    private var cached: Any? = null

    /** 只安装一次 getFormatterDay 钩子；未处于 enter/exit 范围时不改变任何行为。 */
    @Synchronized
    fun install(loader: ClassLoader) {
        if (installed) return
        kLocaleController = Class.forName("org.telegram.messenger.LocaleController", false, loader)
        val getFormatterDay = kLocaleController.declaredMethods.single {
            it.name == "getFormatterDay" && it.parameterTypes.isEmpty()
        }
        val kFastDateFormat = getFormatterDay.returnType
        getInstance = kFastDateFormat.getMethod("getInstance", String::class.java, Locale::class.java)
        formatLong = kFastDateFormat.getMethod("format", Long::class.javaPrimitiveType)
        getFormatterDay.hookBefore(cond = ::active) { it.result = formatter() }
        installed = true
    }

    fun active(): Boolean = (depth.get()?.get(0) ?: 0) > 0

    fun enter() {
        val d = depth.get() ?: IntArray(1).also { depth.set(it) }
        d[0]++
    }

    fun exit() {
        val d = depth.get() ?: return
        if (--d[0] <= 0) depth.remove()
    }

    /** 用作 hook 的 before/after，配合 cond 控制是否启用。 */
    val enterHook: (HookParam) -> Unit = { enter() }
    val exitHook: (HookParam) -> Unit = { exit() }

    private fun is24Hour(): Boolean = runCatching {
        kLocaleController.getDeclaredField("is24HourFormat").also { it.isAccessible = true }.getBoolean(null)
    }.getOrDefault(true)

    /** 与原格式器同一 Locale 规则：除 ar/ko 外使用 Locale.US，保证 AM/PM 文本一致。 */
    private fun locale(): Locale {
        val l = Locale.getDefault()
        val lang = l.language.lowercase()
        return if (lang == "ar" || lang == "ko") l else Locale.US
    }

    @Synchronized
    fun formatter(): Any {
        val pattern = if (is24Hour()) "HH:mm:ss" else "h:mm:ss a"
        val locale = locale()
        val key = "$pattern|$locale"
        cached?.takeIf { cachedKey == key }?.let { return it }
        return getInstance.invoke(null, pattern, locale)!!.also {
            cached = it
            cachedKey = key
        }
    }

    fun format(millis: Long): String = formatLong.invoke(formatter(), millis) as String
}
