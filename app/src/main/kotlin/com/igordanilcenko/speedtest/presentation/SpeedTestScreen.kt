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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.model.NearbyNode

@Composable
fun SpeedTestScreen(
    state: SpeedTestUiState,
    onIntent: (SpeedTestIntent) -> Unit,
    onOpenPreferences: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            if (state.locationPermission == LocationPermission.Denied || !state.locationEnabled) {
                LocationPermissionContent(
                    message = stringResource(if (state.locationPermission == LocationPermission.Denied)
                        R.string.location_permission_denied else R.string.location_disabled),
                    onOpenPreferences = onOpenPreferences,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
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
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(state.status), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge)
                    if (state.isRunning) CircularProgressIndicator()
                    if (state.nodes.isNotEmpty()) {
                        NearbyNodesContent(state.nodes, modifier = Modifier.fillMaxWidth())
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
private fun NearbyNodesContent(nodes: List<NearbyNode>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        nodes.forEach { nearby ->
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(nearby.node.name, style = MaterialTheme.typography.titleMedium)
                Text("${nearby.node.host}:${nearby.node.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.distance_km, nearby.distanceKm))
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LocationPermissionContent(
    message: String,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
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
        TestPhase.NodesReady -> R.string.nearby_servers
        TestPhase.Error -> when (failure) {
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

@Preview(showBackground = true, widthDp = 320, heightDp = 640)
@Composable
private fun IdlePreview() {
    MaterialTheme { SpeedTestScreen(SpeedTestUiState(locationPermission = LocationPermission.NotRequested), {}) }
}
