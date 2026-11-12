package ca.heeney.doppler

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Black like the space between stars; the two playlists are a redshift and a blueshift. */
object Palette {
    val Void = Color(0xFF000000)
    val Starlight = Color(0xFFE9E4FF)
    val Dust = Color(0xFF8E8AA8)
    val Faint = Color(0xFF4A4660)
    val Hairline = Color(0xFF1E1B2B)
    val Red = Color(0xFFFF5A3C)
    val Blue = Color(0xFF5B8CFF)
    val Panel = Color(0xFF0B0A12)

    fun slot(slot: String) = if (slot == "2") Blue else Red
}

@OptIn(ExperimentalTextApi::class)
object Type {
    private fun fraunces(weight: Int, opsz: Float, soft: Float, wonk: Float) = Font(
        R.font.fraunces, FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight), FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("SOFT", soft), FontVariation.Setting("WONK", wonk)))

    /** The numerals: Fraunces at its largest optical size, soft and a little wonky. */
    val Numeral = FontFamily(fraunces(300, 144f, 100f, 1f))
    /** Titles and names. */
    val Display = FontFamily(fraunces(380, 48f, 60f, 0f), fraunces(560, 48f, 60f, 0f))
    val Sans = FontFamily(
        Font(R.font.schibsted, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.schibsted, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(540))),
        Font(R.font.schibsted, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(640))),
    )
}
