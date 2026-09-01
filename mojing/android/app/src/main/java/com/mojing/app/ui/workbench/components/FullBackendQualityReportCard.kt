package com.mojing.app.ui.workbench.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojing.app.data.remote.WorldQualityReportDto

@Composable
fun FullBackendQualityReportCard(report: WorldQualityReportDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("完整质量报告（含设定细则）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("评分 ${report.score} · ${report.verdict}", style = MaterialTheme.typography.bodyMedium)
            if (report.strengths.isNotEmpty()) {
                Text("优点", style = MaterialTheme.typography.labelMedium)
                report.strengths.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (report.risks.isNotEmpty()) {
                HorizontalDivider()
                Text("风险", style = MaterialTheme.typography.labelMedium)
                report.risks.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (report.issues.isNotEmpty()) {
                HorizontalDivider()
                Text("问题与建议", style = MaterialTheme.typography.labelMedium)
                report.issues.forEach { issue ->
                    val hint = issue.suggestion.takeIf { it.isNotBlank() }?.let { " 建议：$it" }.orEmpty()
                    Text("· ${issue.message}$hint", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
