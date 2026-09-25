package me.trace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.trace.app.data.PoseAsset
import me.trace.app.data.loadPoseAssets
import me.trace.app.ui.CameraScreen
import me.trace.app.ui.PoseListScreen
import me.trace.app.ui.theme.TraceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TraceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TraceApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun TraceApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = remember { loadPoseAssets(context) }
    var selected by remember { mutableStateOf<PoseAsset?>(null) }

    val current = selected
    if (current == null) {
        PoseListScreen(
            assets = assets,
            onSelect = { selected = it },
            modifier = modifier,
        )
    } else {
        CameraScreen(
            asset = current,
            onBack = { selected = null },
            modifier = modifier,
        )
    }
}
