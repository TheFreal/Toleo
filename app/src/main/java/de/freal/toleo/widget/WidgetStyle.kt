package de.freal.toleo.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The widget's background, picked when it is placed (and again via the launcher's "Customize").
 * [THEMED] and [COVER] take their colours from the current book's cover and follow light and dark
 * mode; without a cover to theme from they look like [TRANSLUCENT] and [SOLID].
 */
enum class WidgetStyle(val label: String, val description: String) {
    SOLID("Solid", "A card in your wallpaper colours"),
    TRANSLUCENT("Translucent", "The solid card, but see-through"),
    THEMED("Translucent themed", "See-through, in a colour from the book's cover"),
    COVER("Cover", "The book's cover, blurred, behind the text"),
    DARK("Translucent black", "Dark glass with white text"),
    LIGHT("Translucent white", "Frosted white with dark text"),
    TRANSPARENT("Transparent", "White text with a shadow, straight on the wallpaper"),
    ;

    companion object {
        /** Per-widget Glance state; widgets placed before the picker existed read as [SOLID]. */
        val KEY = stringPreferencesKey("style")

        fun from(name: String?): WidgetStyle = entries.firstOrNull { it.name == name } ?: SOLID
    }
}

/** Fixed colours of the non-solid styles, shared by the widget and the picker's previews. */
internal object WidgetInk {
    val DarkScrim = Color(0x8C000000)
    val LightScrim = Color(0xC7FFFFFF)
    val White = Color.White
    val WhiteSecondary = Color(0xD9FFFFFF)
    val WhiteTrack = Color(0x4DFFFFFF)
    val Ink = Color(0xFF1F1B16)
    val InkSecondary = Color(0xFF51463C)
    val InkTrack = Color(0x1F000000)
    val Accent = Color(0xFF8A4B2A)
}
