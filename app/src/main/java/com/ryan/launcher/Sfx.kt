package com.ryan.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** All sounds are synthesized on the device - no audio files are bundled. */
class Sfx(private val ctx: Context) {
    private val rate = 16000
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    private var tickId = 0
    private var chimeId = 0
    @Volatile var on = true
    @Volatile var vol = 0.4f

    private var mp: MediaPlayer? = null
    private var gen = 0
    private var musicVolume = 0.25f

    init {
        Thread {
            val a = FloatArray((0.14 * rate).toInt())
            bell(a, 0.0, 520.0, 0.14, 0.5)
            tickId = load("tick.wav", a)
            val b = FloatArray(rate)
            bell(b, 0.0, 523.25, 0.7, 0.35)
            bell(b, 0.08, 783.99, 0.9, 0.3)
            chimeId = load("chime.wav", b)
        }.start()
    }

    fun tick() { if (on) pool.play(tickId, vol, vol, 1, 0, 0.96f + Math.random().toFloat() * 0.1f) }
    fun chime() { if (on) pool.play(chimeId, vol, vol, 1, 0, 1f) }

    private fun load(name: String, buf: FloatArray): Int {
        val f = File(ctx.cacheDir, name)
        writeWav(f, buf)
        return pool.load(f.path, 1)
    }

    private fun bell(buf: FloatArray, start: Double, f: Double, dur: Double, amp: Double) {
        val s0 = (start * rate).toInt()
        val n = (dur * rate).toInt()
        for (i in 0 until n) {
            val j = s0 + i
            if (j >= buf.size) break
            val t = i.toDouble() / rate
            val env = minOf(1.0, t / 0.008) * exp(-t * 6.0 / dur)
            val v = sin(2 * PI * f * t) + 0.25 * sin(2 * PI * f * 2.01 * t)
            buf[j] += (v * env * amp).toFloat()
        }
    }

    private fun writeWav(f: File, buf: FloatArray) {
        val n = buf.size * 2
        val b = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + n); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1.toShort()); b.putShort(1.toShort())
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2.toShort()); b.putShort(16.toShort())
        b.put("data".toByteArray()); b.putInt(n)
        for (x in buf) b.putShort((x.coerceIn(-1f, 1f) * 30000).toInt().toShort())
        FileOutputStream(f).use { it.write(b.array()) }
    }

    /** 24s seamless loop: slow Am - F - C - G pad (overlapping sin^2 swells) plus soft pentatonic bells. */
    private fun ambientFile(): File {
        val f = File(ctx.cacheDir, "ambient.wav")
        if (f.exists()) return f
        val hop = 6.0
        val chords = arrayOf(
            doubleArrayOf(110.0, 164.81, 220.0, 261.63, 329.63),
            doubleArrayOf(87.31, 130.81, 174.61, 261.63, 349.23),
            doubleArrayOf(65.41, 130.81, 196.0, 261.63, 329.63),
            doubleArrayOf(98.0, 146.83, 196.0, 246.94, 293.66)
        )
        val total = (hop * chords.size * rate).toInt()
        val buf = FloatArray(total)
        val len = (hop * 2 * rate).toInt()
        for ((ci, ch) in chords.withIndex()) {
            val start = (ci * hop * rate).toInt() - (hop * rate / 2).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                val env = sin(PI * i / len).pow(2.0)
                var v = 0.0
                for (fr in ch) {
                    v += sin(2 * PI * fr * t) + 0.5 * sin(2 * PI * fr * 1.003 * t) + 0.25 * sin(2 * PI * fr * 2 * t)
                }
                val trem = 1.0 + 0.15 * sin(2 * PI * 0.2 * t)
                buf[Math.floorMod(start + i, total)] += (v * env * trem * 0.035).toFloat()
            }
        }
        val notes = doubleArrayOf(659.25, 880.0, 783.99, 1046.5, 659.25, 587.33, 880.0)
        val times = doubleArrayOf(2.0, 5.5, 9.0, 12.5, 15.0, 18.5, 21.0)
        for (k in notes.indices) bell(buf, times[k], notes[k], 3.5, 0.06)
        writeWav(f, buf)
        return f
    }

    fun startMusic(custom: File?, v: Float) {
        val g: Int
        synchronized(this) { stopLocked(); gen++; g = gen; musicVolume = v }
        Thread {
            try {
                val src = if (custom != null && custom.exists()) custom else ambientFile()
                val m = MediaPlayer()
                m.setDataSource(src.path)
                m.isLooping = true
                m.prepare()
                synchronized(this) {
                    if (g == gen) {
                        m.setVolume(musicVolume, musicVolume)
                        mp = m
                        m.start()
                    } else m.release()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    fun musicVol(v: Float) {
        synchronized(this) { musicVolume = v; mp?.setVolume(v, v) }
    }

    fun stopMusic() {
        synchronized(this) { gen++; stopLocked() }
    }

    private fun stopLocked() {
        mp?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        mp = null
    }
}
