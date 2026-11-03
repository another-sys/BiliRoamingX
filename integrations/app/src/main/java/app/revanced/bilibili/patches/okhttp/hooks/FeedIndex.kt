package app.revanced.bilibili.patches.okhttp.hooks

import android.net.Uri
import android.os.SystemClock
import app.revanced.bilibili.account.Accounts
import app.revanced.bilibili.http.HttpClient
import app.revanced.bilibili.patches.json.PegasusPatch
import app.revanced.bilibili.patches.okhttp.ApiHook
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.Utils
import app.revanced.bilibili.utils.Versions
import app.revanced.bilibili.utils.bv2av
import app.revanced.bilibili.utils.bvPattern
import app.revanced.bilibili.utils.signQuery
import app.revanced.bilibili.utils.toJSONObject
import org.json.JSONArray
import org.json.JSONObject

object FeedIndex : ApiHook() {
    private const val MIN_VIDEOS = 10
    private const val MAX_EMPTY_PAGES = 3
    private const val RECENT_LIMIT = 200
    private val bvid = Regex(bvPattern)
    private val feedHosts = setOf("app.bilibili.com", "app.biliapi.net", "app.biliapi.com")
    private var recent = LinkedHashSet<Long>()
    private var recentAccount = -1L

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
            val cursor = data.optJSONArray("items")?.lastCursor()
            PegasusPatch.pegasusHook(data)
            if (Versions.ge9_17_0()) {
                try {
                    fillRefresh(Uri.parse(url), data, cursor)
                } catch (e: Exception) {
                    Logger.warn { "Feed refill stopped (${e.javaClass.simpleName})" }
                } catch (e: LinkageError) {
                    Logger.warn { "Feed refill unavailable (${e.javaClass.simpleName})" }
                }
            }
            resp.toString()
        } catch (e: Exception) {
            Logger.error(e) { "Feed filter failed, preserving the original response" }
            response
        } catch (e: LinkageError) {
            Logger.error(e) { "Feed filter model unavailable, preserving the original response" }
            response
        }
    }

    private fun JSONArray.lastCursor(): Long? {
        for (i in length() - 1 downTo 0) {
            val idx = optJSONObject(i)?.optLong("idx") ?: continue
            if (idx > 0) return idx
        }
        return null
    }

    private fun videoId(card: JSONObject): Long? {
        if (card.optString("goto") != "av" && card.optString("card_goto") != "av") return null
        val playerAid = card.optJSONObject("player_args")?.optLong("aid") ?: 0L
        if (playerAid > 0) return playerAid
        val param = card.optString("param")
        return param.removePrefix("av").toLongOrNull()?.takeIf { it > 0 }
            ?: card.optLong("aid").takeIf { it > 0 }
            ?: sequenceOf(card.optString("bvid"), param).firstOrNull(bvid::matches)?.let(::bv2av)
    }

    @Synchronized
    private fun historyFor(account: Long): LinkedHashSet<Long> {
        if (account != recentAccount) {
            // An in-flight request for the old account keeps its own history.
            recent = LinkedHashSet()
            recentAccount = account
        }
        return recent
    }

    private fun LinkedHashSet<Long>.trimHistory() {
        if (size <= RECENT_LIMIT) return
        val oldest = iterator()
        while (size > RECENT_LIMIT) {
            oldest.next()
            oldest.remove()
        }
    }

    private fun fillRefresh(uri: Uri, data: JSONObject, firstCursor: Long?) {
        if (uri.scheme != "https" || uri.host !in feedHosts
            || uri.path != "/x/v2/feed/index") return
        val items = data.optJSONArray("items") ?: return
        val history = historyFor(Accounts.mid)
        val pull = uri.getQueryParameter("pull")
        val loadMore = uri.getQueryParameter("flush") == "8" ||
                ((pull == "false" || pull == "0") && uri.getQueryParameter("idx")?.toLongOrNull() != 0L)
        if (loadMore) {
            synchronized(history) {
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let(::videoId)?.let(history::add)
                }
                history.trimHistory()
            }
            return
        }
        val ids = HashSet<Long>()
        val merged = JSONArray()
        // Claim each page's IDs atomically. Network I/O never holds the history lock.
        fun collect(page: JSONArray, original: Boolean) = synchronized(history) {
            for (i in 0 until page.length()) {
                val card = page.optJSONObject(i) ?: continue
                val id = videoId(card)
                if (id == null) {
                    if (original) merged.put(card)
                } else if (id !in ids && history.add(id)) {
                    ids.add(id)
                    merged.put(card)
                }
            }
            history.trimHistory()
        }
        collect(items, original = true)
        data.put("items", merged)
        val initial = ids.size
        var pages = 0
        if (initial < MIN_VIDEOS && firstCursor != null) {
            val query = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            var emptyPages = 0
            var cursor = firstCursor
            val cursors = HashSet<Long>()
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (ids.size < MIN_VIDEOS && emptyPages < MAX_EMPTY_PAGES && cursor != null && cursors.add(cursor)) {
                val timeLeft = deadline - SystemClock.elapsedRealtime()
                if (timeLeft < 300) break
                pages++
                val before = ids.size
                try {
                    val next = nextPage(uri, query, cursor, timeLeft.coerceAtMost(2500).toInt()) ?: break
                    val raw = next.optJSONArray("items") ?: break
                    cursor = raw.lastCursor()
                    PegasusPatch.pegasusHook(next)
                    collect(raw, original = false)
                } catch (e: Exception) {
                    Logger.warn { "Feed page skipped (${e.javaClass.simpleName})" }
                    break
                }
                emptyPages = if (ids.size == before) emptyPages + 1 else 0
            }
        }
        Logger.info { "Home refresh: initial=$initial extraPages=$pages uniqueVideos=${ids.size}" }
    }

    private fun nextPage(uri: Uri, query: Map<String, String>, cursor: Long, timeout: Int): JSONObject? {
        // 9.17 uses pull=false, flush=8 and the last raw card's idx to load more.
        val paging = mapOf("idx" to cursor.toString(), "pull" to "false", "flush" to "8")
        val url = uri.buildUpon().encodedQuery(signQuery(query, paging)).build().toString()
        // Platform HTTP bypasses the hooked app OkHttp pipeline.
        val resp = HttpClient.get(url, timeout = timeout)?.json() ?: return null
        return if (resp.optInt("code", -1) == 0) resp.optJSONObject("data") else null
    }
}
