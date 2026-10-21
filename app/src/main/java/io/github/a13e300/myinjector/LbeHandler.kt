package io.github.a13e300.myinjector

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import io.github.a13e300.myinjector.arch.IHook
import io.github.a13e300.myinjector.arch.call
import io.github.a13e300.myinjector.arch.callS
import io.github.a13e300.myinjector.arch.hookAfter
import io.github.a13e300.myinjector.arch.hookAllBefore
import io.github.a13e300.myinjector.arch.hookAllNop
import io.github.a13e300.myinjector.arch.hookBefore
import kotlin.concurrent.thread

/**
 * Runs inside the MIUI permission manager service (com.lbe.security.miui).
 *
 * HyperOS 3 (permission manager 1.9.2, packages under com.lbe.security.*) removed the
 * legacy permmgr "active" table (suggestAccept) that the "保持自启动" switch used to
 * read and write. The auto-start revoke scheduler then only skipped packages listed in
 * PermissionConfigSharePref's AUTO_START_WHITE_LIST, private storage of this app, so
 * the permmgr provider is extended with two module-only call() methods that toggle the
 * native whitelist in-process (see MIUISecurityCenterHandler for the callers). While
 * the module is enabled, the scheduler check is additionally nop-ed as a fallback.
 *
 * Android 17 / HyperOS next (permission manager 4.0.0, security center 13.5.8) moved
 * the whole namespace to com.hyperos.security.* and turned autostart into the plain
 * framework permission PERM_ID_AUTOSTART (com.miui.permission.PermissionManager.
 * setApplicationPermission, action 3 = allow / 1 = deny; backed by AppOps). The
 * AUTO_START_WHITE_LIST helpers and the 30-day scheduler are gone from the service;
 * the only deny funnel inside this process is the framework PermissionManager. The
 * keep flag therefore keeps the same storage (the service-private SharedPreferences
 * "permission_config_sharePref", key AUTO_START_WHITE_LIST, StringSet — identical to
 * the 1.9.x format) and is now enforced here: deny sets for keep-listed packages are
 * vetoed at the funnel, and every OnePushJobService run re-grants action 3. Semantics:
 * while keep is ON the autostart permission is locked ON; turn keep OFF first to
 * disable autostart for that package.
 */
class LbeHandler : IHook() {
    override fun onHook() {
        if (loadPackageParam.packageName != "com.lbe.security.miui") return

        // HyperOS 3 and older: neuter the periodic auto-revoke scheduler as a fallback.
        // Android 17's service no longer ships it.
        val legacyScheduler = findClassOrNull("com.miui.privacy.autostart.AutoRevokePermissionManager")
        if (legacyScheduler != null) {
            legacyScheduler.hookAllNop("lambda\$startScheduleASCheck\$1")
        } else {
            logI("keep-autostart: legacy AutoRevokePermissionManager absent (Android 17 perm service)")
        }

        // 4.0.0 renamed every class into com.hyperos.security.*; keep the old names for HyperOS 3.
        val flagManager = findClassOrNull("com.hyperos.security.utility.PermissionConfigSharePref")
            ?: findClassOrNull("com.lbe.security.utility.PermissionConfigSharePref")
        if (flagManager == null) {
            logI("keep-autostart: PermissionConfigSharePref missing, provider extension NOT installed")
            return
        }
        val provider = findClassOrNull("com.hyperos.security.service.provider.PermissionManagerProvider")
            ?: findClassOrNull("com.lbe.security.service.provider.PermissionManagerProvider")
        if (provider == null) {
            logI("keep-autostart: PermissionManagerProvider missing, provider extension NOT installed")
            return
        }
        val legacyHelpers = flagManager.declaredMethods.any { it.name == LEGACY_METHOD_IS_KEEP }
        logI("keep-autostart: flagManager=${flagManager.name} legacyHelpers=$legacyHelpers")

        // The keep list always lives in the service-private SharedPreferences behind
        // PermissionConfigSharePref.mSp; both versions expose the same static field.
        fun sp(): SharedPreferences? = runCatching {
            val field = flagManager.getDeclaredField("mSp").apply { isAccessible = true }
            field.get(null) as? SharedPreferences
        }.onFailure {
            logE("keep-autostart: failed to access PermissionConfigSharePref.mSp", it)
        }.getOrNull()

        fun keepSet(): Set<String> =
            sp()?.getStringSet(KEY_KEEP_AUTO_START, emptySet()).orEmpty()

        fun readKeep(pkg: String): Boolean {
            if (legacyHelpers) {
                return runCatching { flagManager.callS(LEGACY_METHOD_IS_KEEP, pkg) as? Boolean }
                    .onFailure { logE("keep-autostart legacy GET $pkg failed", it) }
                    .getOrDefault(false) == true
            }
            return keepSet().contains(pkg)
        }

        fun writeKeep(pkg: String, enable: Boolean): Boolean {
            if (legacyHelpers) {
                flagManager.callS(
                    if (enable) LEGACY_METHOD_ADD_KEEP else LEGACY_METHOD_REMOVE_KEEP,
                    pkg
                )
                return true
            }
            val prefs = sp() ?: return false
            val set = (prefs.getStringSet(KEY_KEEP_AUTO_START, emptySet()) ?: emptySet()).toMutableSet()
            if (enable) set.add(pkg) else set.remove(pkg)
            prefs.edit().putStringSet(KEY_KEEP_AUTO_START, set).apply()
            return true
        }

        provider.hookBefore("call", String::class.java, String::class.java, Bundle::class.java) { param ->
            when (param.args[0] as? String) {
                METHOD_GET_KEEP_AUTOSTART -> {
                    val pkg = param.args[1] as? String
                    if (!pkg.isNullOrBlank()) {
                        param.result = Bundle().apply {
                            runCatching {
                                val keep = readKeep(pkg)
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
                                val ok = writeKeep(pkg, enable)
                                logI("keep-autostart SET $pkg enable=$enable ok=$ok")
                                putBoolean("ok", ok)
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

        // ------------------- Android 17 (service 4.x) enforcement -------------------
        // On 1.9.x the stock scheduler honours the whitelist itself; keep the proven
        // HyperOS 3 behaviour untouched there.
        if (legacyHelpers) return

        val permManager = findClassOrNull("com.miui.permission.PermissionManager") ?: run {
            logI("keep-autostart: framework PermissionManager missing, enforcement NOT installed")
            return
        }
        val permIdAutoStart = runCatching {
            permManager.getDeclaredField("PERM_ID_AUTOSTART").apply { isAccessible = true }
                .get(null) as? Long
        }.getOrNull() ?: FALLBACK_PERM_ID_AUTOSTART.also {
            logW("keep-autostart: PERM_ID_AUTOSTART unreadable, falling back to $it")
        }

        fun denyPackages(args: Array<Any?>): List<String> = args.drop(2).flatMap { arg ->
            when (arg) {
                is String -> if (arg.isNotBlank()) listOf(arg) else emptyList()
                is Array<*> -> arg.filterIsInstance<String>().filter { it.isNotBlank() }
                else -> emptyList()
            }
        }

        // 4.0.0's own revocation paths (cloud one-push in PermissionManagerProvider.dealOnePush,
        // the provider's generic set method) all funnel into the framework call below;
        // a UI-driven deny arrives here too via the provider switch case 6.
        permManager.hookAllBefore("setApplicationPermission") { param ->
            val permId = (param.args.getOrNull(0) as? Long) ?: return@hookAllBefore
            if (permId != permIdAutoStart) return@hookAllBefore
            val action = (param.args.getOrNull(1) as? Int) ?: return@hookAllBefore
            if (action == ACTION_ALLOW) return@hookAllBefore
            val targets = denyPackages(param.args).filter { it in keepSet() }
            if (targets.isEmpty()) return@hookAllBefore
            logI("keep-autostart: veto PERM_ID_AUTOSTART deny (action=$action) for ${targets.joinToString()}")
            param.result = null
        }
        logI("keep-autostart deny veto installed (permId=$permIdAutoStart)")

        // Safety net for revocations that never pass through this app (e.g. a direct
        // framework call from another process): every OnePushJobService run re-grants
        // action 3 for keep-listed packages.
        val onePush = findClassOrNull("com.hyperos.security.service.OnePushJobService")
        if (onePush == null) {
            logI("keep-autostart: OnePushJobService missing, re-grant watchdog NOT installed")
            return
        }
        onePush.hookAfter("onStartJob", findClass("android.app.job.JobParameters")) { param ->
            val keep = keepSet().filter { it.isNotBlank() }
            if (keep.isEmpty()) return@hookAfter
            val ctx = param.thisObject as? Context ?: return@hookAfter
            val userId = runCatching {
                Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
            }.getOrDefault(0)
            logI("keep-autostart: OnePush job, re-granting autostart for ${keep.joinToString()} (user $userId)")
            thread {
                keep.forEach { pkg ->
                    runCatching {
                        permManager.callS("getInstance", ctx)
                            .call("setApplicationPermission", permIdAutoStart, ACTION_ALLOW, userId, pkg)
                    }.onFailure {
                        logE("keep-autostart: re-grant failed for $pkg", it)
                    }
                }
            }
        }
        logI("keep-autostart re-grant watchdog installed")
    }

    companion object {
        /** Module-only provider method names; PermissionManagerProvider.call parses
         *  every stock method as an integer (both 1.9.2 and 4.0.0), so these strings
         *  never collide with it. */
        const val METHOD_GET_KEEP_AUTOSTART = "myinjector_get_keep_autostart"
        const val METHOD_SET_KEEP_AUTOSTART = "myinjector_set_keep_autostart"

        /** Same storage as the official 1.9.x whitelist: SharedPreferences
         *  "permission_config_sharePref" (PermissionConfigSharePref.mSp), StringSet key. */
        private const val KEY_KEEP_AUTO_START = "AUTO_START_WHITE_LIST"

        private const val LEGACY_METHOD_IS_KEEP = "isAutoStartWhiteList"
        private const val LEGACY_METHOD_ADD_KEEP = "addAutoStartWhiteList"
        private const val LEGACY_METHOD_REMOVE_KEEP = "removeAutoStartWhiteList"

        /** Framework permission ids: PERM_ID_AUTOSTART (read at runtime; the old
         *  suggestAccept autostart bit had the same value). */
        private const val FALLBACK_PERM_ID_AUTOSTART = 16384L
        private const val ACTION_ALLOW = 3
    }
}
