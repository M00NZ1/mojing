package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingButton as Button

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

@Composable
fun AiCompleteButton(isLoading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (isLoading) {
        CircularProgressIndicator(modifier = modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        Button(onClick = onClick, modifier = modifier) {
            Text("AI 补全")
        }
    }
}
