package com.nextmovie.core.cms

import com.nextmovie.core.config.Site
import com.nextmovie.core.net.Http
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpT4Test {
    @Test fun opaqueEpisodeUsesT4PlayEndpointAndPreservesAuxiliaryMedia() = runBlocking {
        val http = Http(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("opaque-episode", request.url.queryParameter("play"))
            assertEquals("line", request.url.queryParameter("flag"))
            assertEquals("site extension", request.url.queryParameter("extend"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(
                    """{"url":"https://media.test/movie.m3u8","subtitles":[{"url":"movie.vtt","name":"中文"}],"danmaku":"https://media.test/d.xml"}"""
                        .toResponseBody()
                ).build()
        }.build())
        val client = CmsSiteClient(http, Site("t4", "T4", "https://source.test/api", type = 4, ext = "site extension"))
        val result = checkNotNull(client.play("line", "opaque-episode"))
        assertEquals("https://media.test/movie.m3u8", result.url)
        assertEquals("movie.vtt", result.subtitles.single().url)
        assertEquals("中文", result.subtitles.single().label)
        assertEquals("https://media.test/d.xml", result.danmaku)
    }
}
