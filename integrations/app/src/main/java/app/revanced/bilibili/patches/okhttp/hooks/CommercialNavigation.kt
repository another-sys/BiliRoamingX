package app.revanced.bilibili.patches.okhttp.hooks

import android.net.Uri
import app.revanced.bilibili.patches.PromotionFilter
import app.revanced.bilibili.patches.okhttp.ApiHook
import app.revanced.bilibili.settings.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Filter before the client caches navigation data, including the newer Mine VIP banner. */
object CommercialNavigation : ApiHook() {
    private val paths = setOf("/x/resource/show/tab/v2", "/x/v2/account/mine", "/x/v2/account/mine/ipad")
    override fun shouldHook(url: String, status: Int) = status.isOk &&
            (url.contains("/x/resource/show/tab/v2") || url.contains("/x/v2/account/mine")) &&
            Uri.parse(url).path in paths

    private fun JSONArray.removeWhere(hidden: (JSONObject) -> Boolean) {
        for (i in length() - 1 downTo 0) if (optJSONObject(i)?.let(hidden) == true) remove(i)
    }

    private fun hidden(item: JSONObject) = PromotionFilter.hidden(
        item.optString("title", item.optString("name")), item.optString("uri", item.optString("url"))
    )

    override fun hook(url: String, status: Int, request: String, response: String): String {
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return response
        val data = root.optJSONObject("data") ?: return response
        if (root.optInt("code", -1) != 0) return response
        if (url.contains("/x/resource/show/tab/v2")) {
            for (key in arrayOf("top", "tab", "bottom", "top_more")) data.optJSONArray(key)?.removeWhere(::hidden)
        } else {
            if (Settings.RemoveVipSection()) {
                for (key in arrayOf("modular_vip_section", "vip_section_v2", "vip_section_right")) data.remove(key)
            }
            if (Settings.HideCommercialEntries()) data.remove("mall_home")
            if (Settings.PurifyGame()) data.remove("game_tip")
            for (key in arrayOf("sections", "sections_v2")) data.optJSONArray(key)?.removeWhere { section ->
                val items = section.optJSONArray("items")
                items?.removeWhere(::hidden)
                hidden(section) || (Settings.PurifyGame() && section.optInt("type") == 2)
            }
            for (key in arrayOf("ipad_sections", "ipad_more_sections", "ipad_recommend_sections"))
                data.optJSONArray(key)?.removeWhere(::hidden)
        }
        return root.toString()
    }
}
