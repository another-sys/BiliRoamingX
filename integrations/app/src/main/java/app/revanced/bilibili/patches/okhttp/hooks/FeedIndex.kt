package app.revanced.bilibili.patches.okhttp.hooks

import android.net.Uri
import android.os.SystemClock
import android.util.Pair
import app.revanced.bilibili.account.Accounts
import app.revanced.bilibili.http.HttpClient
import app.revanced.bilibili.patches.json.PegasusPatch
import app.revanced.bilibili.patches.okhttp.ApiHook
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.Utils
import app.revanced.bilibili.utils.Versions
import app.revanced.bilibili.utils.bv2av
import app.revanced.bilibili.utils.bvPattern
import app.revanced.bilibili.utils.isWifiConnected
import app.revanced.bilibili.utils.signQuery
import app.revanced.bilibili.utils.toJSONObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object FeedIndex : ApiHook() {
    internal const val MIN_VIDEOS = 10
    private const val LOAD_MORE_ROWS = 8
    private const val MAX_EMPTY_PAGES = 3
    private const val RECENT_LIMIT = 200
    private const val REFILL_BUDGET_MS = 6000L
    private const val PAGE_TIMEOUT_MS = 2500
    private val bvid = Regex(bvPattern)
    private val feedHosts = setOf("app.bilibili.com", "app.biliapi.net", "app.biliapi.com")
    private var recent = LinkedHashSet<Long>()
    private var recentAccount = -1L
    @Volatile private var expectShortRefresh = false
    @Volatile private var scrollPageSpan = 0L
    private class PendingPage(val url: String, val cursor: Long, val expires: Long, val result: Future<JSONObject?>)
    private var pending: PendingPage? = null
    private val prefetcher by lazy {
        ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, SynchronousQueue()) { task: Runnable ->
            Thread(task, "BiliRoamingX-feed")
        }
    }

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

    override fun shouldHookBefore(url: String, headers: Array<String>) =
        (expectShortRefresh || scrollPageSpan > 0) && url.contains("/x/v2/feed/index?") && Versions.ge9_17_0()

    override fun hookBefore(url: String, headers: Array<String>): Pair<String, Array<String>> {
        try {
            val uri = Uri.parse(url)
            if (uri.isFeed()) {
                val idx = uri.getQueryParameter("idx")?.toLongOrNull() ?: 0L
                if (idx > 0 && isWifiConnected()) prefetch(url, uri, idx, Accounts.mid)
            }
        } catch (e: Exception) {
            Logger.warn { "Feed prefetch unavailable (${e.javaClass.simpleName})" }
        }
        return Pair.create(url, headers)
    }

    internal fun Uri.isFeed() = scheme == "https" && host in feedHosts && path == "/x/v2/feed/index"

    internal fun Uri.isLoadMore(): Boolean {
        val pull = getQueryParameter("pull")
        return getQueryParameter("flush") == "8" ||
                ((pull == "false" || pull == "0") && getQueryParameter("idx")?.toLongOrNull() != 0L)
    }

    internal fun Uri.queryMap() = queryParameterNames.associateWith { getQueryParameter(it).orEmpty() }

    @Synchronized
    private fun prefetch(url: String, uri: Uri, idx: Long, account: Long) {
        if (account != recentAccount) return
        val cursor = if (uri.isLoadMore()) {
            if (FeedWindow.enabled) return // The rolling window owns scrolling speculation.
            if (scrollPageSpan !in 1 until idx) return
            idx - scrollPageSpan
        } else {
            val pull = uri.getQueryParameter("pull")
            if (!expectShortRefresh || idx == Long.MAX_VALUE || (pull != "true" && pull != "1")) return
            idx + 1
        }
        val now = SystemClock.elapsedRealtime()
        if (pending?.let { it.expires > now } == true) return
        try {
            val result = prefetcher.submit<JSONObject?> { nextPage(uri, uri.queryMap(), cursor, PAGE_TIMEOUT_MS) }
            pending = PendingPage(url, cursor, now + REFILL_BUDGET_MS, result)
        } catch (_: RejectedExecutionException) {
            // Another refresh is already fetching. Never queue speculative requests.
        }
    }

    @Synchronized
    private fun takePrefetch(history: LinkedHashSet<Long>, uri: Uri, cursor: Long?, short: Boolean, loadMore: Boolean): PendingPage? {
        if (history !== recent) return null
        val idx = uri.getQueryParameter("idx")?.toLongOrNull() ?: 0
        // For pull=true with a positive idx, 9.17 normally returns idx+1 as the last raw cursor.
        if (loadMore) rememberScrollSpan(history, idx, cursor, short)
        else expectShortRefresh = short && cursor != null &&
                    (idx == 0L || idx in 1 until Long.MAX_VALUE && cursor == idx + 1)
        val page = pending ?: return null
        if (page.url != uri.toString()) return null
        pending = null
        return page.takeIf { short && it.cursor == cursor && SystemClock.elapsedRealtime() < it.expires }
    }

    @Synchronized
    private fun rememberScrollSpan(history: LinkedHashSet<Long>, idx: Long, cursor: Long?, short: Boolean) {
        if (history === recent)
            scrollPageSpan = if (short && cursor != null && cursor in 1 until idx) idx - cursor else 0L
    }

    override fun hook(url: String, status: Int, request: String, response: String): String {
        return try {
            val ticket = FeedWindow.finishRequest()
            val resp = response.toJSONObject()
            if (resp.optInt("code", -1) != 0) return response
            val data = resp.optJSONObject("data") ?: return response
            val cursor = data.optJSONArray("items")?.lastCursor()
            PegasusPatch.pegasusHook(data)
            if (Versions.ge9_17_0()) {
                try {
                    fillPage(Uri.parse(url), data, cursor, ticket)
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

    internal fun JSONArray.lastCursor(): Long? {
        for (i in length() - 1 downTo 0) {
            val idx = optJSONObject(i)?.optLong("idx") ?: continue
            if (idx > 0) return idx
        }
        return null
    }

    internal fun videoId(card: JSONObject): Long? {
        if (card.optString("goto") != "av" && card.optString("card_goto") != "av") return null
        val playerAid = card.optJSONObject("player_args")?.optLong("aid") ?: 0L
        if (playerAid > 0) return playerAid
        val param = card.optString("param")
        return param.removePrefix("av").toLongOrNull()?.takeIf { it > 0 }
            ?: card.optLong("aid").takeIf { it > 0 }
            ?: sequenceOf(card.optString("bvid"), param).firstOrNull(bvid::matches)?.let(::bv2av)
    }

    @Synchronized
    internal fun historyFor(account: Long): LinkedHashSet<Long> {
        if (account != recentAccount) {
            // An in-flight request for the old account keeps its own history.
            recent = LinkedHashSet()
            recentAccount = account
            expectShortRefresh = false
            scrollPageSpan = 0
            pending = null
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

    internal fun preloadAllowed(data: JSONObject): Boolean {
        val config = data.optJSONObject("config")
        // Pegasus counts the remaining grid rows and issues its own paginated request.
        // Negative distances and no_preload both disable loading ahead in 9.17.
        val rowKey = "trigger_loadmore_left_line_num"
        if (config != null && config.optInt("no_preload") == 0 &&
            config.optInt(rowKey) in 0 until LOAD_MORE_ROWS) {
            config.put(rowKey, LOAD_MORE_ROWS)
        }
        return (config?.optInt("no_preload") ?: 0) == 0 && (config?.optInt(rowKey) ?: 0) >= 0
    }

    private fun fillPage(uri: Uri, data: JSONObject, firstCursor: Long?, ticket: FeedWindow.Ticket?) {
        if (!uri.isFeed()) return
        val items = data.optJSONArray("items") ?: return
        val preload = preloadAllowed(data)
        if (ticket != null && ticket.account != Accounts.mid) return
        val history = ticket?.history ?: historyFor(Accounts.mid)
        var tail = firstCursor
        var span = 0L
        val loadMore = uri.isLoadMore()
        val idx = uri.getQueryParameter("idx")?.toLongOrNull() ?: 0L
        if (loadMore && firstCursor != null && firstCursor in 1 until idx) span = idx - firstCursor
        // Keep the full scrolling session, then carry the most recent IDs into the next refresh.
        if (!loadMore) synchronized(history) { history.trimHistory() }
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
        }
        collect(items, original = true)
        data.put("items", merged)
        val initial = ids.size
        val prefetched = takePrefetch(history, uri, firstCursor, initial < MIN_VIDEOS, loadMore)
        var pages = 0
        var usedPrefetch = false
        if (initial < MIN_VIDEOS && firstCursor != null) {
            val query by lazy(LazyThreadSafetyMode.NONE) { uri.queryMap() }
            var emptyPages = 0
            var cursor = firstCursor
            val cursors = HashSet<Long>()
            if (loadMore) uri.getQueryParameter("idx")?.toLongOrNull()?.let(cursors::add)
            var deadline = SystemClock.elapsedRealtime() + REFILL_BUDGET_MS
            while (ids.size < MIN_VIDEOS && emptyPages < MAX_EMPTY_PAGES && cursor != null && cursors.add(cursor)) {
                val timeLeft = deadline - SystemClock.elapsedRealtime()
                if (timeLeft < 300) break
                pages++
                val before = ids.size
                val pageStart = cursor
                try {
                    val early = if (pages == 1 && prefetched != null) try {
                        prefetched.result.get(timeLeft.coerceAtMost(PAGE_TIMEOUT_MS.toLong()), TimeUnit.MILLISECONDS)
                    } catch (_: ExecutionException) {
                        null
                    } catch (_: TimeoutException) {
                        null
                    } else null
                    if (early != null) usedPrefetch = true
                    val next = early ?: run {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining < 300) return@run null
                        nextPage(uri, query, cursor, remaining.coerceAtMost(PAGE_TIMEOUT_MS.toLong()).toInt())
                    } ?: break
                    val raw = next.optJSONArray("items") ?: break
                    cursor = raw.lastCursor()
                    tail = cursor
                    if (cursor != null && cursor in 1 until pageStart) span = pageStart - cursor
                    PegasusPatch.pegasusHook(next)
                    collect(raw, original = false)
                    // A refresh refill is itself a load-more response and can prime the first scroll.
                    if (!loadMore) rememberScrollSpan(history, pageStart, cursor, ids.size - before < MIN_VIDEOS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Logger.warn { "Feed page skipped (${e.javaClass.simpleName})" }
                    break
                }
                emptyPages = if (ids.size == before) emptyPages + 1 else 0
                // A sparse scrolling feed may need several successful requests to reach ten videos.
                if (loadMore && ids.size > before && ids.size < MIN_VIDEOS)
                    deadline = SystemClock.elapsedRealtime() + REFILL_BUDGET_MS
            }
        }
        if (ticket != null && preload && tail != null && merged.length() > 0) {
            val head = if (loadMore) idx else merged.optJSONObject(0)?.optLong("idx") ?: 0L
            FeedWindow.prime(ticket, uri, head, merged.lastCursor(), tail, span)
        }
        Logger.info {
            val action = if (loadMore) "load more" else "refresh"
            "Home $action: initial=$initial extraPages=$pages uniqueVideos=${ids.size} prefetched=$usedPrefetch"
        }
    }

    private fun nextPage(uri: Uri, query: Map<String, String>, cursor: Long, timeout: Int) =
        nextEnvelope(uri, query, cursor, timeout)?.optJSONObject("data")

    internal fun nextEnvelope(uri: Uri, query: Map<String, String>, cursor: Long, timeout: Int): JSONObject? {
        // 9.17 uses pull=false, flush=8 and the last raw card's idx to load more.
        val paging = mapOf("idx" to cursor.toString(), "pull" to "false", "flush" to "8")
        val url = uri.buildUpon().encodedQuery(signQuery(query, paging)).build().toString()
        // Platform HTTP bypasses the hooked app OkHttp pipeline.
        val resp = HttpClient.get(url, timeout = timeout)?.json() ?: return null
        return resp.takeIf { it.optInt("code", -1) == 0 }
    }
}
