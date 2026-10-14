package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andyyhope.featureflag.ui.FlagIcons
import com.andyyhope.featureflag.ui.FlagStrings

/** While the companion connects (iOS `.loading`). */
@Composable
internal fun ConnectingScreen(modifier: Modifier = Modifier) {
    StatusSurface(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/** iOS FlagCompanionUnavailableView: a symbol, a title, a message, and "Try again" when retrying can help. */
@Composable
internal fun UnavailableScreen(title: String, message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    StatusSurface(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = FlagIcons.FlagOutlined,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text(text = FlagStrings.TRY_AGAIN) }
            }
        }
    }
}

/** The theme's background and its content colour: outside a Surface, text would default to black, even in a dark theme. */
@Composable
private fun StatusSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
}
