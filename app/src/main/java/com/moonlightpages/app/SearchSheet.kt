package com.moonlightpages.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(
    query: String, results: List<SearchHit>, searching: Boolean, failed: Boolean, truncated: Boolean,
    chapterTitle: (Int) -> String, onQuery: (String) -> Unit, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Search this book", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(focus),
                shape = RoundedCornerShape(18.dp), placeholder = { Text("Word or phrase") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                    }
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
            LaunchedEffect(Unit) { focus.requestFocus() }
            when {
                query.trim().length < 2 -> Text("Type at least two letters to look through this book.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                searching -> Column(Modifier.padding(vertical = 20.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Looking through every chapter…", Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failed -> Text("This book's text couldn't be read for searching. Reopen the book to try again.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                results.isEmpty() -> Text("No matches for “${query.trim()}”.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(if (truncated) "First ${results.size} matches" else "${results.size} ${if (results.size == 1) "match" else "matches"}",
                        Modifier.padding(top = 18.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(results, key = { "${it.chapter}:${it.node}:${it.offset}" }) { hit ->
                            SearchResultRow(hit, hit.chapterTitle.ifBlank { chapterTitle(hit.chapter) }) { keyboard?.hide(); onOpen(hit) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, title: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)
        .semantics { contentDescription = "Open result in $title" }
        .padding(vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(buildAnnotatedString {
            append(hit.before)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(hit.text) }
            append(hit.after)
        }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}
