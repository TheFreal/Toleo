package de.freal.toleo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.freal.toleo.library.EpubMeta

/**
 * Confirms an upload to the tolino cloud. With [meta] it also lets the user fix the title, author and
 * publisher, which are written into the uploaded copy; without it (PDFs, protected books) it only
 * confirms. [note] explains anything specific to the caller.
 */
@Composable
fun UploadDialog(
    meta: EpubMeta?,
    note: String?,
    onConfirm: (EpubMeta?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(meta) { mutableStateOf(meta?.title.orEmpty()) }
    var authors by remember(meta) { mutableStateOf(meta?.authors.orEmpty()) }
    var publisher by remember(meta) { mutableStateOf(meta?.publisher.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload to your tolino cloud") },
        text = {
            Column {
                if (meta != null) {
                    Text(
                        "Check the details. They are saved into the uploaded copy and shown in your tolino library.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Field("Title", title, { title = it }, KeyboardCapitalization.Sentences)
                    Spacer(Modifier.height(8.dp))
                    Field("Author", authors, { authors = it }, KeyboardCapitalization.Words)
                    Spacer(Modifier.height(8.dp))
                    Field("Publisher", publisher, { publisher = it }, KeyboardCapitalization.Words, last = true)
                }
                if (note != null) {
                    if (meta != null) Spacer(Modifier.height(12.dp))
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(meta?.let { EpubMeta(title.trim(), authors.trim(), publisher.trim()) }) },
                // An untitled book would be impossible to find in the library.
                enabled = meta == null || title.isNotBlank(),
            ) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    capitalization: KeyboardCapitalization,
    last: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}
