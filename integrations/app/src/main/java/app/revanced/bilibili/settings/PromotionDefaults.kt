package app.revanced.bilibili.settings

import android.content.SharedPreferences

/** Defaults for this distribution. Migrate rc.3 once; subsequent user choices win. */
internal object PromotionDefaults {
    val enabled = setOf(
        "purify_splash", "purify_game", "hide_commercial_entries", "block_top_activity",
        "remove_vip_section", "dynamic_rm_cm", "customize_dynamic_rm_ad_link",
        "block_up_rcmd_ads", "block_bangumi_page_ads", "remove_video_relate_promote",
        "block_comment_goods", "block_activity_tab"
    )
    val filters = mapOf(
        "customize_home_recommend" to setOf("advertisement", "banner", "game"),
        "filter_story" to setOf("ad"),
        "filter_search_type" to setOf("ad", "hot_banner", "game"),
        "customize_space" to setOf("tab.shop", "adV2", "fansDress"),
        "purify_live_popups" to setOf("shoppingCard", "shoppingSelected", "gotoBuy", "banner", "gift", "giftStar", "wish")
    )

    fun install(prefs: SharedPreferences) {
        val marker = "_promotion_defaults_v1"
        if (prefs.getBoolean(marker, false)) return
        val edit = prefs.edit()
        enabled.forEach { edit.putBoolean(it, true) }
        filters.forEach { (key, selected) ->
            edit.putStringSet(key, prefs.getStringSet(key, null).orEmpty() + selected)
        }
        edit.putBoolean(marker, true).commit()
    }
}
