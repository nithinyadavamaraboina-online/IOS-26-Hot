package com.realme.samurai.parts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

object SamuraiAudioEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeEffects = HashMap<Int, DynamicsProcessing>()
    private var currentPreset: Int = 1
    private var callbackRegistered = false

    @Volatile
    private var speakerCleanRunning = false
    private var speakerCleanThread: Thread? = null

    private val bandCutoffs = floatArrayOf(150f, 500f, 2200f, 7500f, 18000f)

    private fun gainsForPreset(preset: Int): FloatArray = when (preset) {
        1 -> floatArrayOf(2.0f, 0.6f, 1.3f, 2.2f, 1.8f) // RMX1931 Balanced Dual TFA9894 Stereo
        2 -> floatArrayOf(3.8f, 1.6f, 0.2f, 1.8f, 2.5f) // Rich Bass / Cinema
        3 -> floatArrayOf(-1.8f, 0.8f, 3.0f, 3.2f, 1.0f) // Clear Voice / Podcast
        else -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (configs == null) return
            syncSessions(configs)
        }
    }

    fun initialize(context: Context) {
        val p = SamuraiHardwareBridge.prefs(context)
        currentPreset = p.getInt(SamuraiHardwareBridge.KEY_AUDIO_PRESET, 1)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (!callbackRegistered) {
            am.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            callbackRegistered = true
        }
        syncSessions(am.activePlaybackConfigurations)
    }

    fun setPreset(context: Context, preset: Int) {
        currentPreset = preset
        SamuraiHardwareBridge.prefs(context).edit()
            .putInt(SamuraiHardwareBridge.KEY_AUDIO_PRESET, preset)
            .apply()
        val am = context.getSystemService(AudioManager::class.java) ?: return
        synchronized(activeEffects) {
            if (preset == 0) {
                activeEffects.values.forEach { fx ->
                    try {
                        fx.enabled = false
                        fx.release()
                    } catch (_: Throwable) {
                    }
                }
                activeEffects.clear()
                return
            }
            activeEffects.values.forEach { fx ->
                applyGainsToEffect(fx, preset)
            }
        }
        syncSessions(am.activePlaybackConfigurations)
    }

    private fun syncSessions(configs: List<AudioPlaybackConfiguration>) {
        synchronized(activeEffects) {
            if (currentPreset == 0) return
            for (cfg in configs) {
                val usage = cfg.audioAttributes?.usage ?: continue
                if (usage != AudioAttributes.USAGE_MEDIA &&
                    usage != AudioAttributes.USAGE_GAME &&
                    usage != AudioAttributes.USAGE_UNKNOWN
                ) {
                    continue
                }
                // Reflectively obtain session ID if available, else attach global mix fallback safely
                val sessionId = try {
                    val m = AudioPlaybackConfiguration::class.java.getMethod("getAudioSessionId")
                    (m.invoke(cfg) as? Int) ?: 0
                } catch (_: Throwable) {
                    0
                }
                if (sessionId <= 0 || activeEffects.containsKey(sessionId)) continue
                attachEffectLocked(sessionId)
            }
        }
    }

    private fun attachEffectLocked(sessionId: Int) {
        try {
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, 5,
                false, 0,
                true, 5,
                true
            )
            val dpConfig = builder.build()
            val eq = DynamicsProcessing.Eq(true, true, 5)
            val gains = gainsForPreset(currentPreset)
            for (i in 0 until 5) {
                eq.getBand(i).apply {
                    isEnabled = true
                    cutoffFrequency = bandCutoffs[i]
                    gain = gains[i]
                }
            }
            dpConfig.setPreEqAllChannelsTo(eq)
            dpConfig.setPostEqAllChannelsTo(eq)

            val limiter = DynamicsProcessing.Limiter(
                true, true, 0,
                2.0f, 45.0f, 10.0f, -0.6f, 0.8f
            )
            dpConfig.setLimiterAllChannelsTo(limiter)

            val dp = DynamicsProcessing(0, sessionId, dpConfig)
            dp.enabled = true
            activeEffects[sessionId] = dp
        } catch (_: Throwable) {
        }
    }

    private fun applyGainsToEffect(dp: DynamicsProcessing, preset: Int) {
        try {
            val gains = gainsForPreset(preset)
            for (i in 0 until 5) {
                val band = DynamicsProcessing.EqBand(true, bandCutoffs[i], gains[i])
                dp.setPreEqBandAllChannelsTo(i, band)
                dp.setPostEqBandAllChannelsTo(i, band)
            }
            dp.enabled = preset != 0
        } catch (_: Throwable) {
        }
    }

    val isSpeakerCleanActive: Boolean
        get() = speakerCleanRunning

    fun startClearSpeakerPulse(onProgress: ((Int) -> Unit)?, onDone: (() -> Unit)?) {
        if (speakerCleanRunning) return
        speakerCleanRunning = true
        speakerCleanThread = Thread {
            val sampleRate = 48000
            val totalSeconds = 30
            val chunkSamples = sampleRate / 10 // 100ms chunks
            val buffer = ShortArray(chunkSamples)

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkSamples * 2)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Throwable) {
                speakerCleanRunning = false
                mainHandler.post { onDone?.invoke() }
                return@Thread
            }

            try {
                track.play()
                var phase = 0.0
                val totalChunks = totalSeconds * 10
                for (c in 0 until totalChunks) {
                    if (!speakerCleanRunning) break
                    val secRemaining = totalSeconds - (c / 10)
                    if (c % 10 == 0) {
                        mainHandler.post { onProgress?.invoke(secRemaining) }
                    }
                    // 165 Hz resonant acoustic wave + final 10s pulse burst modulation
                    val freq = if (c < 200) 165.0 else if ((c / 3) % 2 == 0) 168.0 else 158.0
                    val gate = if (c >= 220 && (c % 5 == 4)) 0.15 else 0.96
                    val step = 2.0 * PI * freq / sampleRate
                    for (i in 0 until chunkSamples) {
                        val s = sin(phase) * gate
                        buffer[i] = (s * 32000.0).toInt().toShort()
                        phase += step
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                    }
                    track.write(buffer, 0, chunkSamples)
                }
            } catch (_: Throwable) {
            } finally {
                speakerCleanRunning = false
                try {
                    track.stop()
                    track.release()
                } catch (_: Throwable) {
                }
                mainHandler.post { onDone?.invoke() }
            }
        }.apply {
            name = "SamuraiSpeakerEjector"
            start()
        }
    }

    fun stopClearSpeakerPulse() {
        speakerCleanRunning = false
    }
}
