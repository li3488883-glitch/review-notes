package app.reviewnotes.timing.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The palette: a black stage, red for up, green for down (A 股习惯). */
object Ink {
    val Stage = Color(0xFF000000)
    val Panel = Color(0xFF0B0B0E)
    val PanelHi = Color(0x14FFFFFF)       // panel top tint
    val Hairline = Color(0x14FFFFFF)      // 8% white
    val HairlineStrong = Color(0x29FFFFFF)
    val Text = Color(0xFFF5F5F7)
    val Muted = Color(0xFFA1A1AA)
    val Faint = Color(0xFF63636E)
    val Up = Color(0xFFFF4D55)
    val Down = Color(0xFF30D17F)
    val Accent = Color(0xFF8EA4FF)
    val Amber = Color(0xFFFFB340)
    val Teal = Color(0xFF4EE0D0)
    val Violet = Color(0xFFB49CFF)
    val CoolLight = Color(0xFF465AFF)
    val Pill = Color(0xFFF5F5F7)          // selected pill
    val OnPill = Color(0xFF000000)
}

/** Color for a signed change: red up, green down, muted flat or unknown. */
fun changeColor(x: Double): Color = when {
    x.isNaN() -> Ink.Muted
    x > 0 -> Ink.Up
    x < 0 -> Ink.Down
    else -> Ink.Muted
}

object Type {
    private val sys = FontFamily.Default
    val Verdict = TextStyle(fontFamily = sys, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontFamily = sys, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp)
    val Heading = TextStyle(fontFamily = sys, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val Small = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val Caption = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val Num = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 15.sp, fontFeatureSettings = "tnum")
    val NumBig = TextStyle(fontFamily = sys, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, fontFeatureSettings = "tnum")
}

/** The verdict color drives the one big light on the stage. */
val LocalGlow = staticCompositionLocalOf { Ink.CoolLight }

@Composable
fun AppTheme(glow: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlow provides glow, content = content)
}
