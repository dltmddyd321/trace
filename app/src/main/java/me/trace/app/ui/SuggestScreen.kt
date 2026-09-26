package me.trace.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.trace.app.ai.PlacementAdvisor
import me.trace.app.ai.PlacementSuggestion
import me.trace.app.data.PoseAsset

/**
 * 찍은 배경을 AI에 보내 구도를 제안받고, 고른 안을 카메라로 넘긴다.
 *
 * 제안을 하나만 내밀지 않고 2~3안을 보여주는 이유는, 검증 단계에서 AI 좌표가 한 번에
 * 맞지 않을 수 있다는 걸 확인했기 때문이다. 여럿 중에 고르게 하면 빗나갔을 때 빠져나갈 길이 있다.
 */
@Composable
fun SuggestScreen(
    background: Bitmap,
    assets: List<PoseAsset>,
    onConfirm: (PoseAsset, PlacementSuggestion) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val advisor = remember { PlacementAdvisor() }
    val scope = rememberCoroutineScope()

    var request by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<PlacementSuggestion>>(emptyList()) }
    var selected by remember { mutableStateOf<PlacementSuggestion?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("뒤로") }
            Text("구도 추천받기", style = MaterialTheme.typography.titleMedium)
            Box(modifier = Modifier.width(64.dp))
        }

        BackgroundPreview(
            background = background,
            asset = selected?.let { pick -> assets.firstOrNull { it.id == pick.asset } },
            suggestion = selected,
            modifier = Modifier.padding(top = 12.dp),
        )

        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            label = { Text("어떻게 찍고 싶은지 (선택)") },
            placeholder = { Text("예) 창가에 앉은 모습으로, 무드있게") },
            minLines = 2,
        )

        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    advisor.suggest(background, request, assets.map { it.id })
                        .onSuccess {
                            suggestions = it
                            selected = it.firstOrNull()
                        }
                        .onFailure { error = it.message ?: "추천을 받지 못했습니다" }
                    loading = false
                }
            },
            enabled = !loading && advisor.isAvailable,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            } else {
                Text(if (suggestions.isEmpty()) "추천받기" else "다시 추천받기")
            }
        }

        if (!advisor.isAvailable) {
            Notice("local.properties 에 anthropicApiKey 를 넣으면 추천 기능이 켜집니다")
        }
        error?.let { Notice(it) }

        if (loading) {
            Notice("바닥과 수평선을 찾고, 샷 유형을 고르는 중…")
        }

        suggestions.forEach { suggestion ->
            SuggestionRow(
                suggestion = suggestion,
                selected = suggestion == selected,
                onClick = { selected = suggestion },
            )
        }

        selected?.let { pick ->
            val asset = assets.firstOrNull { it.id == pick.asset }
            if (asset != null) {
                Button(
                    onClick = { onConfirm(asset, pick) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("이 구도로 찍기") }
            }
        }
    }
}

@Composable
private fun BackgroundPreview(
    background: Bitmap,
    asset: PoseAsset?,
    suggestion: PlacementSuggestion?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = background.asImageBitmap(),
            contentDescription = "촬영한 배경",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (asset != null && suggestion != null) {
            PoseOverlay(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                placement = Placement(suggestion.box),
                crop = Crop.from(suggestion.crop),
                structures = suggestion.structures,
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: PlacementSuggestion,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(
            "${suggestion.shot} · ${suggestion.crop} · ${suggestion.asset}",
            style = MaterialTheme.typography.labelLarge,
        )
        if (suggestion.reason.isNotBlank()) {
            Text(
                suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
}
