package io.github.a13e300.myinjector

import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.a13e300.myinjector.arch.IHook
import io.github.a13e300.myinjector.arch.callS
import io.github.a13e300.myinjector.arch.hookAllNop
import io.github.a13e300.myinjector.arch.hookBefore

/**
 * Runs inside the MIUI permission manager service (com.lbe.security.miui).
 *
 * HyperOS 3 (permission manager 1.9.2) removed the legacy permmgr "active" table
 * (suggestAccept) that the "保持自启动" switch in the security center used to read
 * and write. The auto-start revoke scheduler now only skips packages listed in
 * PermissionConfigSharePref's AUTO_START_WHITE_LIST, which is private storage of
 * this app and cannot be written from any other process. So the existing permmgr
 * provider is extended with two module-only call() methods that toggle the native
 * whitelist in-process (see MIUISecurityCenterHandler for the callers). No
 * revocation logic is hooked for this flag: once it is stored, the stock scheduler
 * honours it even while this module is disabled.
 */
class LbeHandler : IHook() {
    override fun onHook() {
        if (loadPackageParam.packageName != "com.lbe.security.miui") return
        if (findClassOrNull("com.hyperos.security.service.provider.PermissionManagerProvider") != null) {
            hookAndroid17OnePush()
            return
        }
        findClassOrNull("com.miui.privacy.autostart.AutoRevokePermissionManager")
            ?.hookAllNop("lambda\$startScheduleASCheck\$1")
        // HyperOS 3+ flag storage; older versions keep using the legacy table and the
        // switch falls back automatically when these methods answer nothing.
        val flagManager = findClassOrNull("com.lbe.security.utility.PermissionConfigSharePref")
        if (flagManager == null) {
            logI("keep-autostart: PermissionConfigSharePref missing, provider extension NOT installed")
            return
        }
        val provider = findClassOrNull("com.lbe.security.service.provider.PermissionManagerProvider")
        if (provider == null) {
            logI("keep-autostart: PermissionManagerProvider missing, provider extension NOT installed")
            return
        }
        provider.hookBefore("call", String::class.java, String::class.java, Bundle::class.java) { param ->
            when (param.args[0] as? String) {
                METHOD_GET_KEEP_AUTOSTART -> {
                    val pkg = param.args[1] as? String
                    if (!pkg.isNullOrBlank()) {
                        param.result = Bundle().apply {
                            runCatching {
                                val keep = flagManager.callS("isAutoStartWhiteList", pkg) as Boolean
                                logI("keep-autostart GET $pkg -> $keep")
                                putBoolean("keep", keep)
                            }.onFailure {
                                logE("keep-autostart GET $pkg failed", it)
                                putBoolean("keep", false)
                            }
                        }
                    }
                }
                METHOD_SET_KEEP_AUTOSTART -> {
                    val extras = param.args[2] as? Bundle ?: return@hookBefore
                    val pkg = extras.getString("pkg")
                    if (!pkg.isNullOrBlank()) {
                        val enable = extras.getBoolean("enable")
                        param.result = Bundle().apply {
                            runCatching {
                                flagManager.callS(
                                    if (enable) "addAutoStartWhiteList" else "removeAutoStartWhiteList",
                                    pkg
                                )
                                logI("keep-autostart SET $pkg enable=$enable ok")
                                putBoolean("ok", true)
                            }.onFailure {
                                logE("keep-autostart SET $pkg failed", it)
                                putBoolean("ok", false)
                            }
                        }
                    }
                }
            }
        }
        logI("keep-autostart provider extension installed")
    }

    /** 4.0.0: the preference persists; enforcement still requires this module. */
    private fun hookAndroid17OnePush() {
        val provider = findClass("com.hyperos.security.service.provider.PermissionManagerProvider")
        fun prefs(context: Context) = context.getSharedPreferences("myinjector_onepush_keep_v1", Context.MODE_PRIVATE)
        fun protected(context: Context, pkg: String, userId: Int): Boolean = runCatching {
            prefs(context).getBoolean("$userId@$pkg", false)
        }.onFailure { logE("OnePush protection: read failed", it) }.getOrDefault(false)

        val scheduledInstalled = runCatching {
            val task = findClass("com.hyperos.security.service.OnePushJobService\$GetCloudDataTask")
            task.hookBefore(
                "revokeAutoStart", Context::class.java, PackageInfo::class.java, List::class.java
            ) { param ->
                val context = param.args[0] as? Context ?: return@hookBefore
                val info = param.args[1] as? PackageInfo ?: return@hookBefore
                val uid = info.applicationInfo?.uid ?: return@hookBefore
                if (!protected(context, info.packageName, uid / 100000)) return@hookBefore
                logI("OnePush protection: skipped scheduled revoke for ${info.packageName}")
                param.result = null
            }
            logI("OnePush protection: scheduled revoke hook installed")
        }.onFailure { logE("OnePush protection: scheduled revoke hook unavailable", it) }.isSuccess

        val registrationInstalled = runCatching {
            provider.hookBefore("dealOnePush", Bundle::class.java) { param ->
                val request = param.args[0] as? Bundle ?: return@hookBefore
                if (request.getString("call_pkg") != "com.xiaomi.xmsf" ||
                    !request.getBoolean("regist_deregist", false)
                ) return@hookBefore
                val pkg = request.getString("regist_deregist_pkg")?.takeIf { it.isNotBlank() }
                    ?: return@hookBefore
                val context = (param.thisObject as? ContentProvider)?.context ?: return@hookBefore
                val userId = request.getInt("regist_deregist_pkg_userid", Process.myUid() / 100000)
                if (!protected(context, pkg, userId)) return@hookBefore
                logI("OnePush protection: skipped registration revoke for $pkg user=$userId")
                // Deregistration and historical recovery continue through stock code.
                param.result = Bundle().apply { putBoolean("auto_start_set_status", false) }
            }
            logI("OnePush protection: registration revoke hook installed")
        }.onFailure { logE("OnePush protection: registration revoke hook unavailable", it) }.isSuccess

        provider.hookBefore("call", String::class.java, String::class.java, Bundle::class.java) { param ->
            val method = param.args[0] as? String
            if (method != METHOD_GET_KEEP_AUTOSTART && method != METHOD_SET_KEEP_AUTOSTART) return@hookBefore
            // Handle only our extension strings; never change numeric stock methods.
            val result = Bundle()
            param.result = result
            runCatching {
                check(scheduledInstalled && registrationInstalled) { "OnePush hooks incomplete" }
                val context = (param.thisObject as ContentProvider).context ?: error("Provider not attached")
                val caller = Binder.getCallingUid()
                check(caller == Process.myUid() || caller % 100000 == 1000) { "Caller not allowed" }
                val extras = param.args[2] as? Bundle
                val userId = extras?.getInt("userId", caller / 100000) ?: (caller / 100000)
                require(userId >= 0 && (userId == caller / 100000 || caller % 100000 == 1000))
                val pkg = (if (method == METHOD_GET_KEEP_AUTOSTART) param.args[1] as? String
                    else extras?.getString("pkg")) ?: error("Missing package")
                require(pkg.isNotBlank() && pkg.length <= 255 && !pkg.contains('@'))
                val storage = prefs(context)
                val key = "$userId@$pkg"
                if (method == METHOD_SET_KEEP_AUTOSTART) {
                    val values = requireNotNull(extras)
                    require(values.containsKey("enable"))
                    check(storage.edit().putBoolean(key, values.getBoolean("enable")).commit()) { "Preference write failed" }
                }
                val keep = storage.getBoolean(key, false)
                result.putBoolean("keep", keep)
                result.putBoolean("ok", true)
                logI("OnePush keep $method user=$userId pkg=$pkg keep=$keep")
            }.onFailure {
                result.putBoolean("ok", false)
                logE("OnePush keep extension failed", it)
            }
        }
        logI("OnePush protection: per-app provider extension installed; default off, no permanent native flag")
    }

    companion object {
        /** Module-only provider method names; PermissionManagerProvider.call parses
         *  every stock method as an integer, so these strings never collide with it. */
        const val METHOD_GET_KEEP_AUTOSTART = "myinjector_get_keep_autostart"
        const val METHOD_SET_KEEP_AUTOSTART = "myinjector_set_keep_autostart"
    }
}
