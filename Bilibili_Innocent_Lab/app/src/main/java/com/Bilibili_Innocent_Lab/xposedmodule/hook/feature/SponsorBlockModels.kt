package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** A server segment in the same shape as the SponsorBlock extension protocol. */
internal data class SponsorBlockSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val category: String,
    val actionType: String = "skip",
    val videoId: String? = null,
    val cid: Long? = null
) {
    fun contains(positionSeconds: Double, toleranceSeconds: Double = 0.15): Boolean =
        positionSeconds + toleranceSeconds >= startSeconds &&
            positionSeconds < endSeconds - toleranceSeconds
}

/** Pure decision logic kept separate from reflection and networking for JVM tests. */
internal object SponsorBlockDecision {
    private const val REWIND_RESET_THRESHOLD_SECONDS = 1.0

    fun isRewind(
        previousPositionSeconds: Double?,
        positionSeconds: Double,
        thresholdSeconds: Double = REWIND_RESET_THRESHOLD_SECONDS
    ): Boolean = previousPositionSeconds != null &&
        positionSeconds + thresholdSeconds < previousPositionSeconds

    fun segmentAt(
        segments: List<SponsorBlockSegment>,
        positionSeconds: Double,
        toleranceSeconds: Double = 0.15
    ): SponsorBlockSegment? = segments
        .asSequence()
        .filter { it.actionType == "skip" || it.actionType.isBlank() }
        .filter { it.contains(positionSeconds, toleranceSeconds) }
        .minByOrNull { it.endSeconds }

    fun nextPosition(segment: SponsorBlockSegment, positionSeconds: Double): Double =
        if (segment.endSeconds > positionSeconds) segment.endSeconds else positionSeconds
}

/** Bilibili's public AV/BV conversion used by the browser extension. */
internal object BilibiliVideoIdCodec {
    private const val XOR = 23442827791579L
    private const val MASK = 2251799813685247L
    private const val OFFSET = 1L shl 51
    private const val TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    fun toBvid(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.matches(Regex("BV1[a-zA-Z0-9]{9}"))) return value
        val aidValue = Regex("^(?:av)?([0-9]+)(?:\\+[0-9]+)?$")
            .matchEntire(value)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val aid = aidValue.toLongOrNull() ?: return null
        if (aid <= 0) return null
        val chars = CharArray(12) { '0' }.apply {
            this[0] = 'B'
            this[1] = 'V'
            this[2] = '1'
        }
        var encoded = (OFFSET or aid) xor XOR
        var index = chars.lastIndex
        while (encoded > 0 && index >= 0) {
            chars[index--] = TABLE[(encoded % 58).toInt()]
            encoded /= 58
        }
        chars[3] = chars[9].also { chars[9] = chars[3] }
        chars[4] = chars[7].also { chars[7] = chars[4] }
        return String(chars)
    }
}
