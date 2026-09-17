package dev.opencode.mobile.ui.common

import android.content.Context
import android.content.Intent

/** Opens the system share sheet (Slack, WhatsApp, mail, …) with plain text. */
fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    val chooser = Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
