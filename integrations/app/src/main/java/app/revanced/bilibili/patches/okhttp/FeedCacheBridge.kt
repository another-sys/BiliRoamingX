package app.revanced.bilibili.patches.okhttp

import android.content.SharedPreferences
import androidx.annotation.Keep
import app.revanced.bilibili.patches.main.ApplicationDelegate
import app.revanced.bilibili.patches.okhttp.hooks.FeedWindow
import app.revanced.bilibili.settings.Setting
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.Versions
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

object FeedCacheBridge {
    // The response has already passed Pegasus filtering and history accounting.
    // A JSON subtype keeps the normal OkHttp response hook from applying both a second time.
    private const val CONTENT_TYPE = "application/vnd.biliroamingx.feed+json"
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> FeedWindow.invalidate() }
    private val initialized by lazy { Setting.registerPreferenceChangeListener(settingsListener); true }

    @Keep
    @JvmStatic
    fun proceed(chain: Interceptor.Chain, request: Request): Response {
        val url = request.url().toString()
        if (!url.contains("/x/v2/feed/index?") || !ApplicationDelegate.attached() || !Versions.ge9_17_0())
            return chain.proceed(request)
        val cached = try {
            if (initialized) FeedWindow.open(url) else null
        } catch (e: Exception) {
            Logger.warn { "Feed cache unavailable (${e.javaClass.simpleName})" }
            null
        }
        if (cached != null) {
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", CONTENT_TYPE).header("Cache-Control", "no-store")
                .body(ResponseBody.create(MediaType.get(CONTENT_TYPE), cached)).build()
        }
        try {
            return chain.proceed(request)
        } catch (e: Throwable) {
            FeedWindow.finishRequest()
            throw e
        }
    }
}
