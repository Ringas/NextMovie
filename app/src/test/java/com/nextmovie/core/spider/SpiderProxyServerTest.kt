package com.nextmovie.core.spider

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SpiderProxyServerTest {
    @Test fun streamsPluginReplyAndRangeWithoutBinder() {
        SpiderProxyServer().use { proxy ->
            proxy.activate { params ->
                assertEquals("a+b", params["url"])
                assertEquals("bytes=1-3", params["range"])
                arrayOf(206, "video/mp4", "abc".byteInputStream(), mapOf("Content-Range" to "bytes 1-3/9", "Content-Length" to "3"))
            }
            val connection = URL(proxy.rewrite("http://127.0.0.1:9978/proxy?url=a%2Bb")).openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.setRequestProperty("Range", "bytes=1-3")
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 1-3/9", connection.getHeaderField("Content-Range"))
            assertEquals("abc", connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            assertEquals("https://example.test/video.mp4", proxy.rewrite("https://example.test/video.mp4"))
        }
    }

    @Test fun pluginOwnedVideoServerKeepsItsPortAndRestPath() {
        SpiderProxyServer().use { proxy ->
            val examples = listOf(
                "http://127.0.0.1:6677/proxy/play/夸父盘/两京十五日/01.mkv",
                "http://127.0.0.1:9978/proxy/play/夸父盘/两京十五日/01.mkv",
                "http://localhost:6677/proxy/play/a.mkv?token=a%2Bb%26c",
                "http://127.0.0.1:6677/proxy?url=a%2Bb",
                "http://127.0.0.1:6677/video.m3u8"
            )
            examples.forEach { assertEquals(it, proxy.rewrite(it)) }
        }
    }

    @Test fun nestedVideoAddressIsNotRewrittenOrDoubleDecoded() {
        SpiderProxyServer().use { proxy ->
            val nested = "http%3A%2F%2F127.0.0.1%3A6677%2Fproxy%2Fplay%2F01.mkv%3Ftoken%3Da%252Bb"
            val rewritten = java.net.URI(proxy.rewrite("http://127.0.0.1:9978/proxy?do=danmaku&url=$nested"))
            assertEquals(proxy.port, rewritten.port)
            assertEquals("do=danmaku&url=$nested", rewritten.rawQuery)
        }
    }

    @Test fun mediaRequestActuallyReachesPluginOwnedServerIncludingRangeAndUnicodePath() {
        val backend = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.concurrent.CompletableFuture<String>()
        Thread {
            runCatching {
                backend.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val first = input.readLine()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    assertEquals("bytes=1-3", headers["range"])
                    seen.complete(java.net.URI(first.split(' ')[1]).path)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes 1-3/9\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray())
                        flush()
                    }
                }
            }.onFailure { seen.completeExceptionally(it) }
        }.apply { isDaemon = true; start() }
        try {
            SpiderProxyServer().use { proxy ->
                proxy.activate { error("Plugin video must not be sent to the host's parameter dispatcher") }
                val path = "/proxy/play/夸父盘/两京十五日/01.mkv"
                val url = java.net.URI("http", null, "127.0.0.1", backend.localPort, path, null, null).toASCIIString()
                val connection = URL(proxy.rewrite(url)).openConnection() as HttpURLConnection
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                connection.setRequestProperty("Range", "bytes=1-3")
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 1-3/9", connection.getHeaderField("Content-Range"))
                assertEquals("abc", connection.inputStream.bufferedReader().use { it.readText() })
                assertEquals(path, seen.get(2, java.util.concurrent.TimeUnit.SECONDS))
                connection.disconnect()
            }
        } finally { backend.close() }
    }

    @Test fun originAndConditionalRangeHeadersReachHostCallback() {
        SpiderProxyServer().use { proxy ->
            proxy.activate { params ->
                assertEquals("https://example.test", params["origin"])
                assertEquals("etag-123", params["if-range"])
                arrayOf(200, "video/mp4", "ok".byteInputStream())
            }
            val connection = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
                .url(proxy.rewrite("http://127.0.0.1:9978/proxy?do=video"))
                .header("Origin", "https://example.test").header("If-Range", "etag-123").build()).execute()
            connection.use { assertEquals("ok", it.body!!.string()) }
        }
    }
}
