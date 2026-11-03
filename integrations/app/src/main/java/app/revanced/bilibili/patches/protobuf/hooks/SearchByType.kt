package app.revanced.bilibili.patches.protobuf.hooks

import app.revanced.bilibili.patches.okhttp.BangumiSeasonHook
import app.revanced.bilibili.patches.protobuf.MossHook
import app.revanced.bilibili.settings.Settings
import com.bapis.bilibili.polymer.app.search.v1.Item
import com.bapis.bilibili.polymer.app.search.v1.SearchByTypeRequest
import com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse
import com.bilibili.lib.moss.api.MossException
import com.google.protobuf.GeneratedMessageLite

object SearchByType : MossHook<SearchByTypeRequest, SearchByTypeResponse>() {
    override val async: Boolean = true

    override fun shouldHook(req: GeneratedMessageLite<*, *>): Boolean {
        return req is SearchByTypeRequest && BangumiSeasonHook.extraSearchHandleable(req.type)
    }

    override fun hookBefore(req: SearchByTypeRequest): Any {
        return BangumiSeasonHook.handleExtraSearch(req)
    }
}

internal fun Item.isSearchPromotion(): Boolean {
    if (hasCm()) return true
    val type = linktype
    return type.split('_').any { it == "ad" || it == "cm" || it == "product" } || type.startsWith("special_s")
}

// The preceding SearchByType hook handles region-specific tabs with a replacement response.
object SearchByTypeAds : MossHook<SearchByTypeRequest, SearchByTypeResponse>() {
    override fun shouldHook(req: GeneratedMessageLite<*, *>): Boolean =
        req is SearchByTypeRequest && "ad" in Settings.FilterSearchType()

    override fun hookAfter(req: SearchByTypeRequest, reply: SearchByTypeResponse?, error: MossException?): SearchByTypeResponse? {
        if (reply != null) {
            for (i in reply.itemsCount - 1 downTo 0) {
                if (reply.getItems(i).isSearchPromotion()) reply.removeItems(i)
            }
        }
        return super.hookAfter(req, reply, error)
    }
}
