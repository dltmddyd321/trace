package me.trace.app.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateRequestTest {

    private val request = GenerateRequest(
        systemInstruction = Content(parts = listOf(Part(text = "system"))),
        contents = listOf(
            Content(parts = listOf(Part(inlineData = InlineData(data = "AAAA")), Part(text = "user")))
        ),
        generationConfig = GenerationConfig(),
    )

    @Test
    fun printsWhatTheAppSends() {
        println("기본 Json: " + Json { ignoreUnknownKeys = true }.encodeToString(GenerateRequest.serializer(), request))
    }

    @Test
    fun defaultsThatChangeModelBehaviourAreSent() {
        val body = encodeGenerateRequest(request)
        println("수정 후:   $body")
        assertTrue("JSON 강제가 빠졌습니다", body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue("생각 끄기가 빠졌습니다", body.contains("\"thinkingBudget\":0"))
        assertTrue("이미지 형식이 빠졌습니다", body.contains("\"mime_type\":\"image/jpeg\""))
        assertTrue("null 필드가 실려 나갑니다", !body.contains("null"))
    }
}
