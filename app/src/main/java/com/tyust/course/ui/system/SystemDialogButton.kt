package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Visible action surface, including when a dialog has no glass sampling source. */
@Composable
fun SystemDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    LiquidButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 64.dp),
        enabled = enabled,
        style = if (primary || destructive) LiquidButtonStyle.SolidTinted else LiquidButtonStyle.SolidSurface,
        tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        minHeight = 48.dp
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
