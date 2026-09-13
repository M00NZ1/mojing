package com.mojing.app.ui.settings.usage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mojing.app.domain.billing.CurrencyDisplayState
import com.mojing.app.domain.billing.formatBillingAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(viewModel: UsageViewModel, onBack: (() -> Unit)? = null) {
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currencyState by viewModel.currencyState.collectAsStateWithLifecycle()
    val level = state.level
    val screenStates = rememberSaveableStateHolder()
    val title = when (level) {
        1 -> state.selectedPlatform?.name ?: "平台用量"
        2 -> state.selectedModel?.name ?: "模型用量"
        else -> "本机用量"
    }
    fun goBack() { if (!viewModel.back()) onBack?.invoke() }
    if (level > 0) BackHandler(onBack = ::goBack)
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        if (level > 0) IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }) { padding ->
        screenStates.SaveableStateProvider("$level:${if (level > 0) state.selectedPlatform?.id.orEmpty() else ""}:${if (level > 1) state.selectedModel?.name.orEmpty() else ""}") {
        when (level) {
            0 -> SummaryLevel(state, currencyState, viewModel, Modifier.padding(padding), onPlatform = viewModel::openPlatform)
            1 -> ModelLevel(state, currencyState, viewModel, Modifier.padding(padding), onModel = viewModel::openModel)
            else -> RequestLevel(state, currencyState, viewModel, Modifier.padding(padding))
        }
        }
    }
}

@Composable private fun SummaryLevel(state: UsageUiState, currencyState: CurrencyDisplayState, vm: UsageViewModel, modifier: Modifier, onPlatform: (UsagePlatformUi) -> Unit) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    UsageContainer(modifier, state.loading, state.error, vm::refresh) {
        Text("按渠道汇总", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CurrencyToolbar(currencyState, vm)
                Text("${count(state.currencies.sumOf { it.calls.toLong() })} 次请求 · ${count(state.currencies.sumOf { it.tokens })} Token", style = MaterialTheme.typography.titleMedium)
                CurrencyRows(state.currencies, currencyState)
            } }
            items(state.platforms, key = { it.id }) { platform ->
                UsageCard(platform.name.ifBlank { "历史记录（未记录平台）" }, "${count(platform.calls.toLong())} 次请求 · ${count(platform.tokens)} Token", "成功 ${platform.calls - platform.failed} · 失败或取消 ${platform.failed} · 待定价 ${platform.unknownPrice}", platform.currencies, currencyState, onClick = { onPlatform(platform) })
            }
            if (state.platforms.isEmpty() && !state.loading && state.error == null) item { Text("暂无用量记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun ModelLevel(state: UsageUiState, currencyState: CurrencyDisplayState, vm: UsageViewModel, modifier: Modifier, onModel: (UsageModelUi) -> Unit) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    UsageContainer(modifier, state.loading, state.error, { state.selectedPlatform?.let(vm::openPlatform) }) {
        Text("按模型查看", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.selectedPlatform?.let { Text("共 ${count(it.tokens)} Token · ${it.failed} 次失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(state.models, key = { it.name }) { model ->
                UsageCard(model.name.ifBlank { "未记录模型" }, "${count(model.calls.toLong())} 次请求 · ${count(model.tokens)} Token", "成功 ${model.calls - model.failed} · 失败或取消 ${model.failed} · 待定价 ${model.unknownPrice}", model.currencies, currencyState, onClick = { onModel(model) })
            }
            if (state.models.isEmpty() && !state.loading) item { Text("暂无模型用量记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun RequestLevel(state: UsageUiState, currencyState: CurrencyDisplayState, vm: UsageViewModel, modifier: Modifier) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    UsageContainer(modifier, state.loading && state.requests.isEmpty(), state.error, vm::loadMoreRequests) {
        Text("每次请求", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(state.requests, key = { it.record.id }) { item ->
                val r = item.record
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelMedium)
                        val statusLabel = when (r.status) { "cancelled" -> "已取消"; "failed" -> "失败"; else -> if (r.success) "成功" else "失败" }
                        Text(statusLabel, color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    Text("输入 ${count(r.promptTokens.toLong())} · 输出 ${count(r.completionTokens.toLong())} · 共 ${count(r.totalTokens.toLong())} Token", style = MaterialTheme.typography.bodySmall)
                    val cost = if (r.costKnown) formatBillingAmount(r.estimatedCost, r.currency, currencyState) else "价格未配置"
                    Text("耗时 ${formatDuration(r.durationMs)} · ${if (r.costKnown) "≈" else ""}$cost${if (r.tokenSource == "estimated") " · Token 估算" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (state.requests.isEmpty() && !state.loading && state.error == null) item { Text("暂无请求记录") }
            if (state.canLoadMore && state.requests.isNotEmpty()) item { TextButton(onClick = vm::loadMoreRequests, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(if (state.loading) "正在加载…" else "加载更早记录") } }
        }
    }
}

@Composable private fun UsageContainer(modifier: Modifier, loading: Boolean, error: String?, retry: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Text(error, Modifier.weight(1f).padding(horizontal = 8.dp)); TextButton(onClick = retry) { Text("重试") } }
        content()
    }
}

@Composable private fun UsageCard(title: String, subtitle: String, detail: String, currencies: List<UsageCurrencyUi>, currencyState: CurrencyDisplayState = CurrencyDisplayState(), onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.DataUsage, null, tint = MaterialTheme.colorScheme.primary); Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.SemiBold); Icon(Icons.Outlined.ChevronRight, "查看") }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CurrencyRows(currencies, currencyState)
    } }
}

@Composable private fun CurrencyToolbar(state: CurrencyDisplayState, vm: UsageViewModel) {
    var manual by rememberSaveable { mutableStateOf("") }
    var rateError by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { vm.setDisplayCurrency("CNY") }) { Text(if (state.displayCurrency == "CNY") "✓ CNY ¥" else "CNY ¥") }
        TextButton(onClick = { vm.setDisplayCurrency("USD") }) { Text(if (state.displayCurrency == "USD") "✓ USD $" else "USD $") }
        TextButton(onClick = vm::refreshCurrency, enabled = !state.loading) { Text("刷新") }
    }
    Text(state.usdToCny?.let { "1 USD = ${java.math.BigDecimal.valueOf(it).setScale(4, java.math.RoundingMode.HALF_UP)} CNY · ${state.rateDate}" }
        ?: "暂无汇率，按原币显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true, label = { Text("USD/CNY 汇率") }, placeholder = { Text("例如 7.20") })
        TextButton(onClick = {
            val rate = manual.toDoubleOrNull()
            if (rate == null || !rate.isFinite() || rate <= 0) rateError = "请输入有效的正数汇率"
            else { vm.setManualRate(rate); rateError = null }
        }) { Text("应用") }
    }
    (rateError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun CurrencyRows(rows: List<UsageCurrencyUi>, state: CurrencyDisplayState) { rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${row.currency} · ${count(row.tokens)} Token", style = MaterialTheme.typography.labelMedium); Text(if (row.unknownPrice == row.calls && row.calls > 0) "价格待配置" else "≈" + formatBillingAmount(row.cost, row.currency, state), style = MaterialTheme.typography.labelMedium) } } }
private fun count(value: Long) = String.format(Locale.US, "%,d", value)
private fun formatDate(value: Long) = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(value))
private fun formatDuration(value: Int) = if (value < 1000) "${value} ms" else "${value / 1000.0}s"
