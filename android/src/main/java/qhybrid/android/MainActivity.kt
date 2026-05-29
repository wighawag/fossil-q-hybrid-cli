package qhybrid.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * WP0 skeleton Activity.
 *
 * This is intentionally empty beyond a single Compose screen. Its only job in
 * WP0 is to prove the Android module builds and links against :protocol.
 * Real UI (permission carousel, dashboard, etc.) arrives in later work packages.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Greeting()
                }
            }
        }
    }
}

@Composable
fun Greeting() {
    Text(
        text = "Fossil Q Hybrid — skeleton",
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
    )
}
