package me.trace.app.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Size
import java.util.concurrent.Executors
import me.trace.app.camera.SilentCapture
import me.trace.app.camera.saveToGallery
import me.trace.app.data.PoseAsset
import me.trace.app.data.Structure

/**
 * 카메라 프리뷰. 두 가지로 쓰인다.
 *
 * - 구도를 얹고 찍기: [asset] 을 주면 오버레이가 뜨고, 찍은 사진은 갤러리로 간다
 * - 배경만 찍기: [asset] 이 null 이고 [onCaptured] 를 주면 저장하지 않고 비트맵을 넘긴다
 */
@Composable
fun CameraScreen(
    asset: PoseAsset?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    placement: Placement = Placement.Original,
    crop: Crop = Crop.Full,
    structures: List<Structure> = emptyList(),
    onCaptured: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    val permission = rememberCameraPermission()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (permission.state) {
            PermissionState.Granted -> CameraContent(
                asset = asset,
                placement = placement,
                crop = crop,
                structures = structures,
                onCaptured = onCaptured,
                onBack = onBack,
            )
            PermissionState.Denied -> PermissionNotice(
                message = "구도를 화면에 겹쳐 보여주려면 카메라가 필요합니다.",
                actionLabel = "권한 허용하기",
                onAction = permission.request,
                onBack = onBack,
            )
            PermissionState.PermanentlyDenied -> PermissionNotice(
                message = "카메라 권한이 꺼져 있습니다. 설정에서 켜주세요.",
                actionLabel = "설정 열기",
                onAction = permission.openSettings,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun CameraContent(
    asset: PoseAsset?,
    placement: Placement,
    crop: Crop,
    structures: List<Structure>,
    onCaptured: ((android.graphics.Bitmap) -> Unit)?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showOverlay by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val silentCapture = remember { SilentCapture() }
    // 분석 콜백은 카메라 스레드를 잡으므로 전용 실행기에 태운다.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            // 기본값이 640x480이라 그대로 두면 저장 화질이 못 쓸 수준이 된다.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1080, 1920),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, silentCapture.analyzer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // 오버레이 좌표는 화면을 꽉 채운 프리뷰를 전제로 계산되므로
                    // 여백을 남기는 FIT 대신 잘라내는 FILL이어야 가이드와 실제 화각이 맞는다.
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                }, androidx.core.content.ContextCompat.getMainExecutor(context))
            },
        )

        if (showOverlay && asset != null) {
            PoseOverlay(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                placement = placement,
                crop = crop,
                // 경로 B는 AI가 배경에서 고른 선을, 경로 A는 자산이 들고 있는 선을 쓴다.
                structures = structures.ifEmpty { asset.structures },
            )
        }

        TopBar(
            hint = asset?.hint.orEmpty(),
            showOverlay = showOverlay,
            overlayToggleEnabled = asset != null,
            onToggleOverlay = { showOverlay = !showOverlay },
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ShutterButton(
            message = message,
            onClick = {
                silentCapture.requestFrame { bitmap ->
                    if (onCaptured != null) onCaptured(bitmap)
                    else saveToGallery(context, bitmap) { message = it }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // 화면을 벗어나면 카메라를 놓아준다. 안 그러면 다른 앱이 카메라를 못 잡는다.
    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            analysisExecutor.shutdown()
        }
    }
}

@Composable
private fun TopBar(
    hint: String,
    showOverlay: Boolean,
    overlayToggleEnabled: Boolean,
    onToggleOverlay: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("뒤로", color = Color.White) }
            if (overlayToggleEnabled) {
                TextButton(onClick = onToggleOverlay) {
                    Text(if (showOverlay) "가이드 끄기" else "가이드 켜기", color = Color.White)
                }
            }
        }
        if (hint.isNotBlank()) {
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun ShutterButton(
    message: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (message != null) {
            Text(message, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
        }
        // 카메라 셔터는 라벨 없이 형태로 알아보는 컨트롤이다. 글자를 넣으면 원형 안에서 눌린다.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
                .border(3.dp, Color.White, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "촬영" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun PermissionNotice(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) {
            Text(actionLabel)
        }
        TextButton(onClick = onBack) { Text("돌아가기", color = Color.White) }
    }
}
