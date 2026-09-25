package me.trace.app

import android.graphics.Bitmap
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
import me.trace.app.ui.HomeScreen
import me.trace.app.ui.Placement
import me.trace.app.ui.PoseListScreen
import me.trace.app.ui.SuggestScreen
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

/** 화면 전이. 경로 A(PoseList)와 경로 B(BackgroundCapture → Suggest)가 Shoot 에서 합류한다. */
private sealed interface Screen {
    data object Home : Screen
    data object PoseList : Screen
    data object BackgroundCapture : Screen
    data class Suggest(val background: Bitmap) : Screen
    data class Shoot(val asset: PoseAsset, val placement: Placement) : Screen
}

@Composable
private fun TraceApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = remember { loadPoseAssets(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onPickPose = { screen = Screen.PoseList },
            onCaptureBackground = { screen = Screen.BackgroundCapture },
            modifier = modifier,
        )

        Screen.PoseList -> PoseListScreen(
            assets = assets,
            // 사진에서 본뜬 템플릿은 원래 구도를 그대로 쓴다.
            onSelect = { screen = Screen.Shoot(it, Placement.Original) },
            modifier = modifier,
        )

        Screen.BackgroundCapture -> CameraScreen(
            asset = null,
            onBack = { screen = Screen.Home },
            onCaptured = { screen = Screen.Suggest(it) },
            modifier = modifier,
        )

        is Screen.Suggest -> SuggestScreen(
            background = current.background,
            assets = assets,
            onConfirm = { asset, placement -> screen = Screen.Shoot(asset, placement) },
            onBack = { screen = Screen.BackgroundCapture },
            modifier = modifier,
        )

        is Screen.Shoot -> CameraScreen(
            asset = current.asset,
            placement = current.placement,
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )
    }
}
