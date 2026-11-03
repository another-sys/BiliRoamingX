package app.revanced.bilibili.patches.okhttp.hooks

import android.net.Uri
import android.os.SystemClock
import app.revanced.bilibili.account.Accounts
import app.revanced.bilibili.patches.json.PegasusPatch
import app.revanced.bilibili.patches.okhttp.hooks.FeedIndex.isFeed
import app.revanced.bilibili.patches.okhttp.hooks.FeedIndex.isLoadMore
import app.revanced.bilibili.patches.okhttp.hooks.FeedIndex.lastCursor
import app.revanced.bilibili.patches.okhttp.hooks.FeedIndex.queryMap
import app.revanced.bilibili.utils.Logger
import app.revanced.bilibili.utils.isWifiConnected
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.*

/** One consumable continuation, never a crawler. IDs become seen only when a page is delivered. */
internal object FeedWindow {
    private const val TTL_MS = 90_000L
    private const val JOIN_MS = 350L
    private const val MAX_PAGES = 12
    private const val TIMEOUT_MS = 2500
    private val scopeKeys = listOf("access_key", "mobi_app", "build", "buvid", "teenagers_mode",
        "s_locale", "c_locale", "window_size_type", "raw_window_size_type", "interest_id", "interest_result")
    class Ticket(val account: Long, val history: LinkedHashSet<Long>, val scope: List<String?>)
    private class Window(val owner: Ticket, val base: Uri, val head: Long, val visibleTail: Long?,
                         val cursor: Long, val span: Long) {
        val expires = SystemClock.elapsedRealtime() + TTL_MS
        val result = CompletableFuture<Page?>()
        @Volatile var stopped = false
        fun active() = !stopped && SystemClock.elapsedRealtime() < expires
    }
    private class Page(val json: String, val ids: Set<Long>, val tail: Long, val visibleTail: Long?,
                       val span: Long, val count: Int)
    private val lock = Any()
    private var current: Ticket? = null
    private var window: Window? = null
    private val nativeRequest = ThreadLocal<Ticket>()
    @Volatile var enabled = false
        private set
    private val workers = ThreadFactory { task -> Thread(task, "BRX-feed-prefetch").apply { isDaemon = true } }
    private val coordinator by lazy {
        ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(1), workers,
            ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
    private val parallel by lazy { ThreadPoolExecutor(0, 2, 15, TimeUnit.SECONDS, SynchronousQueue(), workers) }

    fun finishRequest(): Ticket? = nativeRequest.get().also { nativeRequest.remove() }

    fun invalidate() = synchronized(lock) {
        window?.stopped = true
        window = null
        current = null
    }

    fun open(url: String): String? {
        val uri = Uri.parse(url)
        if (!uri.isFeed()) return null
        enabled = true
        val started = SystemClock.elapsedRealtime()
        val idx = uri.getQueryParameter("idx")?.toLongOrNull()
        val loadMore = uri.isLoadMore() && idx != null && idx > 0
        val account = Accounts.mid
        val ticket = Ticket(account, FeedIndex.historyFor(account), listOf(uri.host) + scopeKeys.map(uri::getQueryParameter))
        nativeRequest.set(ticket)
        val old = synchronized(lock) {
            current = ticket
            window.also { window = null }
        } ?: return null
        val reason = when {
            !loadMore -> "refresh"
            !old.active() -> "expired"
            old.owner.account != account || old.owner.history !== ticket.history || old.owner.scope != ticket.scope -> "scope"
            // 9.17's ViewModel uses the current list's first idx for load-more. Other layouts use the tail.
            idx != old.head && idx != old.visibleTail && idx != old.cursor -> "cursor"
            else -> null
        }
        val ready = old.result.isDone
        val page = try {
            if (reason == null) old.result.get(JOIN_MS, TimeUnit.MILLISECONDS) else null
        } catch (_: TimeoutException) { null }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        finally { old.stopped = true }
        val claimed = page != null && synchronized(lock) {
            current === ticket && Accounts.mid == account && synchronized(ticket.history) {
                page.ids.none(ticket.history::contains) && ticket.history.addAll(page.ids)
            }
        }
        if (page == null || !claimed) {
            if (loadMore) Logger.info { "Home feed cache: miss=${reason ?: if (ready) "unavailable" else "building"}" }
            return null
        }
        finishRequest()
        prime(ticket, old.base, idx!!, page.visibleTail, page.tail, page.span)
        Logger.info { "Home feed cache: hit uniqueVideos=${page.ids.size} pages=${page.count} waitMs=${SystemClock.elapsedRealtime() - started} ready=$ready" }
        return page.json
    }

    fun prime(ticket: Ticket, base: Uri, head: Long, visibleTail: Long?, cursor: Long, span: Long): String {
        if (cursor <= 0) return "no_cursor"
        if (!isWifiConnected()) return "no_wifi"
        val job = Window(ticket, base, head, visibleTail, cursor, span)
        synchronized(lock) {
            if (current !== ticket || window != null || Accounts.mid != ticket.account) return "superseded"
            window = job
            coordinator.execute {
                val page = try { prepare(job) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
                catch (e: Exception) { Logger.warn { "Feed cache preparation stopped (${e.javaClass.simpleName})" }; null }
                catch (e: LinkageError) { Logger.warn { "Feed cache model unavailable (${e.javaClass.simpleName})" }; null }
                job.result.complete(page)
                if (job.active()) Logger.info { "Home feed window: prepared=${page?.ids?.size ?: 0} pages=${page?.count ?: 0}" }
            }
        }
        return "scheduled"
    }

    private fun prepare(job: Window): Page? {
        val query = job.base.queryMap()
        val ids = LinkedHashSet<Long>()
        val merged = JSONArray()
        var envelope: JSONObject? = null
        var data: JSONObject? = null
        var cursor = job.cursor
        var span = job.span
        var pages = 0
        var empty = 0
        fun fetch(at: Long) = if (job.active() && isWifiConnected() && Accounts.mid == job.owner.account)
            FeedIndex.nextEnvelope(job.base, query, at, TIMEOUT_MS) else null
        // Only the coordinator merges/filters. Requests can finish in any order.
        batch@ while (job.active() && pages < MAX_PAGES && empty < 3 && ids.size < FeedIndex.MIN_VIDEOS) {
            val predicted = HashMap<Long, Future<JSONObject?>>()
            var predictedCursor = cursor
            try {
                for (n in 1..minOf(2, MAX_PAGES - pages - 1)) {
                    if (span !in 1 until predictedCursor) break
                    predictedCursor -= span
                    val at = predictedCursor
                    try { predicted[at] = parallel.submit<JSONObject?> { fetch(at) } }
                    catch (_: RejectedExecutionException) { break }
                }
                do {
                    val start = cursor
                    val future = predicted.remove(start)
                    val raw = (if (future == null) fetch(start) else future.get()) ?: break@batch
                    if (!job.active()) return null
                    val d = raw.optJSONObject("data") ?: break@batch
                    val cards = d.optJSONArray("items") ?: break@batch
                    val next = cards.lastCursor() ?: break@batch
                    if (next !in 1 until start) break@batch
                    span = start - next
                    cursor = next
                    PegasusPatch.pegasusHook(d)
                    val first = envelope == null
                    if (first) {
                        envelope = raw
                        data = d
                        FeedIndex.configureScrollRows(d)
                    }
                    val before = ids.size
                    val history = job.owner.history
                    synchronized(history) {
                        for (i in 0 until cards.length()) {
                            val card = cards.optJSONObject(i) ?: continue
                            val id = FeedIndex.videoId(card)
                            if ((id == null && first) || (id != null && id !in history && ids.add(id))) merged.put(card)
                        }
                    }
                    empty = if (before == ids.size) empty + 1 else 0
                    pages++
                    // Use a prediction only if its request cursor matches the preceding RAW response.
                } while (cursor in predicted && job.active() && empty < 3)
            } finally { predicted.values.forEach { it.cancel(true) } }
        }
        if (!job.active() || ids.size < FeedIndex.MIN_VIDEOS) return null
        data!!.put("items", merged)
        return Page(envelope!!.toString(), ids, cursor, merged.lastCursor(), span, pages)
    }
}
