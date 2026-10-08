package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Dependency-free reader for Bilibili player subtitle metadata and body JSON. */
internal class SubtitleExportClient(
    private val timeoutMs: Int = 8_000
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "bil-subtitle-export").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, String>()

    fun loadAsync(
        videoId: String,
        cid: Long? = null,
        callback: (Result<String>) -> Unit,
        log: ((String) -> Unit)? = null
    ) {
        val bvid = BilibiliVideoIdCodec.toBvid(videoId)
        if (bvid == null) {
            log?.invoke("load skipped: invalid video id")
            callback(Result.failure(SubtitleExportException("视频编号无效")))
            return
        }
        val key = "$bvid:${cid ?: 0L}"
        cache[key]?.let {
            log?.invoke("cache hit: bvid=$bvid chars=${it.length}")
            callback(Result.success(it))
            return
        }
        log?.invoke("request start: bvid=$bvid cid=${cid ?: "resolve"}")
        executor.execute {
            val result = runCatching { request(bvid, cid, log) }
                .fold(
                    onSuccess = { text ->
                        if (text.isNotBlank()) cache[key] = text
                        log?.invoke("request complete: bvid=$bvid chars=${text.length}")
                        Result.success(text)
                    },
                    onFailure = { throwable ->
                        val safe = throwable as? SubtitleExportException
                            ?: SubtitleExportException("字幕请求失败")
                        log?.invoke("request failed: ${safe.message ?: "unknown"}")
                        Result.failure(safe)
                    }
                )
            callback(result)
        }
    }

    private fun request(bvid: String, requestedCid: Long?, log: ((String) -> Unit)?): String {
        val cid = requestedCid ?: resolveCid(bvid, log)
        val metadata = requestMetadata(bvid, cid, log)
        val tracks = SubtitleExportText.parseTracks(metadata)
        if (tracks.isEmpty()) throw SubtitleExportException("当前视频没有字幕")
        val selected = SubtitleExportText.chooseTrack(tracks)
            ?: throw SubtitleExportException("当前视频没有可用字幕")
        log?.invoke("track selected: language=${selected.language.ifBlank { "unknown" }} tracks=${tracks.size}")
        val subtitleUrl = SubtitleExportText.normalizeSubtitleUrl(selected.url)
        if (subtitleUrl.isBlank()) throw SubtitleExportException("字幕地址无效")
        val body = requestJson(subtitleUrl, log)
            .optJSONArray("body")
            ?: throw SubtitleExportException("字幕格式无法识别")
        return SubtitleExportText.fromBody(body)
            .takeIf { it.isNotBlank() }
            ?: throw SubtitleExportException("当前字幕没有正文")
    }

    private fun resolveCid(bvid: String, log: ((String) -> Unit)?): Long {
        val data = requestJson(
            "https://api.bilibili.com/x/web-interface/view?bvid=${encode(bvid)}",
            log
        ).optJSONObject("data") ?: throw SubtitleExportException("无法读取视频信息")
        val pages = data.optJSONArray("pages")
        val cid = pages?.optJSONObject(0)?.optLong("cid", 0L) ?: 0L
        if (cid <= 0L) throw SubtitleExportException("无法读取视频分段")
        return cid
    }

    private fun requestMetadata(bvid: String, cid: Long, log: ((String) -> Unit)?): JSONObject {
        val query = "bvid=${encode(bvid)}&cid=$cid"
        val endpoints = listOf(
            "https://api.bilibili.com/x/player/v2?$query",
            "https://api.bilibili.com/x/player/wbi/v2?$query",
            "https://api.bilibili.com/x/v2/dm/view?bvid=${encode(bvid)}&oid=$cid&type=1"
        )
        var lastError: SubtitleExportException? = null
        for (endpoint in endpoints) {
            try {
                val json = requestJson(endpoint, log)
                if (json.optInt("code", -1) == 0) {
                    // Some app versions return a successful empty player response while
                    // the legacy dm endpoint still carries the subtitle configuration.
                    if (SubtitleExportText.parseTracks(json).isNotEmpty()) return json
                    lastError = SubtitleExportException("当前接口没有字幕配置")
                    continue
                }
                lastError = SubtitleExportException(
                    "字幕接口返回错误(${json.optInt("code", -1)})"
                )
            } catch (error: SubtitleExportException) {
                lastError = error
            }
        }
        throw lastError ?: SubtitleExportException("无法读取字幕配置")
    }

    private fun requestJson(endpoint: String, log: ((String) -> Unit)?): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("User-Agent", USER_AGENT)
            useCaches = true
        }
        return try {
            val status = connection.responseCode
            log?.invoke("http response: code=$status path=${URL(endpoint).path}")
            if (status !in 200..299) throw SubtitleExportException("字幕接口网络错误($status)")
            val text = BufferedReader(
                InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
            ).use { it.readText() }
            runCatching { JSONObject(text) }
                .getOrElse { throw SubtitleExportException("字幕接口数据无效") }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    internal class SubtitleExportException(message: String) : Exception(message)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
