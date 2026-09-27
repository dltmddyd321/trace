package me.trace.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.trace.app.data.Structure

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
    /** 구도를 지탱하는 배경 선. 인물이 아니라 프레임 기준 좌표다. */
    val structures: List<Structure> = emptyList(),
    /** 찍는 사람에게 건네는 한 문장. */
    val hint: String = "",
    /** 이 구도를 고른 이유. 사용자가 고를 때 판단 근거가 된다. */
    val reason: String = "",
)

@Serializable
internal data class SuggestionEnvelope(
    val suggestions: List<PlacementSuggestion> = emptyList(),
    /** 이 배경으로는 인물 구도를 잡을 수 없을 때 그 이유. 비어 있으면 제안이 나온 것이다. */
    val unavailable: String = "",
)

/** 제안 목록이거나, 왜 제안할 수 없는지에 대한 설명이거나 둘 중 하나다. */
sealed interface SuggestionResult {
    data class Ready(val suggestions: List<PlacementSuggestion>) : SuggestionResult
    data class Unavailable(val reason: String) : SuggestionResult
}

/** Gemini generateContent 요청/응답 중 실제로 쓰는 부분만 정의한다. */
@Serializable
internal data class GenerateRequest(
    @SerialName("system_instruction") val systemInstruction: Content,
    val contents: List<Content>,
    @SerialName("generationConfig") val generationConfig: GenerationConfig,
)

@Serializable
internal data class Content(
    val parts: List<Part>,
)

@Serializable
internal data class Part(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: InlineData? = null,
)

@Serializable
internal data class InlineData(
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    val data: String,
)

@Serializable
internal data class GenerationConfig(
    /** JSON 으로 답하도록 강제한다. 설명을 곁들이거나 코드펜스로 감싸는 걸 막는다. */
    @SerialName("responseMimeType") val responseMimeType: String = "application/json",
)

@Serializable
internal data class GenerateResponse(
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
internal data class Candidate(
    val content: Content? = null,
)
