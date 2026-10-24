package app.reviewnotes.timing.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Renders on the JVM (no emulator) so screens can be checked as images while developing. */
class SmokeShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    @Test fun smoke() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).padding(24.dp)) { Text("A股择时体检 12.34 +3.21%", color = Color.White) }
        }
    }
}
