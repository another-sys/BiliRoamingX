package app.revanced.bilibili.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import app.revanced.bilibili.integrations.BuildConfig
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.Toasts
import app.revanced.bilibili.utils.Utils
import app.revanced.bilibili.utils.versionCode
import tv.danmaku.bili.MainActivityV2
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Independent worker: update traffic never blocks the shared module worker or UI. */
object AppUpdater {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, "BRX-updater").apply { isDaemon = true } } }
    private val checking = AtomicBoolean()
    private val downloading = AtomicBoolean()
    private val prefs get() = Utils.getContext().getSharedPreferences("brx-updater", 0)
    private var pending: UpdateManifest? = null
    private var waitingForInstallPermission = false
    private var dialogShowing = false
    private val apk get() = File(Utils.getContext().filesDir, "brx-update/update.apk")

    @JvmStatic
    fun onActivityResumed(activity: Activity) {
        if (!Utils.isMainProcess() || activity.packageName != "tv.danmaku.bili") return
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
                pending?.let { offerInstall(activity, it) }
            }
        }
        if (activity is MainActivityV2 && Settings.ForkAutoUpdate()) {
            val reference = WeakReference(activity)
            Utils.runOnMainThread(15000) {
                reference.get()?.takeIf { usable(it) && it.hasWindowFocus() }?.let { check(it, false) }
            }
        }
    }

    @JvmStatic
    fun check(activity: Activity, manual: Boolean) {
        if (!usable(activity) || dialogShowing) return
        if (downloading.get()) { if (manual) Toasts.showShort("正在下载更新"); return }
        if (manual) pending?.let { offerInstall(activity, it); return }
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_attempt", 0)
        if (!manual && now >= last && now - last < DAY) return
        if (!checking.compareAndSet(false, true)) { if (manual) Toasts.showShort("正在检查更新"); return }
        prefs.edit().putLong("last_attempt", now).apply()
        if (manual) Toasts.showShort("正在检查更新")
        val reference = WeakReference(activity)
        worker.execute {
            try {
                val connection = connect(BuildConfig.UPDATE_URL + "?check=" + now)
                val text = try {
                    connection.inputStream.use { input ->
                        val bytes = input.readBytesLimited(131072)
                        bytes.toString(Charsets.UTF_8)
                    }
                } finally { connection.disconnect() }
                val info = UpdateManifest.parse(text, activity.packageName)
                require(info.versionCode >= versionCode.toLong()) { "服务器返回了旧版客户端" }
                val highest = prefs.getLong("highest_seen", 0)
                val newer = info.code > BuildConfig.UPDATE_BUILD_CODE && info.code >= highest
                if (newer) prefs.edit().putLong("highest_seen", info.code).apply()
                Utils.runOnMainThread {
                    val current = reference.get() ?: return@runOnMainThread
                    if (!usable(current) || !current.hasWindowFocus()) return@runOnMainThread
                    if (newer && (manual || prefs.getLong("ignored", 0) != info.code)) offer(current, info)
                    else if (manual) Toasts.showShort("已是当前发布版本")
                }
            } catch (error: Exception) {
                Logger.warn { "Update check failed: ${error.javaClass.simpleName}" }
                if (manual) Utils.runOnMainThread { Toasts.showShort("检查更新失败，请稍后重试") }
            } finally { checking.set(false) }
        }
    }

    private fun offer(activity: Activity, info: UpdateManifest) {
        if (dialogShowing) return
        dialogShowing = true
        AlertDialog.Builder(activity).setTitle("发现新版哔哩漫游 X")
            .setMessage("${info.release}\n下载大小：${info.size / 1024 / 1024} MB\n\n${info.notes}")
            .setPositiveButton("下载更新") { _, _ -> download(activity, info) }
            .setNegativeButton("稍后", null)
            .setNeutralButton("忽略本版本") { _, _ -> prefs.edit().putLong("ignored", info.code).apply() }
            .setOnDismissListener { dialogShowing = false }.show()
    }

    @Suppress("DEPRECATION")
    private fun download(activity: Activity, info: UpdateManifest) {
        if (!downloading.compareAndSet(false, true)) return
        val cancelled = AtomicBoolean()
        val reference = WeakReference(activity)
        val progress = ProgressDialog(activity).apply {
            setTitle("下载更新")
            setMessage("下载完成后会校验安装包")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "取消") { _, _ -> cancelled.set(true) }
            show()
        }
        worker.execute {
            val part = File(apk.parentFile, "update.apk.part")
            try {
                apk.parentFile!!.mkdirs()
                part.delete()
                val hash = MessageDigest.getInstance("SHA-256")
                val connection = connect(info.url)
                try {
                    val length = connection.contentLengthLong
                    require(length < 0 || length == info.size) { "安装包大小不匹配" }
                    connection.inputStream.use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        var lastProgress = 0L
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("Cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= info.size) { "安装包超出预期大小" }
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - lastProgress >= 500) {
                                lastProgress = now
                                val percent = (total * 100 / info.size).toInt()
                                Utils.runOnMainThread { if (usable(activity)) progress.progress = percent }
                            }
                        }
                        require(total == info.size && hex(hash.digest()) == info.sha256) { "安装包校验失败" }
                    } }
                } finally { connection.disconnect() }
                if (cancelled.get()) throw InterruptedException("Cancelled")
                val identity = activity.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
                require(identity?.packageName == activity.packageName && identity.versionCode.toLong() == info.versionCode) { "安装包身份不匹配" }
                apk.delete()
                require(part.renameTo(apk)) { "无法保存安装包" }
                pending = info
                Utils.runOnMainThread {
                    runCatching { progress.dismiss() }
                    reference.get()?.takeIf(::usable)?.let { offerInstall(it, info) }
                }
            } catch (error: Exception) {
                part.delete()
                Logger.warn { "Update download failed: ${error.javaClass.simpleName}" }
                Utils.runOnMainThread {
                    runCatching { progress.dismiss() }
                    if (!cancelled.get()) Toasts.showShort("下载或校验失败，请重新检查更新后重试")
                }
            } finally { downloading.set(false) }
        }
    }

    private fun offerInstall(activity: Activity, info: UpdateManifest) {
        if (!apk.isFile) { pending = null; return }
        if (!usable(activity) || dialogShowing) return
        dialogShowing = true
        AlertDialog.Builder(activity).setTitle("更新已下载并通过校验")
            .setMessage("${info.release}\n覆盖安装会保留账号和设置。")
            .setPositiveButton("安装更新") { _, _ ->
                if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    waitingForInstallPermission = true
                    activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                } else install(activity)
            }.setNegativeButton("稍后", null)
            .setOnDismissListener { dialogShowing = false }.show()
    }

    private fun install(activity: Activity) {
        val uri = Uri.parse("content://${activity.packageName}.brx.update/update/update.apk")
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { Toasts.showShort("无法打开安装器，请稍后从检查更新重试") }
    }

    private fun connect(address: String): HttpURLConnection {
        var url = address
        repeat(5) {
            UpdateManifest.requireDownloadUrl(url)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "BiliRoamingX-Updater/${BuildConfig.UPDATE_BUILD_CODE}")
            val status = connection.responseCode
            if (status in arrayOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrEmpty()) { "下载地址重定向无效" }
                url = URL(URL(url), location).toString()
            } else {
                if (status != 200) { connection.disconnect(); error("HTTP $status") }
                return connection
            }
        }
        error("下载地址重定向过多")
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "更新信息过大" }
            output.write(buffer, 0, count)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun usable(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
}
