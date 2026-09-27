package me.trace.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onCaptureBackground: () -> Unit,
    onExtractFromPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("따라찍", style = MaterialTheme.typography.headlineLarge)
        Text(
            "구도를 고르고 그대로 따라 찍으세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Button(
            onClick = onCaptureBackground,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        ) { Text("배경 찍고 추천받기") }
        Text(
            "지금 있는 자리를 찍으면 AI가 구도를 제안합니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        OutlinedButton(
            onClick = onExtractFromPhoto,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) { Text("사진에서 따오기") }
        Text(
            "마음에 드는 사진을 고르면 그 자세를 본떠옵니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
