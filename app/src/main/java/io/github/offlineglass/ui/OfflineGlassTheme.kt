package io.github.offlineglass.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineGlassTheme(settings: ManagerSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.colorMode) {
        ColorMode.SYSTEM -> systemDark
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

    LaunchedEffect(dark) {
        val controller = (context as? Activity)?.window?.insetsController ?: return@LaunchedEffect
        val lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(if (dark) 0 else lightFlags, lightFlags)
    }

    when (settings.uiMode) {
        UiMode.MATERIAL -> {
            val scheme = if (settings.dynamicColor) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) {
                darkColorScheme(primary = Color(0xFFAFC6FF), secondary = Color(0xFFC0C6DD))
            } else {
                lightColorScheme(primary = Color(0xFF2F5FA7), secondary = Color(0xFF52627B))
            }
            MaterialExpressiveTheme(
                colorScheme = scheme,
                motionScheme = MotionScheme.expressive(),
                content = content,
            )
        }

        UiMode.MIUIX -> {
            val mode = when (settings.colorMode) {
                ColorMode.SYSTEM -> if (settings.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
                ColorMode.LIGHT -> if (settings.dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
                ColorMode.DARK -> if (settings.dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
            }
            val controller = ThemeController(mode, isDark = dark)
            MiuixTheme(controller = controller, content = content)
        }
    }
}
