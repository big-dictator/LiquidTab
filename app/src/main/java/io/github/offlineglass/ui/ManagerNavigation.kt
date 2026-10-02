package io.github.offlineglass.ui

import androidx.navigation3.runtime.NavKey

/** Navigation keys used by the manager's application list. */
internal sealed interface ManagerAppRoute : NavKey {
    data object List : ManagerAppRoute
    data class Detail(val targetKey: String) : ManagerAppRoute
}
