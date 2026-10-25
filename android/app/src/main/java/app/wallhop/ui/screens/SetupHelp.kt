package app.wallhop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Type

/** Numbered help lines, e.g. the notification access steps. */
@Composable
fun SetupHelp(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row {
                Text("${i + 1}.", style = Type.subheadline, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.widthIn(min = 22.dp))
                Text(line, style = Type.subheadline, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Start)
            }
        }
    }
}
