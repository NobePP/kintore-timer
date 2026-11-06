package com.kintoretimer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 合図の音と読み上げ。
 *
 * 音楽を止めないための要点は「オーディオフォーカスを要求しない」こと。
 * 要求しなければ音楽アプリには何も通知されず、そのまま上に重なって鳴る。
 * USAGE_MEDIA にしているのは、着信音や通知の音量と違ってマナーモードでも消えず、
 * 音楽と同じメディア音量で調整できるため。
 */
class AudioCues(context: Context) : Cues {
    private val main = Handler(Looper.getMainLooper())

    private val toneAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var volume = 0.8f
    private var ttsOk = false
    private var pendingSpeech: Runnable? = null
    private lateinit var tts: TextToSpeech

    private val tickTrack by lazy { track(listOf(Note(1175.0, 0.0, 0.10, Wave.SQUARE, 0.30))) }
    private val goTrack by lazy {
        track(
            listOf(
                Note(1568.0, 0.0, 0.13, Wave.SQUARE, 0.30),
                Note(2093.0, 0.14, 0.36, Wave.SQUARE, 0.32),
            ),
        )
    }
    private val restTrack by lazy {
        track(
            listOf(
                Note(1319.0, 0.0, 0.16, Wave.TRIANGLE, 0.75),
                Note(988.0, 0.17, 0.32, Wave.TRIANGLE, 0.75),
            ),
        )
    }
    private val doneTrack by lazy {
        track(
            listOf(1047.0, 1319.0, 1568.0, 2093.0).mapIndexed { i, f ->
                Note(f, i * 0.14, if (i == 3) 0.6 else 0.13, Wave.SQUARE, 0.28)
            },
        )
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsOk = tts.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE
                tts.setSpeechRate(1.08f)
                tts.setAudioAttributes(speechAttrs)
            }
        }
    }

    val ttsReady: Boolean get() = ttsOk

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    override fun tick() = play(tickTrack)
    override fun go() = play(goTrack)
    override fun rest() = play(restTrack)
    override fun done() = play(doneTrack)

    override fun say(text: String, delayMs: Long) {
        dropPendingSpeech()
        if (delayMs <= 0) {
            speak(text)
        } else {
            val r = Runnable {
                pendingSpeech = null
                speak(text)
            }
            pendingSpeech = r
            main.postDelayed(r, delayMs)
        }
    }

    override fun dropPendingSpeech() {
        pendingSpeech?.let { main.removeCallbacks(it) }
        pendingSpeech = null
    }

    override fun hush() {
        dropPendingSpeech()
        if (ttsOk) tts.stop()
    }

    private fun speak(text: String) {
        if (ttsOk) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    private fun play(t: AudioTrack) {
        try {
            // 再生し終えた静的トラックも状態はPLAYINGのままなので、毎回止めて巻き戻す
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.reloadStaticData()
            t.setVolume(volume)
            t.play()
        } catch (_: IllegalStateException) {
            // 合図が1回鳴らなくてもタイマーは止めない
        }
    }

    private fun track(notes: List<Note>): AudioTrack {
        val pcm = renderPcm(notes)
        val t = AudioTrack.Builder()
            .setAudioAttributes(toneAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private enum class Wave { SQUARE, TRIANGLE }

    private class Note(
        val freq: Double,
        val at: Double,
        val dur: Double,
        val wave: Wave,
        val level: Double,
    )

    private fun renderPcm(notes: List<Note>): ShortArray {
        val total = notes.maxOf { it.at + it.dur } + 0.03
        val mix = DoubleArray((total * SAMPLE_RATE).toInt())
        for (n in notes) {
            val first = (n.at * SAMPLE_RATE).toInt()
            val count = (n.dur * SAMPLE_RATE).toInt()
            for (i in 0 until min(count, mix.size - first)) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = min(1.0, min(t / 0.01, (n.dur - t) / 0.05))
                val cycle = n.freq * t
                val frac = cycle - floor(cycle)
                val s = when (n.wave) {
                    Wave.SQUARE -> if (frac < 0.5) 1.0 else -1.0
                    Wave.TRIANGLE -> 4.0 * abs(frac - 0.5) - 1.0
                }
                mix[first + i] += s * n.level * max(0.0, env)
            }
        }
        return ShortArray(mix.size) { i ->
            (mix[i].coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
