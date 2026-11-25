package com.nextmovie.core.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded TTL cache with shared in-flight work; cancelling one screen does not cancel other readers. */
class RequestCache<K, V>(
    private val ttlMs: Long,
    private val maxEntries: Int = 100,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheIf: (V) -> Boolean = { true }
) : java.io.Closeable {
    private data class Entry<V>(val value: V, val time: Long)
    private val values = LinkedHashMap<K, Entry<V>>()
    @Volatile private var published: Map<K, Entry<V>> = emptyMap()
    private val flights = HashMap<K, Deferred<V>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(key: K, load: suspend () -> V): V {
        val task = mutex.withLock {
            values[key]?.takeIf { now() - it.time in 0 until ttlMs }?.let { return it.value }
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val flight = currentCoroutineContext()[Job]
                try {
                    val result = load()
                    mutex.withLock {
                        if (flights[key] === flight && cacheIf(result)) {
                            values.remove(key)
                            values[key] = Entry(result, now())
                            while (values.size > maxEntries) values.remove(values.keys.first())
                            published = values.toMap()
                        }
                    }
                    result
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { if (flights[key] === flight) flights.remove(key) }
                    }
                }
            }.also { flights[key] = it }
        }
        return task.await()
    }

    /** 不触发加载：有有效缓存就返回，否则 null。用于首页秒开。 */
    suspend fun peek(key: K): V? = mutex.withLock {
        values[key]?.takeIf { now() - it.time in 0 until ttlMs }?.value
    }

    /** Immutable read snapshot for a screen's first composition; never starts work. */
    fun peekNow(key: K): V? = published[key]?.takeIf { now() - it.time in 0 until ttlMs }?.value

    suspend fun invalidate(key: K) { mutex.withLock { values.remove(key); flights.remove(key); published = values.toMap() } }
    suspend fun clear() { mutex.withLock { values.clear(); flights.clear(); published = emptyMap() } }
    override fun close() { scope.cancel() }
}
