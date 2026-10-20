package com.moonlightpages.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Marks a passage, and lets the reader write down why it matters. The note travels with the
 * highlight, so it is found again from the notebook and from the marked page itself.
 *
 * It rises from the bottom of the page like the search sheet. The writing sits last, so the
 * keyboard only ever covers the empty room below the buttons rather than the buttons themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightEditor(
    passage: SelectedPassage, existing: BookHighlight?, saving: Boolean, error: String?,
    onSave: (HighlightColor, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    // The note belongs to one highlight, so opening another one starts from that one's words.
    var note by remember(passage, existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    val written = note.trim()
    val changed = written != existing?.note.orEmpty()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) "Highlight passage" else "Edit highlight", Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close the marker",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(passageChapter(passage), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(passage.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (existing == null) "Choose a colour to keep this passage, and write down why if you like."
                else "Change the colour, or say more about this passage.",
                style = MaterialTheme.typography.bodySmall)
            HighlightColor.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    row.forEach { color ->
                        FilledTonalButton(onClick = { onSave(color, written) }, enabled = !saving,
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(android.graphics.Color.parseColor(color.hex)).copy(alpha = .3f))) {
                            Box(Modifier.size(12.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(color.hex))))
                            Spacer(Modifier.width(6.dp))
                            Text(color.label)
                        }
                    }
                }
            }
            if (existing != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDelete, enabled = !saving) {
                        Text("Delete highlight", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSave(existing.color, written) }, enabled = !saving && changed) {
                        Text(if (written.isEmpty()) "Remove note" else "Save note")
                    }
                }
            }
            OutlinedTextField(value = note, onValueChange = { note = it.take(2000) }, enabled = !saving,
                label = { Text("Your note") },
                placeholder = { Text("Why this passage matters") },
                trailingIcon = {
                    if (note.isNotEmpty()) IconButton(onClick = { note = "" }, enabled = !saving) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear note")
                    }
                }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().navigationBarsPadding())
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
