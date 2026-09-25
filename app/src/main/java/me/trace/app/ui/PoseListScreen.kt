package me.trace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.trace.app.data.PoseAsset

@Composable
fun PoseListScreen(
    assets: List<PoseAsset>,
    onSelect: (PoseAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("구도 고르기", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${assets.size}개의 자세",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(assets, key = { it.id }) { asset ->
                PoseCard(asset = asset, onClick = { onSelect(asset) })
            }
        }
    }
}

@Composable
private fun PoseCard(asset: PoseAsset, onClick: () -> Unit) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 카메라 프리뷰와 같은 세로 비율로 보여줘야 목록에서 고른 구도가 그대로 재현된다.
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2A2A2A))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            PoseOverlay(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                // 자산 목록은 포즈 모양을 보는 곳이라 인물을 꽉 채운다.
                // 구조선은 원본 프레임 기준이라 이 모드에서 같이 그리면 인물과 어긋난다.
                placement = Placement.Fill,
                showStructures = false,
            )
        }
        Text(
            asset.id,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
