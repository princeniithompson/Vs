package com.princeniithompson.rapcha.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongDictationLogicTest {

    @Test
    fun dedupDropsTheLongestSharedSeam() {
        val overlap = TranscriptSeam.dropOverlap(
            "the closing words of piece one",
            "words of piece one and then the new sentence"
        )
        assertEquals(4, overlap.wordsRemoved)
        assertEquals("and then the new sentence", overlap.text)
        assertEquals(
            "the closing words of piece one and then the new sentence",
            TranscriptSeam.append("the closing words of piece one", overlap.text)
        )
    }

    @Test
    fun dedupIgnoresASingleSharedWord() {
        val overlap = TranscriptSeam.dropOverlap("hello there", "there friend")
        assertEquals(0, overlap.wordsRemoved)
        assertEquals("there friend", overlap.text)
    }

    @Test
    fun silenceDoesNotSplitAndAPauseAtTwoThirtyDoes() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        planner.onPieceStarted(0L)
        val silence = ByteArray(32_000)
        var split: DictationPiecePlanner.Split? = null
        repeat(149) {
            split = planner.onAudio(silence, it * 1000L)
        }
        assertNull(split)
        split = planner.onAudio(silence, 149_000L)
        assertEquals(DictationPiecePlanner.Reason.PAUSE, split?.reason)
        assertFalse(split!!.replay)
        assertFalse(split.freshConnection)
        assertTrue(split.pauseLengthMs >= 700L)
    }

    @Test
    fun noPauseByThreeFifteenForcesAReplaySplit() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        planner.onPieceStarted(0L)
        val speech = InterimPreviewMonitor.speechPcm(sampleCount = 16_000)
        planner.onInterim(0L)
        var split: DictationPiecePlanner.Split? = null
        var now = 0L
        while (split == null && now < 200_000L) {
            split = planner.onAudio(speech, now)
            planner.onInterim(now)
            now += 1_000L
        }
        assertEquals(DictationPiecePlanner.Reason.FORCED, split?.reason)
        assertTrue(split!!.replay)
        assertTrue(split.pieceAudioMs >= DictationPiecePlanner.FORCE_AFTER_MS)
    }

    @Test
    fun sixSecondsOfSpeechWithoutInterimRescuesOntoAFreshSocket() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        planner.onPieceStarted(0L)
        val speech = InterimPreviewMonitor.speechPcm(sampleCount = 16_000)
        var split: DictationPiecePlanner.Split? = null
        repeat(5) { index ->
            split = planner.onAudio(speech, index * 1000L)
        }
        assertNull(split)
        split = planner.onAudio(speech, 5_000L)
        assertEquals(DictationPiecePlanner.Reason.RESCUE, split?.reason)
        assertTrue(split!!.freshConnection)
        assertTrue(planner.freshConnectionMode)
    }

    @Test
    fun freshModeAsksForTheNextSocketTenSecondsEarly() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        planner.armFreshMode()
        planner.onPieceStarted(0L)
        planner.onInterim(0L)
        val silence = ByteArray(32_000)
        var now = 0L
        while (now < 140_000L) {
            planner.onAudio(silence, now)
            planner.onInterim(now)
            now += 1_000L
        }
        assertTrue(planner.consumePreconnect())
    }

    @Test
    fun interimPastTheConnectionLimitIsMarked() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        assertNull(planner.ageMarker(200_000L))
        val first = planner.ageMarker(223_000L)
        assertEquals(false, first?.interimPastLimit)
        planner.onInterim(230_000L)
        val second = planner.ageMarker(230_000L)
        assertEquals(true, second?.interimPastLimit)
        assertTrue(planner.interimPastDeathAge())
    }

    @Test
    fun sessionClockWarnsThenFinishesOnTheRightLimit() {
        val single = DictationSessionClock()
        assertTrue(single.onTick(DictationSessionClock.WARNING_MS, singleConnection = true).warn)
        assertFalse(single.onTick(DictationSessionClock.WARNING_MS + 1, singleConnection = true).warn)
        val armed = single.onTick(DictationSessionClock.PAUSE_FINISH_MS, singleConnection = true)
        assertTrue(armed.armFinishAtPause)
        assertFalse(armed.hardStop)
        assertTrue(single.shouldSplitOnGoAway(DictationSessionClock.PAUSE_FINISH_MS - 1))
        assertFalse(single.shouldSplitOnGoAway(DictationSessionClock.PAUSE_FINISH_MS))
        assertTrue(
            single.onTick(DictationSessionClock.SINGLE_CONNECTION_HARD_STOP_MS, singleConnection = true).hardStop
        )

        val rotated = DictationSessionClock()
        assertFalse(
            rotated.onTick(DictationSessionClock.SINGLE_CONNECTION_HARD_STOP_MS, singleConnection = false).hardStop
        )
        assertTrue(rotated.onTick(DictationSessionClock.HARD_STOP_MS, singleConnection = false).hardStop)
    }
}
