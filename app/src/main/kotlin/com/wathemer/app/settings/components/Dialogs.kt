// The settings app's dialogs: one sheet, a body that scrolls when it grows, the dismiss outside it.
package com.wathemer.app.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ThemeDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun DialogAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Fg,
        fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ThemeDialog(onDismiss) {
        Text(title, color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (body.isNotEmpty()) Text(body, color = Palette.FgMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, Palette.RuleStrong),
            ) { Text("Cancel", color = Palette.Fg) }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(confirmLabel, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NoticeDialog(lines: List<String>, onDismiss: () -> Unit) {
    ThemeDialog(onDismiss) {
        Text("Notes", color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEach { Text(it, color = Palette.FgMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        DialogDismiss("OK", onDismiss)
    }
}

@Composable
fun DialogDismiss(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.FgMuted,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}
