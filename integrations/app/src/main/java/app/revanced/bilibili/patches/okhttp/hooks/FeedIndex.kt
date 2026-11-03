package app.revanced.bilibili.patches.okhttp.hooks

import app.revanced.bilibili.patches.json.PegasusPatch
import app.revanced.bilibili.patches.okhttp.ApiHook
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.Utils
import app.revanced.bilibili.utils.Versions
import app.revanced.bilibili.utils.toJSONObject

object FeedIndex : ApiHook() {
    // 尊重官方，不强制关闭AB实验
    private val newPegasusEnabled by lazy {
        // KMP FeedItem is no longer controlled by the old AB flag in 9.x.
        runCatching { Class.forName("com.bilibili.bilifeed.card.FeedItem") }.isSuccess ||
                Utils.getAb("ff_key_use_new_pegasus", false)
    }

    override fun shouldHook(url: String, status: Int): Boolean {
        return status.isOk && Versions.ge7_76_0() && url.contains("/x/v2/feed/index?")
                && newPegasusEnabled
    }

    override fun hook(url: String, status: Int, request: String, response: String): String {
        return try {
            val resp = response.toJSONObject()
            if (resp.optInt("code", -1) != 0) return response
            val data = resp.optJSONObject("data") ?: return response
            PegasusPatch.pegasusHook(data)
            resp.toString()
        } catch (e: Exception) {
            Logger.error(e) { "Feed filter failed, preserving the original response" }
            response
        } catch (e: LinkageError) {
            Logger.error(e) { "Feed filter model unavailable, preserving the original response" }
            response
        }
    }
}
