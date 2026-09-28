package com.igordanilcenko.speedtest.presentation

import androidx.lifecycle.ViewModelStore
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.LocationRepository
import com.igordanilcenko.speedtest.domain.PingService
import com.igordanilcenko.speedtest.domain.ServerDirectoryRepository
import com.igordanilcenko.speedtest.domain.intent.FindNearestNodes
import com.igordanilcenko.speedtest.domain.intent.SelectLowestPingServer
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.PingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val coordinates = Coordinates(0.0, 0.0)
    private val location = object : LocationRepository {
        override suspend fun getCurrentCoordinates() = coordinates
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start obtains location and finishes with five sorted candidates`() = runTest {
        val directory = Directory()
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(TestPhase.FindingNodes, vm.state.value.phase)
        advanceUntilIdle()
        assertEquals(TestPhase.ServerReady, vm.state.value.phase)
        assertEquals("4", vm.state.value.selectedServer?.node?.id)
        assertEquals(listOf("0", "1", "2", "3", "4"), vm.state.value.nodes.map { it.node.id })
        assertFalse(vm.state.value.isRunning)
        assertEquals(1, directory.requests)
        val result = vm.state.value
        advanceTimeBy(30_000)
        assertEquals(result, vm.state.value)
    }

    @Test
    fun `stop and exit cancel request and reset without stale results`() = runTest {
        for (intent in listOf(SpeedTestIntent.Stop, SpeedTestIntent.ScreenLeft)) {
            val directory = Directory()
            val vm = viewModel(directory)
            vm.onIntent(SpeedTestIntent.Start)
            runCurrent()
            vm.onIntent(intent)
            advanceUntilIdle()
            assertTrue(directory.cancelled)
            assertEquals(TestPhase.Idle, vm.state.value.phase)
            assertTrue(vm.state.value.nodes.isEmpty())
        }
    }

    @Test
    fun `clearing viewmodel cancels directory request`() = runTest {
        val directory = Directory()
        val vm = viewModel(directory)
        val store = ViewModelStore().apply { put("test", vm) }
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertTrue(directory.cancelled)
    }

    @Test
    fun `double start is ignored and immediate restart works`() = runTest {
        val directory = Directory()
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.Start)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(1, directory.requests)
        vm.onIntent(SpeedTestIntent.Stop)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(2, directory.requests)
        assertEquals(TestPhase.ServerReady, vm.state.value.phase)
    }

    @Test
    fun `empty directory shows error and can be retried`() = runTest {
        val directory = Directory(empty = true)
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(DirectoryFailure.NoServers, vm.state.value.failure)
        directory.empty = false
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.ServerReady, vm.state.value.phase)
    }

    @Test
    fun `permission denial blocks start and grant only enables it`() = runTest {
        val directory = Directory()
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true))
        vm.onIntent(SpeedTestIntent.Start)
        vm.onIntent(SpeedTestIntent.ScreenLeft)
        advanceUntilIdle()
        assertFalse(vm.state.value.canStart)
        assertEquals(0, directory.requests)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
        advanceUntilIdle()
        assertTrue(vm.state.value.canStart)
        assertEquals(0, directory.requests)
    }

    @Test
    fun `location disabled blocks start until enabled`() = runTest {
        val vm = viewModel(Directory())
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, false))
        assertFalse(vm.state.value.canStart)
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
        assertTrue(vm.state.value.canStart)
    }

    @Test
    fun `discovery waits for coordinates and exit cancels location request`() = runTest {
        var cancelled = false
        val directory = Directory()
        val pendingLocation = object : LocationRepository {
            override suspend fun getCurrentCoordinates(): Coordinates {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val vm = viewModel(directory, pendingLocation)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        assertEquals(TestPhase.Locating, vm.state.value.phase)
        assertEquals(0, directory.requests)
        vm.onIntent(SpeedTestIntent.ScreenLeft)
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    @Test
    fun `location failure prevents directory request`() = runTest {
        for (failure in LocationFailure.entries) {
            val directory = Directory()
            val failedLocation = object : LocationRepository {
                override suspend fun getCurrentCoordinates(): Coordinates = throw LocationException(failure)
            }
            val vm = viewModel(directory, failedLocation)
            vm.onIntent(SpeedTestIntent.Start)
            advanceUntilIdle()
            assertEquals(failure, vm.state.value.locationFailure)
            assertEquals(0, directory.requests)
        }
    }

    @Test
    fun `timeout ends loading and allows retry`() = runTest {
        val directory = object : ServerDirectoryRepository {
            override suspend fun getNodes(): List<Node> = awaitCancellation()
        }
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertTrue(vm.state.value.canStart)
    }

    @Test
    fun `permission revocation cancels active request`() = runTest {
        val directory = Directory()
        val vm = viewModel(directory)
        vm.onIntent(SpeedTestIntent.Start)
        runCurrent()
        vm.onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true))
        advanceUntilIdle()
        assertTrue(directory.cancelled)
        assertFalse(vm.state.value.canStart)
    }

    private fun viewModel(
        directory: ServerDirectoryRepository,
        locationRepository: LocationRepository = location,
        log: (String) -> Unit = {},
        pingService: PingService = PingService { host ->
            delay(100)
            PingResult.Success(10.0 - host.removePrefix("server").substringBefore('.').toInt())
        },
    ) = SpeedTestViewModel(
        FindNearestNodes(directory, locationRepository, dispatcher),
        SelectLowestPingServer(pingService),
        log,
    ).apply {
        onIntent(SpeedTestIntent.LocationAccessChanged(LocationPermission.Granted, true))
    }

    @Test
    fun `stop exit and permission revocation cancel all ping jobs`() = runTest {
        for (intent in listOf(
            SpeedTestIntent.Stop, SpeedTestIntent.ScreenLeft,
            SpeedTestIntent.LocationAccessChanged(LocationPermission.Denied, true)
        )) {
            var cancelled = 0
            val vm = viewModel(Directory(), pingService = PingService {
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            })
            vm.onIntent(SpeedTestIntent.Start)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(TestPhase.Pinging, vm.state.value.phase)
            assertTrue(vm.state.value.isRunning)
            assertFalse(vm.state.value.canStart)
            vm.onIntent(intent)
            advanceUntilIdle()
            assertEquals(5, cancelled)
            assertEquals(TestPhase.Idle, vm.state.value.phase)
            assertNull(vm.state.value.selectedServer)
        }
    }

    @Test
    fun `clearing viewmodel cancels pings`() = runTest {
        var cancelled = 0
        val vm = viewModel(Directory(), pingService = PingService {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        })
        val store = ViewModelStore().apply { put("test", vm) }
        vm.onIntent(SpeedTestIntent.Start)
        advanceTimeBy(500)
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertEquals(5, cancelled)
    }

    @Test
    fun `failed pings allow retry without stale selection`() = runTest {
        var reachable = false
        val vm = viewModel(Directory(), pingService = PingService {
            if (reachable) PingResult.Success(12.5) else PingResult.NoReply
        })
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.Error, vm.state.value.phase)
        assertTrue(vm.state.value.pingFailed)
        assertTrue(vm.state.value.canStart)
        assertNull(vm.state.value.selectedServer)
        reachable = true
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertEquals(TestPhase.ServerReady, vm.state.value.phase)
        assertFalse(vm.state.value.pingFailed)
        vm.onIntent(SpeedTestIntent.Stop)
        assertNull(vm.state.value.selectedServer)
    }

    @Test
    fun `logs location success candidate list and lowest ping selection in order`() = runTest {
        val logs = mutableListOf<String>()
        val vm = viewModel(Directory(), log = { logs += it })
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        val located = logs.indexOfFirst { it.contains("Location received") }
        val candidates = logs.indexOfFirst { it.contains("Nearest servers (5)") }
        val selected = logs.indexOfFirst { it.contains("Selected lowest RTT") }
        assertTrue(located in 0..<candidates && selected > candidates)
        assertTrue(logs[candidates].contains("1. [NEAREST] Server 0 | server0.test:80 | 0.00 km"))
        assertEquals(6, logs[candidates].lines().size)
    }

    @Test
    fun `logs location failure reason without starting server lookup`() = runTest {
        val logs = mutableListOf<String>()
        val denied = object : LocationRepository {
            override suspend fun getCurrentCoordinates(): Coordinates =
                throw LocationException(LocationFailure.PermissionDenied)
        }
        val vm = viewModel(Directory(), denied, log = { logs += it })
        vm.onIntent(SpeedTestIntent.Start)
        advanceUntilIdle()
        assertTrue(logs.any { it.contains("LocationException (PermissionDenied)") })
        assertFalse(logs.any { it.contains("loading server directory") })
    }

    private class Directory(var empty: Boolean = false) : ServerDirectoryRepository {
        var requests = 0
        var cancelled = false
        override suspend fun getNodes(): List<Node> {
            requests++
            try {
                delay(500)
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw error
            }
            return if (empty) emptyList() else (9 downTo 0).map {
                Node("$it", "Server $it", "server$it.test", 80, Coordinates(0.0, it.toDouble()))
            }
        }
    }
}
