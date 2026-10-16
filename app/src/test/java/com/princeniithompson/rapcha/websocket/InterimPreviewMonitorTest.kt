package com.princeniithompson.rapcha.websocket

import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingTranscriptFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterimPreviewMonitorTest {

    @Test
    fun silenceDoesNotStall() {
        val monitor = InterimPreviewMonitor()
        monitor.start(0L)
        val silence = InterimPreviewMonitor.silencePcm()
        for (t in 1_000L..20_000L step 1_000L) {
            monitor.onPcm(silence, accepted = true, nowMs = t)
            val tick = monitor.poll(t, "open")
            assertTrue(tick.events.none { it is InterimPreviewMonitor.Event.Stall })
            assertEquals(InterimPreviewMonitor.Phase.LISTENING, tick.phase)
        }
    }

    @Test
    fun speechWithoutTranscriptionStallsOnceThenResumesWithGap() {
        val monitor = InterimPreviewMonitor()
        monitor.start(0L)
        val speech = InterimPreviewMonitor.speechPcm()
        for (t in 0L..9_000L step 100L) {
            monitor.onPcm(speech, accepted = true, nowMs = t)
        }
        val stalled = monitor.poll(9_000L, "open")
        val stall = stalled.events.filterIsInstance<InterimPreviewMonitor.Event.Stall>().single()
        assertEquals(9_000L, stall.gapMs)
        assertEquals(InterimPreviewMonitor.Phase.PREVIEW_DELAYED, stalled.phase)

        val again = monitor.poll(9_500L, "open")
        assertTrue(again.events.none { it is InterimPreviewMonitor.Event.Stall })
        assertEquals(InterimPreviewMonitor.Phase.PREVIEW_DELAYED, again.phase)

        val resumed = monitor.onInterim(12_000L)
        assertTrue(resumed is InterimPreviewMonitor.Event.Resumed)
        assertEquals(12_000L, (resumed as InterimPreviewMonitor.Event.Resumed).gapMs)
        val after = monitor.poll(12_000L, "open")
        assertEquals(InterimPreviewMonitor.Phase.LISTENING, after.phase)
        assertTrue(monitor.summary().contains("interim_count=1"))
        assertTrue(monitor.summary().contains("max_interim_gap_ms=12000"))
    }

    @Test
    fun heartbeatAndFinalizeLatencyAreRecorded() {
        val monitor = InterimPreviewMonitor(heartbeatEveryMs = 5_000L)
        monitor.start(0L)
        monitor.onPcm(InterimPreviewMonitor.speechPcm(), accepted = true, nowMs = 1_000L)
        monitor.onInterim(1_000L)
        val beat = monitor.poll(6_000L, "open").events.filterIsInstance<InterimPreviewMonitor.Event.Heartbeat>().single()
        assertEquals(1, beat.interimCount)
        assertEquals(5_000L, beat.lastInterimAgeMs)
        assertTrue(beat.bytesSent > 0L)
        assertEquals("open", beat.socketState)

        monitor.onActivityEnd(10_000L)
        monitor.onFinalTranscript(14_200L)
        val summary = monitor.summary()
        assertTrue(summary.contains("finalize_latency_ms=4200"))
        assertTrue(summary.contains("interim_count=1"))
    }

    @Test
    fun closedSocketIsAnErrorPhaseNotAPreviewStall() {
        val monitor = InterimPreviewMonitor()
        monitor.start(0L)
        val speech = InterimPreviewMonitor.speechPcm()
        for (t in 0L..9_000L step 100L) {
            monitor.onPcm(speech, accepted = false, nowMs = t)
        }
        val tick = monitor.poll(9_000L, "closed")
        assertEquals(InterimPreviewMonitor.Phase.CONNECTION_LOST, tick.phase)
        assertTrue(tick.events.none { it is InterimPreviewMonitor.Event.Stall })
    }

    @Test
    fun livePreviewShowsTheTailOfALongTranscript() {
        val opening = "The opening sentence stays at the start of a long dictation. "
        val ending = "The newest words are about the quarterly plan and must stay visible."
        val text = opening + "middle ".repeat(80) + ending
        val preview = FloatingTranscriptFormatter.livePreviewText(text, maxChars = 180)
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.endsWith(ending))
        assertFalse(preview.contains("opening sentence"))
    }

    @Test
    fun polishingMessageUsesMinutesOfSpeech() {
        assertEquals(
            "Polishing 6 minutes of speech...",
            InterimPreviewMonitor.polishingMessage(342)
        )
        assertEquals(
            "Polishing 1 minute of speech...",
            InterimPreviewMonitor.polishingMessage(20)
        )
    }
}
