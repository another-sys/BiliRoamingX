package app.revanced.bilibili.patches

import androidx.annotation.Keep
import app.revanced.bilibili.settings.Settings
import com.bilibili.lib.homepage.mine.MenuGroup
import com.bilibili.video.story.StoryDetail

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
        return Settings.HideCommercialEntries() && (name in commercialNames
                    || route.startsWith("bilibili://mall/")
                    || route.startsWith("bilibili://workshop/")
                    || route.startsWith("https://mall.bilibili.com/")
                    || route.startsWith("https://shop.bilibili.com/"))
    }

    private val commercialNames = setOf("会员购", "会员购中心", "会员购订单", "商城", "必火推广", "工房", "我的工房",
        "装扮中心", "个性装扮", "有奖活动", "开播福利")
    private val regionalTabs = setOf("6544", "168644", "163541", "95636")

    private val promotedMentionTypes = setOf(
        "MENTION_TYPE_GAME", "MENTION_TYPE_TOY", "MENTION_TYPE_LOTTERY"
    )
    @Keep @JvmStatic fun hiddenMention(type: String?) = Settings.BlockUpRcmdAds() && type in promotedMentionTypes

    @Keep @JvmStatic fun filterStoryEntry(entry: StoryDetail.CartIconInfo?): StoryDetail.CartIconInfo? {
        return when (entry?.entryGoto) {
            "cart", "game" -> if ("ad" in Settings.FilterStory()) null else entry
            else -> entry
        }
    }

    private inline fun <T> List<T>.withoutHidden(hidden: (T) -> Boolean): List<T> {
        var visible: ArrayList<T>? = null
        for ((index, entry) in withIndex()) {
            if (hidden(entry)) {
                if (visible == null) {
                    visible = ArrayList(size - 1)
                    visible.addAll(subList(0, index))
                }
            } else visible?.add(entry)
        }
        return visible ?: this
    }

    @Keep @JvmStatic fun filterMine(groups: List<MenuGroup>?): List<MenuGroup>? {
        return groups?.withoutHidden { group ->
            if (group.moduleType == 2 && Settings.PurifyGame() || hidden(group.title, null)) true
            else {
                val items = group.itemList
                val visible = items?.withoutHidden { hidden(it.title, it.uri) }
                if (visible != null && visible !== items) {
                    group.itemList = visible
                    visible.isEmpty()
                } else false
            }
        }
    }

    @Keep @JvmStatic fun filterEntries(entries: List<*>?): List<*>? {
        return entries?.withoutHidden { it is PromotionEntry && it.brxHideEntry() }
    }
}
