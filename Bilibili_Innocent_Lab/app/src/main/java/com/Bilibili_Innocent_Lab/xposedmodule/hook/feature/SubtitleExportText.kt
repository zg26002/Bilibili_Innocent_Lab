package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject

/** A single language track returned by Bilibili's player subtitle metadata API. */
internal data class SubtitleTrack(
    val language: String,
    val languageName: String,
    val url: String
)

/** Pure subtitle selection and clipboard-text formatting helpers. */
internal object SubtitleExportText {
    private const val MAX_CLIPBOARD_CHARS = 500_000

    fun parseTracks(value: JSONObject): List<SubtitleTrack> {
        val subtitles = value.optJSONObject("data")
            ?.optJSONObject("subtitle")
            ?.optJSONArray("subtitles")
            ?: return emptyList()
        return parseTrackArray(subtitles)
    }

    fun parseTrackArray(subtitles: JSONArray): List<SubtitleTrack> = buildList {
        for (index in 0 until subtitles.length()) {
            val item = subtitles.optJSONObject(index) ?: continue
            val url = item.optString("subtitle_url").trim()
            if (url.isBlank()) continue
            add(
                SubtitleTrack(
                    language = item.optString("lan").trim(),
                    languageName = item.optString("lan_doc").trim(),
                    url = url
                )
            )
        }
    }

    /** Prefer Simplified Chinese, then Traditional Chinese, then any available language. */
    fun chooseTrack(tracks: List<SubtitleTrack>): SubtitleTrack? = tracks
        .asSequence()
        .withIndex()
        .filter { it.value.url.isNotBlank() }
        .minWithOrNull(compareBy<IndexedValue<SubtitleTrack>> { languagePriority(it.value) }.thenBy { it.index })
        ?.value

    fun fromBody(body: JSONArray): String = buildString {
        for (index in 0 until body.length()) {
            val item = body.optJSONObject(index) ?: continue
            val line = normalizeLine(item.optString("content"))
            if (line.isBlank()) continue
            if (isNotEmpty()) append('\n')
            append(line)
            if (length >= MAX_CLIPBOARD_CHARS) {
                setLength(MAX_CLIPBOARD_CHARS)
                break
            }
        }
    }.trim()

    fun normalizeSubtitleUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
        raw.startsWith("https://") -> raw
        else -> ""
    }

    private fun normalizeLine(raw: String): String {
        val firstLine = raw.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .firstOrNull()
            .orEmpty()
        return firstLine.replace('\u0000'.toString(), "").trim()
    }

    private fun languagePriority(track: SubtitleTrack): Int {
        val language = track.language.lowercase()
        val name = track.languageName.lowercase()
        val simplified = language in setOf("zh-cn", "zh-hans", "zh-s", "zh") ||
            name.contains("简体") || name.contains("simplified")
        if (simplified) return 0
        val traditional = language in setOf("zh-tw", "zh-hant", "zh-hk") ||
            name.contains("繁体") || name.contains("traditional")
        if (traditional) return 1
        return 2
    }
}
