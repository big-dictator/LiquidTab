package io.github.offlineglass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import io.github.offlineglass.config.ManagerLanguage

internal val LocalManagerLanguage = staticCompositionLocalOf { ManagerLanguage.CHINESE }

@Composable
internal fun managerText(text: String): String =
    if (LocalManagerLanguage.current == ManagerLanguage.ENGLISH) ManagerTranslations.english(text) else text

/** Localize at the display boundary, including dynamic labels and remembered messages. */
@Composable
internal fun ManagerMaterialText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    androidx.compose.material3.Text(managerText(text), modifier = modifier, color = color,
        fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign, lineHeight = lineHeight,
        overflow = overflow, softWrap = softWrap, maxLines = maxLines, style = style)
}

@Composable
internal fun ManagerMiuixText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1,
) {
    top.yukonga.miuix.kmp.basic.Text(managerText(text), modifier = modifier, color = color,
        fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign, lineHeight = lineHeight,
        overflow = overflow, softWrap = softWrap, maxLines = maxLines, style = style)
}

@Composable
internal fun ManagerArrowPreference(
    title: String,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    // Keep the upstream layout, interaction and sizing unchanged.
    if (endActions == null) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(title = managerText(title),
            summary = summary?.let { managerText(it) }, startAction = startAction, onClick = onClick)
    } else {
        top.yukonga.miuix.kmp.preference.ArrowPreference(title = managerText(title),
            summary = summary?.let { managerText(it) }, startAction = startAction,
            endActions = endActions, onClick = onClick)
    }
}
