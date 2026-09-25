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
    onPickPose: () -> Unit,
    onCaptureBackground: () -> Unit,
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
            onClick = onPickPose,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) { Text("구도 고르기") }
        Text(
            "미리 준비된 자세 중에서 고릅니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
