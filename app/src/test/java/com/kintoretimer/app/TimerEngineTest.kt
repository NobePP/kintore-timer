package com.kintoretimer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimerEngineTest {
    private class Log : Cues {
        val events = ArrayList<String>()
        override fun tick() { events += "tick" }
        override fun go() { events += "go" }
        override fun rest() { events += "rest" }
        override fun done() { events += "done" }
        override fun say(text: String, delayMs: Long) { events += "say:$text" }
        override fun dropPendingSpeech() {}
        override fun hush() { events += "hush" }
    }

    private var time = 1000L
    private val log = Log()
    private val e = TimerEngine({ time }, log, {})

    private val menu = listOf(
        Phase(PhaseType.PREP, 5),
        Phase(PhaseType.WORK, 10, "カール", "腕", 1),
        Phase(PhaseType.REST, 5),
        Phase(PhaseType.WORK, 10, "クランチ", "腹", 2),
    )

    /** 実機と同じく1秒の変わり目ごとにtickする */
    private fun advance(ms: Long) {
        val end = time + ms
        while (time < end) {
            time = minOf(end, time + 100)
            e.tick()
        }
    }

    @Before
    fun setUp() {
        e.load(menu)
        log.events.clear()
    }

    @Test
    fun startAnnouncesFirstExercise() {
        e.start()
        assertTrue(e.running)
        assertEquals(listOf("say:準備。最初は腕、カール"), log.events)
    }

    @Test
    fun countsDownLastThreeSecondsThenGoes() {
        e.start()
        log.events.clear()
        advance(5000)
        assertEquals(1, e.idx)
        assertEquals(3, log.events.count { it == "tick" })
        assertTrue("go" in log.events)
    }

    @Test
    fun workAlreadyAnnouncedDuringPrepIsNotRepeated() {
        e.start()
        log.events.clear()
        advance(5000)
        assertFalse(log.events.any { it.startsWith("say:") })
    }

    @Test
    fun restAnnouncesLastExercise() {
        e.start()
        advance(15000)
        assertEquals(2, e.idx)
        assertTrue("rest" in log.events)
        assertTrue("say:休憩。次は腹、ラスト、クランチ" in log.events)
    }

    @Test
    fun finishesAfterAllPhases() {
        e.start()
        advance(30000)
        assertTrue(e.finished)
        assertFalse(e.running)
        assertFalse(e.hasSession)
        assertTrue("done" in log.events)
        assertEquals("say:おつかれさまでした", log.events.last())
    }

    @Test
    fun pauseKeepsRemainingTimeWhileClockAdvances() {
        e.start()
        advance(2000)
        e.pause()
        val left = e.remainMs()
        time += 60_000
        assertEquals(left, e.remainMs())
        e.start()
        advance(3100)
        assertEquals(1, e.idx)
    }

    @Test
    fun catchesUpWhenTicksWereDelayed() {
        e.start()
        time += 16_000
        e.tick()
        assertEquals(2, e.idx)
        assertEquals(4000L, e.remainMs())
    }

    @Test
    fun prevRestartsPhaseAfterTwoAndAHalfSeconds() {
        e.start()
        advance(5000)
        advance(3000)
        e.prev()
        assertEquals(1, e.idx)
        assertEquals(10_000L, e.remainMs())
        e.prev()
        assertEquals(0, e.idx)
    }

    @Test
    fun nextOnLastPhaseFinishes() {
        e.start()
        repeat(3) { e.next() }
        assertEquals(3, e.idx)
        e.next()
        assertTrue(e.finished)
    }

    @Test
    fun startAfterFinishRunsAgain() {
        e.start()
        advance(30000)
        e.start()
        assertTrue(e.running)
        assertFalse(e.finished)
        assertEquals(0, e.idx)
    }

    @Test
    fun voiceAndBeepsCanBeTurnedOff() {
        e.voice = false
        e.beeps = false
        e.start()
        advance(30000)
        assertEquals(emptyList<String>(), log.events.filter { it != "hush" })
    }
}
