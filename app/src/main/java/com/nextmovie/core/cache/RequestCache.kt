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
    private val flights = HashMap<K, Deferred<V>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(key: K, load: suspend () -> V): V {
        val task = mutex.withLock {
            values[key]?.takeIf { now() - it.time < ttlMs }?.let { return it.value }
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val flight = currentCoroutineContext()[Job]
                try {
                    val result = load()
                    mutex.withLock {
                        if (flights[key] === flight && cacheIf(result)) {
                            values.remove(key)
                            values[key] = Entry(result, now())
                            while (values.size > maxEntries) values.remove(values.keys.first())
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
    suspend fun invalidate(key: K) { mutex.withLock { values.remove(key); flights.remove(key) } }
    suspend fun clear() { mutex.withLock { values.clear(); flights.clear() } }
    override fun close() { scope.cancel() }
}
