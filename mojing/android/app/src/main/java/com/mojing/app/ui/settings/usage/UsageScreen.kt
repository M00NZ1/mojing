package com.mojing.app.ui.settings.usage

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.time.YearMonth
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
            TopAppBar(
                        expandedHeight = 52.dp,title = { Text(if (level == 1) platformName.ifBlank { "历史平台" } else modelName.orEmpty().ifBlank { "未记录模型" },
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
    val displayedMonth = state.loadedMonth ?: state.selectedMonth
    UsageContainer(modifier, state.loading, state.error, vm::refresh) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MonthPicker(state.selectedMonth, vm::setMonth)
                if (state.loadedMonth != null && state.loadedMonth != state.selectedMonth) {
                    Text("下方保留 ${monthLabel(state.loadedMonth)} 的已加载数据", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CurrencyToolbar(currencyState, vm)
                if (state.loadedMonth == null) {
                    Text(if (state.loading) "正在读取所选月份…" else "所选月份尚未加载，请重试",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                val monthTokens = state.daily.sumOf { it.tokens }
                val monthCalls = state.daily.sumOf { it.totalCalls }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${monthLabel(displayedMonth)}费用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DailyCostRows(state.daily, currencyState)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${monthLabel(displayedMonth)} Token", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatNumber(monthTokens), style = MaterialTheme.typography.titleLarge)
                        Text("${formatNumber(monthCalls)} 次请求", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.daily.sumOf { it.unknownCalls }.takeIf { it > 0 }?.let { unknown ->
                    Text("${count(unknown)} 次请求尚未配置价格，未计入费用", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                DailyCostChart(state.daily, currencyState, displayedMonth)
                        Text("平台费用占比 · ${monthLabel(displayedMonth)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.platforms.any { platform -> platform.currencies.any { it.cost > 0 && displayCost(it.cost, it.currency, currencyState) == null } }) {
                    Text("占比仅包含可折算为当前币种的费用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
            } }
            items(state.platforms, key = { it.id }) { platform ->
                val totalCost = state.platforms.sumOf { platformKnownCost(it, currencyState) }
                MonthlyPlatformRow(platform, currencyState,
                    share = platformKnownCost(platform, currencyState).takeIf { totalCost > 0 }?.div(totalCost),
                    onClick = { onPlatform(platform) })
            }
            if (state.loadedMonth != null && state.platforms.isEmpty() && !state.loading && state.error == null) item { Text("该月暂无用量记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MonthlyPlatformRow(platform: UsagePlatformUi, state: CurrencyDisplayState, share: Double?, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(platform.name.ifBlank { "历史记录（未记录平台）" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (platform.calls > platform.unknownPrice) platform.currencies.joinToString(" · ") { formatBillingAmount(it.cost, it.currency, state) } else "未计价",
                    style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ChevronRight, "查看平台明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.LinearProgressIndicator(progress = { (share ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp))
                Text(share?.let { "${(it * 100).toInt()}%" } ?: "—", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${count(platform.tokens)} Token · ${count(platform.calls.toLong())} 次请求${if (platform.unknownPrice > 0) " · ${platform.unknownPrice} 次待定价" else ""}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun platformKnownCost(platform: UsagePlatformUi, state: CurrencyDisplayState): Double =
    platform.currencies.sumOf { displayCost(it.cost, it.currency, state) ?: 0.0 }

@Composable
private fun MonthPicker(month: YearMonth, onChange: (YearMonth) -> Unit) {
    val current = YearMonth.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(month.minusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "上个月" }) { Text("‹") }
        Text(monthLabel(month), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(enabled = month < current, onClick = { onChange(month.plusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "下个月" }) { Text("›") }
    }
}

@Composable
private fun DailyCostRows(rows: List<UsageDayUi>, state: CurrencyDisplayState) {
    val totals = rows.groupBy { it.currency }.mapValues { (_, values) -> values.sumOf { it.costKnownAmount } }
    if (totals.isEmpty()) {
        Text("该月暂无已计价记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            totals.entries.sortedBy { it.key }.forEach { (currency, amount) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(formatBillingAmount(amount, currency, state), style = MaterialTheme.typography.titleLarge)
                    Text(currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DailyCostChart(rows: List<UsageDayUi>, state: CurrencyDisplayState, month: YearMonth) {
    val bars = rows.groupBy { it.day }.toSortedMap().mapValues { (_, dayRows) ->
        dayRows.sumOf { displayCost(it.costKnownAmount, it.currency, state) ?: 0.0 }
    }
    val max = bars.values.maxOrNull() ?: 0.0
    if (bars.isEmpty() || max <= 0.0) {
        Text("该月暂无可绘制的费用数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val description = bars.entries.joinToString("；") { "${it.key} ${formatBillingAmount(it.value, state.displayCurrency, state)}" }
    val calendarMonth = month
    val days = (1..calendarMonth.lengthOfMonth()).map { day -> bars[calendarMonth.atDay(day).toString()] ?: 0.0 }
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("每日费用 · ${state.displayCurrency}", style = MaterialTheme.typography.titleSmall)
        Text(formatBillingAmount(max, state.displayCurrency, state), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = "每日费用柱图：$description" }) {
            val slotWidth = size.width / days.size
            val barWidth = (slotWidth * 0.65f).coerceAtLeast(1.dp.toPx())
            for (line in 0..2) {
                val y = line * size.height / 2
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            days.forEachIndexed { index, value ->
                val barHeight = (value / max * (size.height - 16.dp.toPx())).toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(index * slotWidth + (slotWidth - barWidth) / 2, size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("1日", "15日", "${calendarMonth.lengthOfMonth()}日").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun displayCost(amount: Double, currency: String, state: CurrencyDisplayState): Double? {
    val source = currency.uppercase(Locale.US)
    val target = state.displayCurrency.uppercase(Locale.US)
    if (source == target) return amount
    val rate = state.usdToCny?.takeIf { it.isFinite() && it > 0 } ?: return null
    return when {
        source == "USD" && target == "CNY" -> amount * rate
        source == "CNY" && target == "USD" -> amount / rate
        else -> null
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
                    UsageScopeSummary("平台合计 · 全部历史", platform.tokens, platform.calls, platform.failed,
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
                Text("全部历史请求", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.selectedModel?.takeIf { it.currencies.isNotEmpty() }?.let { model ->
                    val platformLabel = state.selectedPlatform?.name.orEmpty().takeIf(String::isNotBlank)
                    UsageScopeSummary(platformLabel?.let { "模型合计 · 全部历史 · $it" } ?: "模型合计 · 全部历史", model.tokens, model.calls,
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
    Surface(shape = MaterialTheme.shapes.small, onClick = onClick, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, "查看明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CurrencyRows(currencies, currencyState)
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
private fun formatNumber(value: Long) = count(value)
private fun monthLabel(month: YearMonth) = "${month.year}年${month.monthValue}月"
private fun formatDate(value: Long) = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(value))
private fun formatDuration(value: Int) = if (value < 1000) "${value} ms" else "${value / 1000.0}s"
