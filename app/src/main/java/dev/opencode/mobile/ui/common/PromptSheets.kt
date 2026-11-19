package dev.opencode.mobile.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Shield
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
    val c = OcTheme.colors
    ModalBottomSheet(onDismissRequest = {}, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.orange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Shield, contentDescription = null, tint = c.orange, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("opencode wants permission", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(request.label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
                if (pending > 1) StatusPill("+${pending - 1} more", c.textSecondary, dot = false)
            }
            val command = request.metadata?.get("command")?.toString()?.trim('"')
            val detail = command?.takeIf { it.isNotBlank() } ?: request.patterns.joinToString("\n").take(400).takeIf { it.isNotBlank() }
            if (detail != null) {
                Text(
                    detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = c.textBody,
                    maxLines = 10,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(c.bg).padding(12.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            androidx.compose.material3.Button(
                onClick = { onRespond("once") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text("Allow once") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = { onRespond("always") }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Always allow") }
                androidx.compose.material3.OutlinedButton(onClick = { onRespond("reject") }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Deny", color = c.red) }
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
