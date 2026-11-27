package com.rommmobile.app.core.input

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the button printed "A" physically sits. The button map is by label (the recorder asks
 * for "the button marked A"), but the on-screen glyphs are a diamond that lights a position, so
 * they have to know which corner that label is in.
 */
enum class FaceLayout {
    /** A south, B east, X west, Y north: Xbox, Ayaneo, most PC pads. */
    XBOX,

    /** A east, B south, X north, Y west: Nintendo, and many Android handhelds (AYN Thor...). */
    NINTENDO;

    companion object {
        fun parse(s: String?): FaceLayout? = s?.let { runCatching { valueOf(it) }.getOrNull() }

        /**
         * Best guess when the user has not said: handheld makers known to print A on the right.
         * Observed on the AYN Thor ("Odin Controller"); Retroid and Anbernic are assumed.
         */
        fun guess(padName: String? = ButtonMap.detect()?.first): FaceLayout {
            val device = "${Build.MANUFACTURER} ${Build.MODEL} ${padName.orEmpty()}".lowercase()
            val nintendoStyle = listOf("ayn", "odin", "thor", "retroid", "anbernic")
            return if (nintendoStyle.any { it in device }) NINTENDO else XBOX
        }
    }
}

/** The layout in force, read by every glyph; set from the preference at start-up and on change. */
object PadLayout {
    var face: FaceLayout by mutableStateOf(FaceLayout.XBOX)
}
