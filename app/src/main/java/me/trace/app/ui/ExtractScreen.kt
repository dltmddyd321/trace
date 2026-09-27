package me.trace.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.trace.app.data.PoseAsset
import me.trace.app.extract.PoseExtractor
import kotlin.math.max

/**
 * 갤러리에서 고른 사진의 자세를 본떠 템플릿으로 만든다.
 *
 * tools/extract.py 와 같은 일을 온디바이스로 한다. 사진은 앱 밖으로 나가지 않고,
 * 남는 것도 좌표뿐이라 원본이 저장되지 않는다.
 */
@Composable
fun ExtractScreen(
    onConfirm: (PoseAsset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extractor = remember { PoseExtractor(context) }

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var asset by remember { mutableStateOf<PoseAsset?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            asset = null
            val bitmap = withContext(Dispatchers.IO) { context.loadBitmap(uri) }
            source = bitmap
            if (bitmap == null) {
                error = "사진을 불러오지 못했습니다"
            } else {
                extractor.extract(bitmap, id = "user_${System.currentTimeMillis()}")
                    .onSuccess { asset = it }
                    .onFailure { error = it.message ?: "자세를 읽지 못했습니다" }
            }
            loading = false
        }
    }

    // 들어오자마자 선택기를 띄운다. 빈 화면을 한 번 더 거칠 이유가 없다.
    LaunchedEffect(Unit) {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("뒤로") }
            Text("사진에서 따오기", style = MaterialTheme.typography.titleMedium)
            Box(modifier = Modifier.width(64.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C1C)),
            contentAlignment = Alignment.Center,
        ) {
            source?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "고른 사진",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            asset?.let {
                // 원본 위에 겹쳐 보여줘야 제대로 따졌는지 눈으로 판단할 수 있다.
                PoseOverlay(
                    asset = it,
                    modifier = Modifier.fillMaxSize(),
                    showStructures = false,
                )
            }
            if (loading) CircularProgressIndicator()
            if (source == null && !loading) {
                Text("사진을 고르면 자세를 본떠옵니다", color = Color.White)
            }
        }

        asset?.let {
            Text(
                "관절 ${it.person.joints.size}개 · 윤곽 ${it.person.silhouette.size}점",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        OutlinedButton(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("다른 사진 고르기") }

        asset?.let { picked ->
            Button(
                onClick = { onConfirm(picked) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("이 자세로 찍기") }
        }
    }
}

/**
 * 화면에 띄우고 추출에 넘길 만큼만 줄여서 읽는다.
 *
 * 요즘 폰 사진은 4000×3000 이 흔해 ARGB 로 펼치면 한 장에 48MB다. 원본을 그대로 들고
 * 그리면서 추출용 복사본까지 만들면 메모리가 버티지 못한다. 실루엣 품질은 이 해상도에서
 * 충분하므로 디코딩 단계에서 줄이는 게 맞다.
 */
private const val LOAD_MAX_EDGE = 1600

private fun android.content.Context.loadBitmap(uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            // 하드웨어 비트맵은 픽셀을 직접 읽을 수 없어 추출이 실패한다. 소프트웨어로 강제한다.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true

            val longest = max(info.size.width, info.size.height)
            if (longest > LOAD_MAX_EDGE) {
                val ratio = LOAD_MAX_EDGE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).toInt(),
                    (info.size.height * ratio).toInt(),
                )
            }
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }
}.getOrNull()
