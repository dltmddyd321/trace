package me.trace.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 배경을 보고 AI가 제안한 한 가지 구도. */
@Serializable
data class PlacementSuggestion(
    /** standing / sitting / cropped — 이 배경이 지원하는 샷 유형. */
    val shot: String,
    /** 쓸 포즈 자산의 id. assets/poses 에 있는 것 중 하나여야 한다. */
    val asset: String,
    /** 인물의 전신 범위 [x0, y0, x1, y1]. **0~1을 벗어날 수 있다** — 크롭된 구도다. */
    val box: List<Float>,
    /** full / knee / thigh / waist / chest */
    val crop: String = "full",
    /** 찍는 사람에게 건네는 한 문장. */
    val hint: String = "",
    /** 이 구도를 고른 이유. 사용자가 고를 때 판단 근거가 된다. */
    val reason: String = "",
)

@Serializable
internal data class SuggestionEnvelope(
    val suggestions: List<PlacementSuggestion>,
)

/** Anthropic Messages API 요청/응답 중 실제로 쓰는 부분만 정의한다. */
@Serializable
internal data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<Message>,
)

@Serializable
internal data class Message(
    val role: String,
    val content: List<ContentBlock>,
)

@Serializable
internal data class ContentBlock(
    val type: String,
    val text: String? = null,
    val source: ImageSource? = null,
)

@Serializable
internal data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String,
)

@Serializable
internal data class MessagesResponse(
    val content: List<ResponseBlock> = emptyList(),
)

@Serializable
internal data class ResponseBlock(
    val type: String,
    val text: String = "",
)
