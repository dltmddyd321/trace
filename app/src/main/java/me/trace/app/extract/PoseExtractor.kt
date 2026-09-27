package me.trace.app.extract

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.trace.app.data.Person
import me.trace.app.data.PoseAsset
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MODEL = "models/pose_landmarker_lite.task"

/** 이 값 아래로 떨어지는 관절은 모델이 가려진 부위를 추측한 것이라 가이드로 그리지 않는다. */
private const val VISIBILITY_MIN = 0.5f

/** 얼굴 세부 랜드마크. 눈·코·입 위치는 구도 가이드에 쓸모가 없고 선만 지저분해진다. */
private val FACE_LANDMARKS = (0..10).toSet()

/** 윤곽을 몇 점까지 줄일지. 이미지 대각선 길이에 대한 비율로 준다. */
private const val SIMPLIFY_RATIO = 0.004

/** 추론에 쓸 최대 변 길이. 원본 해상도는 실루엣 품질에 거의 영향이 없고 시간만 먹는다. */
private const val MAX_EDGE = 1024

private val SKELETON_EDGES = listOf(
    11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
    11 to 23, 12 to 24, 23 to 24,
    23 to 25, 25 to 27, 24 to 26, 26 to 28,
)

/**
 * 사진 한 장에서 사람의 실루엣과 자세 라인을 뽑아 [PoseAsset] 으로 만든다.
 *
 * tools/extract.py 와 같은 절차다 — 세그멘테이션 마스크에서 가장 큰 덩어리의 윤곽을 따고
 * Douglas-Peucker 로 줄인 뒤, 가려진 관절과 얼굴 랜드마크를 걸러낸다.
 * 파이썬 쪽에서 결과를 눈으로 검증하며 다듬은 값들을 그대로 옮겼다.
 */
class PoseExtractor(private val context: Context) {

    suspend fun extract(source: Bitmap, id: String): Result<PoseAsset> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = source.scaleToMaxEdge(MAX_EDGE)
                val result = detect(bitmap)

                val landmarks = result.landmarks().firstOrNull()
                    ?: error("사진에서 사람을 찾지 못했습니다")

                val joints = buildMap {
                    landmarks.forEachIndexed { index, landmark ->
                        if (index in FACE_LANDMARKS) return@forEachIndexed
                        if (landmark.visibility().orElse(0f) < VISIBILITY_MIN) return@forEachIndexed
                        put(index.toString(), listOf(landmark.x(), landmark.y()))
                    }
                }

                val mask = result.segmentationMasks()
                    .orElse(null)?.firstOrNull()
                    ?: error("실루엣을 만들지 못했습니다")

                val silhouette = traceSilhouette(mask, bitmap.width, bitmap.height)
                if (silhouette.size < 3) error("사진에서 사람 윤곽을 분리하지 못했습니다")

                val xs = silhouette.map { it[0] }
                val ys = silhouette.map { it[1] }

                PoseAsset(
                    id = id,
                    category = "user",
                    person = Person(
                        silhouette = silhouette,
                        box = listOf(xs.min(), ys.min(), xs.max(), ys.max()),
                        joints = joints,
                        edges = SKELETON_EDGES
                            .filter { (a, b) -> joints.containsKey(a.toString()) && joints.containsKey(b.toString()) }
                            .map { (a, b) -> listOf(a, b) },
                    ),
                )
            }
        }

    private fun detect(bitmap: Bitmap): PoseLandmarkerResult {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputSegmentationMasks(true)
            .setNumPoses(1)
            .build()

        return PoseLandmarker.createFromOptions(context, options).use { landmarker ->
            landmarker.detect(BitmapImageBuilder(bitmap).build())
        }
    }
}

/**
 * 마스크에서 가장 큰 덩어리의 윤곽을 정규화 좌표로 돌려준다.
 *
 * OpenCV 가 없으므로 findContours 대신 무어 이웃 추적(Moore neighborhood tracing)으로 경계를 따라간다.
 * 사람은 한 덩어리라 가장 바깥 윤곽 하나만 있으면 충분하다.
 */
private fun traceSilhouette(mask: MPImage, width: Int, height: Int): List<List<Float>> {
    val w = mask.width
    val h = mask.height

    // 세그멘테이션 마스크는 픽셀당 float 하나로 된 신뢰도다. 이미지 포맷으로 읽으면
    // 값이 통째로 어긋나 사람 픽셀을 하나도 못 찾는다.
    val confidence = ByteBufferExtractor
        .extract(mask, MPImage.IMAGE_FORMAT_VEC32F1)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun solid(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= w || y >= h) return false
        return confidence.get(y * w + x) > 0.5f
    }

    var start: Pair<Int, Int>? = null
    outer@ for (y in 0 until h) {
        for (x in 0 until w) {
            if (solid(x, y)) { start = x to y; break@outer }
        }
    }
    val origin = start ?: return emptyList()

    // 시계방향 8이웃. 경계를 따라가며 다음 방향을 이 순서로 탐색한다.
    val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    val contour = mutableListOf<Pair<Int, Int>>()
    var current = origin
    var dir = 0
    val limit = (w + h) * 4

    do {
        contour += current
        var moved = false
        // 직전에 온 방향의 뒤쪽부터 훑어야 경계를 따라 돈다.
        for (step in 0 until 8) {
            val d = (dir + 6 + step) % 8
            val nx = current.first + dx[d]
            val ny = current.second + dy[d]
            if (solid(nx, ny)) {
                current = nx to ny
                dir = d
                moved = true
                break
            }
        }
        if (!moved) break
    } while (current != origin && contour.size < limit)

    val epsilon = SIMPLIFY_RATIO * hypot(width.toDouble(), height.toDouble())
    return simplify(contour, epsilon).map {
        listOf(it.first.toFloat() / w, it.second.toFloat() / h)
    }
}

/** Douglas-Peucker. 손가락 같은 디테일이 물러나는 건 손실이 아니라 의도된 추상화다. */
private fun simplify(points: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
    if (points.size < 3) return points

    var maxDistance = 0.0
    var index = 0
    val first = points.first()
    val last = points.last()

    for (i in 1 until points.size - 1) {
        val d = perpendicularDistance(points[i], first, last)
        if (d > maxDistance) {
            maxDistance = d
            index = i
        }
    }

    return if (maxDistance > epsilon) {
        simplify(points.subList(0, index + 1), epsilon).dropLast(1) +
            simplify(points.subList(index, points.size), epsilon)
    } else {
        listOf(first, last)
    }
}

private fun perpendicularDistance(
    point: Pair<Int, Int>,
    lineStart: Pair<Int, Int>,
    lineEnd: Pair<Int, Int>,
): Double {
    val dx = (lineEnd.first - lineStart.first).toDouble()
    val dy = (lineEnd.second - lineStart.second).toDouble()
    if (dx == 0.0 && dy == 0.0) {
        return hypot(
            (point.first - lineStart.first).toDouble(),
            (point.second - lineStart.second).toDouble(),
        )
    }
    val numerator = abs(
        dy * point.first - dx * point.second + lineEnd.first * lineStart.second -
            lineEnd.second * lineStart.first
    )
    return numerator / hypot(dx, dy)
}

private fun Bitmap.scaleToMaxEdge(maxEdge: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}
