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

/**
 * 앞의 것부터 쓰고, 혼잡하면 다음으로 넘어간다.
 * 무료 티어는 모델마다 혼잡도가 달라서, 기다리는 것보다 옮겨 타는 쪽이 빠를 때가 많다.
 */
private val MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")

private fun endpointFor(model: String) =
    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

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
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
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
        availableAssets: List<AssetSummary>,
    ): Result<SuggestionResult> = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext Result.failure(IllegalStateException("API 키가 설정되지 않았습니다"))
        }

        runCatching {
            val body = json.encodeToString(
                GenerateRequest.serializer(),
                GenerateRequest(
                    systemInstruction = Content(
                        parts = listOf(Part(text = systemPrompt(availableAssets)))
                    ),
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(inlineData = InlineData(data = background.toBase64Jpeg())),
                                Part(text = userText(userRequest)),
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(),
                )
            )

            val payload = requestWithFallback(body)

            val text = json.decodeFromString<GenerateResponse>(payload)
                .candidates.firstOrNull()?.content?.parts
                ?.firstNotNullOfOrNull { it.text }
                ?: error("응답에 내용이 없습니다")

            val envelope = json.decodeFromString<SuggestionEnvelope>(text.extractJsonObject())
            when {
                envelope.suggestions.isNotEmpty() -> SuggestionResult.Ready(envelope.suggestions)
                envelope.unavailable.isNotBlank() -> SuggestionResult.Unavailable(envelope.unavailable)
                else -> SuggestionResult.Unavailable("이 사진으로는 구도를 잡기 어렵습니다")
            }
        }
    }

    /**
     * 혼잡하면 잠시 기다렸다가 재시도하고, 그래도 안 되면 다음 모델로 옮겨 탄다.
     * 키나 요청 자체가 잘못된 경우는 기다려도 달라지지 않으므로 즉시 알린다.
     */
    private fun requestWithFallback(body: String): String {
        var lastMessage = "요청에 실패했습니다"

        MODELS.forEach { model ->
            val request = Request.Builder()
                .url(endpointFor(model))
                // 키를 쿼리 문자열이 아니라 헤더로 보낸다. URL 은 로그·프록시에 남는다.
                .addHeader("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string().orEmpty()

                    when (response.code) {
                        503, 429, 500 -> lastMessage = "AI 서버가 혼잡합니다"
                        401, 403 -> error("API 키가 올바르지 않습니다")
                        else -> return@use  // 이 모델만의 문제일 수 있으니 다음 모델로 넘어간다
                    }
                }
                if (attempt < RETRY_DELAYS_MS.lastIndex) Thread.sleep(delayMs)
            }
        }
        error("$lastMessage. 잠시 후 다시 시도해주세요")
    }
}

private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000)

private fun userText(userRequest: String): String =
    if (userRequest.isBlank()) {
        "이 배경에서 인물 사진을 찍으려고 합니다. 구도를 제안해주세요."
    } else {
        "이 배경에서 인물 사진을 찍으려고 합니다. 요청: $userRequest"
    }

/** 프롬프트에 넘기는 자산 요약. 비율을 모르면 AI가 박스를 자산에 맞게 잡을 수 없다. */
data class AssetSummary(val id: String, val shot: String, val aspect: String)

private fun systemPrompt(assets: List<AssetSummary>) = """
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
${assets.joinToString("\n") { "- " + it.id + " (" + it.shot + ", 가로세로비 " + it.aspect + ")" }}

**box 의 가로세로비를 자산의 비율에 맞춰야 한다.** 비율이 어긋나면 렌더러가 비율을 지키느라
인물을 통째로 줄여버려 의도한 크기보다 훨씬 작게 들어간다. 앉은 자세는 다리를 뻗어 가로로 넓고,
선 자세는 좁고 길다. 원하는 세로 길이를 먼저 정한 뒤 가로는 비율을 곱해 구한다.

## 인물을 세울 수 없는 배경이면 그렇다고 답한다

억지로 제안하지 않는다. 다음 같은 사진은 인물 구도를 잡을 수 없다.
- 접사나 사물 위주라 사람이 설 깊이가 없는 사진 (음식, 소품, 문서)
- 하늘·벽면처럼 거리 기준이 없어 인물 크기를 정할 수 없는 사진
- 이미 인물이 화면을 채우고 있어 배경으로 쓸 수 없는 사진
- 너무 어둡거나 흐려 공간 구조를 읽을 수 없는 사진

이 경우 suggestions 를 비우고 unavailable 에 **왜 어려운지와 어떤 배경이면 되는지**를
한두 문장으로 쓴다. 찍는 사람이 다음에 무엇을 하면 되는지 알 수 있어야 한다.

{"suggestions":[],"unavailable":"음식이 화면을 채우고 있어 사람이 설 자리가 없습니다. 한 걸음 물러나 테이블과 주변 공간이 함께 보이게 찍어보세요"}

## 구조선
구도를 지탱하는 배경 선을 최대 3개 고른다. 테이블 모서리, 창틀, 벽 경계, 수평선, 바닥 경계처럼
찍는 사람이 화면을 맞출 기준이 되는 선이다. 많이 넣으면 가이드가 아니라 복잡한 선화가 된다.
type 은 table_edge / window_frame / wall_line / horizon / floor_line 중에서만 쓴다.
line 은 [[x0,y0],[x1,y1]] 두 점이며 화면 전체 기준 0~1 좌표다.

## 출력
서로 다른 구도 2~3개를 제안한다. JSON만 출력하고 다른 말은 쓰지 않는다.
box 는 크롭되어 안 보이는 부분까지 포함한 전신 범위다.
reason 은 왜 이 구도인지 한 문장으로 쓴다.

{"suggestions":[{"shot":"cropped","asset":"pose03","box":[0.07,0.38,0.53,1.55],"crop":"thigh","structures":[{"type":"table_edge","line":[[0.0,0.72],[0.55,0.62]]}],"hint":"창쪽을 보게 하고 시선 방향에 여백을 두세요","reason":"바닥이 의자로 덮여 있어 전신은 어렵고, 창의 수직선이 인물을 받쳐줍니다"}]}
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
