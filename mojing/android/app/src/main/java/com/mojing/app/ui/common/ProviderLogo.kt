package com.mojing.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mojing.app.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Identify the endpoint, not a user-editable display name. Logos are bundled for offline use. */
@Composable
fun ProviderLogo(baseUrl: String, modifier: Modifier = Modifier) {
    val asset = when (baseUrl.trim().toHttpUrlOrNull()?.host) {
        "api.deepseek.com" -> R.drawable.provider_deepseek
        "api.openai.com" -> R.drawable.provider_openai
        "api.anthropic.com" -> R.drawable.provider_anthropic
        "api.siliconflow.cn", "api.siliconflow.com" -> R.drawable.provider_siliconflow
        else -> null
    }
    Surface(modifier.size(32.dp), shape = MaterialTheme.shapes.small,
        color = if (asset != null) Color.White else MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(contentAlignment = Alignment.Center) {
            if (asset != null) Image(painterResource(asset), null, Modifier.size(28.dp).padding(2.dp))
            else Icon(Icons.Outlined.Dns, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
