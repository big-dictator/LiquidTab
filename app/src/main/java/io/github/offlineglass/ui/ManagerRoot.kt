package io.github.offlineglass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode

@Composable
fun ManagerRoot(settings: ManagerSettings, actions: UiActions) {
    var selectedPageName by rememberSaveable { mutableStateOf(ManagerPage.HOME.name) }
    val selectedPage = ManagerPage.entries.firstOrNull { it.name == selectedPageName } ?: ManagerPage.HOME
    val onSelected: (ManagerPage) -> Unit = { selectedPageName = it.name }

    when (settings.uiMode) {
        UiMode.MATERIAL -> MaterialManager(settings, selectedPage, onSelected, actions)
        UiMode.MIUIX -> MiuixManager(settings, selectedPage, onSelected, actions)
    }
}
