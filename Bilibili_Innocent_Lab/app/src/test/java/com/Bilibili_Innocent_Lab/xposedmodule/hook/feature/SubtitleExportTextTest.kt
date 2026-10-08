package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleExportTextTest {
    @Test fun prefersSimplifiedChineseBeforeTraditionalAndOtherLanguages() {
        val tracks = listOf(
            SubtitleTrack("en", "English", "https://example/en.json"),
            SubtitleTrack("zh-TW", "繁體中文", "https://example/zh-tw.json"),
            SubtitleTrack("zh-CN", "简体中文", "//example/zh-cn.json")
        )
        assertEquals("zh-CN", SubtitleExportText.chooseTrack(tracks)?.language)
    }

    @Test fun fallsBackToTraditionalThenAnyLanguage() {
        assertEquals(
            "zh-TW",
            SubtitleExportText.chooseTrack(
                listOf(SubtitleTrack("zh-TW", "繁體中文", "tw"), SubtitleTrack("en", "English", "en"))
            )?.language
        )
        assertEquals(
            "ja",
            SubtitleExportText.chooseTrack(listOf(SubtitleTrack("ja", "日本語", "ja")))?.language
        )
        assertNull(SubtitleExportText.chooseTrack(emptyList()))
    }

    @Test fun formatsOnlyTheFirstNonEmptyLineOfEachCue() {
        val body = JSONArray()
            .put(JSONObject().put("content", "  第一行  \n第二行"))
            .put(JSONObject().put("content", "\r\n"))
            .put(JSONObject().put("content", "第三行\r第四行"))
        assertEquals("第一行\n第三行", SubtitleExportText.fromBody(body))
    }

    @Test fun parsesPlayerSubtitleMetadataAndNormalizesUrls() {
        val metadata = JSONObject().put(
            "data", JSONObject().put(
                "subtitle", JSONObject().put(
                    "subtitles", JSONArray().put(
                        JSONObject()
                            .put("lan", "zh-CN")
                            .put("lan_doc", "简体中文")
                            .put("subtitle_url", "//aisubtitle.hdslb.com/a.json")
                    )
                )
            )
        )
        val track = SubtitleExportText.parseTracks(metadata).single()
        assertEquals("zh-CN", track.language)
        assertEquals("https://aisubtitle.hdslb.com/a.json", SubtitleExportText.normalizeSubtitleUrl(track.url))
        assertEquals("", SubtitleExportText.normalizeSubtitleUrl("relative.json"))
    }
}
