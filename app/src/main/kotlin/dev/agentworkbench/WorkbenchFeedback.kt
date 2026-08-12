package dev.agentworkbench

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.sin

/**
 * Som só em momentos-chave — enviar, missão concluída, erro, abrir/fechar histórico — nunca em
 * toque comum ou navegação. Nada de arquivo de áudio: cada evento é um acorde curto sintetizado
 * uma vez em PCM 16-bit, em escala pentatônica, então nenhuma combinação de notas soa "errada".
 * Silenciado automaticamente pelo modo silencioso/vibrar do aparelho e por um interruptor
 * próprio guardado em [ProviderSettingsRepository]-style prefs simples.
 */
object WorkbenchFeedback {
    private const val SAMPLE_RATE = 22_050
    private const val PREFS_NAME = "workbench_feedback"
    private const val PREF_ENABLED = "sound_enabled"

    // Escala pentatônica maior em torno de A4 — qualquer subconjunto dessas notas soa consonante.
    private const val A4 = 440.0
    private const val B4 = 493.88
    private const val CS5 = 554.37
    private const val E5 = 659.25
    private const val FS5 = 739.99

    private val send by lazy { chord(listOf(A4 to 0.0, CS5 to 0.06), 0.14) }
    private val completed by lazy { chord(listOf(A4 to 0.0, CS5 to 0.07, FS5 to 0.14), 0.24) }
    private val error by lazy { chord(listOf(B4 to 0.0, A4 to 0.09), 0.20) }
    private val drawer by lazy { chord(listOf(E5 to 0.0), 0.05) }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
    }

    private fun canPlay(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val manager = context.getSystemService(AudioManager::class.java) ?: return true
        return manager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    /** Toque no botão de enviar do Composer. Nunca chamar em continuações internas do agente. */
    fun onSend(context: Context) = play(context, send)

    /** Turno terminou com sucesso (status COMPLETE, não erro/cancelado). */
    fun onCompleted(context: Context) = play(context, completed)

    /** Turno terminou em erro real — não confundir com "aguardando rede" do Lote Z. */
    fun onError(context: Context) = play(context, error)

    /** Histórico abrindo ou fechando, tanto por toque quanto por arrasto. */
    fun onDrawerToggle(context: Context) = play(context, drawer)

    private fun play(context: Context, samples: ShortArray) {
        if (!canPlay(context)) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) = t.release()
                    override fun onPeriodicNotification(t: AudioTrack) = Unit
                },
            )
            track.play()
        }
    }

    /** Soma senoides com onset escalonado (`offsetSeconds`) e um envelope curto pra não estalar. */
    private fun chord(notes: List<Pair<Double, Double>>, durationSeconds: Double): ShortArray {
        val totalSamples = (durationSeconds * SAMPLE_RATE).toInt()
        val out = ShortArray(totalSamples)
        val attack = (0.01 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val release = (0.08 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for ((freq, offset) in notes) {
                if (t < offset) continue
                val noteT = t - offset
                val noteSamples = totalSamples - (offset * SAMPLE_RATE).toInt()
                val noteI = i - (offset * SAMPLE_RATE).toInt()
                val envelope = when {
                    noteI < attack -> noteI.toDouble() / attack
                    noteI > noteSamples - release -> (noteSamples - noteI).toDouble() / release
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                value += sin(2 * PI * freq * noteT) * envelope
            }
            val normalized = (value / notes.size) * 0.5
            out[i] = (normalized * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
