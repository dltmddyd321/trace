package me.trace.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.max

/**
 * 화면에 띄우고 추출·분석에 넘길 만큼만 줄여서 읽는다.
 *
 * 요즘 폰 사진은 4000×3000 이 흔해 ARGB 로 펼치면 한 장에 48MB고, 더 큰 것도 들어온다.
 * 원본을 그대로 들고 그리면 Canvas 가 "too large bitmap" 으로 죽는다. 실루엣 품질과
 * 구도 판단 모두 이 해상도에서 충분하므로 디코딩 단계에서 줄이는 게 맞다.
 */
private const val LOAD_MAX_EDGE = 1600

fun Context.loadBitmap(uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            // 하드웨어 비트맵은 픽셀을 직접 읽을 수 없어 추출이 실패한다. 소프트웨어로 강제한다.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true

            val longest = max(info.size.width, info.size.height)
            if (longest > LOAD_MAX_EDGE) {
                val ratio = LOAD_MAX_EDGE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).toInt(),
                    (info.size.height * ratio).toInt(),
                )
            }
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }
}.getOrNull()
