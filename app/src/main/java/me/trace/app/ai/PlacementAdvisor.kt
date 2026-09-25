package me.trace.app.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.trace.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
private const val MODEL = "claude-sonnet-5"
private const val API_VERSION = "2023-06-01"

/** 배경 이미지를 보낼 때 줄이는 긴 변 길이. 구도 판단에 원본 해상도는 필요 없고 토큰만 먹는다. */
private const val UPLOAD_MAX_EDGE = 900

private val json = Json { ignoreUnknownKeys = true }

/**
 * 배경 사진과 사용자의 요청을 받아 구도를 제안한다.
 *
 * 판정 기준은 tools/prompt.md 와 같은 내용이다. 파이프라인에서 손으로 수행하며 검증한 절차를
 * 그대로 옮겼다 — 샷 유형을 먼저 정하지 않으면 설 바닥이 없는 배경에 전신을 넣어 깨진다.
 */
class PlacementAdvisor(
    private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY,
) {
    val isAvailable: Boolean get() = apiKey.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 이미지를 포함한 응답은 수 초 걸린다. 기본 10초로는 정상 요청도 끊긴다.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun suggest(
        background: Bitmap,
        userRequest: String,
        availableAssets: List<String>,
    ): Result<List<PlacementSuggestion>> = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext Result.failure(IllegalStateException("API 키가 설정되지 않았습니다"))
        }

        runCatching {
            val body = json.encodeToString(
                MessagesRequest.serializer(),
                MessagesRequest(
                    model = MODEL,
                    maxTokens = 1024,
                    system = systemPrompt(availableAssets),
                    messages = listOf(
                        Message(
                            role = "user",
                            content = listOf(
                                ContentBlock(
                                    type = "image",
                                    source = ImageSource(data = background.toBase64Jpeg()),
                                ),
                                ContentBlock(type = "text", text = userText(userRequest)),
                            ),
                        )
                    ),
                )
            )

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("요청 실패 (${response.code})")

                val text = json.decodeFromString<MessagesResponse>(payload)
                    .content.firstOrNull { it.type == "text" }?.text
                    ?: error("응답에 내용이 없습니다")

                json.decodeFromString<SuggestionEnvelope>(text.extractJsonObject()).suggestions
            }
        }
    }
}

private fun userText(userRequest: String): String =
    if (userRequest.isBlank()) {
        "이 배경에서 인물 사진을 찍으려고 합니다. 구도를 제안해주세요."
    } else {
        "이 배경에서 인물 사진을 찍으려고 합니다. 요청: $userRequest"
    }

private fun systemPrompt(assets: List<String>) = """
당신은 인물 사진의 구도를 제안한다. 배경 사진을 보고, 그 자리에서 사람을 어디에 어떤 자세로
세울지 정한다.

## 1단계 — 샷 유형을 먼저 정한다
이 판단을 건너뛰면 실패한다. 설 바닥이 없는 배경에 전신을 넣으면 다리가 가구를 관통한다.
1. 비어 있는 바닥이 보이는가 — 실제로 설 수 있는 면. 의자·테이블로 덮여 있으면 아니다
2. 앉을 자리가 있는가 — 의자, 벤치, 계단, 난간
3. 둘 다 없으면 전경 크롭 샷. 인물이 카메라 가까이 서고 프레임이 하반신을 자른다
결과는 standing / sitting / cropped 중 하나다.

## 2단계 — 위치와 크기
- 시각적 무게의 반대편에 둔다. 눈길을 끄는 요소가 한쪽에 있으면 인물은 반대쪽이다
- 배경을 다 가리지 않는다. 그 장소를 고른 이유가 배경인데 인물이 덮으면 의미가 없다
- 시선 방향에 여백을 둔다
- 주변 가구·문 크기와 비교해 인물 크기가 말이 되어야 한다

## 3단계 — 크롭
full / knee / thigh / waist / chest 중 하나.
box는 0~1을 벗어나도 된다. y1이 1보다 크면 발이 화면 아래에 있다는 뜻이고 렌더러가 잘라낸다.
인물이 프레임에 전부 들어갈 필요는 없다 — 무릎이나 허벅지에서 잘린 구도가 더 자연스러울 때가 많다.

## 쓸 수 있는 포즈 자산
${assets.joinToString(", ")}
sit 으로 시작하는 것이 앉은 자세, pose 로 시작하는 것이 서 있는 자세다.
shot 이 sitting 이면 sit 자산을, 아니면 pose 자산을 고른다.

## 출력
서로 다른 구도 2~3개를 제안한다. JSON만 출력하고 다른 말은 쓰지 않는다.
box 는 크롭되어 안 보이는 부분까지 포함한 전신 범위다.
reason 은 왜 이 구도인지 한 문장으로 쓴다.

{"suggestions":[{"shot":"cropped","asset":"pose03","box":[0.07,0.38,0.53,1.55],"crop":"thigh","hint":"창쪽을 보게 하고 시선 방향에 여백을 두세요","reason":"바닥이 의자로 덮여 있어 전신은 어렵고, 창의 수직선이 인물을 받쳐줍니다"}]}
""".trimIndent()

/** 모델이 설명을 곁들이거나 코드펜스로 감싸는 경우가 있어 JSON 부분만 잘라낸다. */
private fun String.extractJsonObject(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    return if (start >= 0 && end > start) substring(start, end + 1) else this
}

private fun Bitmap.toBase64Jpeg(): String {
    val scaled = scaleToMaxEdge(UPLOAD_MAX_EDGE)
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private fun Bitmap.scaleToMaxEdge(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}
