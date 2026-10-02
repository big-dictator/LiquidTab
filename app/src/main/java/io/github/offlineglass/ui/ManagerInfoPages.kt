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

@Composable
internal fun ManagerHomeLeadingIcon(image: ImageVector, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(30.dp)
            .background(Color.Black, CircleShape),
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
        contentDescription = "作者赞赏码",
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White),
        contentScale = ContentScale.Fit,
    )
}
