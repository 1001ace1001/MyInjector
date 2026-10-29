package io.github.a13e300.myinjector

import android.app.IActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.widget.Toast
import io.github.a13e300.myinjector.arch.DynHook
import io.github.a13e300.myinjector.arch.DynHookManager
import io.github.a13e300.myinjector.arch.call
import io.github.a13e300.myinjector.arch.getObj
import io.github.a13e300.myinjector.arch.getObjAsN
import io.github.a13e300.myinjector.arch.hookAllBefore
import io.github.a13e300.myinjector.ui.settings.SettingItem
import io.github.a13e300.myinjector.ui.settings.SettingsGroup
import io.github.a13e300.myinjector.ui.settings.ToggleSetting
import java.io.InputStream
import java.io.OutputStream

/**
 * Lawnchair（Launcher3/Quickstep）最近任务划卡强杀。
 *
 * 单张卡片移除最终都会走 RecentsView.removeGroupTaskInternal(GroupTask)，
 * 它在 UI_HELPER_EXECUTOR 上逐个 ActivityManagerWrapper.removeTask(taskId)。
 * 全部清除走 removeAllRecentTasks，不经过这里；另在 dismissAllTasks 期间屏蔽，
 * 防止新旧动画实现回调到单卡路径。
 */
class LawnchairSwipeKill : DynHook() {
    override fun isFeatureEnabled(): Boolean = LawnchairHandler.settings.swipeKill

    @Volatile
    private var clearAllUntil = 0L

    private val worker by lazy {
        Handler(HandlerThread("MyInjector-LawnchairKill").also { it.start() }.looper)
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val ams by lazy {
        IActivityManager.Stub.asInterface(ServiceManager.getService("activity"))
    }

    override fun onHook() {
        val recentsView = findClass("com.android.quickstep.views.RecentsView")
        recentsView.hookAllBefore("dismissAllTasks") {
            clearAllUntil = SystemClock.uptimeMillis() + 3000
        }
        recentsView.hookAllBefore("removeGroupTaskInternal", cond = { isFeatureEnabled() }) { param ->
            if (SystemClock.uptimeMillis() < clearAllUntil) return@hookAllBefore
            val group = param.args[0] ?: return@hookAllBefore
            // 桌面窗口模式的整组任务不处理
            if (group.javaClass.name == "com.android.quickstep.util.DesktopTask") return@hookAllBefore
            val ctx = (param.thisObject as android.view.View).context
            val targets = (group.call("getTasks") as? List<*>).orEmpty().mapNotNull { task ->
                val key = task?.getObj("key") ?: return@mapNotNull null
                val pkg = key.call("getPackageName") as? String ?: return@mapNotNull null
                val user = key.getObjAsN<Int>("userId") ?: return@mapNotNull null
                if (pkg == ctx.packageName) null else pkg to user
            }.distinct()
            if (targets.isEmpty()) return@hookAllBefore
            // 原方法把 removeTask 投递到 UI_HELPER_EXECUTOR；强停放到独立线程，不阻塞 UI。
            worker.post { targets.forEach { (pkg, user) -> forceStop(ctx, pkg, user) } }
        }
    }

    private fun forceStop(ctx: Context, pkg: String, user: Int) {
        runCatching { ams.forceStopPackage(pkg, user) }
            .onSuccess {
                logI("force stopped $pkg/$user")
                toast(ctx, "已停止 $pkg")
            }
            .onFailure {
                logE("forceStopPackage $pkg/$user", it)
                toast(ctx, "停止 $pkg 失败：${it.javaClass.simpleName}")
            }
    }

    private fun toast(ctx: Context, text: String) = main.post {
        runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
    }
}

object LawnchairHandler : DynHookManager<LawnchairSettings>() {
    override fun onHook() {
        if (loadPackageParam.processName.contains(":")) return
        super.onHook()
        addSettingsIntentInterceptor { LawnchairSettingsDialog(it).show() }
        subHook(LawnchairSwipeKill())
    }

    override fun onReadSettings(input: InputStream): LawnchairSettings =
        LawnchairSettings.parseFrom(input)

    override fun onWriteSettings(output: OutputStream, setting: LawnchairSettings) =
        setting.writeTo(output)

    override fun defaultSettings(): LawnchairSettings = LawnchairSettings.getDefaultInstance()
}

class LawnchairSettingsDialog(ctx: Context) : SettingDialog(ctx) {
    override val dialogTitle = "Lawnchair 设置"

    override fun onSettingChanged(setting: SettingItem, newValue: Any?): Boolean {
        if (setting.key != "swipeKill") return false
        LawnchairHandler.updateSettings(
            LawnchairHandler.settings.toBuilder().setSwipeKill(newValue as Boolean).build()
        )
        return true
    }

    override fun onSettingClicked(setting: SettingItem): Boolean = false

    override fun loadSetting(setting: SettingItem) {
        if (setting.key == "swipeKill") {
            (setting as ToggleSetting).isChecked = LawnchairHandler.settings.swipeKill
        }
    }

    override fun createSettings(settingGroup: SettingsGroup) {
        settingGroup.toggle(
            "划卡强杀进程", "swipeKill",
            summary = "最近任务中划走单张卡片时强行停止该应用（不含全部清除）。需要 Lawnchair 作为 Quickstep 最近任务提供者并持有 FORCE_STOP_PACKAGES 权限。"
        )
    }
}
