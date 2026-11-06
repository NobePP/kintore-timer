package com.kintoretimer.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * プロセスに1つのタイマー。画面（Activity）とは独立して動くので、
 * 画面が破棄されても計時は続き、開き直すと今の状態を受け取れる。
 * すべてメインスレッドから呼ぶこと。
 */
class TimerController private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cues = AudioCues(app)

    val engine = TimerEngine(SystemClock::elapsedRealtime, cues, ::onEngineChange)

    /** 画面へ状態を送る先。表示中だけ設定する。 */
    var viewListener: ((String) -> Unit)? = null
    var service: TimerService? = null

    private var loadedPhases: String? = null
    private var serviceOn = false

    private val ticker = Runnable {
        engine.tick()
        scheduleTick()
    }
    private val stopServiceLater = Runnable { stopService() }

    val ttsReady: Boolean get() = cues.ttsReady

    fun load(phasesJson: String, voice: Boolean, beeps: Boolean, vol: Double, force: Boolean) {
        engine.voice = voice
        engine.beeps = beeps
        cues.setVolume(vol.toFloat())
        if (force || phasesJson != loadedPhases) {
            loadedPhases = phasesJson
            engine.load(parsePhases(phasesJson))
        } else {
            pushState()
        }
    }

    fun setVolume(vol: Double) = cues.setVolume(vol.toFloat())

    fun testSound(vol: Double, voice: Boolean) {
        cues.setVolume(vol.toFloat())
        cues.tick()
        main.postDelayed({ cues.go() }, 320)
        if (voice) cues.say("音のテストです。次は胸、ダンベルフライ", 900)
    }

    fun stateJson(): String = JSONObject()
        .put("idx", engine.idx)
        .put("running", engine.running)
        .put("started", engine.started)
        .put("finished", engine.finished)
        .put("remainMs", engine.remainMs())
        .toString()

    fun pushState() {
        viewListener?.invoke(stateJson())
    }

    private fun onEngineChange() {
        scheduleTick()
        pushState()
        syncService()
    }

    /** 次の「1秒の変わり目」の直後に起きる。 */
    private fun scheduleTick() {
        main.removeCallbacks(ticker)
        if (engine.running) main.postDelayed(ticker, engine.remainMs() % 1000 + 1)
    }

    private fun syncService() {
        main.removeCallbacks(stopServiceLater)
        when {
            engine.hasSession && !serviceOn -> {
                serviceOn = true
                ContextCompat.startForegroundService(app, Intent(app, TimerService::class.java))
            }
            !engine.hasSession && serviceOn -> {
                service?.render()
                // 完走時は終了の音と声を最後まで鳴らしてから止める
                if (engine.finished) main.postDelayed(stopServiceLater, 4000) else stopService()
            }
            else -> service?.render()
        }
    }

    private fun stopService() {
        serviceOn = false
        app.stopService(Intent(app, TimerService::class.java))
    }

    private fun parsePhases(json: String): List<Phase> {
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Phase(
                type = when (o.getString("type")) {
                    "prep" -> PhaseType.PREP
                    "rest" -> PhaseType.REST
                    else -> PhaseType.WORK
                },
                secs = o.getInt("secs"),
                name = o.optString("name"),
                group = o.optString("group"),
                no = o.optInt("no"),
            )
        }
    }

    companion object {
        @Volatile
        private var instance: TimerController? = null

        fun get(context: Context): TimerController =
            instance ?: synchronized(this) {
                instance ?: TimerController(context).also { instance = it }
            }
    }
}
