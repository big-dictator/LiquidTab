package io.github.offlineglass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import io.github.offlineglass.ui.ManagerMaterialText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.offlineglass.R

@Composable
internal fun AuthorInfoPage(padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
        bottom = padding.calculateBottomPadding() + 20.dp)) {
        item { AuthorInfoContent() }
    }
}

@Composable
internal fun AuthorInfoContent(miuix: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileInfoCard("bigdictator", "LiquidTab 作者，欢迎在酷安关注项目动态。",
            R.drawable.module_author, AUTHOR_COOLAPK_URL, miuix)
        ProjectInfoCard(miuix)
    }
}

@Composable
private fun ProjectInfoCard(miuix: Boolean) {
    val uriHandler = LocalUriHandler.current
    InfoLinkCard(miuix, "LiquidTab · GitHub",
        "项目源码、版本更新与问题反馈，点击访问 GitHub 项目主页。",
        { uriHandler.openUri(PROJECT_GITHUB_URL) })
}

internal const val PROJECT_GITHUB_URL = "https://github.com/big-dictator/LiquidTab"

/** Use the same preference layout and trailing arrow as the other home cards. */
@Composable
internal fun InfoLinkCard(
    miuix: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    if (miuix) {
        ManagerStyleCard(Modifier.fillMaxWidth()) {
            ManagerArrowPreference(title = title, summary = summary,
                startAction = leading, onClick = onClick)
        }
    } else {
        ManagerMaterialCard(modifier = Modifier.fillMaxWidth(), onClick = onClick,
            shape = MaterialTheme.shapes.extraLarge) {
            androidx.compose.material3.ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(summary) },
                leadingContent = leading,
                trailingContent = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.ChevronRight, null)
                },
            )
        }
    }
}
