package com.example.easybooks.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.example.easybooks.R
import kotlin.random.Random

/**
 * Spielt das Umblätter-Geräusch. Die kurzen Sounds werden beim Öffnen des Readers
 * vorgeladen, damit sie auch bei schnellem Blättern ohne Verzögerung starten.
 *
 * Im Lautlos- und Vibrationsmodus bleibt es still; die Lautstärke folgt den
 * System-Tönen des Handys.
 */
class PageTurnSound(context: Context) : AutoCloseable {

    private val audio = context.getSystemService(AudioManager::class.java)

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val pageSounds = listOf(R.raw.page_turn_1, R.raw.page_turn_2, R.raw.page_turn_3)
        .map { pool.load(context, it, 1) }
    private val slideSound = pool.load(context, R.raw.page_slide, 1)
    private val coverSound = pool.load(context, R.raw.cover_open, 1)
    private var lastIndex = -1

    /** @param bookPages true für "Buchseiten" (volles Rascheln), false für "Slideshow". */
    fun play(bookPages: Boolean) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val sound = if (bookPages) {
            // Nie zweimal hintereinander dieselbe Variante.
            var index = Random.nextInt(pageSounds.size)
            if (index == lastIndex) index = (index + 1) % pageSounds.size
            lastIndex = index
            pageSounds[index]
        } else {
            slideSound
        }
        // Leicht schwankende Tonhöhe, damit es natürlicher klingt.
        val rate = Random.nextDouble(0.94, 1.06).toFloat()
        pool.play(sound, 1f, 1f, 1, 0, rate)
    }

    /** Ein fester Buchdeckel wird auf- oder zugeklappt (Ansicht "Altes Buch"). */
    fun playCover() {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(coverSound, 1f, 1f, 1, 0, Random.nextDouble(0.97, 1.03).toFloat())
    }

    override fun close() {
        pool.release()
    }
}
