package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorBlockModelsTest {
    @Test fun convertsAvToBvUsingExtensionAlgorithm() {
        assertEquals("BV17x411w7KC", BilibiliVideoIdCodec.toBvid("av170001"))
    }

    @Test fun convertsAvidCidMediaIdToBvid() {
        assertEquals("BV17x411w7KC", BilibiliVideoIdCodec.toBvid("170001+42208987760"))
    }

    @Test fun choosesOnlySkipSegmentContainingPosition() {
        val segments = listOf(
            SponsorBlockSegment(10.0, 20.0, "sponsor"),
            SponsorBlockSegment(30.0, 40.0, "intro", actionType = "mute")
        )
        assertEquals("sponsor", SponsorBlockDecision.segmentAt(segments, 12.0)?.category)
        assertNull(SponsorBlockDecision.segmentAt(segments, 32.0))
    }

    @Test fun rewindResetsSkipStateOnlyAfterMeaningfulBackwardsJump() {
        assertEquals(true, SponsorBlockDecision.isRewind(246.5, 0.0))
        assertEquals(true, SponsorBlockDecision.isRewind(170.0, 166.0))
        assertEquals(false, SponsorBlockDecision.isRewind(170.0, 169.2))
        assertEquals(false, SponsorBlockDecision.isRewind(null, 0.0))
    }
}
