package com.mojing.app.ui.settings.usage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
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
fun UsageScreen(
    viewModel: UsageViewModel, onBack: (() -> Unit)? = null,
    platformId: String? = null, platformName: String = "", modelName: String? = null,
    onPlatform: (UsagePlatformUi) -> Unit = viewModel::openPlatform,
    onModel: (UsageModelUi) -> Unit = viewModel::openModel,
) {
    androidx.compose.runtime.LaunchedEffect(viewModel, platformId, modelName) {
        if (platformId == null) viewModel.refresh() else viewModel.openDestination(platformId, platformName, modelName)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currencyState by viewModel.currencyState.collectAsStateWithLifecycle()
    val level = if (platformId == null) 0 else if (modelName == null) 1 else 2
    if (level == 0) {
        SummaryLevel(state, currencyState, viewModel, Modifier, onPlatform)
    } else {
        BackHandler { onBack?.invoke() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(if (level == 1) platformName.ifBlank { "历史平台" } else modelName.orEmpty().ifBlank { "未记录模型" },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { onBack?.invoke() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } })
        }) { padding ->
            if (level == 1) ModelLevel(state, currencyState, viewModel, Modifier.padding(padding), onModel)
            else RequestLevel(state, currencyState, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable private fun SummaryLevel(state: UsageUiState, currencyState: CurrencyDisplayState, vm: UsageViewModel, modifier: Modifier, onPlatform: (UsagePlatformUi) -> Unit) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    UsageContainer(modifier, state.loading, state.error, vm::refresh) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CurrencyToolbar(currencyState, vm)
                Text("${count(state.currencies.sumOf { it.tokens })} Token", style = MaterialTheme.typography.headlineMedium)
                Text("${count(state.currencies.sumOf { it.calls.toLong() })} 次请求", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CurrencyRows(state.currencies, currencyState)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("平台 · ${state.platforms.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var sort by rememberSaveable { mutableStateOf("最近使用") }
    val models = when (sort) { "Token" -> state.models.sortedByDescending { it.tokens }; "请求数" -> state.models.sortedByDescending { it.calls }; else -> state.models }
    UsageContainer(modifier, state.loading, state.error, { state.selectedPlatform?.let(vm::openPlatform) }) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "model-summary") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.selectedPlatform?.takeIf { it.currencies.isNotEmpty() }?.let { platform ->
                    UsageScopeSummary("平台合计", platform.tokens, platform.calls, platform.failed,
                        platform.unknownPrice, platform.currencies, currencyState)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("最近使用", "Token", "请求数").forEach { option -> FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option) }) }
                }
                Text("${state.models.size} 个模型 · ${count(state.models.sumOf { it.tokens })} Token", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            items(models, key = { "model:${it.name}" }) { model ->
                UsageCard(model.name.ifBlank { "未记录模型" }, "${count(model.calls.toLong())} 次请求 · ${count(model.tokens)} Token", "成功 ${model.calls - model.failed} · 失败或取消 ${model.failed} · 待定价 ${model.unknownPrice}", model.currencies, currencyState, onClick = { onModel(model) })
            }
            if (state.models.isEmpty() && !state.loading) item { Text("暂无模型用量记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun RequestLevel(state: UsageUiState, currencyState: CurrencyDisplayState, vm: UsageViewModel, modifier: Modifier) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    UsageContainer(modifier, state.loading && state.requests.isEmpty(), state.error, vm::loadMoreRequests) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "request-filters") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.selectedModel?.takeIf { it.currencies.isNotEmpty() }?.let { model ->
                    val platformLabel = state.selectedPlatform?.name.orEmpty().takeIf(String::isNotBlank)
                    UsageScopeSummary(platformLabel?.let { "模型合计 · $it" } ?: "模型合计", model.tokens, model.calls,
                        model.failed, model.unknownPrice, model.currencies, currencyState)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "success" to "成功", "failed" to "失败", "cancelled" to "已取消").forEach { (value, label) ->
                        FilterChip(selected = state.requestFilter == value, onClick = { vm.filterRequests(value, state.requestOrder) }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.requestOrder == "time", onClick = { vm.filterRequests(state.requestFilter, "time") }, label = { Text("最新记录") })
                    FilterChip(selected = state.requestOrder == "tokens", onClick = { vm.filterRequests(state.requestFilter, "tokens") }, label = { Text("Token 最多") })
                }
            } }
            items(state.requests, key = { it.record.id }) { item ->
                val r = item.record
                var detailsExpanded by rememberSaveable(r.id) { mutableStateOf(false) }
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDate(r.createdAt), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val statusLabel = when (r.status) { "cancelled" -> "已取消"; "failed" -> "失败"; else -> if (r.success) "成功" else "失败" }
                            Text(statusLabel, Modifier.padding(start = 12.dp), color = when {
                                r.status == "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
                                r.success -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }, style = MaterialTheme.typography.labelMedium)
                        }
                        val cost = if (r.costKnown) "≈" + formatBillingAmount(r.estimatedCost, r.currency, currencyState) else "价格未配置"
                        Text(cost, style = MaterialTheme.typography.titleMedium)
                        Text("${count(r.totalTokens.toLong())} Token", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("输入 ${count(r.promptTokens.toLong())} · 输出 ${count(r.completionTokens.toLong())}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("耗时 ${formatDuration(r.durationMs)}${if (r.tokenSource == "estimated") " · Token 估算" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { detailsExpanded = !detailsExpanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(if (detailsExpanded) "收起请求信息" else "查看请求信息")
                        }
                        AnimatedVisibility(visible = detailsExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("平台：${r.platformName.ifBlank { "历史记录（未记录平台）" }}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("模型：${r.modelName.ifBlank { "未记录模型" }}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("接口格式：${r.provider}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                r.sessionId?.let { Text("对话编号：$it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                r.characterId?.let { Text("角色编号：$it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (r.cachedPromptTokens > 0) Text("缓存输入：${count(r.cachedPromptTokens.toLong())} Token",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (state.requests.isEmpty() && !state.loading && state.error == null) item { Text("暂无请求记录") }
            if (state.canLoadMore && state.requests.isNotEmpty()) item { TextButton(onClick = vm::loadMoreRequests, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(if (state.loading) "正在加载…" else "加载更早记录") } }
        }
    }
}

@Composable private fun UsageScopeSummary(label: String, tokens: Long, calls: Int, failed: Int,
    unknownPrice: Int, currencies: List<UsageCurrencyUi>, currencyState: CurrencyDisplayState) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${count(tokens)} Token", style = MaterialTheme.typography.headlineMedium)
            Text("${count(calls.toLong())} 次请求 · 成功 ${count((calls - failed).coerceAtLeast(0).toLong())} · 失败或取消 ${count(failed.toLong())}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unknownPrice > 0) Text("${count(unknownPrice.toLong())} 次待定价", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            CurrencyRows(currencies, currencyState)
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
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, "查看明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CurrencyRows(currencies, currencyState)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    } }
}

@Composable private fun CurrencyToolbar(state: CurrencyDisplayState, vm: UsageViewModel) {
    var editRate by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    var rateError by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.displayCurrency == "CNY", onClick = { vm.setDisplayCurrency("CNY") }, label = { Text("人民币 ¥") })
        FilterChip(selected = state.displayCurrency == "USD", onClick = { vm.setDisplayCurrency("USD") }, label = { Text("美元 $") })
        TextButton(onClick = { editRate = !editRate }) { Text("汇率") }
    }
    Text(state.usdToCny?.let { "1 USD = ${java.math.BigDecimal.valueOf(it).setScale(4, java.math.RoundingMode.HALF_UP)} CNY · ${state.rateDate}" }
        ?: "暂无汇率，按原币显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (editRate) Column {
    TextButton(onClick = vm::refreshCurrency, enabled = !state.loading) { Text("刷新汇率") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("USD/CNY 汇率") }, placeholder = { Text("例如 7.20") })
        TextButton(onClick = {
            val rate = manual.toDoubleOrNull()
            if (rate == null || !rate.isFinite() || rate <= 0) rateError = "请输入有效的正数汇率"
            else { vm.setManualRate(rate); rateError = null }
        }) { Text("应用") }
    }
    }
    (rateError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CurrencyRows(rows: List<UsageCurrencyUi>, state: CurrencyDisplayState) {
    rows.forEach { row ->
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${row.currency} · ${count(row.tokens)} Token", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (row.unknownPrice == row.calls && row.calls > 0) "价格待配置"
                else "≈" + formatBillingAmount(row.cost, row.currency, state),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
private fun count(value: Long) = String.format(Locale.US, "%,d", value)
private fun formatDate(value: Long) = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(value))
private fun formatDuration(value: Int) = if (value < 1000) "${value} ms" else "${value / 1000.0}s"
