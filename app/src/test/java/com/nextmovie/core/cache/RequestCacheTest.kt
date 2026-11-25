package com.nextmovie.core.cache

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RequestCacheTest {
    @Test fun synchronousSnapshotTracksExpiryEvictionAndInvalidation() = runBlocking {
        var now = 100L
        RequestCache<String, Int>(100, maxEntries = 2, now = { now }).use { cache ->
            assertNull(cache.peekNow("a"))
            cache.get("a") { 1 }; cache.get("b") { 2 }; cache.get("c") { 3 }
            assertNull(cache.peekNow("a"))
            assertEquals(2, cache.peekNow("b"))
            cache.invalidate("b")
            assertNull(cache.peekNow("b"))
            now = 200L
            assertNull(cache.peekNow("c"))
            cache.get("c") { 4 }
            assertEquals(4, cache.peekNow("c"))
            cache.clear()
            assertNull(cache.peekNow("c"))
        }
    }
    @Test fun concurrentHomeAndCategoryShareOneRequest() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        RequestCache<String, Int>(10000).use { cache ->
            val a = async { cache.get("same-source") { calls.incrementAndGet(); entered.complete(Unit); finish.await(); 7 } }
            entered.await()
            val b = async { cache.get("same-source") { error("duplicate request") } }
            yield(); finish.complete(Unit)
            assertEquals(7, a.await()); assertEquals(7, b.await()); assertEquals(1, calls.get())
            assertEquals(7, cache.get("same-source") { error("warm navigation fetched again") })
        }
    }
    @Test fun cancelledScreenDoesNotCancelSharedWork() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        RequestCache<String, Int>(10000).use { cache ->
            val a = launch { cache.get("same") { entered.complete(Unit); finish.await(); 42 } }
            entered.await(); a.cancelAndJoin()
            val b = async { cache.get("same") { error("must reuse") } }
            finish.complete(Unit); assertEquals(42, b.await())
        }
    }
    @Test fun expiredEntriesAndFailuresCanRefresh() = runBlocking {
        var now = 0L
        RequestCache<String, Int>(100, now = { now }).use { cache ->
            assertEquals(1, cache.get("k") { 1 })
            now = 101
            assertEquals(2, cache.get("k") { 2 })
            runCatching { cache.get("failure") { error("offline") } }
            assertEquals(3, cache.get("failure") { 3 })
            cache.clear(); assertEquals(4, cache.get("k") { 4 })
        }
    }
    @Test fun invalidatedFlightCannotReplaceRefreshedValue() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        RequestCache<String, Int>(10_000).use { cache ->
            val old = async { cache.get("site") { started.complete(Unit); finish.await(); 1 } }
            started.await()
            cache.invalidate("site")
            assertEquals(2, cache.get("site") { 2 })
            finish.complete(Unit)
            assertEquals(1, old.await())
            assertEquals(2, cache.get("site") { error("fresh value was overwritten") })
            assertEquals(2, cache.peekNow("site"))
        }
    }
}
