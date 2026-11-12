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
    private fun grotesk(weight: Int, as_: FontWeight = FontWeight(weight)) = Font(
        R.font.space_grotesk, as_, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    /** Titles and names: Space Grotesk a step heavier than the text around it. */
    val Display = FontFamily(grotesk(500, FontWeight.Normal), grotesk(650, FontWeight.Medium))
    val Sans = FontFamily(grotesk(400, FontWeight.Normal), grotesk(500, FontWeight.Medium), grotesk(600, FontWeight.SemiBold))
}
