package com.moonlightpages.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three rooms of the app, in the order the bar shows them. */
internal enum class ShelfTab(val label: String, val icon: Int) {
    Notebook("Notebook", R.drawable.ic_notebook),
    Library("Library", R.drawable.ic_library),
    Settings("Settings", R.drawable.ic_settings),
}

/** How much of the screen's foot the bar stands over, so content can keep out from under it. */
internal val ShelfBarHeight = 78.dp

/**
 * The bar along the foot of the app. It carries no slab of its own colour: whatever is behind it —
 * the shelf's sheet, the notebook's page — shows through, so the bar reads as part of the screen
 * rather than as a lid on it. The chosen room puts a soft ring round its mark and brightens its name.
 */
@Composable
internal fun ShelfBar(current: ShelfTab, onSelect: (ShelfTab) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
        .height(ShelfBarHeight).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ShelfTab.entries.forEach { tab ->
            val active = tab == current
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .clickable { onSelect(tab) }
                .semantics {
                    role = Role.Tab
                    contentDescription = tab.label
                    selected = active
                }
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 58.dp, height = 30.dp).clip(RoundedCornerShape(50))
                    .background(if (active) colours.primary.copy(alpha = .16f) else Color.Transparent),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(tab.icon), contentDescription = null,
                        tint = if (active) colours.primary else colours.onSurfaceVariant,
                        modifier = Modifier.size(22.dp))
                }
                Text(tab.label, style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp, letterSpacing = .3.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) colours.onSurface else colours.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * The sheet the shelf and the notebook are written on: a single pale leaf that runs to the foot of
 * the screen rather than a card that ends, so the bar may stand over it.
 */
@Composable
internal fun ShelfSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(modifier.fillMaxSize().clip(shape).background(colours.surfaceVariant.copy(alpha = .85f))
        .border(1.dp, colours.outlineVariant.copy(alpha = .45f), shape)) {
        content()
    }
}
