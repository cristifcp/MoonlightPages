package com.moonlightpages.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How the page is set: how big the words are, what they are printed on, how far apart the lines sit,
 * how much room the page keeps at its edges, and the face it is written in. Plus night dimming,
 * which is not part of the page at all — it takes the light out of the screen once the page is
 * drawn, below what the phone's own brightness setting offers, which is the whole point of it in a
 * dark room. It dims by taking the screen's brightness down rather than by laying a film over the
 * page: a film would end up inside the copy a page turn holds, and the page being left would be
 * dimmed twice over while the page arriving was dimmed once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(
    fontSize: Int, paperIndex: Int, spacingIndex: Int, insetIndex: Int, fontIndex: Int, dim: Int,
    onSize: (Int) -> Unit, onPaper: (Int) -> Unit, onSpacing: (Int) -> Unit, onInset: (Int) -> Unit,
    onFont: (Int) -> Unit, onDim: (Int) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reading appearance", style = MaterialTheme.typography.headlineSmall)
                Text("Make the page comfortable. Your place stays with the text.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text size", Modifier.weight(1f))
                    OutlinedButton(onClick = { onSize((fontSize - 2).coerceAtLeast(14)) }, enabled = fontSize > 14,
                        modifier = Modifier.semantics { contentDescription = "Smaller text" }) { Text("A−") }
                    Text("$fontSize", Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(onClick = { onSize((fontSize + 2).coerceAtMost(32)) }, enabled = fontSize < 32,
                        modifier = Modifier.semantics { contentDescription = "Larger text" }) { Text("A+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    papers.forEachIndexed { index, paper ->
                        FilterChip(selected = paperIndex == index, onClick = { onPaper(index) },
                            label = { Text(paper.name) }, leadingIcon = {
                                Box(Modifier.size(12.dp).clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(paper.background))))
                            })
                    }
                }
                ChipRow("Line spacing", ReadingTypography.spacingNames, spacingIndex, onSpacing)
                ChipRow("Margins", ReadingTypography.insetNames, insetIndex, onInset)
                ChipRow("Typeface", ReadingTypography.fontNames, fontIndex, onFont)
                ChipRow("Night dimming", nightDimNames,
                    nightDimSteps.indexOf(dim).takeIf { it >= 0 } ?: 0) { onDim(nightDimSteps[it]) }
                Text("Night dimming takes the phone's own brightness down, below the setting the phone " +
                    "offers, for reading in the dark. The page, the controls and everything else dim " +
                    "together, so nothing glares.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            // Always within reach, however many choices the sheet is holding.
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp)) { Text("Done") }
        }
    }
}

/**
 * One line of choices, named, with the one in force picked out. The chips wrap rather than squeeze:
 * five choices with long names do not fit across a phone, and a chip cut in half is no choice at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, names: List<String>, selected: Int, onChoose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { index, name ->
                FilterChip(selected = selected == index, onClick = { onChoose(index) }, label = { Text(name) })
            }
        }
    }
}
