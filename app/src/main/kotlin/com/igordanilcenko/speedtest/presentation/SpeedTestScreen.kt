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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.igordanilcenko.speedtest.R
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SelectedServer
import com.igordanilcenko.speedtest.domain.TEST_DURATION_MILLIS
import com.igordanilcenko.speedtest.domain.TestFailure
import java.util.Locale

@Composable
fun SpeedTestScreen(state: SpeedTestUiState, onIntent: (SpeedTestIntent) -> Unit) {
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            if (state.phase == TestPhase.Idle) {
                Button(
                    onClick = { onIntent(SpeedTestIntent.Start) },
                    shape = CircleShape,
                    modifier = Modifier.size(192.dp),
                ) {
                    Text(stringResource(R.string.start), style = MaterialTheme.typography.headlineMedium)
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(state.status), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge)
                    if (state.phase == TestPhase.FindingNodes || state.phase == TestPhase.Pinging) {
                        CircularProgressIndicator()
                    }
                    state.selectedServer?.let { server ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(if (state.phase == TestPhase.Finished)
                                R.string.average_speed else R.string.download_speed))
                            Text(
                                state.speedMbps?.let { String.format(Locale.ENGLISH, "%.1f", it) } ?: "--",
                                style = MaterialTheme.typography.displayMedium,
                            )
                            Text(stringResource(R.string.mbps))
                        }
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.selected_server), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(server.node.name, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.ping_response_time), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.ping_value, server.pingMs), style = MaterialTheme.typography.titleLarge)
                        }
                        if (state.phase == TestPhase.Measuring) {
                            LinearProgressIndicator(
                                progress = { (state.elapsedMillis.toFloat() / TEST_DURATION_MILLIS).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.time_remaining,
                                ((TEST_DURATION_MILLIS - state.elapsedMillis).coerceAtLeast(0) + 999) / 1_000))
                        }
                    }
                    Text(stringResource(R.string.demo_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { onIntent(if (state.isRunning) SpeedTestIntent.Stop else SpeedTestIntent.Start) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(stringResource(if (state.isRunning) R.string.stop else R.string.start))
                    }
                }
            }
        }
    }
}

private val SpeedTestUiState.status: Int
    get() = when (phase) {
        TestPhase.Idle -> R.string.start
        TestPhase.FindingNodes -> R.string.finding_nodes
        TestPhase.Pinging -> R.string.pinging
        TestPhase.Measuring -> R.string.measuring
        TestPhase.Finished -> R.string.finished
        TestPhase.Error -> when (failure) {
            TestFailure.NoServers -> R.string.no_servers
            TestFailure.NoPingReplies -> R.string.no_ping_replies
            TestFailure.NoMeasurements -> R.string.no_measurements
            null -> R.string.test_error
        }
    }

@Preview(showBackground = true, widthDp = 320, heightDp = 640)
@Composable
private fun IdlePreview() {
    MaterialTheme { SpeedTestScreen(SpeedTestUiState(), {}) }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun MeasuringPreview() {
    MaterialTheme {
        SpeedTestScreen(SpeedTestUiState(
            phase = TestPhase.Measuring,
            selectedServer = SelectedServer(Node("demo-2", "Demo 3", 38), 12.0),
            speedMbps = 84.6,
            elapsedMillis = 5_000,
        ), {})
    }
}
