package com.example.blockbreaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class SoundManager(private val context: Context) {

    enum class SfxId { WALL_HIT, PADDLE_HIT, BLOCK_HIT, LIFE_LOST, GAME_OVER, WIN }

    private val sampleRate = 44100

    @Volatile private var soundPool: SoundPool? = null
    private val sfxIds = ConcurrentHashMap<SfxId, Int>()
    private val tempFiles = mutableListOf<File>()

    @Volatile private var audioTrack: AudioTrack? = null

    // Yo pentatonic scale frequencies (Hz)
    private val D4 = 293.66
    private val E4 = 329.63
    private val G4 = 392.00
    private val A4 = 440.00
    private val B4 = 493.88
    private val D5 = 587.33
    private val A5 = 880.00

    // ── Public API ────────────────────────────────────────────────────────────

    fun init() {
        Thread {
            initSoundPool()
            initBgm()
        }.apply { isDaemon = true; start() }
    }

    fun playSfx(id: SfxId, rate: Float = 1f) {
        val soundId = sfxIds[id] ?: return
        soundPool?.play(soundId, 1f, 1f, 1, 0, rate.coerceIn(0.5f, 2.0f))
    }

    fun pauseBgm() {
        val at = audioTrack ?: return
        if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause()
    }

    fun resumeBgm() {
        val at = audioTrack ?: return
        if (at.state == AudioTrack.STATE_INITIALIZED &&
            at.playState != AudioTrack.PLAYSTATE_PLAYING) {
            at.play()
        }
    }

    fun release() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        soundPool?.release()
        soundPool = null
        synchronized(tempFiles) {
            tempFiles.forEach { it.delete() }
            tempFiles.clear()
        }
    }

    // ── SFX init ──────────────────────────────────────────────────────────────

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()
        soundPool = sp

        sfxIds[SfxId.WALL_HIT]   = loadSfx(sp, synthNote(A5, 0.080, 0.025))
        sfxIds[SfxId.PADDLE_HIT] = loadSfx(sp, synthNote(D5, 0.120, 0.040))
        sfxIds[SfxId.BLOCK_HIT]  = loadSfx(sp, synthNote(A4, 0.200, 0.070))
        sfxIds[SfxId.LIFE_LOST]  = loadSfx(sp, synthSequence(listOf(D5, A4, E4),          0.200, 0.100))
        sfxIds[SfxId.GAME_OVER]  = loadSfx(sp, synthSequence(listOf(B4, A4, G4, E4, D4),  0.300, 0.150))
        sfxIds[SfxId.WIN]        = loadSfx(sp, synthSequence(listOf(D4, E4, G4, A4, B4, D5), 0.300, 0.120))
    }

    // ── PCM synthesis ─────────────────────────────────────────────────────────

    /** Single-note koto-style pluck: sine * exponential decay with 5ms attack ramp. */
    private fun synthNote(freq: Double, durationSec: Double, tauSec: Double): ShortArray {
        val n = (durationSec * sampleRate).toInt()
        val attackSamples = (0.005 * sampleRate).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val envAttack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val v = envAttack * 0.8 * sin(2.0 * PI * freq * t) * exp(-t / tauSec)
            (v * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Concatenate multiple notes end-to-end. */
    private fun synthSequence(
        freqs: List<Double>,
        noteDurSec: Double,
        tauSec: Double
    ): ShortArray {
        val n = (noteDurSec * sampleRate).toInt()
        val out = ShortArray(n * freqs.size)
        freqs.forEachIndexed { i, freq ->
            synthNote(freq, noteDurSec, tauSec).copyInto(out, i * n)
        }
        return out
    }

    /**
     * Sparse BGM melody using Yo pentatonic (D4 E4 G4 A4 B4).
     * 72 BPM, 4/4, 4-bar loop (~13 s). null = rest beat.
     */
    private fun synthBgm(): ShortArray {
        val bpm = 72.0
        val beatSamples = (60.0 / bpm * sampleRate).toInt()
        val tau = 0.8           // 800ms decay — meditative
        val amplitude = 0.35
        val attackSamples = (0.005 * sampleRate).toInt()

        // D4 . . A4 | G4 . E4 . | B4 . . . | A4 . G4 .
        val pattern: List<Double?> = listOf(
            D4, null, null, A4,
            G4, null, E4,  null,
            B4, null, null, null,
            A4, null, G4,  null
        )

        val totalSamples = beatSamples * pattern.size
        val pcm = ShortArray(totalSamples)

        pattern.forEachIndexed { beat, freq ->
            if (freq == null) return@forEachIndexed
            val start = beat * beatSamples
            val count = minOf(beatSamples, totalSamples - start)
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val envAttack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val v = envAttack * amplitude * sin(2.0 * PI * freq * t) * exp(-t / tau)
                pcm[start + i] = (v * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return pcm
    }

    // ── BGM init ──────────────────────────────────────────────────────────────

    private fun initBgm() {
        val pcm = synthBgm()
        val at = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size * 2,                        // buffer = full loop in bytes
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        at.write(pcm, 0, pcm.size)
        at.setLoopPoints(0, pcm.size, -1)        // infinite loop
        audioTrack = at
    }

    // ── WAV helper ───────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val baos = ByteArrayOutputStream(44 + dataSize)
        val dos = DataOutputStream(baos)

        fun intLE(v: Int) {
            dos.write(v and 0xFF); dos.write((v shr 8) and 0xFF)
            dos.write((v shr 16) and 0xFF); dos.write((v shr 24) and 0xFF)
        }
        fun shortLE(v: Int) { dos.write(v and 0xFF); dos.write((v shr 8) and 0xFF) }

        dos.writeBytes("RIFF"); intLE(36 + dataSize)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt "); intLE(16)
        shortLE(1); shortLE(1)                   // PCM, mono
        intLE(sampleRate); intLE(sampleRate * 2) // sample rate, byte rate
        shortLE(2); shortLE(16)                  // block align, bits/sample
        dos.writeBytes("data"); intLE(dataSize)
        for (s in pcm) shortLE(s.toInt())
        dos.flush()
        return baos.toByteArray()
    }

    private fun loadSfx(sp: SoundPool, pcm: ShortArray): Int {
        val wav = pcmToWav(pcm)
        val tmp = File(context.cacheDir, "sfx_${System.nanoTime()}.wav")
        synchronized(tempFiles) { tempFiles.add(tmp) }
        FileOutputStream(tmp).use { it.write(wav) }
        return sp.load(tmp.absolutePath, 1)
    }
}
