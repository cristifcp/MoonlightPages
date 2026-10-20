package com.moonlightpages.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * App-wide preferences. Text size and paper stay in the reader's Aa sheet, where they can be judged
 * against the page they change.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsScreen(
    store: EpubStore,
    onBack: () -> Unit,
    backingUp: Boolean = false,
    restoring: Boolean = false,
    darkTheme: Boolean = false,
    onDarkTheme: (Boolean) -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    var swipeToTurn by remember { mutableStateOf(store.swipeToTurn()) }
    var tapToTurn by remember { mutableStateOf(store.tapToTurn()) }
    var keepAwake by remember { mutableStateOf(store.keepAwake()) }
    var dark by remember { mutableStateOf(darkTheme) }
    var confirmingRestore by remember { mutableStateOf(false) }
    val busy = backingUp || restoring
    // The two ways of turning a page are exclusive, and one of them always stays on.
    val chooseSwipe: (Boolean) -> Unit = { enabled ->
        swipeToTurn = enabled
        tapToTurn = !enabled
        store.saveSwipeToTurn(enabled)
    }
    val context = LocalContext.current
    val version = remember(context) { packageVersion(context) }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to library")
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 8.dp)) {
            SectionLabel("Turning pages")
            SectionNote("Pick one. Switching one on switches the other off, so a swipe and an edge " +
                "tap never compete for the same touch.")
            SettingSwitch(
                title = "Swipe between pages",
                detail = "Swipe left or right, and the page turns over the way it does when tapped.",
                checked = swipeToTurn,
            ) { enabled -> if (enabled || tapToTurn) chooseSwipe(enabled) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingSwitch(
                title = "Tap the screen edges",
                detail = "Tapping the left or right edge turns the page. Links and highlights keep " +
                    "their own taps.",
                checked = tapToTurn,
            ) { enabled -> if (enabled || swipeToTurn) chooseSwipe(!enabled) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Appearance")
            SettingSwitch(
                title = "Dark theme",
                detail = "Night colours for the shelf and these settings. The reading page keeps its " +
                    "own paper, so the page and its controls always match.",
                checked = dark,
            ) { enabled -> dark = enabled; onDarkTheme(enabled) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Reading")
            SettingSwitch(
                title = "Keep the screen on while reading",
                detail = "Stops the display from sleeping while a book is open.",
                checked = keepAwake,
            ) { keepAwake = it; store.saveKeepAwake(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Your library")
            SectionNote("Your books, reading places, bookmarks, highlights and notes live only on this " +
                "phone. A backup gathers them into one file you choose, so they survive a lost or " +
                "replaced phone.")
            SettingAction(
                title = "Back up your library",
                detail = "Saves everything to one file, wherever you choose to keep it.",
                enabled = !busy,
            ) { onBackup() }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingAction(
                title = "Restore from a backup",
                detail = "Adds what a backup holds, including your reading settings. Nothing already on " +
                    "this phone is removed.",
                enabled = !busy,
            ) { confirmingRestore = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (busy) Column(Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (backingUp) "Saving your library…" else "Putting your library back…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionLabel("About")
            Text("Moonlight Pages", style = MaterialTheme.typography.titleMedium)
            Text("Version $version · Offline by design", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Text("Your books, progress, bookmarks, and highlights stay on this device and are never uploaded.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(28.dp))
        }
    }
    if (confirmingRestore) AlertDialog(onDismissRequest = { confirmingRestore = false },
        title = { Text("Restore from a backup?") },
        text = {
            Text("Books, reading places, bookmarks, highlights and notes from the backup are added to " +
                "this phone, and your reading settings are set to the ones it holds. Nothing already " +
                "here is removed, and a book’s place only moves when the backup’s is further along.")
        },
        confirmButton = {
            TextButton(onClick = { confirmingRestore = false; onRestore() }) { Text("Choose a file") }
        },
        dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text("Cancel") } })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun SectionNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting that opens something rather than toggling, so it carries a chevron instead of a switch. */
@Composable
private fun SettingAction(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (!enabled) disabled() }
        .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        Text("›", style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Suppress("DEPRECATION")
private fun packageVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty().ifEmpty { "1.0" }
