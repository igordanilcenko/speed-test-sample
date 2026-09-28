package com.igordanilcenko.speedtest

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.igordanilcenko.speedtest.data.AndroidLocationRepository
import com.igordanilcenko.speedtest.data.AndroidPingService
import com.igordanilcenko.speedtest.data.HttpServerDirectoryRepository
import com.igordanilcenko.speedtest.domain.intent.FindNearestNodes
import com.igordanilcenko.speedtest.domain.intent.SelectLowestPingServer
import com.igordanilcenko.speedtest.presentation.SpeedTestRoute
import com.igordanilcenko.speedtest.presentation.SpeedTestViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val log: (String) -> Unit = { message -> if (debug) Log.d("SpeedTest", message) }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val viewModel: SpeedTestViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SpeedTestViewModel(
                            findNearestNodes = FindNearestNodes(
                                repository = HttpServerDirectoryRepository.create(),
                                locationRepository = AndroidLocationRepository(applicationContext, log)
                            ),
                            selectLowestPingServer = SelectLowestPingServer(AndroidPingService()),
                            log = log,
                        )
                    }
                })
                SpeedTestRoute(viewModel, isChangingConfigurations = { this@MainActivity.isChangingConfigurations })
            }
        }
    }
}
