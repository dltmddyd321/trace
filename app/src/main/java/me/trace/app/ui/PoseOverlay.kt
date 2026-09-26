package me.trace.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import me.trace.app.data.PoseAsset
import me.trace.app.data.Structure

/**
 * 인물을 놓을 자리. 정규화 좌표이며 **0~1을 벗어날 수 있다.**
 * y1이 1보다 크면 발이 화면 아래에 있다는 뜻이고 그 부분은 그려지지 않는다.
 * 전신이 프레임에 다 들어가야 하는 건 아니다 — 무릎이나 허리에서 잘린 구도가 더 자연스러울 때가 많다.
 */
data class Placement(val box: List<Float>?) {
    companion object {
        /**
         * 원본 프레임 좌표 그대로 그린다. 인물이 원래 있던 자리에 남으므로 구조선과 위치가 맞는다.
         * 경로 A(사진에서 본뜬 템플릿)처럼 구도 전체를 보여줄 때 쓴다.
         */
        val Original = Placement(null)

        /**
         * 인물을 캔버스에 꽉 채운다. 포즈 모양만 보면 되는 자산 목록용이다.
         * 인물만 확대되므로 이 모드에서는 구조선을 같이 그리면 안 된다 — 서로 어긋난다.
         */
        val Fill = Placement(listOf(0f, 0f, 1f, 1f))
    }
}

/**
 * 인물을 어디서 자를지. 사진에 담기는 범위이자 가이드로 그릴 범위이기도 하다.
 *
 * 가려져 안 보일 부위를 굳이 그릴 이유가 없고, 자르고 나면 **그 아래 자세가 무의미해진다.**
 * 라운지 자세의 뻗은 다리로도 테이블 앞 허리 컷을 만들 수 있는 게 이 덕분이라,
 * 자산 라이브러리가 작아도 여러 구도를 커버한다.
 */
enum class Crop {
    Full, Knee, Thigh, Waist, Chest;

    companion object {
        fun from(value: String) = when (value.lowercase()) {
            "knee" -> Knee
            "thigh" -> Thigh
            "waist" -> Waist
            "chest" -> Chest
            else -> Full
        }
    }
}

// MediaPipe 랜드마크 번호.
private const val SHOULDER_L = 11
private const val SHOULDER_R = 12
private const val HIP_L = 23
private const val HIP_R = 24
private const val KNEE_L = 25
private const val KNEE_R = 26

private val SilhouetteColor = Color.White
private val SkeletonColor = Color(0xFFFFC14D)
private val StructureColor = Color(0xFF6BE58C)

/**
 * 포즈 자산을 지정한 자리에 그린다.
 *
 * 목록 섬네일과 카메라 오버레이가 **같은 함수를 쓴다.** 명세가 하나라 고른 구도와
 * 실제 카메라에 뜨는 구도가 어긋날 수 없고, 섬네일 이미지를 따로 관리할 필요도 없다.
 */
@Composable
fun PoseOverlay(
    asset: PoseAsset,
    modifier: Modifier = Modifier,
    placement: Placement = Placement.Original,
    crop: Crop = Crop.Full,
    structures: List<Structure> = asset.structures,
    showStructures: Boolean = true,
) {
    Canvas(modifier = modifier) {
        val transform = Transform.fit(asset.person.box, placement.box, size)
        val cropY = cropLine(asset, transform, crop, size)

        clipRect(top = 0f, bottom = cropY) {
            drawSilhouette(asset, transform)
            drawSkeleton(asset, transform)
        }
        if (showStructures) drawStructures(structures, transform)
    }
}

/**
 * 인물을 자를 높이를 캔버스 픽셀로 구한다.
 *
 * 관절이 보이면 그 위치를 쓴다 — 자세마다 허리·무릎 높이가 다르므로 비율로 때려 맞추면
 * 어떤 자세에서는 허벅지를, 어떤 자세에서는 배를 자르게 된다. 가려져 관절이 없을 때만
 * 인물 박스에 대한 비율로 되돌아간다.
 */
private fun cropLine(asset: PoseAsset, transform: Transform, crop: Crop, size: Size): Float {
    if (crop == Crop.Full) return size.height

    val joints = asset.person.joints
    fun y(vararg ids: Int): Float? {
        val values = ids.toList().mapNotNull { joints[it.toString()]?.get(1) }
        return if (values.isEmpty()) null else values.average().toFloat()
    }

    val shoulder = y(SHOULDER_L, SHOULDER_R)
    val hip = y(HIP_L, HIP_R)
    val knee = y(KNEE_L, KNEE_R)

    val normalized = when (crop) {
        Crop.Chest -> if (shoulder != null && hip != null) (shoulder + hip) / 2f else null
        Crop.Waist -> hip
        Crop.Thigh -> if (hip != null && knee != null) (hip + knee) / 2f else null
        Crop.Knee -> knee
        Crop.Full -> null
    } ?: fallbackCropLine(asset, crop)

    return transform.map(listOf(0f, normalized)).y
}

/** 관절이 가려졌을 때 쓰는 인체 비율 근사. 머리 끝을 0, 발끝을 1로 본다. */
private fun fallbackCropLine(asset: PoseAsset, crop: Crop): Float {
    val box = asset.person.box
    val top = box[1]
    val height = (box[3] - box[1]).coerceAtLeast(1e-4f)
    val ratio = when (crop) {
        Crop.Chest -> 0.42f
        Crop.Waist -> 0.55f
        Crop.Thigh -> 0.68f
        Crop.Knee -> 0.78f
        Crop.Full -> 1f
    }
    return top + height * ratio
}

/**
 * 정규화 좌표를 캔버스 픽셀로 옮긴다.
 *
 * 계산을 전부 픽셀 공간에서 하는 이유는 한 배율을 가로세로에 똑같이 곱해야
 * 사람이 눌리거나 늘어나지 않기 때문이다. 정규화 공간에서 맞추면 캔버스 종횡비만큼 왜곡된다.
 */
private class Transform(
    private val scale: Float,
    private val dx: Float,
    private val dy: Float,
    private val size: Size,
) {
    fun map(point: List<Float>) = Offset(
        x = point[0] * size.width * scale + dx,
        y = point[1] * size.height * scale + dy,
    )

    /** 구조선은 인물이 아니라 프레임 기준이라 배치 변환을 적용하지 않는다. */
    fun raw(point: List<Float>) = Offset(point[0] * size.width, point[1] * size.height)

    companion object {
        fun fit(sourceBox: List<Float>, targetBox: List<Float>?, size: Size): Transform {
            // 목표 박스가 없으면 변환하지 않는다 — 좌표를 그대로 캔버스에 펼친다.
            if (targetBox == null) return Transform(scale = 1f, dx = 0f, dy = 0f, size = size)

            val sx0 = sourceBox[0] * size.width
            val sy0 = sourceBox[1] * size.height
            val sx1 = sourceBox[2] * size.width
            val sy1 = sourceBox[3] * size.height

            val tx0 = targetBox[0] * size.width
            val ty0 = targetBox[1] * size.height
            val tx1 = targetBox[2] * size.width
            val ty1 = targetBox[3] * size.height

            val sourceW = (sx1 - sx0).coerceAtLeast(1f)
            val sourceH = (sy1 - sy0).coerceAtLeast(1f)
            val scale = minOf((tx1 - tx0) / sourceW, (ty1 - ty0) / sourceH)

            // 비율 유지로 남는 여백은 가로는 가운데, 세로는 아래에 맞춘다.
            // 사람은 바닥에 서 있으므로 발 위치가 머리 위 여백보다 중요하다.
            val dx = tx0 + ((tx1 - tx0) - sourceW * scale) / 2f - sx0 * scale
            val dy = ty1 - sy1 * scale

            return Transform(scale, dx, dy, size)
        }
    }
}

private fun DrawScope.drawSilhouette(asset: PoseAsset, transform: Transform) {
    val points = asset.person.silhouette
    if (points.size < 3) return

    val path = Path().apply {
        val first = transform.map(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            val offset = transform.map(point)
            lineTo(offset.x, offset.y)
        }
        close()
    }

    // 안쪽을 옅게 채워야 "설 자리"로 읽힌다. 선만 있으면 그냥 도형처럼 보인다.
    drawPath(path, SilhouetteColor.copy(alpha = 0.22f))
    drawPath(path, SilhouetteColor, style = Stroke(width = 4f))
}

private fun DrawScope.drawSkeleton(asset: PoseAsset, transform: Transform) {
    val joints = asset.person.joints
    asset.person.edges.forEach { edge ->
        val from = joints[edge[0].toString()] ?: return@forEach
        val to = joints[edge[1].toString()] ?: return@forEach
        drawLine(SkeletonColor, transform.map(from), transform.map(to), strokeWidth = 5f)
    }
}

private fun DrawScope.drawStructures(structures: List<Structure>, transform: Transform) {
    structures.forEach { structure ->
        if (structure.line.size < 2) return@forEach
        drawLine(
            StructureColor,
            transform.raw(structure.line[0]),
            transform.raw(structure.line[1]),
            strokeWidth = 4f,
        )
    }
}
