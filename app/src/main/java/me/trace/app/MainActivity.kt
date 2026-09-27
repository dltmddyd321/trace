package me.trace.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.trace.app.data.PoseAsset
import me.trace.app.data.Structure
import me.trace.app.data.loadPoseAssets
import me.trace.app.ui.CameraScreen
import me.trace.app.ui.ExtractScreen
import me.trace.app.ui.HomeScreen
import me.trace.app.ui.loadBitmap
import me.trace.app.ui.Crop
import me.trace.app.ui.Placement
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
    data object BackgroundCapture : Screen
    data object Extract : Screen
    data class Suggest(val background: Bitmap) : Screen
    data class Shoot(
        val asset: PoseAsset,
        val placement: Placement,
        val crop: Crop = Crop.Full,
        val structures: List<Structure> = emptyList(),
    ) : Screen
}

@Composable
private fun TraceApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = remember { loadPoseAssets(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    // 지금 자리를 찍는 대신 예전 사진으로도 추천받을 수 있어야 한다.
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { context.loadBitmap(uri) }
            if (bitmap != null) screen = Screen.Suggest(bitmap)
        }
    }
    val pickBackground = {
        backgroundPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onCaptureBackground = { screen = Screen.BackgroundCapture },
            onPickBackground = pickBackground,
            onExtractFromPhoto = { screen = Screen.Extract },
            modifier = modifier,
        )

        Screen.Extract -> ExtractScreen(
            // 본뜬 자세는 원본 구도 그대로 쓴다 — 그 사진처럼 찍겠다는 뜻이므로.
            onConfirm = { screen = Screen.Shoot(it, Placement.Original) },
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )

        Screen.BackgroundCapture -> CameraScreen(
            asset = null,
            onBack = { screen = Screen.Home },
            onCaptured = { screen = Screen.Suggest(it) },
            onPickFromGallery = pickBackground,
            modifier = modifier,
        )

        is Screen.Suggest -> SuggestScreen(
            background = current.background,
            assets = assets,
            onConfirm = { asset, suggestion ->
                screen = Screen.Shoot(
                    asset = asset,
                    placement = Placement(suggestion.box),
                    crop = Crop.from(suggestion.crop),
                    structures = suggestion.structures,
                )
            },
            onBack = { screen = Screen.BackgroundCapture },
            modifier = modifier,
        )

        is Screen.Shoot -> CameraScreen(
            asset = current.asset,
            placement = current.placement,
            crop = current.crop,
            structures = current.structures,
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )
    }
}
