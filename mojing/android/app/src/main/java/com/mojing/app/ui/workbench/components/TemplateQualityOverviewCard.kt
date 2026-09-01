package com.mojing.app.ui.workbench.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.domain.workbench.LocalTemplateQualityOverview

@Composable
fun TemplateQualityOverviewCard(overview: LocalTemplateQualityOverview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("本地质量概览", style = MaterialTheme.typography.titleMedium)
            Text(
                "评分 ${overview.score} / 100 · ${overview.verdict}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(
                progress = { overview.score / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (overview.strengths.isNotEmpty()) {
                Text("优点", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                overview.strengths.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (overview.risks.isNotEmpty()) {
                Text("风险", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                overview.risks.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (overview.improvements.isNotEmpty()) {
                Text("改进建议", style = MaterialTheme.typography.labelLarge)
                overview.improvements.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
