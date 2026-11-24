package com.nextmovie.core.pairing

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

class LanImportServerTest {
    private fun post(server: LanImportServer, value: String, token: String = server.token, origin: String? = null): Int {
        val body = ("config=" + URLEncoder.encode(value, "UTF-8")).toByteArray()
        val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.port}/import?token=$token")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        origin?.let { request.header("Origin", it) }
        return okhttp3.OkHttpClient().newCall(request.build()).execute().use { it.code }

    }
    @Test fun phoneFormDeliversTheExactConfiguration() {
        val received = AtomicReference<String>()
        LanImportServer { received.set(it) }.use { server ->
            val page = URL(server.url("127.0.0.1")).readText()
            assertTrue(page.contains("发送配置到电视"))
            val config = "https://example.test/config.json?key=a+b&x=中文"
            assertEquals(200, post(server, config))
            assertEquals(config, received.get())
        }
    }
    @Test fun badTokenAndCrossOriginNeverImport() {
        val received = AtomicReference<String>()
        LanImportServer { received.set(it) }.use { server ->
            assertEquals(403, post(server, "https://example.test/c.json", "wrong"))
            assertEquals(403, post(server, "https://example.test/c.json", origin = "https://other.test"))
            assertEquals(400, post(server, "javascript:alert(1)"))
            assertNull(received.get())
        }
    }
    @Test fun pastedJsonCanBeSentWithoutAHostedFile() {
        val received = AtomicReference<String>()
        LanImportServer { received.set(it) }.use { server ->
            val config = """{"sites":[{"api":"https://example.test/api","type":1}]}"""
            assertEquals(200, post(server, config)); assertEquals(config, received.get())
        }
    }
}
