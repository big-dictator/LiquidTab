package io.github.offlineglass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.offlineglass.R

internal const val AUTHOR_COOLAPK_URL = "https://www.coolapk.com/u/24915298"

// KernelSU HomeMiuix status palette; used only for the manager home card.
internal val ManagerStatusIconGreen = Color(0xFF36D167)

@Composable
internal fun managerStatusCardColor(): Color =
    if (LocalIosDark.current) Color(0xFF1A3825) else Color(0xFFDFFAE4)

/** iOS-style semantic system colors, with brighter variants on dark surfaces. */
internal enum class HomeIconTone(val light: Color, val dark: Color) {
    BLUE(Color(0xFF0088FF), Color(0xFF0091FF)),
    ORANGE(Color(0xFFFF9500), Color(0xFFFF9F0A)),
    PINK(Color(0xFFFF2D55), Color(0xFFFF375F)),
    PURPLE(Color(0xFFAF52DE), Color(0xFFBF5AF2)),
    TEAL(Color(0xFF00AFA8), Color(0xFF40C8E0)),
}

@Composable
internal fun ManagerHomeLeadingIcon(image: ImageVector, modifier: Modifier = Modifier, tone: HomeIconTone = HomeIconTone.BLUE) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(30.dp)
            .background(if (LocalIosDark.current) tone.dark else tone.light, CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.5.dp),
        )
    }
}

@Composable
internal fun DonationQrImage(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.donation_qr),
        contentDescription = managerText("作者赞赏码"),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White),
        contentScale = ContentScale.Fit,
    )
}
