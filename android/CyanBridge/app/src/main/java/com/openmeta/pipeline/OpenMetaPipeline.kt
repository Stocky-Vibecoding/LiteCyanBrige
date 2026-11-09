package com.openmeta.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Три режима скачивания и рендера на выбор в интерфейсе
enum class RenderMode {
    RAW_ORIGINAL,     // 1. Чистый исходник 1600x1200 без улучшалок
    META_FAST_2K,     // 2. Апскейл 2K (2048x1536) + CAS резкость + LUT + легкая стаба
    HORIZON_LOCK_2K   // 3. Полная стаба + выравнивание наклона головы (Горизонт Lock) + 2K
}

@UnstableApi
class OpenMetaPipeline(private val context: Context) {

    // Обработка или копирование видео в зависимости от выбранного режима
    fun processVideo(
        inputFile: File,
        outputFile: File,
        mode: RenderMode,
        onComplete: (File) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (mode == RenderMode.RAW_ORIGINAL) {
            try {
                inputFile.copyTo(outputFile, overwrite = true)
                inputFile.delete()
                onComplete(outputFile)
            } catch (e: Exception) {
                onError(e)
            }
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val lockHorizon = (mode == RenderMode.HORIZON_LOCK_2K)
                val radius = if (lockHorizon) 12 else 8
                val trajectory = VideoStabilizerEffect.analyzeWithHorizonLock(inputFile, radius)

                withContext(Dispatchers.Main) {
                    val videoEffects = listOf<Effect>(
                        VideoStabilizerEffect(
                            trajectory = trajectory,
                            baseZoom = 1.08f,
                            lockHorizon = lockHorizon
                        ),
                        Presentation.createForWidthAndHeight(
                            2048,
                            1536,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        ),
                        MetaCinemaGlEffect()
                    )

                    val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                    val encoderSettings = VideoEncoderSettings.Builder()
                        .setBitrate(18_000_000)
                        .build()

                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderS


ettings(encoderSettings)
                        .build()

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                inputFile.delete()
                                onComplete(outputFile)
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException
                            ) {
                                onError(exception)
                            }
                        })
                        .build()

                    transformer.start(editedMediaItem, outputFile.absolutePath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    // Обработка фотографий 1600x1200 (или сохранение исходника)
    fun processPhoto(rawJpg: File, outputJpg: File, mode: RenderMode) {
        if (mode == RenderMode.RAW_ORIGINAL) {
            rawJpg.copyTo(outputJpg, overwrite = true)
            rawJpg.delete()
            return
        }

        val original = BitmapFactory.decodeFile(rawJpg.absolutePath) ?: return
        val contrast = 1.12f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast * 1.04f, 0f, 0f, 0f, shift,
                0f, contrast * 0.97f, 0f, 0f, shift,
                0f, 0f, contrast * 1.02f, 0f, shift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(ColorMatrix().apply { setSaturation(1.22f) })

        val resultBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        Canvas(resultBitmap).drawBitmap(original, 0f, 0f, paint)

        FileOutputStream(outputJpg).use { out ->
            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        }
        original.recycle()
        resultBitmap.recycle()
        rawJpg.delete()
    }
}

@UnstableApi
class VideoStabilizerEffect(
    private val trajectory: List<FramePose>,
    private val frameDurationUs: Long = 33_333L,
    private val baseZoom: Float = 1.08f,
    private val lockHorizon: Boolean = true
) : MatrixTransformation {

    data class FramePose(val dx: Float, val dy: Float, val rollDeg: Float)

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        if (trajectory.isEmpty()) {
            matrix.postScale(baseZoom, baseZoom)
            return matrix
        }

        val exactIndex = presentationTimeUs.toFloat() / frameDurationUs.toFloat()
        val index0 = exactIndex.toInt().coerceIn(0, trajectory.lastIndex)
        val index1 = (index0 + 1).coerceIn(0, trajectory.lastIndex)
        val fraction = exactIndex - index0

        val p0 = trajectory[index0]
        val p1 = trajectory[index1]

        val shiftX = p0.dx + (p1.dx - p0.dx) * fraction
        val shiftY = p0.dy + (p1.dy - p0.dy) * fraction
        val angleDeg = if (lockHorizon) {
            (p0.rollDeg + (p1.rollDeg - p0.rollDeg) * fraction).coerceIn(-14f, 14f)
        } else 0f

        val rad = Math.toRadians(abs(angleDeg).toDouble())
        val rotationSafeZoom = (cos(rad) + (4.0 / 3.0) * sin(rad)).toFloat()
        val totalZoom = max(baseZoom, rotationSafeZoom * 1.04f)

        val maxShift = baseZoom - 1.0f
        val clampedX = shiftX.coerceIn(-maxShift, maxShift)
        val cl


ampedY = shiftY.coerceIn(-maxShift, maxShift)

        if (abs(angleDeg) > 0.05f) {
            matrix.postRotate(angleDeg)
        }
        matrix.postScale(totalZoom, totalZoom)
        matrix.postTranslate(clampedX, clampedY)

        return matrix
    }

    companion object {
        fun analyzeWithHorizonLock(videoFile: File, smoothingRadius: Int): List<FramePose> {
            val retriever = MediaMetadataRetriever()
            val rawPoses = mutableListOf<FramePose>()

            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return emptyList()

                var prevLuma: IntArray? = null
                var cumX = 0f
                var cumY = 0f
                var cumAngle = 0f

                var timeMs = 0L
                while (timeMs < durationMs) {
                    val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp != null) {
                        val small = Bitmap.createScaledBitmap(bmp, 96, 72, true)
                        bmp.recycle()
                        val currLuma = extractLuma(small)
                        small.recycle()

                        if (prevLuma != null) {
                            val (lx, ly) = matchZone(prevLuma, currLuma, 96, 72, 12, 44)
                            val (rx, ry) = matchZone(prevLuma, currLuma, 96, 72, 52, 84)

                            cumX += (lx + rx) * 0.5f
                            cumY += (ly + ry) * 0.5f

                            val deltaY = (ry - ly) * 36f
                            val deltaAngleDeg = Math.toDegrees(atan2(deltaY.toDouble(), 40.0)).toFloat()
                            cumAngle = (cumAngle + deltaAngleDeg) * 0.97f
                        }
                        prevLuma = currLuma
                    }
                    rawPoses.add(FramePose(cumX, cumY, cumAngle))
                    timeMs += 33L
                }
            } catch (_: Exception) {
                return emptyList()
            } finally {
                retriever.release()
            }

            return rawPoses.mapIndexed { i, current ->
                var sumX = 0f
                var sumY = 0f
                var sumAngle = 0f
                var count = 0
                val start = max(0, i - smoothingRadius)
                val end = min(rawPoses.lastIndex, i + smoothingRadius)

                for (j in start..end) {
                    sumX += rawPoses[j].dx
                    sumY += rawPoses[j].dy
                    sumAngle += rawPoses[j].rollDeg
                    count++
                }
                FramePose(
                    dx = (sumX / count) - current.dx,
                    dy = (sumY / count) - current.dy,
                    rollDeg = ((sumAngle / count) * 0.3f) - current.rollDeg
                )
            }
        }

        private fun extractLuma(bmp: Bitmap): IntArray {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            return IntArray(w * h) { idx ->
                val p = pixels[idx]
                (((p shr 16 and 0xFF) * 77) + ((p shr 8 and 0xFF) * 150) + ((p and 0xFF) * 29)) shr 8
            }
        }

        private fun matchZone(
            prev: IntArray,
            curr: IntArray,
            w: Int,
            h: Int,
            startX: Int,
            endX: Int
        ): Pair<Float, Float> {
            val range = 6
            var bestDx = 0
            var bestDy = 0
            var minSad = Int.MAX_VALUE

            for (dy in -range..range) {
                for (dx in -range..range) {
                    var sad = 0
                    for (y in 18 until h - 18 step 2) {
                        for (x in startX until endX st


ep 2) {
                            sad += abs(prev[y * w + x] - curr[(y + dy) * w + (x + dx)])
                        }
                    }
                    if (sad < minSad) {
                        minSad = sad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
            return Pair((bestDx.toFloat() / w) * 2f, -(bestDy.toFloat() / h) * 2f)
        }
    }
}

@UnstableApi
class MetaCinemaGlEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MetaCinemaShaderProgram(useHdr)
    }
}

@UnstableApi
class MetaCinemaShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute("aFramePosition", GlProgram.getNormalizedCoordinateBounds(), 4)
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec2 texel = vec2(1.0 / 2048.0, 1.0 / 1536.0);
                vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
                vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -texel.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  texel.y)).rgb;
                vec3 w = texture2D(uTexSampler, vTexSamplingCoord + vec2(-texel.x, 0.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2( texel.x, 0.0)).rgb;

                vec3 minRGB = min(min(min(n, s), min(w, e)), c);
                vec3 maxRGB = max(max(max(n, s), max(w, e)), c);
                vec3 amp = clamp(min(minRGB, 1.0 - maxRGB) / max(maxRGB, vec3(0.0001)), 0.0, 1.0);
                amp = sqrt(amp);
                vec3 weight = amp * (-0.18);
                vec3 sharpened = clamp((c + (n + s + w + e) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);

                vec3 graded = vec3(sharpened.r * 1.04, sharpened.g * 0.97, sharpened.b * 1.02);
                graded = clamp(graded, 0.0, 1.0);
                graded = mix(graded, smoothstep(0.03, 0.97, graded), 0.35);

                float luma = dot(graded, vec3(0.299, 0.587, 0.114));
                float sat = max(graded.r, max(graded.g, graded.b)) - min(graded.r, min(graded.g, graded.b));
                graded = mix(vec3(luma), graded, 1.0 + (0.28 * (1.0 - sat)));

                gl_FragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
            }
        """
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
