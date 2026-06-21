package com.portalhacks.frame

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Portal design tokens for the Compose UI — mirrors the view-based {@link Ui}
 * palette (dark theme, platform blue, warm neutrals) so the migrated screens look
 * identical. Inter is loaded from assets (no GMS downloadable-font provider).
 */
object PortalColors {
    val Blue = Color(0xFF0A84FF)       // iOS System Blue
    val BluePressed = Color(0xFF0056B3)
    val Green = Color(0xFF30D158)      // iOS System Green
    val Red = Color(0xFFFF453A)        // iOS System Red
    val Bg = Color(0xFF000000)         // iOS Pure Black (OLED contrast)
    val Surface = Color(0xFF1C1C1E)    // iOS Elevated Dark background (cards)
    val Field = Color(0xFF2C2C2E)      // iOS secondary group background (fields/items)
    val OnPrimary = Color(0xFFFFFFFF)
    val Text = Color(0xFFFFFFFF)
    val TextBody = Color(0xFFE5E5EA)
    val TextMuted = Color(0xFF8E8E93)
    val Hairline = Color(0xFF24242A)   // iOS subtle separator line
}

/** Inter font family loaded from bundled assets (cached). */
object PortalFont {
    @Volatile private var family: FontFamily? = null

    fun inter(context: Context): FontFamily {
        family?.let { return it }
        val am = context.assets
        val f = try {
            FontFamily(
                Font("fonts/inter_regular.ttf", am, weight = FontWeight.Normal),
                Font("fonts/inter_medium.ttf", am, weight = FontWeight.Medium),
                Font("fonts/inter_bold.ttf", am, weight = FontWeight.Bold),
            )
        } catch (t: Throwable) {
            FontFamily.SansSerif
        }
        family = f
        return f
    }
}

@Composable
fun rememberInterTypography(context: Context): Typography {
    val inter = PortalFont.inter(context)
    val base = Typography()
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
    )
}
