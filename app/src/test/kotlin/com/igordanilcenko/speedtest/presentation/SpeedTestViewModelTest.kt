package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModelStore
import com.igordanilcenko.speedtest.domain.LocationRepository
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.model.Coordinates
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
        val vm = newViewModel(StubSpeedTestRepository())
        assertEquals(SpeedTestUiState(locationPermission = LocationPermission.Granted), vm.state.value)
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
                val vm = newViewModel(StubSpeedTestRepository())
                vm.onIntent(SpeedTestIntent.Start)
                advanceTimeBy(elapsed)
                runCurrent()
                vm.onIntent(intent)
                assertEquals(SpeedTestUiState(locationPermission = LocationPermission.Granted), vm.state.value)
                advanceUntilIdle()
                assertEquals(SpeedTestUiState(locationPermission = LocationPermission.Granted), vm.state.value)
            }
        }
    }

    @Test fun `ignores double start and starts fresh immediately after stop`() = runTest {
        val repository = RecordingRepository()
        val vm = newViewModel(repository)
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
        val vm = newViewModel(repository)
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
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(setOf("0", "1", "2", "3", "4"), repository.pinged.toSet())
        assertEquals("1", vm.state.value.selectedServer?.node?.id)
        assertEquals("1", repository.downloadedNode?.id)
    }

    @Test fun `reports when all pings fail without starting download`() = runTest {
        val repository = RecordingRepository(unreachable = (0..4).map { "$it" }.toSet())
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertEquals(TestFailure.NoPingReplies, vm.state.value.failure)
        assertNull(repository.downloadedNode)
    }

    @Test fun `reports empty directory and allows retry`() = runTest {
        val repository = RecordingRepository(empty = true)
        val vm = newViewModel(repository)
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
            override suspend fun getNearestNodes(coordinates: Coordinates): List<Node> = awaitCancellation()
        }
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertFalse(vm.state.value.isRunning)
    }

    @Test fun `average is computed from cumulative bytes and time`() {
        assertEquals(80.0, SpeedMeasurement(120.0, 100_000_000, 10_000).averageMbps, 0.0)
    }

    @Test fun `permission denied blocks start and survives screen exit`() = runTest {
        val repository = RecordingRepository()
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true))
        vm.onIntent(SpeedTestIntent.Start)
        vm.onIntent(SpeedTestIntent.ScreenLeft)
        advanceUntilIdle()
        assertFalse(vm.state.value.canStart)
        assertEquals(LocationPermission.Denied, vm.state.value.locationPermission)
        assertEquals(0, repository.discoveries)
    }

    @Test fun `settings grant enables start without starting automatically`() = runTest {
        val repository = RecordingRepository()
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true))
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
        advanceUntilIdle()
        assertTrue(vm.state.value.canStart)
        assertEquals(0, repository.discoveries)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Finished, vm.state.value.phase)
    }

    @Test fun `location off blocks start until enabled`() = runTest {
        val vm = newViewModel(RecordingRepository())
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, false))
        assertFalse(vm.state.value.canStart)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
        assertTrue(vm.state.value.canStart)
    }

    @Test fun `coordinates reach discovery before any ping or download`() = runTest {
        val repository = RecordingRepository()
        val coordinates = Coordinates(50.0, 14.0)
        val location = object : LocationRepository {
            override suspend fun getCurrentCoordinates(): Coordinates {
                delay(1_000)
                return coordinates
            }
        }
        val vm = newViewModel(repository, location)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(TestPhase.Locating, vm.state.value.phase)
        assertEquals(0, repository.discoveries)
        assertTrue(repository.pinged.isEmpty())
        advanceUntilIdle()
        assertEquals(coordinates, repository.receivedCoordinates)
    }

    @Test fun `screen exit cancels coordinate acquisition and never starts discovery`() = runTest {
        var cancelled = false
        val repository = RecordingRepository()
        val location = object : LocationRepository {
            override suspend fun getCurrentCoordinates(): Coordinates {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val vm = newViewModel(repository, location)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        vm.onIntent(SpeedTestIntent.ScreenLeft)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(TestPhase.Idle, vm.state.value.phase)
        assertEquals(0, repository.discoveries)
    }

    @Test fun `location errors do not start server discovery`() = runTest {
        for (failure in LocationFailure.entries) {
            val repository = RecordingRepository()
            val location = object : LocationRepository {
                override suspend fun getCurrentCoordinates(): Coordinates = throw LocationException(failure)
            }
            val vm = newViewModel(repository, location)
            vm.onIntent(SpeedTestIntent.Start)
            advanceUntilIdle()
            assertEquals(failure, vm.state.value.locationFailure)
            assertEquals(0, repository.discoveries)
            assertEquals(failure == LocationFailure.Unavailable, vm.state.value.canStart)
        }
    }

    @Test fun `revoking permission cancels an active test`() = runTest {
        val repository = RecordingRepository()
        val vm = newViewModel(repository)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertTrue(repository.downloading)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true))
        advanceUntilIdle()
        assertFalse(repository.downloading)
        assertFalse(vm.state.value.canStart)
        assertEquals(TestPhase.Idle, vm.state.value.phase)
    }

    @Test fun `unchecked permission never starts location acquisition`() = runTest {
        var requested = false
        val location = object : LocationRepository {
            override suspend fun getCurrentCoordinates(): Coordinates {
                requested = true
                return Coordinates(0.0, 0.0)
            }
        }
        val vm = SpeedTestViewModel(RunSpeedTest(RecordingRepository(), location))
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertFalse(requested)
        assertFalse(vm.state.value.canStart)
    }

    private fun newViewModel(
        repository: SpeedTestRepository,
        location: LocationRepository = object : LocationRepository {
            override suspend fun getCurrentCoordinates() = Coordinates(0.0, 0.0)
        },
    ): SpeedTestViewModel = SpeedTestViewModel(RunSpeedTest(repository, location)).apply {
        onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
    }

    private open class RecordingRepository(
        var empty: Boolean = false,
        private val unreachable: Set<String> = emptySet(),
    ) : SpeedTestRepository {
        var discoveries = 0
        var receivedCoordinates: Coordinates? = null
        val pinged = mutableListOf<String>()
        var downloadedNode: Node? = null
        var downloading = false

        override suspend fun getNearestNodes(coordinates: Coordinates): List<Node> {
            discoveries++
            receivedCoordinates = coordinates
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
