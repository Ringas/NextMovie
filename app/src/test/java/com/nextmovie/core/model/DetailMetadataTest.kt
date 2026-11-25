package com.nextmovie.core.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DetailMetadataTest {
    @Test fun snapshotRoundTripKeepsSourceMetadataAndDropsPlaybackFields() {
        val item = VodParser.fromJson(JSONObject("""{
            "vod_id":"id","vod_name":"Test","vod_content":"简介","vod_score":8.5,
            "doubanScore":"9.0","vod_resolution":"2160p","vod_audio":"Atmos"
        }"""))!!
        val json = VodParser.toJsonList(listOf(item))
        val restored = VodParser.list(json).single()
        assertEquals("简介", restored.displayOverview)
        assertEquals(listOf("豆瓣 9.0", "片源评分 8.5"), restored.ratingBadges())
        assertEquals(listOf("4K", "Dolby Atmos"), restored.mediaBadges())
        assertFalse(json.toString().contains("vod_play"))
    }

    @Test fun episodeResolutionWinsAndUnknownAudioIsOmitted() {
        val vod = Vod("id", "Test", resolution = "4K", remark = "高清 杜比")
        assertEquals(listOf("1080p", "DTS-HD", "5.1"), vod.mediaBadges("EP01.1080p.DTS-HD.5.1"))
        assertEquals(listOf("4K"), vod.mediaBadges())
    }
}
