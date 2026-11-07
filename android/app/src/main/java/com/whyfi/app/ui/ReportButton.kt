package com.whyfi.app.ui

import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.whyfi.app.report.PhoneReports
import java.io.File
import kotlinx.coroutines.launch

/** "📄 Report": builds a PDF (map tiles may download, so it can take a few
 * seconds) and opens it in the default PDF viewer. */
@Composable
fun ReportButton(enabled: Boolean, modifier: Modifier = Modifier, generate: suspend () -> File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    TextButton(
        enabled = enabled && !busy,
        modifier = modifier,
        onClick = {
            busy = true
            scope.launch {
                val message = runCatching { generate() }
                    .fold({ PhoneReports.open(context, it) }, { "Report failed: ${it.message}" })
                busy = false
                message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        },
    ) { Text(if (busy) "Preparing…" else "📄 Report") }
}
