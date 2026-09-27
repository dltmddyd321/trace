package me.trace.app.ui

import android.graphics.Bitmap
import android.net.Uri
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
                    // 오버레이가 프레임을 잘라 채우므로 사진도 같은 방식이어야 겹쳐진다.
                    contentScale = ContentScale.Crop,
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
