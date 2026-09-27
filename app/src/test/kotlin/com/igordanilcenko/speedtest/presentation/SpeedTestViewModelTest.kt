package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModelStore
import com.igordanilcenko.speedtest.data.StubSpeedTestRepository
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.RunSpeedTest
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import com.igordanilcenko.speedtest.domain.SpeedTestRepository
import com.igordanilcenko.speedtest.domain.TEST_DURATION_MILLIS
import com.igordanilcenko.speedtest.domain.TestFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SpeedTestViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `selects minimum ping and runs download for fifteen seconds after discovery`() = runTest {
        val vm = SpeedTestViewModel(RunSpeedTest(StubSpeedTestRepository()))
        assertEquals(SpeedTestUiState(), vm.state.value)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(TestPhase.FindingNodes, vm.state.value.phase)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(TestPhase.Pinging, vm.state.value.phase)
        advanceTimeBy(600)
        runCurrent()
        assertEquals("Demo 3", vm.state.value.selectedServer?.node?.name)
        assertEquals(12.0, vm.state.value.selectedServer!!.pingMs, 0.0)
        assertEquals(0L, vm.state.value.elapsedMillis)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(TestPhase.Measuring, vm.state.value.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TestPhase.Finished, vm.state.value.phase)
        assertEquals(TEST_DURATION_MILLIS, vm.state.value.elapsedMillis)
        assertEquals(86.4, vm.state.value.speedMbps!!, 0.001)
    }

    @Test fun `stop and screen exit reset every active phase without later updates`() = runTest {
        for (intent in listOf(SpeedTestIntent.Stop, SpeedTestIntent.ScreenLeft)) {
            for (elapsed in listOf(0L, 500L, 2_000L)) {
                val vm = SpeedTestViewModel(RunSpeedTest(StubSpeedTestRepository()))
                vm.onIntent(SpeedTestIntent.Start)
                advanceTimeBy(elapsed)
                runCurrent()
                vm.onIntent(intent)
                assertEquals(SpeedTestUiState(), vm.state.value)
                advanceUntilIdle()
                assertEquals(SpeedTestUiState(), vm.state.value)
            }
        }
    }

    @Test fun `ignores double start and starts fresh immediately after stop`() = runTest {
        val repository = RecordingRepository()
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        vm.onIntent(SpeedTestIntent.Start)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(1, repository.discoveries)
        vm.onIntent(SpeedTestIntent.Stop)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(2, repository.discoveries)
        assertEquals(TestPhase.Finished, vm.state.value.phase)
    }

    @Test fun `clearing viewmodel cancels download`() = runTest {
        val repository = RecordingRepository()
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        val store = ViewModelStore().apply { put("test", vm) }
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertTrue(repository.downloading)
        store.clear()
        runCurrent()
        assertFalse(repository.downloading)
        advanceUntilIdle()
        assertNotEquals(TestPhase.Finished, vm.state.value.phase)
    }

    @Test fun `pings all five nodes and skips unreachable nodes`() = runTest {
        val repository = RecordingRepository(unreachable = setOf("0", "2"))
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(setOf("0", "1", "2", "3", "4"), repository.pinged.toSet())
        assertEquals("1", vm.state.value.selectedServer?.node?.id)
        assertEquals("1", repository.downloadedNode?.id)
    }

    @Test fun `reports when all pings fail without starting download`() = runTest {
        val repository = RecordingRepository(unreachable = (0..4).map { "$it" }.toSet())
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertEquals(TestFailure.NoPingReplies, vm.state.value.failure)
        assertNull(repository.downloadedNode)
    }

    @Test fun `reports empty directory and allows retry`() = runTest {
        val repository = RecordingRepository(empty = true)
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestFailure.NoServers, vm.state.value.failure)
        repository.empty = false
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Finished, vm.state.value.phase)
    }

    @Test fun `request timeout becomes error rather than leaving progress running`() = runTest {
        val repository = object : RecordingRepository() {
            override suspend fun getNearestNodes(): List<Node> = awaitCancellation()
        }
        val vm = SpeedTestViewModel(RunSpeedTest(repository))
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertFalse(vm.state.value.isRunning)
    }

    @Test fun `average is computed from cumulative bytes and time`() {
        assertEquals(80.0, SpeedMeasurement(120.0, 100_000_000, 10_000).averageMbps, 0.0)
    }

    private open class RecordingRepository(
        var empty: Boolean = false,
        private val unreachable: Set<String> = emptySet(),
    ) : SpeedTestRepository {
        var discoveries = 0
        val pinged = mutableListOf<String>()
        var downloadedNode: Node? = null
        var downloading = false

        override suspend fun getNearestNodes(): List<Node> {
            discoveries++
            return if (empty) emptyList() else (0..4).map { Node("$it", "Server $it", it * 10) }
        }

        override suspend fun ping(node: Node): Double? {
            pinged += node.id
            return if (node.id in unreachable) null else node.id.toDouble() + 10
        }

        override fun measureSpeed(server: Node, durationMillis: Long): Flow<SpeedMeasurement> = flow {
            downloadedNode = server
            downloading = true
            try {
                delay(durationMillis)
                emit(SpeedMeasurement(80.0, 150_000_000, durationMillis))
            } finally {
                downloading = false
            }
        }
    }
}
