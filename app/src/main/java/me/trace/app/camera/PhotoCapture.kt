package me.trace.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.provider.MediaStore
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.text.SimpleDateFormat
import java.util.Locale

private const val ALBUM = "Pictures/Trace"
private const val JPEG_QUALITY = 95

/**
 * 프리뷰 스트림에서 프레임을 뽑아 저장한다. 셔터를 누르지 않으므로 소리가 나지 않는다.
 *
 * `ImageCapture`를 쓰지 않는 이유는 한국·일본 출시 단말이 카메라 HAL이나 프레임워크에서
 * 셔터음을 강제로 재생하고 앱이 끌 수 있는 API가 없기 때문이다. 촬영 경로 자체를 피하는 게
 * 무음을 보장하는 유일한 방법이다.
 *
 * 대가는 해상도다. 프리뷰 스트림은 센서 최대 해상도가 아니라 분석용 해상도로 내려온다.
 */
class SilentCapture {
    private var pending: ((Bitmap) -> Unit)? = null

    /**
     * 다음에 도착하는 프레임 하나를 넘겨준다.
     *
     * 프레임을 계속 붙잡아 두지 않고 요청이 있을 때만 변환하는 이유는, 매 프레임 Bitmap을
     * 만들면 그대로 GC 압박이 되기 때문이다. 초당 30장을 버리려고 만들 이유가 없다.
     */
    fun requestFrame(onFrame: (Bitmap) -> Unit) {
        pending = onFrame
    }

    val analyzer = ImageAnalysis.Analyzer { proxy ->
        val callback = pending
        if (callback == null) {
            proxy.close()
            return@Analyzer
        }
        pending = null

        try {
            callback(proxy.toUprightBitmap())
        } finally {
            proxy.close()
        }
    }
}

/** 센서 방향 그대로 오면 눕거나 뒤집혀 저장되므로 회전을 적용해 세워둔다. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bitmap

    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * 갤러리에 저장한다.
 *
 * MediaStore로 쓰기 때문에 저장소 권한이 필요 없다 — minSdk 29(scoped storage) 덕분이고,
 * 이게 minSdk를 29로 올린 이유다.
 */
fun saveToGallery(
    context: Context,
    bitmap: Bitmap,
    onDone: (String) -> Unit,
) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Trace_$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
        // 저장이 끝날 때까지 다른 앱에 보이지 않게 잠가둔다. 반쯤 쓰인 파일이 갤러리에 뜨는 걸 막는다.
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        onDone("저장할 위치를 만들지 못했습니다")
        return
    }

    runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        } ?: error("출력 스트림을 열지 못했습니다")
    }.onSuccess {
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        onDone("갤러리에 저장했습니다")
    }.onFailure {
        // 실패한 채로 두면 갤러리에 영원히 안 보이는 빈 항목이 남는다.
        resolver.delete(uri, null, null)
        onDone(it.message ?: "저장에 실패했습니다")
    }
}
