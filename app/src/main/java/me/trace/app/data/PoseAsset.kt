package me.trace.app.data

import kotlinx.serialization.Serializable

/**
 * tools/extract.py 가 내보내는 JSON과 1:1로 맞춘다.
 * 한쪽 필드를 고치면 반드시 반대쪽도 같이 고쳐야 한다 — 어긋나면 런타임에 터진다.
 */
@Serializable
data class PoseAsset(
    val id: String,
    val category: String,
    val person: Person,
    val structures: List<Structure> = emptyList(),
    val hint: String = "",
)

@Serializable
data class Person(
    /** 정규화된 윤곽 좌표. [[x, y], ...] 순서대로 이으면 닫힌 실루엣이 된다. */
    val silhouette: List<List<Float>>,
    /** 실루엣을 감싸는 [x0, y0, x1, y1]. 배치할 때 목표 박스에 맞추는 기준이 된다. */
    val box: List<Float>,
    /** MediaPipe 랜드마크 번호 → 좌표. 가려진 관절과 얼굴(0~10)은 빠져 있다. */
    val joints: Map<String, List<Float>>,
    /** 이을 관절 쌍. joints에 없는 번호는 extract 단계에서 이미 제외됐다. */
    val edges: List<List<Int>>,
)

@Serializable
data class Structure(
    val type: String,
    /** [[x0, y0], [x1, y1]] 두 점으로 된 선분. */
    val line: List<List<Float>>,
)
