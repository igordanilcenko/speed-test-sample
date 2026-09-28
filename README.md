# Speed Test

A small Android speed-test sample with Clean Architecture and an MVI-style, single-screen UI. Built with Kotlin, Jetpack Compose, Material 3, ViewModel, Coroutines/Flow, Retrofit with Gson, OkHttp, and AndroidX Core.

## From Start to result

```text
Start
  -> Approximate location (ACCESS_COARSE_LOCATION, AndroidX LocationManagerCompat)
  -> HTTPS GET https://sp-dir.uwn.com/api/v2/servers
  -> Keep the 5 geographically closest nodes
  -> Real ICMP ping to each node; select the lowest valid RTT
  -> HTTPS POST https://sp-dir.uwn.com/api/v1/tokens
  -> HTTP GET /hello on the selected node
  -> 4 parallel HTTP /download streams for 15 seconds
  -> Current Mbps every ~500 ms, then average download Mbps
```

Android's `LocationManager` may provide a cached location; AndroidX `LocationManagerCompat` requests a fresh one when needed. `DownloadSpeedService` receives the selected node, validates it with the temporary token, and repeatedly streams 50 MB requests without saving the bytes to disk. The screen shows the selected server, its ping, and download speed; debug logs show all five ping results.

## Architecture

```text
MainActivity (wires the components together)
    |
    v
presentation
    SpeedTestRoute       permission and lifecycle handling
    SpeedTestScreen      Compose UI
    SpeedTestIntent ---> SpeedTestViewModel ---> SpeedTestUiState (StateFlow)
                              |
                              | calls
                              v
domain
    FindNearestNodes          -> LocationRepository, ServerDirectoryRepository
    SelectLowestPingServer    -> PingService
    MeasureDownloadSpeed      -> DownloadSpeedService
    Models: Node, NearbyNode, SelectedServer, SpeedMeasurement
                              ^
                              | interfaces implemented by
data
    AndroidLocationRepository, HttpServerDirectoryRepository
    AndroidPingService, HttpDownloadSpeedService
```

The screen sends user intents to the ViewModel and renders its UI state. The domain decides which nodes to test, which ping wins, and how long to measure; its interfaces do not depend on Android or HTTP. Data adapters connect those interfaces to Android location, Retrofit, the system `ping` process, and OkHttp. `MainActivity` assembles them without a DI framework.

The run lives in `viewModelScope`, so **Stop or leaving the screen cancels active work**. Permission denial, disabled location, missing ping replies, invalid responses, and timeouts have explicit error paths.

## Run and verify

Open the project in Android Studio, run it on Android 6.0+, enable location, and tap **Start**. Debug logs use the `SpeedTest` tag. The hardcoded directory and token endpoints use HTTPS; dynamic test nodes currently use HTTP for `/hello` and `/download`, so the app allows cleartext traffic.

Compose Previews cover the main states. Unit tests cover selection, ICMP parsing, ViewModel transitions, speed math, and download behavior with MockWebServer. Run them with `./gradlew :app:testDebugUnitTest`.

## Demo

[Watch the measurement demo](media/speed-measurement.mp4)
