package com.kintoretimer.app

import kotlin.math.ceil

enum class PhaseType { PREP, WORK, REST }

data class Phase(
    val type: PhaseType,
    val secs: Int,
    val name: String = "",
    val group: String = "",
    val no: Int = 0,
)

/** 合図の出力先。テストでは記録用の実装に差し替える。 */
interface Cues {
    fun tick()
    fun go()
    fun rest()
    fun done()
    fun say(text: String, delayMs: Long = 0)
    fun dropPendingSpeech()
    fun hush()
}

/**
 * タイマー本体。Androidに依存しない。
 * 時刻は [clock]、進行は外から [tick] を呼んで進める。
 * 種目の切り替え・開始・停止など、表示の変わる出来事のたびに [onChange] を呼ぶ。
 */
class TimerEngine(
    private val clock: () -> Long,
    private val cues: Cues,
    private val onChange: () -> Unit,
) {
    var phases: List<Phase> = emptyList()
        private set
    var idx = 0
        private set
    var running = false
        private set
    var started = false
        private set
    var finished = false
        private set

    var voice = true
    var beeps = true

    private var pendingCue = false
    private var endAt = 0L
    private var remain = 0L
    private var lastWhole: Int? = null
    private val announced = HashSet<Int>()

    val hasSession: Boolean get() = started && !finished
    val current: Phase? get() = phases.getOrNull(idx)
    val works: Int get() = phases.count { it.type == PhaseType.WORK }

    fun remainMs(): Long = if (running) maxOf(0, endAt - clock()) else remain

    fun nextWorkPhase(): Phase? = nextWork(idx).takeIf { it >= 0 }?.let { phases[it] }

    fun load(newPhases: List<Phase>) {
        phases = newPhases
        reset()
    }

    fun toggle() {
        if (running) pause() else start()
    }

    fun start() {
        if (phases.isEmpty()) return
        if (finished) resetFields()
        running = true
        endAt = clock() + remain
        lastWhole = null
        if (!started) {
            started = true
            announced.clear()
            cueEnter(false)
        } else if (pendingCue) {
            cueEnter(true)
        }
        pendingCue = false
        onChange()
        tick()
    }

    fun pause() {
        if (!running) return
        remain = maxOf(0, endAt - clock())
        running = false
        cues.hush()
        onChange()
    }

    fun reset() {
        cues.hush()
        resetFields()
        onChange()
    }

    private fun resetFields() {
        running = false
        idx = 0
        started = false
        finished = false
        pendingCue = false
        announced.clear()
        remain = phases.firstOrNull()?.let { it.secs * 1000L } ?: 0L
        lastWhole = null
    }

    fun next() {
        if (phases.isEmpty() || finished) return
        if (idx + 1 >= phases.size) {
            if (started) finish()
            return
        }
        jump(idx + 1)
    }

    fun prev() {
        if (phases.isEmpty()) return
        if (finished) {
            jump(phases.size - 1)
            return
        }
        val elapsed = phases[idx].secs * 1000L - remainMs()
        jump(if (elapsed > 2500 || idx == 0) idx else idx - 1)
    }

    private fun jump(to: Int) {
        finished = false
        idx = to.coerceIn(0, phases.size - 1)
        remain = phases[idx].secs * 1000L
        lastWhole = null
        if (running) {
            endAt = clock() + remain
            cueEnter(true)
        } else if (started) {
            pendingCue = true
        }
        onChange()
    }

    private fun finish() {
        running = false
        finished = true
        started = true
        pendingCue = false
        remain = 0
        cues.dropPendingSpeech()
        if (beeps) cues.done()
        say("おつかれさまでした")
        onChange()
    }

    fun tick() {
        if (!running) return
        val t = clock()
        if (t >= endAt) {
            while (t >= endAt) {
                if (idx + 1 >= phases.size) {
                    finish()
                    return
                }
                idx++
                endAt += phases[idx].secs * 1000L
            }
            lastWhole = null
            cueEnter(false)
            onChange()
        }
        val whole = ceil((endAt - t) / 1000.0).toInt()
        val prev = lastWhole
        if (prev != null && whole < prev) {
            val p = phases[idx]
            if (beeps && whole in 1..3 && p.secs >= 4) cues.tick()
            if (p.type == PhaseType.WORK && whole == 6 && p.secs >= 8) {
                val nx = phases.getOrNull(idx + 1)
                if (nx != null && nx.type == PhaseType.WORK && (idx + 1) !in announced) {
                    announced += idx + 1
                    say("次は、" + lastPrefix(nx) + nx.name)
                }
            }
        }
        lastWhole = whole
    }

    private fun cueEnter(manual: Boolean) {
        val p = phases.getOrNull(idx) ?: return
        cues.dropPendingSpeech()
        if (p.type == PhaseType.WORK) {
            if (beeps) cues.go()
            if (manual || idx !in announced) {
                announced += idx
                say(lastPrefix(p) + p.name)
            }
            return
        }
        val nw = nextWork(idx)
        if (p.type == PhaseType.REST && beeps) cues.rest()
        if (nw < 0) return
        val np = phases[nw]
        announced += nw
        val grp = if (np.group.isNotEmpty()) np.group + "、" else "、"
        val msg = if (p.type == PhaseType.PREP) {
            "準備。最初は$grp${np.name}"
        } else {
            "休憩。次は$grp${lastPrefix(np)}${np.name}"
        }
        // 休憩の音と読み上げが重ならないよう少し待つ
        say(msg, if (p.type == PhaseType.REST && beeps) 420 else 0)
    }

    private fun say(text: String, delayMs: Long = 0) {
        if (voice) cues.say(text, delayMs)
    }

    private fun lastPrefix(p: Phase) = if (isLast(p)) "ラスト、" else ""

    private fun isLast(p: Phase) = p.type == PhaseType.WORK && p.no == works

    private fun nextWork(from: Int): Int {
        for (j in from + 1 until phases.size) if (phases[j].type == PhaseType.WORK) return j
        return -1
    }
}
