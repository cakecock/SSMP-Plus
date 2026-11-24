package com.tamaverse.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

object SoundEngine {
    @Volatile
    var muted: Boolean = false

    @Volatile
    var volume: Float = 0.7f

    @Volatile
    var hapticsEnabled: Boolean = true

    private val scope = CoroutineScope(Dispatchers.Default)

    fun vibrate(context: Context, durationMs: Long = 22L) {
        if (!hapticsEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun playClick() = playNotes(listOf(784.0 to 55))
    fun playEat() = playNotes(listOf(523.25 to 60, 659.25 to 65, 783.99 to 80))
    fun playBath() = playNotes(listOf(587.33 to 70, 698.46 to 70, 880.0 to 100))
    fun playHappy() = playNotes(listOf(523.25 to 70, 659.25 to 70, 783.99 to 75, 1046.50 to 130))
    fun playHeal() = playNotes(listOf(440.0 to 80, 554.37 to 80, 659.25 to 80, 880.0 to 140))
    fun playSleep() = playNotes(listOf(659.25 to 110, 523.25 to 140, 392.0 to 180))
    fun playHatch() = playNotes(listOf(523.25 to 70, 587.33 to 70, 659.25 to 70, 783.99 to 90, 1046.50 to 190))

    private fun playNotes(notes: List<Pair<Double, Int>>) {
        val vol = volume.coerceIn(0f, 1f)
        if (muted || vol <= 0.01f) return
        scope.launch {
            try {
                val sampleRate = 22050
                val totalSamples = notes.sumOf { (sampleRate * it.second) / 1000 }
                if (totalSamples <= 0) return@launch
                val pcm = ShortArray(totalSamples)
                var offset = 0
                val baseAmp = 7200.0 * vol
                for ((freq, ms) in notes) {
                    val count = (sampleRate * ms) / 1000
                    for (i in 0 until count) {
                        val env = if (i > count * 0.7) (count - i).toDouble() / (count * 0.3) else 1.0
                        val phase = 2.0 * PI * freq * i / sampleRate
                        val wave = if (sin(phase) >= 0) 0.55 else -0.55
                        val s = (wave * baseAmp * env).toInt().coerceIn(-32767, 32767)
                        if (offset + i < pcm.size) pcm[offset + i] = s.toShort()
                    }
                    offset += count
                }
                val bufBytes = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                val totalMs = notes.sumOf { it.second }.toLong() + 80L
                Thread.sleep(totalMs)
                track.stop()
                track.release()
            } catch (_: Throwable) {
            }
        }
    }
}
