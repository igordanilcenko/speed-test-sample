package com.igordanilcenko.speedtest

import android.os.Bundle
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
import com.igordanilcenko.speedtest.data.StubSpeedTestRepository
import com.igordanilcenko.speedtest.data.AndroidLocationRepository
import com.igordanilcenko.speedtest.domain.RunSpeedTest
import com.igordanilcenko.speedtest.presentation.SpeedTestRoute
import com.igordanilcenko.speedtest.presentation.SpeedTestViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val viewModel: SpeedTestViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SpeedTestViewModel(RunSpeedTest(StubSpeedTestRepository(), AndroidLocationRepository(applicationContext)))
                    }
                })
                SpeedTestRoute(viewModel, isChangingConfigurations = { this@MainActivity.isChangingConfigurations })
            }
        }
    }
}
