package com.nextmovie.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavBackupTest {
    @Test fun concurrentWriteRetriesAgainstLatestRevision() = runBlocking {
        var reads = 0
        var writes = 0
        val merged = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
            if (request.method == "GET") {
                reads++
                response.header("ETag", "\"revision-$reads\"").code(200).body("remote-$reads".toResponseBody()).build()
            } else {
                writes++
                assertEquals("\"revision-$reads\"", request.header("If-Match"))
                val body = Buffer()
                checkNotNull(request.body).writeTo(body)
                assertEquals(merged.joinToString(), body.readUtf8())
                response.code(if (writes == 1) 412 else 204).body("".toResponseBody()).build()
            }
        }.build()
        WebDavBackup(client).sync("https://webdav.test/backup.json", "", "", { merged += it }, { merged.joinToString() })
        assertEquals(listOf("remote-1", "remote-2"), merged)
        assertEquals(2, writes)
    }
}
