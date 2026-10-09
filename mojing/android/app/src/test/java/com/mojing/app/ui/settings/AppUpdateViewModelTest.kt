package com.mojing.app.ui.settings

import com.mojing.app.data.update.AppRelease
import com.mojing.app.data.update.ReleaseVersion
import com.mojing.app.data.update.UpdatePackage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val current = ReleaseVersion(1, 2, 2)
    private val release = AppRelease(ReleaseVersion(1, 2, 3), "更新说明", UpdatePackage("app.apk", "https://github.com/example/app.apk", 1024))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedRecheckKeepsPackageAndNotesAndAllowsRetry() = runTest(dispatcher) {
        var request: suspend () -> AppRelease = { release }
        var calls = 0
        val vm = AppUpdateViewModel({ calls++; request() }, { current })
        vm.check(); advanceUntilIdle(); vm.dismissPrompt()
        val pending = CompletableDeferred<AppRelease>()
        request = { pending.await() }
        vm.check(); vm.check(); runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.state.value.checking)
        assertEquals(release, vm.state.value.release)
        pending.completeExceptionally(IOException("private response must not appear"))
        advanceUntilIdle()
        assertFalse(vm.state.value.checking)
        assertEquals(release, vm.state.value.release)
        assertEquals("无法连接 GitHub，请检查网络后重试", vm.state.value.error)
        vm.showDownload()
        assertTrue(vm.state.value.showPrompt)
        val newer = release.copy(version = ReleaseVersion(1, 2, 4))
        request = { newer }
        vm.check(); advanceUntilIdle()
        assertEquals(newer, vm.state.value.release)
        assertNull(vm.state.value.error)
    }

    @Test fun successfulLatestCheckClearsOldDownload() = runTest(dispatcher) {
        var next = release
        val vm = AppUpdateViewModel({ next }, { current })
        vm.check(); advanceUntilIdle()
        next = release.copy(version = current)
        vm.check(); advanceUntilIdle()
        assertNull(vm.state.value.release)
        assertEquals("当前已是最新版本", vm.state.value.message)
        vm.showDownload()
        assertFalse(vm.state.value.showPrompt)
    }

    @Test fun newReleaseWithoutCompatiblePackageKeepsNewNotesButNoOldDownload() = runTest(dispatcher) {
        var next = release
        val vm = AppUpdateViewModel({ next }, { current })
        vm.check(); advanceUntilIdle()
        next = AppRelease(ReleaseVersion(1, 2, 4), "等待安装包", null)
        vm.check(); advanceUntilIdle()
        assertEquals(next, vm.state.value.release)
        assertNotNull(vm.state.value.error)
        vm.showDownload()
        assertFalse(vm.state.value.showPrompt)
    }

    @Test fun cancellationReleasesBusyStateForRetry() = runTest(dispatcher) {
        var cancel = true
        val vm = AppUpdateViewModel({ if (cancel) throw CancellationException() else release }, { current })
        vm.check(); advanceUntilIdle()
        assertFalse(vm.state.value.checking)
        assertNull(vm.state.value.error)
        cancel = false
        vm.check(); advanceUntilIdle()
        assertEquals(release, vm.state.value.release)
    }
}
