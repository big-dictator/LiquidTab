package io.github.offlineglass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.offlineglass.R

@Composable
internal fun TopBarLayoutContributorCard(miuix: Boolean = false) = ProfileInfoCard(
    "小郑大王", "感谢提供顶栏布局创意。", R.drawable.topbar_layout_contributor,
    "https://www.coolapk.com/u/2242350", miuix,
)

@Composable
internal fun ProfileInfoCard(title: String, summary: String, avatar: Int, url: String, miuix: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val avatarShape = BottomBarSquircleShape(CornerSize(10.dp), LocalIosSmoothing.current)
    val localizedSummary = managerText(summary) + " " + managerText("点击查看酷安主页。")
    InfoLinkCard(miuix, title, localizedSummary, { uriHandler.openUri(url) }) {
        Image(
            painter = painterResource(avatar),
            contentDescription = managerText("$title 的头像"),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(avatarShape),
        )
    }
}
