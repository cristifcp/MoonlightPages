package com.moonlightpages.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Floating reading controls: no bars, so the page keeps as much of the screen as possible. */

/** The controls fade in and out of the page rather than flashing on top of it. */
internal const val CONTROL_FADE_MILLIS = 150

@Composable
internal fun ReaderHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
internal fun ReaderCircleButton(
    icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp, modifier = Modifier.size(44.dp)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(painterResource(icon), contentDescription = description,
                modifier = Modifier.size(22.dp).alpha(if (enabled) 1f else .35f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReaderPill(
    onContents: () -> Unit, onAppearance: () -> Unit, onBookmarks: () -> Unit, onSearch: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PillAction(R.drawable.ic_contents, "Contents", onContents)
            PillTextAction(onAppearance)
            PillAction(R.drawable.ic_bookmarks, "Bookmarks", onBookmarks)
            PillAction(R.drawable.ic_search, "Search in book", onSearch)
        }
    }
}

@Composable
private fun PillAction(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(23.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PillTextAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).semantics {
        contentDescription = "Reading appearance"
    }) {
        Text("Aa", fontFamily = FontFamily.Serif, fontSize = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
