package app.revanced.bilibili.patches

import androidx.annotation.Keep
import app.revanced.bilibili.settings.Settings
import com.bilibili.lib.homepage.mine.MenuGroup

/** Implemented on the client's navigation items and video mention groups at patch time. */
@Keep
interface PromotionEntry {
    fun brxHideEntry(): Boolean
}

object PromotionFilter {
    @Keep @JvmStatic fun blockSplash() = Settings.PurifySplash()
    @Keep @JvmStatic fun hideVipBanner() = Settings.RemoveVipSection()

    @Keep @JvmStatic fun hidden(name: String?, uri: String?): Boolean {
        val route = uri.orEmpty().substringBefore('?').substringBefore('#')
        if (Settings.PurifyGame() && (name == "游戏中心" || name == "游戏礼包"
                    || route.startsWith("bilibili://game_center/")
                    || route.startsWith("https://game.bilibili.com/"))) return true
        if (Settings.BlockTopActivity() && (route.startsWith("https://www.bilibili.com/blackboard/tab/")
                    || (route.startsWith("bilibili://following/home_activity_tab/")
                        && route.substringAfterLast('/') !in regionalTabs))) return true
        return Settings.HideCommercialEntries() && (name in shopNames
                    || route.startsWith("bilibili://mall/")
                    || route.startsWith("bilibili://workshop/")
                    || route.startsWith("https://mall.bilibili.com/")
                    || route.startsWith("https://shop.bilibili.com/"))
    }

    private val shopNames = setOf("会员购", "会员购中心", "会员购订单", "商城", "必火推广", "工房", "我的工房", "装扮中心", "个性装扮")
    private val regionalTabs = setOf("6544", "168644", "163541", "95636")

    private val promotedMentionTypes = setOf(
        "MENTION_TYPE_GAME", "MENTION_TYPE_TOY", "MENTION_TYPE_LOTTERY"
    )
    @Keep @JvmStatic fun hiddenMention(type: String?) = Settings.BlockUpRcmdAds() && type in promotedMentionTypes

    @Keep @JvmStatic fun filterMine(groups: List<MenuGroup>?): List<MenuGroup>? {
        return groups?.filterNot { group ->
            if (group.moduleType == 2 && Settings.PurifyGame() || hidden(group.title, null)) true
            else {
                val items = group.itemList
                if (items != null && items.any { hidden(it.title, it.uri) }) {
                    val visible = items.filterNot { hidden(it.title, it.uri) }
                    group.itemList = visible
                    visible.isEmpty()
                } else false
            }
        }
    }

    @Keep @JvmStatic fun filterEntries(entries: List<*>?): List<*>? {
        if (entries == null || entries.none { it is PromotionEntry && it.brxHideEntry() }) return entries
        return entries.filterNot { it is PromotionEntry && it.brxHideEntry() }
    }
}
