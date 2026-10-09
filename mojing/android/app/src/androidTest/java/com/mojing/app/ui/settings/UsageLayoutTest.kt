package com.mojing.app.ui.settings

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ContextWrapper
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.domain.billing.BillingCurrencyRepository
import com.mojing.app.ui.settings.usage.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

class UsageLayoutTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var currency: BillingCurrencyRepository
    private val stores = mutableListOf<androidx.lifecycle.ViewModelStore>()
    private val prefsName = "usage_layout_test_${java.util.UUID.randomUUID()}"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
        }
        currency = BillingCurrencyRepository(isolated, OkHttpClient()).apply { setManualRate(7.2) }
        repeat(70) { n -> db.costRecordDao().insert(CostRecordEntity(platformId = "p", platformName = "硅基流动", modelName = "test/model", totalTokens = n % 3 + 10,
            success = n % 2 == 0, status = if (n % 2 == 0) "success" else "cancelled")) }
        db.costRecordDao().insert(CostRecordEntity(platformId = "other", modelName = "test/model", totalTokens = 99999))
        Unit
    }
    private fun vm(dao: com.mojing.app.data.local.dao.CostRecordDao = db.costRecordDao()): UsageViewModel = UsageViewModel(dao, currency).also {
        androidx.lifecycle.ViewModelStore().apply { put("vm", it); stores.add(this) }
    }
    @After fun finish() {
        compose.runOnIdle { stores.forEach { it.clear() } }
        db.close(); context.getSharedPreferences(prefsName, 0).edit().clear().commit()
    }
    private fun saveShot(name: String) {
        val file = java.io.File(context.getExternalFilesDir(null), "$prefsName-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun summaryHasNoSecondAppBar() {
        val model = vm()
        compose.setContent { com.mojing.app.ui.theme.MoJingTheme(themeMode = "sky") { UsageScreen(model) } }
        compose.waitUntil(10000) { !model.state.value.loading }
        compose.onNodeWithText("本机用量").assertDoesNotExist()
        compose.onNodeWithText("硅基流动").assertIsDisplayed()
        compose.onNodeWithText("USD/CNY 汇率").assertDoesNotExist()
        saveShot("usage-summary")
    }
    @Test fun requestPageHasSingleHeaderAndWorkingFilters() {
        val model = vm()
        compose.setContent { com.mojing.app.ui.theme.MoJingTheme(themeMode = "sky") { UsageScreen(model, onBack = {}, platformId = "p", platformName = "硅基流动", modelName = "test/model") } }
        compose.waitUntil(10000) { model.state.value.level == 2 && !model.state.value.loading }
        compose.onNodeWithText("test/model").assertIsDisplayed()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.onNodeWithText("Token 最多").performClick()
        compose.waitUntil(10000) { !model.state.value.loading && model.state.value.requestOrder == "tokens" }
        assertEquals(12, model.state.value.requests.first().record.totalTokens)
        assertTrue(model.state.value.requests.all { it.record.platformId == "p" })
        saveShot("usage-requests")
    }
    @Test fun failedMonthReadLabelsTheRetainedDataWithItsLoadedMonth() {
        val failRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val source = db.costRecordDao()
        val dao = object : com.mojing.app.data.local.dao.CostRecordDao by source {
            override suspend fun dailyUsage(fromMillis: Long, toMillis: Long): List<com.mojing.app.data.local.dao.DailyUsageSummary> {
                check(!failRead.get()) { "isolated monthly read unavailable" }
                return source.dailyUsage(fromMillis, toMillis)
            }
        }
        val model = vm(dao)
        compose.setContent { com.mojing.app.ui.theme.MoJingTheme(themeMode = "sky") { UsageScreen(model) } }
        compose.waitUntil(10000) { !model.state.value.loading }
        val loaded = model.state.value.loadedMonth!!
        failRead.set(true)
        compose.runOnIdle { model.setMonth(loaded.minusMonths(1)) }
        compose.waitUntil(10000) { !model.state.value.loading && model.state.value.error != null }
        fun label(month: java.time.YearMonth) = "${month.year}年${month.monthValue}月"
        compose.onNodeWithText("${label(loaded)}费用").assertIsDisplayed()
        compose.onNodeWithText("${label(loaded)} Token").assertIsDisplayed()
        compose.onNodeWithText("下方保留 ${label(loaded)} 的已加载数据").assertIsDisplayed()
        compose.onNodeWithText("${label(loaded.minusMonths(1))}费用").assertDoesNotExist()
        assertEquals(loaded, model.state.value.loadedMonth)
        saveShot("usage-failed-month-retained")
    }
    @Test fun firstMonthReadFailureIsNotPresentedAsZeroUsage() {
        val source = db.costRecordDao()
        val dao = object : com.mojing.app.data.local.dao.CostRecordDao by source {
            override suspend fun dailyUsage(fromMillis: Long, toMillis: Long): List<com.mojing.app.data.local.dao.DailyUsageSummary> =
                error("isolated first monthly read unavailable")
        }
        val model = vm(dao)
        compose.setContent { com.mojing.app.ui.theme.MoJingTheme(themeMode = "sky") { UsageScreen(model) } }
        compose.waitUntil(10000) { !model.state.value.loading && model.state.value.error != null }
        assertNull(model.state.value.loadedMonth)
        compose.onNodeWithText("所选月份尚未加载，请重试").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("该月暂无已计价记录").assertDoesNotExist()
        compose.onNodeWithText("0 次请求").assertDoesNotExist()
        saveShot("usage-first-month-failed")
    }
    @Test fun tokenPaginationDoesNotLoseTiesOrMixPlatforms() = runBlocking {
        val dao = db.costRecordDao()
        val first = dao.filteredRequestPage("p", "test/model", Long.MAX_VALUE, Long.MAX_VALUE, 40, "all", "tokens")
        val last = first.last()
        val second = dao.filteredRequestPage("p", "test/model", last.id, last.totalTokens.toLong(), 40, "all", "tokens")
        assertEquals(70, (first + second).map { it.id }.distinct().size)
        assertTrue((first + second).all { it.platformId == "p" })
        assertEquals((first + second).map { it.totalTokens }.sortedDescending(), (first + second).map { it.totalTokens })
        assertEquals(35, dao.filteredRequestPage("p", "test/model", Long.MAX_VALUE, Long.MAX_VALUE, 100, "cancelled", "time").size)
    }
    @Test fun editorKeepsActionsVisibleWithBoundedForm() {
        compose.setContent { com.mojing.app.ui.theme.MoJingTheme(themeMode = "sky") {
            PlatformEditorDialog(onDismissRequest = {}, title = { Text("编辑平台") }, text = {
                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { OutlinedTextField("硅基流动", {}, Modifier.fillMaxWidth(), label = { Text("平台名称") }) }
                    item { OutlinedTextField("model/a\nmodel/b", {}, Modifier.fillMaxWidth(), label = { Text("模型名称") }, minLines = 3) }
                }
            }, confirmButton = { Button({}) { Text("保存") } }, dismissButton = { TextButton({}) { Text("取消") } })
        } }
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("平台名称").assertIsDisplayed()
        compose.onNodeWithText("模型名称").assertIsDisplayed()
    }
}
