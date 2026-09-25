package me.trace.app.data

import android.content.Context
import kotlinx.serialization.json.Json

private const val POSE_DIR = "poses"

private val json = Json { ignoreUnknownKeys = true }

/**
 * assets/poses 에 번들된 포즈 자산을 읽는다.
 *
 * 자산은 빌드 시점에 고정되므로 갱신을 신경 쓸 필요가 없고, 네트워크도 타지 않는다.
 * [ignoreUnknownKeys] 를 켠 이유는 파이프라인이 디버깅용 필드(source 등)를 같이 내보내기 때문이다.
 */
fun loadPoseAssets(context: Context): List<PoseAsset> =
    context.assets.list(POSE_DIR)
        .orEmpty()
        .filter { it.endsWith(".json") }
        .sorted()
        .map { name ->
            val text = context.assets.open("$POSE_DIR/$name").bufferedReader().use { it.readText() }
            json.decodeFromString<PoseAsset>(text)
        }
