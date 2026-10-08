package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Small, dependency-free reader for the SponsorBlock-compatible BSB service. */
internal class SponsorBlockClient(
    private val serverAddress: String = DEFAULT_SERVER,
    private val timeoutMs: Int = 8_000
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "bil-sponsorblock").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, List<SponsorBlockSegment>>()

    fun loadAsync(
        videoId: String,
        cid: Long?,
        callback: (List<SponsorBlockSegment>) -> Unit,
        log: ((String) -> Unit)? = null
    ) {
        val bvid = BilibiliVideoIdCodec.toBvid(videoId)
        if (bvid == null) {
            log?.invoke("load skipped: video id could not be converted")
            return callback(emptyList())
        }
        val key = "$bvid:${cid ?: 0L}"
        cache[key]?.let {
            log?.invoke("cache hit: bvid=$bvid segments=${it.size}")
            callback(it)
        }?.also { return }
        log?.invoke("request start: bvid=$bvid cid=${cid ?: "none"} server=$serverAddress")
        executor.execute {
            val result = runCatching { request(bvid, cid, log) }
                .onFailure { log?.invoke("request failed: ${it.javaClass.simpleName}: ${it.message ?: "no message"}") }
                .getOrDefault(emptyList())
            cache[key] = result
            log?.invoke("request complete: bvid=$bvid segments=${result.size}")
            callback(result)
        }
    }

    private fun request(
        bvid: String,
        cid: Long?,
        log: ((String) -> Unit)?
    ): List<SponsorBlockSegment> {
        val prefix = sha256(bvid).take(4)
        val categories = JSONArray(DEFAULT_CATEGORIES).toString()
        val url = URL("${serverAddress.trimEnd('/')}/api/skipSegments/$prefix?categories=" +
            URLEncoder.encode(categories, StandardCharsets.UTF_8.name()))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            useCaches = true
        }
        return try {
            val responseCode = connection.responseCode
            log?.invoke("http response: code=$responseCode")
            if (responseCode !in 200..299) return emptyList()
            val body = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
                .use { it.readText() }
            val values = JSONArray(body)
            buildList {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val itemVideoId = item.optString("videoID").takeIf { it.isNotBlank() }
                    if (itemVideoId != null && BilibiliVideoIdCodec.toBvid(itemVideoId) != bvid) continue
                    // BSB returns one video object containing a `segments` array;
                    // accept the legacy single-segment shape as well.
                    val segmentValues = item.optJSONArray("segments")
                        ?: JSONArray().apply { item.optJSONObject("segment")?.let(::put) }
                    for (segmentIndex in 0 until segmentValues.length()) {
                        val segmentItem = segmentValues.optJSONObject(segmentIndex) ?: continue
                        val itemCid = segmentItem.optLong("cid", item.optLong("cid", Long.MIN_VALUE))
                            .takeIf { it != Long.MIN_VALUE }
                        if (cid != null && itemCid != null && itemCid != cid) continue
                        val range = segmentItem.optJSONArray("segment") ?: continue
                        if (range.length() < 2) continue
                        val start = range.optDouble(0, Double.NaN)
                        val end = range.optDouble(1, Double.NaN)
                        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) continue
                        add(SponsorBlockSegment(start, end,
                            segmentItem.optString("category", item.optString("category", "unknown")),
                            segmentItem.optString("actionType", item.optString("actionType", "skip")),
                            itemVideoId, itemCid))
                    }
                }
            }.sortedBy { it.startSeconds }.also {
                log?.invoke("parsed segments: count=${it.size}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val DEFAULT_SERVER = "https://www.bsbsb.top"
        val DEFAULT_CATEGORIES = listOf(
            "sponsor", "selfpromo", "exclusive_access", "interaction", "intro",
            "outro", "preview", "padding", "filler", "music_offtopic", "poi_highlight"
        )
    }
}
