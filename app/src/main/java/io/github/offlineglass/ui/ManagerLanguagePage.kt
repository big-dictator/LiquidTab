package io.github.offlineglass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.offlineglass.config.ManagerLanguage
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode

@Composable
internal fun ManagerLanguagePage(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val content: @Composable ColumnScope.() -> Unit = {
                for (language in ManagerLanguage.entries) {
                    // Exactly one language stays enabled; turning on one switches off the other.
                    val label = if (language == ManagerLanguage.CHINESE) "中文 / Chinese" else "English / 英文"
                    val otherLanguage = ManagerLanguage.entries.first { it != language }
                    val change: (Boolean) -> Unit = { enabled ->
                        // A radio-style pair of switches: turning either one off
                        // moves selection to the other instead of leaving both off.
                        actions.setLanguage(if (enabled) language else otherLanguage)
                    }
                    if (settings.uiMode == UiMode.MATERIAL) {
                        androidx.compose.material3.ListItem(
                            headlineContent = { ManagerMaterialText(label) },
                            trailingContent = { ManagerFeedbackMaterialSwitch(settings.language == language, change) },
                        )
                    } else {
                        ManagerStyleSwitchPreference(title = label, checked = settings.language == language,
                            onCheckedChange = change)
                    }
                }
            }
            if (settings.uiMode == UiMode.MATERIAL) ManagerMaterialCard(Modifier.fillMaxWidth(), content = content)
            else ManagerStyleCard(Modifier.fillMaxWidth(), content = content)
        }
    }
}
