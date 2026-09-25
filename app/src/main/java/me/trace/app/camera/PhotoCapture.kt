package me.trace.app.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

private const val ALBUM = "Pictures/Trace"

/**
 * 찍은 사진을 갤러리에 저장한다.
 *
 * MediaStore로 쓰기 때문에 저장소 권한이 필요 없다 — minSdk 29(scoped storage) 덕분이고,
 * 이게 minSdk를 29로 올린 이유다.
 *
 * 셔터음: CameraX는 소리를 내지 않으므로 기본 동작이 무음이다. 다만 한국·일본 출시 단말은
 * 다수가 카메라 HAL이나 프레임워크에서 강제로 재생하며, 앱이 끌 수 있는 방법이 없다.
 * 이 경로로 완전한 무음을 보장할 수는 없다.
 */
fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Trace_$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
        // 저장이 끝날 때까지 다른 앱에 보이지 않게 잠가둔다. 갤러리에 반쯤 쓰인 파일이 뜨는 걸 막는다.
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val options = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values,
    ).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri != null) {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
                onSaved("갤러리에 저장했습니다")
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "저장에 실패했습니다")
            }
        },
    )
}
