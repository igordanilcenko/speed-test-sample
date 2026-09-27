package com.igordanilcenko.speedtest.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igordanilcenko.speedtest.R

@Composable
fun SpeedTestRoute(
    viewModel: SpeedTestViewModel,
    isChangingConfigurations: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = remember(context) { context.getSharedPreferences("location_permission", Context.MODE_PRIVATE) }
    val manager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf(false) }

    fun refreshAccess() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val permission = when {
            granted -> LocationPermission.Granted
            preferences.getBoolean("requested", false) -> LocationPermission.Denied
            else -> LocationPermission.NotRequested
        }
        viewModel.onIntent(SpeedTestIntent.LocationAccessChanged(permission, LocationManagerCompat.isLocationEnabled(manager)))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshAccess()
        pendingStart = granted
    }

    LifecycleResumeEffect(viewModel) {
        refreshAccess()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(viewModel) {
        onStopOrDispose {
            if (!isChangingConfigurations()) {
                pendingStart = false
                showRationale = false
                viewModel.onIntent(SpeedTestIntent.ScreenLeft)
            }
        }
    }
    LaunchedEffect(pendingStart, lifecycleState) {
        if (pendingStart && lifecycleState == Lifecycle.State.RESUMED) {
            pendingStart = false
            refreshAccess()
            viewModel.onIntent(SpeedTestIntent.Start)
        }
    }

    SpeedTestScreen(
        state = state,
        modifier = modifier,
        onIntent = { intent ->
            if (intent == SpeedTestIntent.Start) {
                refreshAccess()
                if (viewModel.state.value.locationPermission == LocationPermission.NotRequested) {
                    showRationale = true
                } else {
                    viewModel.onIntent(intent)
                }
            } else {
                viewModel.onIntent(intent)
            }
        },
        onOpenPreferences = {
            val intent = if (state.locationPermission == LocationPermission.Denied) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            } else {
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            context.startActivity(intent)
        },
    )

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.location_permission_title)) },
            text = { Text(stringResource(R.string.location_permission_reason)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    preferences.edit().putBoolean("requested", true).apply()
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }
}
