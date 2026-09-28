package com.igordanilcenko.speedtest.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.igordanilcenko.speedtest.R
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.DownloadFailure
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.intent.MeasureDownloadSpeed
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement

@Composable
fun SpeedTestScreen(
    state: SpeedTestUiState,
    onIntent: (SpeedTestIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPreferences: () -> Unit = {},
) {
    Scaffold(modifier = modifier) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets), contentAlignment = Alignment.Center
        ) {
            if (state.locationPermission == LocationPermission.Denied || !state.locationEnabled) {
                LocationPermissionContent(
                    message = stringResource(
                        if (state.locationPermission == LocationPermission.Denied)
                            R.string.location_permission_denied else R.string.location_disabled
                    ),
                    onOpenPreferences = onOpenPreferences,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(),
                )
            } else if (state.phase == TestPhase.Idle) {
                Button(
                    onClick = { onIntent(SpeedTestIntent.Start) },
                    enabled = state.canStart,
                    shape = CircleShape,
                    modifier = Modifier.size(192.dp),
                ) {
                    Text(stringResource(R.string.start), style = MaterialTheme.typography.headlineMedium)
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(state.status), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (state.isRunning) CircularProgressIndicator()
                    state.download?.let {
                        DownloadContent(
                            it, state.phase == TestPhase.Finished, state.phase == TestPhase.Measuring,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.selectedServer != null) {
                        SelectedServerContent(state.selectedServer, modifier = Modifier.fillMaxWidth())
                    }
                    Button(
                        onClick = { onIntent(if (state.isRunning) SpeedTestIntent.Stop else SpeedTestIntent.Start) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.isRunning || state.canStart,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(stringResource(if (state.isRunning) R.string.stop else R.string.start))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadContent(
    measurement: SpeedMeasurement,
    finished: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(if (finished) R.string.average_speed else R.string.download_speed))
        Text(
            stringResource(R.string.speed_value, if (finished) measurement.averageMbps else measurement.currentMbps),
            style = MaterialTheme.typography.headlineMedium
        )
        if (running) {
            val remaining =
                ((MeasureDownloadSpeed.duration.inWholeMilliseconds - measurement.elapsedMillis).coerceAtLeast(0) + 999) / 1000
            Text(pluralStringResource(R.plurals.seconds_remaining, remaining.toInt(), remaining))
        }
    }
}

@Composable
private fun SelectedServerContent(server: SelectedServer, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(server.node.name, style = MaterialTheme.typography.titleMedium)
        Text("${server.node.host}:${server.node.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ping_response_time), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.ping_value, server.pingMs), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun LocationPermissionContent(
    message: String,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start))
        }
        OutlinedButton(onClick = onOpenPreferences, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.go_to_preferences))
        }
    }
}

private val SpeedTestUiState.status: Int
    get() = when (phase) {
        TestPhase.Idle -> R.string.start
        TestPhase.Locating -> R.string.getting_location
        TestPhase.FindingNodes -> R.string.finding_nodes
        TestPhase.Pinging -> R.string.pinging
        TestPhase.ServerReady -> R.string.selected_server
        TestPhase.Measuring -> R.string.measuring
        TestPhase.Finished -> R.string.finished
        TestPhase.Error -> when (downloadFailure) {
            DownloadFailure.TokenRequest -> R.string.download_token_error
            DownloadFailure.InvalidHello -> R.string.download_hello_error
            DownloadFailure.Unauthorized -> R.string.download_unauthorized
            DownloadFailure.Connection -> R.string.download_connection_error
            DownloadFailure.TruncatedResponse -> R.string.download_truncated
            DownloadFailure.Timeout -> R.string.download_timeout
            DownloadFailure.InvalidResponse -> R.string.download_invalid
            null -> if (pingFailed) R.string.no_ping_replies else when (failure) {
                DirectoryFailure.NoServers -> R.string.no_servers
                DirectoryFailure.Unavailable -> R.string.directory_unavailable
                DirectoryFailure.InvalidResponse -> R.string.directory_invalid
                null -> when (locationFailure) {
                    LocationFailure.Unavailable -> R.string.location_unavailable
                    LocationFailure.Disabled -> R.string.location_disabled
                    LocationFailure.PermissionDenied -> R.string.location_permission_denied
                    null -> R.string.test_error
                }
            }
        }
    }

private val previewNode = Node(
    id = "1",
    name = "Prague - Casablanca INT",
    host = "prg.speedtest.net",
    port = 8080,
    coordinates = Coordinates(50.08, 14.43)
)

@Composable
private fun SpeedTestPreviewWrapper(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}

@Preview(showBackground = true, name = "Permission: Not Requested")
@Composable
private fun PreviewNotRequested() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(locationPermission = LocationPermission.NotRequested),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Permission: Denied")
@Composable
private fun PreviewPermissionDenied() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(locationPermission = LocationPermission.Denied),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Location: Disabled")
@Composable
private fun PreviewLocationDisabled() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(locationEnabled = false),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Phase: Locating")
@Composable
private fun PreviewLocating() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(phase = TestPhase.Locating),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Phase: Finding Servers")
@Composable
private fun PreviewFindingNodes() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(phase = TestPhase.FindingNodes),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Phase: Pinging")
@Composable
private fun PreviewPinging() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(phase = TestPhase.Pinging),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Download: Progress")
@Composable
private fun PreviewDownloadProgress() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(
            phase = TestPhase.Measuring,
            selectedServer = SelectedServer(previewNode, 12.5),
            download = SpeedMeasurement(64.0, 40_000_000, 5_000),
            locationPermission = LocationPermission.Granted,
        ),
        onIntent = {},
    )
}

@Preview(showBackground = true, name = "Download: Result")
@Composable
private fun PreviewDownloadResult() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(
            phase = TestPhase.Finished,
            selectedServer = SelectedServer(previewNode, 12.5),
            download = SpeedMeasurement(64.0, 120_000_000, 15_000),
            locationPermission = LocationPermission.Granted,
        ),
        onIntent = {},
    )
}

@Preview(showBackground = true, name = "Phase: Server Ready")
@Composable
private fun PreviewServerReady() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(
            phase = TestPhase.ServerReady,
            selectedServer = SelectedServer(previewNode, 14.5)
        ),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Error: No Servers", group = "Errors")
@Composable
private fun PreviewErrorNoServers() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(phase = TestPhase.Error, failure = DirectoryFailure.NoServers),
        onIntent = {}
    )
}

@Preview(showBackground = true, name = "Error: No Ping Replies", group = "Errors")
@Composable
private fun PreviewErrorPingFailed() = SpeedTestPreviewWrapper {
    SpeedTestScreen(
        state = SpeedTestUiState(phase = TestPhase.Error, pingFailed = true),
        onIntent = {}
    )
}
