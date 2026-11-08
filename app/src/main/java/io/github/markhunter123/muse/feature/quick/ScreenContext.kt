package io.github.markhunter123.muse.feature.quick

import android.graphics.Bitmap

/** A screenshot taken at summon time (floating-ball double tap), handed to the panel's composer once. */
object ScreenContext {
    @Volatile private var shot: Bitmap? = null

    fun attachShot(bmp: Bitmap?) { shot = bmp }
    fun takeShot(): Bitmap? = shot.also { shot = null }
    fun clear() { shot = null }
}
