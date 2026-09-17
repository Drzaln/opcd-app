package dev.opencode.mobile.ui.common

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.model.PermissionRequest
import dev.opencode.mobile.data.model.QuestionRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    request: PermissionRequest,
    pending: Int,
    onRespond: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = {}) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Permission requested", style = MaterialTheme.typography.titleMedium)
                if (pending > 1) {
                    Spacer(Modifier.width(8.dp))
                    MutedLabel("+${pending - 1} more")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(request.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            val command = request.metadata?.get("command")?.toString()?.trim('"')
            if (!command.isNullOrBlank()) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = OcTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (request.patterns.isNotEmpty()) {
                Text(
                    request.patterns.joinToString(", ").take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = OcTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRespond("once") }) { Text("Allow once") }
                TextButton(onClick = { onRespond("always") }) { Text("Always allow") }
                TextButton(onClick = { onRespond("reject") }) { Text("Deny", color = OcTheme.colors.red) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(
    request: QuestionRequest,
    pending: Int,
    onSubmit: (List<List<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selections = remember(request.id) { mutableStateListOf(*Array(request.questions.size) { emptySet<String>() }) }
    val customs = remember(request.id) { mutableStateListOf(*Array(request.questions.size) { "" }) }
    val answers = request.questions.indices.map { i ->
        val chosen = selections[i].toMutableSet()
        if (request.questions[i].custom && customs[i].isNotBlank()) chosen.add(customs[i].trim())
        chosen.toList()
    }
    val canSubmit = answers.isNotEmpty() && answers.all { it.isNotEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Question from opencode", style = MaterialTheme.typography.titleMedium)
                if (pending > 1) {
                    Spacer(Modifier.width(8.dp))
                    MutedLabel("+${pending - 1} more")
                }
            }
            for ((i, q) in request.questions.withIndex()) {
                Spacer(Modifier.height(12.dp))
                if (q.header.isNotBlank()) {
                    Text(q.header, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(q.question, style = MaterialTheme.typography.bodyMedium)
                if (q.multiple) {
                    MutedLabel("select one or more", modifier = Modifier.padding(top = 2.dp))
                }
                for (option in q.options) {
                    val selected = option.label in selections[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val current = selections[i]
                                selections[i] = if (q.multiple) {
                                    if (selected) current - option.label else current + option.label
                                } else {
                                    setOf(option.label)
                                }
                            }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (q.multiple) {
                            Checkbox(checked = selected, onCheckedChange = null)
                        } else {
                            RadioButton(selected = selected, onClick = null)
                        }
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            if (option.description.isNotBlank()) {
                                Text(option.description, style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                if (q.custom) {
                    OutlinedTextField(
                        value = customs[i],
                        onValueChange = { customs[i] = it },
                        placeholder = { Text("Or type your own") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        maxLines = 3,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Dismiss", color = OcTheme.colors.red) }
                TextButton(onClick = { onSubmit(answers) }, enabled = canSubmit) { Text("Submit") }
            }
        }
    }
}
