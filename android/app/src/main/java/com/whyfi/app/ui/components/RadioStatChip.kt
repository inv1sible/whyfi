package com.whyfi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One radio type's live result count, filled in progressively as a scan
 * moves through its phases — replaces the old single "WiFi: 8 · Cellular:
 * 2 · ..." text line. `count == null` reads as "not scanned yet / this
 * radio wasn't included"; `isActivePhase` shows a spinner specifically for
 * "scanning this one right now".
 *
 * Pass [onClick] to make the chip a way into the matching results table.
 * It's optional because there's nothing to open until a pass has completed
 * — a chip that looks tappable and does nothing is worse than a flat one.
 */
@Composable
fun RadioStatChip(
    icon: String,
    label: String,
    count: Int?,
    isActivePhase: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // Fixed height so all chips in a row align regardless of whether they
    // show a count, a spinner, or a dash — and so the row doesn't reflow
    // when a phase starts/stops and swaps count↔spinner. The icon sits at
    // top, the count/spinner in a fixed-height centered slot, the label
    // at the bottom; the outer Column fills the fixed height so every chip
    // in the row is exactly the same size.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(icon, fontSize = 18.sp)
        Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            when {
                isActivePhase -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = accentColor)
                count != null -> Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                )
                else -> Text("—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
