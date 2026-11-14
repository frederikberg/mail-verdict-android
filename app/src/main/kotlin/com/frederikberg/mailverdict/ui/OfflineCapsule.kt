package com.frederikberg.mailverdict.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.kit.live.OfflineNotice

/**
 * Says a screen is not live: offline, the server unreachable, or a saved copy on screen. It takes no
 * taps, so it can sit over content that still must.
 */
@Composable
fun OfflineCapsule(
    notice: OfflineNotice,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Text(
            notice.text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
