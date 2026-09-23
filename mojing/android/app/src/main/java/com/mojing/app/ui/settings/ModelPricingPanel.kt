package com.mojing.app.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.repository.BillingPreferences
import com.mojing.app.domain.billing.ModelPricing
import com.mojing.app.domain.billing.ModelPricingDiscovery
import com.mojing.app.domain.billing.PriceUnavailableException
import com.mojing.app.ui.common.MoJingOutlinedButton
import com.mojing.app.ui.common.MoJingTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ModelPricingPanel(
    platform: ModelPlatform,
    viewModel: BillingPriceViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var editing by remember { mutableStateOf<String?>(null) }
    val prices = viewModel.prices
    var revision by remember { mutableStateOf(0) }
    val discovery = remember { ModelPricingDiscovery() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型价格（每百万 Token）", style = MaterialTheme.typography.titleSmall)
        Text(
            "平台没有报价时可手动填写；未配置的模型不会估算费用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        items(platform.models, key = { it }) { model ->
            val configured by androidx.compose.runtime.produceState<ModelPricing?>(null, platform.id, model, revision) {
                value = runCatching { prices.price(platform.id, model) }.getOrNull()
            }
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(model, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        configured?.let { "输入 ${it.inputPerMillion.formatRate()} · 输出 ${it.outputPerMillion.formatRate()} ${it.currency}" }
                            ?: "未配置价格",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { editing = model }) { Text("编辑") }
            }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        }
        if (platform.models.isEmpty()) Text("请先添加模型名称。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { model ->
        var loaded by remember(platform.id, model, revision) { mutableStateOf(false) }
        var current by remember(platform.id, model, revision) { mutableStateOf<ModelPricing?>(null) }
        var hasHistory by remember(platform.id, model, revision) { mutableStateOf(false) }
        var loadError by remember(platform.id, model, revision) { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(platform.id, model, revision) {
            try { current = prices.price(platform.id, model); hasHistory = prices.hasPricedHistory(platform.id, model); loaded = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadError = true }
        }
        if (!loaded) AlertDialog(onDismissRequest = { editing = null }, title = { Text("模型价格") },
            text = { Text(if (loadError) "价格读取失败，请重试" else "正在读取价格…") },
            confirmButton = { if (loadError) TextButton(onClick = { revision++ }) { Text("重试") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
        else PricingEditor(
            platformId = platform.id,
            model = model,
            existing = current,
            hasPricedHistory = hasHistory,
            baseUrl = platform.baseUrl,
            apiKey = platform.apiKey,
            discovery = discovery,
            onDismiss = { editing = null },
            onSave = { pricing, sync -> prices.save(platform.id, model, pricing, sync); revision++; editing = null },
            onRemove = { prices.remove(platform.id, model); revision++; editing = null },
        )
    }
}

@Composable
private fun PricingEditor(
    platformId: String,
    model: String,
    existing: ModelPricing?,
    hasPricedHistory: Boolean,
    baseUrl: String,
    apiKey: String,
    discovery: ModelPricingDiscovery,
    onDismiss: () -> Unit,
    onSave: suspend (ModelPricing, Boolean) -> Unit,
    onRemove: suspend () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var syncHistory by remember { mutableStateOf(false) }
    var currency by remember(platformId, model) { mutableStateOf(existing?.currency ?: "CNY") }
    var input by remember(platformId, model) { mutableStateOf(existing?.inputPerMillion?.toPlainString().orEmpty()) }
    var output by remember(platformId, model) { mutableStateOf(existing?.outputPerMillion?.toPlainString().orEmpty()) }
    var cached by remember(platformId, model) { mutableStateOf(existing?.cachedInputPerMillion?.toPlainString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetchNotice by remember { mutableStateOf<String?>(null) }
    var fetchedPricing by remember { mutableStateOf<ModelPricing?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    val initialInput = remember(platformId, model) { input }
    val initialOutput = remember(platformId, model) { output }
    val initialCached = remember(platformId, model) { cached }
    val initialCurrency = remember(platformId, model) { currency }
    PlatformEditorDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("模型价格") },
        error = error,
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model, style = MaterialTheme.typography.titleMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("每百万 Token 的单价", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(enabled = !saving, selected = currency == "CNY", onClick = { currency = "CNY" }, label = { Text("￥ CNY") })
                    FilterChip(enabled = !saving, selected = currency == "USD", onClick = { currency = "USD" }, label = { Text("$ USD") })
                }
                RateField(input, { input = it }, "输入价格 / 百万 Token", !saving)
                RateField(output, { output = it }, "输出价格 / 百万 Token", !saving)
                RateField(cached, { cached = it }, "缓存输入价格 / 百万（可选）", !saving)
                MoJingOutlinedButton(
                    enabled = !saving && apiKey.isNotBlank() && baseUrl.isNotBlank(),
                    onClick = {
                        if (fetching) {
                            fetchJob?.cancel()
                            return@MoJingOutlinedButton
                        }
                        fetching = true
                        error = null
                        fetchNotice = null
                        fetchJob = scope.launch {
                            try {
                                val result = discovery.discover(baseUrl, apiKey, model)
                                result.fold(
                                    onSuccess = { fetched ->
                                        val untouched = input == initialInput && output == initialOutput && cached == initialCached && currency == initialCurrency
                                        if (untouched) {
                                            fetchedPricing = fetched
                                            currency = fetched.currency
                                            input = fetched.inputPerMillion.toPlainString()
                                            output = fetched.outputPerMillion.toPlainString()
                                            cached = fetched.cachedInputPerMillion?.toPlainString().orEmpty()
                                            fetchNotice = "已获取平台报价，请确认后保存"
                                        } else fetchNotice = "已获取报价，但当前手动修改已保留；请自行核对后保存"
                                    },
                                    onFailure = { failure ->
                                        fetchNotice = if (failure is PriceUnavailableException) failure.message
                                            ?: "平台接口未提供价格，请填写单价"
                                        else "获取价格失败，请检查平台地址与 Key"
                                    },
                                )
                            } catch (_: CancellationException) {
                                fetchNotice = "已取消获取"
                            } finally { fetching = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (fetching) "取消获取价格" else "通过平台接口获取价格") }
                fetchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                androidx.compose.material3.HorizontalDivider()
                if (existing != null || hasPricedHistory) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("同步更新历史费用", style = MaterialTheme.typography.titleSmall)
                            Text("仅更新当前平台的此模型", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.Switch(checked = syncHistory, onCheckedChange = { syncHistory = it }, enabled = !saving)
                    }
                }
                Text(if (syncHistory) "已计价和未计价的历史记录均按新单价重算。" else if (!hasPricedHistory)
                    "保存后自动补算当前平台、此模型尚未计价的历史记录。" else "历史费用保持不变，新单价用于之后开始的请求。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (existing != null) TextButton(enabled = !saving, onClick = {
                    saving = true
                    error = null
                    fetchJob?.cancel()
                    scope.launch {
                        try { onRemove() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "价格清除失败，请重试" }
                        finally { saving = false }
                    }
                }) { Text("清除价格", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            com.mojing.app.ui.common.MoJingButton(enabled = !saving, onClick = {
                val i = input.toDoubleOrNull(); val o = output.toDoubleOrNull()
                val c = cached.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
                if (i == null || o == null || (cached.isNotBlank() && c == null) || listOf(i, o, c).filterNotNull().any { !it.isFinite() || it < 0 }) {
                    error = "请输入大于等于 0 的有效数字，缓存价格可留空"
                } else {
                    error = null
                    saving = true
                    fetchJob?.cancel()
                    scope.launch { try { onSave(ModelPricing(currency, i, o, c, source = if (fetchedPricing?.let { it.currency == currency && it.inputPerMillion == i && it.outputPerMillion == o && it.cachedInputPerMillion == c } == true) "provider" else "manual"), syncHistory) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "价格保存失败，请重试" }
                        finally { saving = false }
                    }
                }
            }) { Text(if (saving) "正在保存并更新…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RateField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    MoJingTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun Double.formatRate(): String = if (isFinite()) toPlainString() else "—"

private fun Double.toPlainString(): String = java.math.BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
