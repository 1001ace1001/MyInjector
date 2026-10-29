package io.github.a13e300.myinjector

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.content.Context
import io.github.a13e300.myinjector.arch.DynHookManager
import io.github.a13e300.myinjector.ui.settings.SettingItem
import io.github.a13e300.myinjector.ui.settings.SettingsGroup
import io.github.a13e300.myinjector.ui.settings.ToggleSetting
import java.io.InputStream
import java.io.OutputStream
import io.github.a13e300.myinjector.arch.call
import io.github.a13e300.myinjector.arch.callS
import io.github.a13e300.myinjector.arch.getObjAsN
import io.github.a13e300.myinjector.arch.hookAllBefore

object MiuiGalleryHandler : DynHookManager<MiuiGallerySettings>() {
    override fun onHook() {
        if (loadPackageParam.processName.contains(":")) return
        super.onHook()
        addSettingsIntentInterceptor { MiuiGallerySettingsDialog(it).show() }
        subHook(MiuiGalleryPhotoDetails())
        runCatching { hookShare() }.onFailure { logE("gallery share hook unavailable", it) }
    }

    override fun onReadSettings(input: InputStream): MiuiGallerySettings =
        MiuiGallerySettings.parseFrom(input)

    override fun onWriteSettings(output: OutputStream, setting: MiuiGallerySettings) =
        setting.writeTo(output)

    override fun defaultSettings(): MiuiGallerySettings = MiuiGallerySettings.getDefaultInstance()

    private fun hookShare() {
        val galleryOpenProviderClass = findClass("com.miui.gallery.provider.GalleryOpenProvider")
        findClass("com.miui.gallery.ui.photoPage.bars.menuitem.Send").hookAllBefore("onClick") { param ->
            val item = param.args[0]
            if (item == null) {
                logE("no item")
                return@hookAllBefore
            }
            val path = item.getObjAsN<String>("mFilePath")
            if (path == null) {
                logE("no mFilePath: $item")
                return@hookAllBefore
            }
            val uri = galleryOpenProviderClass.callS("translateToContent", path) as Uri
            logD("uri=$uri")
            val activity = param.thisObject.call("getBaseActivity") as Activity
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, null).also {
                // allow intentresolver to preview
                it.clipData = ClipData.newRawUri("", uri)
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            param.result = null
        }
        // TODO: multiple send
    }
}

class MiuiGallerySettingsDialog(ctx: Context) : SettingDialog(ctx) {
    override val dialogTitle = "相册设置"

    override fun onSettingChanged(setting: SettingItem, newValue: Any?): Boolean {
        if (setting.key != "photoDetails") return false
        MiuiGalleryHandler.updateSettings(
            MiuiGalleryHandler.settings.toBuilder().setPhotoDetails(newValue as Boolean).build()
        )
        return true
    }

    override fun onSettingClicked(setting: SettingItem): Boolean = false

    override fun loadSetting(setting: SettingItem) {
        if (setting.key == "photoDetails") {
            (setting as ToggleSetting).isChecked = MiuiGalleryHandler.settings.photoDetails
        }
    }

    override fun createSettings(settingGroup: SettingsGroup) {
        settingGroup.toggle(
            "照片预览页显示更多详情", "photoDetails",
            summary = "预览页内显示详情按钮；开启后重新打开照片。读取当前应用已有权限可访问的文件和 EXIF，不申请 root。"
        )
    }
}
