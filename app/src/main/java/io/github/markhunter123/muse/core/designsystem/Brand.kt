package io.github.markhunter123.muse.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.markhunter123.muse.R

/** The Muse brand mark (the launcher's "Mu" glyph), drawn in the current text color. The launcher
 *  PNG bakes in the adaptive-icon safe-zone padding, so it is drawn oversized to match the old
 *  whale's visual weight. (Named Whale for its dsh lineage; kept so all call sites stay put.) */
@Composable
fun Whale(modifier: Modifier = Modifier, width: Dp = 34.dp, tint: Color = Dsh.colors.labelPrimary) =
    Icon(painterResource(R.drawable.ic_launcher_fg), null, modifier.size(width * 1.35f), tint = tint)

/**
 * Running indicator mark: the Mu glyph "breathes" (gentle scale + fade, 1.6 s loop) — alive and
 * thinking, not swimming. Static when reduced motion is on.
 */
@Composable
fun BreathingMu(width: Dp = 14.dp, tint: Color = Dsh.colors.deepDiving) {
    if (LocalReducedMotion.current) { Whale(width = width, tint = tint); return }
    val t = rememberInfiniteTransition(label = "mu")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    Whale(Modifier.wrapContentSize().graphicsLayer {
        scaleX = 1f + breath * 0.07f; scaleY = scaleX
        alpha = 0.72f + breath * 0.28f
    }, width, tint)
}

/** Sidebar brand row: mark + wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Whale(width = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text("Muse", style = DshType.l20.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight(600), letterSpacing = (-0.2).sp), color = Dsh.colors.labelPrimary)
    }
}
