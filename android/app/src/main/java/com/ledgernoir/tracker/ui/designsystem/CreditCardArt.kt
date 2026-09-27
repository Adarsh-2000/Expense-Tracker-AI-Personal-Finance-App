package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bank → 3-stop gradient for the card face. Falls back to a teal gradient. */
private fun bankGradient(bank: String): List<Color> = when (bank.uppercase()) {
    "AXIS" -> listOf(Color(0xFFC43170), Color(0xFF7D1C46), Color(0xFF3F0F25))
    "IDFC" -> listOf(Color(0xFFB8232E), Color(0xFF7C151C), Color(0xFF400A0E))
    "BOB" -> listOf(Color(0xFFF26522), Color(0xFFB14413), Color(0xFF5E230A))
    else -> listOf(Color(0xFF2DD4BF), Color(0xFF0F766E), Color(0xFF053B35))
}

/**
 * A realistic credit-card face: bank gradient + sheen, the [BankLogo] chip and a
 * contactless glyph on top, an EMV chip + contactless waves in the middle, the
 * embossed masked number, and at the bottom an optional card-holder block plus a
 * per-card [NetworkMark]. No utilization bar lives on the face anymore — callers
 * render utilization below the card. Used on Bills and Rewards.
 *
 * Renders gracefully when [holderName] and/or [network] are null.
 */
@Composable
fun CreditCardArt(
    bank: String,
    last4: String?,
    modifier: Modifier = Modifier,
    holderName: String? = null,
    network: String? = null,      // "visa"|"mastercard"|"rupay" or null
    height: Dp = 200.dp,
) {
    val colors = bankGradient(bank)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radii.cardArt))
            .background(Brush.linearGradient(colors)),
    ) {
        // Subtle diagonal sheen of low-alpha white over the gradient.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
        )

        Column(Modifier.fillMaxSize().padding(18.dp)) {
            // Top: bank logo chip left, contactless glyph right.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BankLogo(bank = bank)
                Spacer(Modifier.weight(1f))
                ContactlessWaves(modifier = Modifier.size(width = 18.dp, height = 22.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Middle: EMV chip + contactless waves.
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmvChip()
                Spacer(Modifier.width(12.dp))
                ContactlessWaves(modifier = Modifier.size(width = 16.dp, height = 20.dp))
            }

            Spacer(Modifier.weight(1f))

            // Embossed masked number.
            Text(
                text = "•••• •••• •••• ${last4 ?: "----"}",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.35f),
                        offset = Offset(0f, 1f),
                        blurRadius = 2f,
                    ),
                ),
            )

            Spacer(Modifier.height(14.dp))

            // Bottom: holder block (optional) + network mark.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (holderName != null) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "CARD HOLDER",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            holderName.uppercase(),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                NetworkMark(network)
            }
        }
    }
}

/** Gold EMV chip: ~46x34dp rounded box with a gold gradient + faint inner border. */
@Composable
private fun EmvChip() {
    Box(
        Modifier
            .size(width = 46.dp, height = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFF7E08A), Color(0xFFCAA23C), Color(0xFF9C7A25)),
                ),
            )
            .border(0.7.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
    ) {
        // Faint inner contact line.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.7f)
                .height(0.7.dp)
                .background(Color(0xFF7A5E18).copy(alpha = 0.5f)),
        )
    }
}

/** Three white arc strokes evoking the NFC contactless symbol. */
@Composable
private fun ContactlessWaves(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        val cy = size.height / 2f
        val cx = -size.width * 0.4f
        for (i in 1..3) {
            val d = size.width * (0.55f * i)
            drawArc(
                color = Color.White.copy(alpha = 0.85f),
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - d, cy - d),
                size = Size(d * 2, d * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
    }
}

/** Per-card payment-network mark. Renders nothing when [network] is unknown/null. */
@Composable
private fun NetworkMark(network: String?) {
    when (network?.lowercase()) {
        "visa" -> Text(
            "VISA",
            color = Color.White,
            fontSize = 20.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        )
        "rupay" -> Row(verticalAlignment = Alignment.Bottom) {
            Text("Ru", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Pay", color = Color(0xFF5FD38D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        "mastercard" -> Canvas(modifier = Modifier.size(width = 34.dp, height = 22.dp)) {
            val r = size.height / 2f
            val cy = size.height / 2f
            // Red circle (right).
            drawCircle(
                color = Color(0xFFEB001B),
                radius = r,
                center = Offset(size.width - r, cy),
            )
            // Amber circle (left), offset and semi-transparent for the overlap.
            drawCircle(
                color = Color(0xFFF79E1B).copy(alpha = 0.85f),
                radius = r,
                center = Offset(r, cy),
            )
        }
        else -> Spacer(Modifier.size(0.dp))
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun CreditCardArtPreview() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CreditCardArt(bank = "AXIS", last4 = "6978", holderName = "Harsh K", network = "visa",
            modifier = Modifier.width(300.dp))
        CreditCardArt(bank = "IDFC", last4 = "2198", network = "rupay",
            modifier = Modifier.width(300.dp))
        CreditCardArt(bank = "HDFC", last4 = "4410", holderName = "Harsh K", network = "mastercard",
            modifier = Modifier.width(300.dp))
        CreditCardArt(bank = "BOB", last4 = "0001", modifier = Modifier.width(300.dp))
    }
}
