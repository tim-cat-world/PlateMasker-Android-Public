package com.timcatworld.platemasker

import android.graphics.*
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.core.Point
import org.opencv.core.Rect as OpenCvRect

object MaskProcessor {

    private const val TAG = "MaskProcessor"
    private var isNativeLoaded = false

    init {
        try {
            // OpenCV のベースを初期化
            if (OpenCVLoader.initLocal()) {
                // utils_core (旧 mask_processor) をロード
                System.loadLibrary("utils_core")
                isNativeLoaded = true
                Log.d(TAG, "Utils library loaded successfully")
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Native library load error", e)
        }
    }

    @JvmStatic
    private external fun blurNative(bitmap: Bitmap, vertices: FloatArray, radius: Float): Boolean
    @JvmStatic
    private external fun calcAverageColorNative(bitmap: Bitmap, vertices: FloatArray): Int

    fun polygonToPath(poly: EditablePolygon): Path =
        Path().apply {
            poly.vertices.forEachIndexed { i, p ->
                if (i == 0) moveTo(p.x, p.y)
                else lineTo(p.x, p.y)
            }
            close()
        }

    fun processWithSettings(
        src: Bitmap,
        polygons: List<EditablePolygon>,
        settings: AppSettings
    ): Bitmap {
        if (src.isRecycled) return src
        // ARGB_8888 を強制
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val globalConfig = polygons.find { it.config.applyToAll }?.config

        for (poly in polygons) {
            val config = globalConfig ?: poly.config
            when (config.type) {
                MaskType.SOLID_COLOR -> fillPolygon(result, poly, config.color)
                MaskType.AVERAGE_COLOR -> {
                    val color = calcAverageColor(src, poly)
                    fillPolygon(result, poly, color)
                }
                MaskType.BLUR -> blurPolygon(result, poly, config.blurRadius)
                MaskType.IMAGE -> overlayImageWithSettings(result, poly, config, settings)
            }
        }
        return result
    }

    private fun fillPolygon(src: Bitmap, poly: EditablePolygon, color: Int) {
        val canvas = Canvas(src)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(polygonToPath(poly), paint)
    }

    private fun blurPolygon(src: Bitmap, poly: EditablePolygon, radius: Float) {
        if (src.isRecycled) return
        
        // 1. Native 試行
        if (isNativeLoaded) {
            val vertices = poly.vertices.flatMap { listOf(it.x, it.y) }.toFloatArray()
            try {
                if (blurNative(src, vertices, radius)) return
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Native blur failed", e)
            }
        }

        // 2. Kotlin 側のフォールバック (常に正常動作することを保証)
        val mat = Mat(); val mask = Mat(); val blurred = Mat(); val mPoints = MatOfPoint()
        try {
            Utils.bitmapToMat(src, mat)
            mask.create(mat.size(), CvType.CV_8UC1); mask.setTo(Scalar(0.0))
            val pts = poly.vertices.map { Point(it.x.toDouble(), it.y.toDouble()) }
            mPoints.fromList(pts)
            Imgproc.fillPoly(mask, listOf(mPoints), Scalar(255.0), Imgproc.LINE_AA)
            val kSize = (radius.toInt() * 2 + 1).toDouble().coerceIn(1.0, 255.0)
            Imgproc.GaussianBlur(mat, blurred, Size(kSize, kSize), 0.0)
            blurred.copyTo(mat, mask)
            Utils.matToBitmap(mat, src)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Fallback blur failed", e)
        } finally {
            mat.release(); mask.release(); blurred.release(); mPoints.release()
        }
    }

    fun calcAverageColor(bitmap: Bitmap, poly: EditablePolygon): Int {
        if (bitmap.isRecycled) return Color.WHITE
        
        // 1. Native 試行
        if (isNativeLoaded) {
            val vertices = poly.vertices.flatMap { listOf(it.x, it.y) }.toFloatArray()
            try {
                val color = calcAverageColorNative(bitmap, vertices)
                // -1 以外の有効な色が返ってきた場合のみ採用
                if (color != -1) return color
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Native avg color failed", e)
            }
        }

        // 2. Kotlin 側のフォールバック (常に正常動作することを保証)
        val mat = Mat(); val gray = Mat(); val mask = Mat(); val mPoints = MatOfPoint()
        val binary = Mat(); val maskAbove = Mat(); val maskBelow = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            mask.create(mat.size(), CvType.CV_8UC1); mask.setTo(Scalar(0.0))
            val pts = poly.vertices.map { Point(it.x.toDouble(), it.y.toDouble()) }
            mPoints.fromList(pts)
            Imgproc.fillPoly(mask, listOf(mPoints), Scalar(255.0))
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            val rect = Imgproc.boundingRect(mPoints)
            val safeRect = OpenCvRect(rect.x.coerceAtLeast(0), rect.y.coerceAtLeast(0), rect.width.coerceAtMost(gray.cols() - rect.x.coerceAtLeast(0)), rect.height.coerceAtMost(gray.rows() - rect.y.coerceAtLeast(0)))
            val subGray = gray.submat(safeRect)
            val thresh = Imgproc.threshold(subGray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            subGray.release()
            Imgproc.threshold(gray, binary, thresh, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(binary, mask, maskAbove)
            Core.bitwise_not(binary, binary)
            Core.bitwise_and(binary, mask, maskBelow)
            
            val countAbove = Core.countNonZero(maskAbove)
            val countBelow = Core.countNonZero(maskBelow)
            val finalMask = if (countAbove >= countBelow) maskAbove else maskBelow
            val finalFinalMask = if (Core.countNonZero(finalMask) > 0) finalMask else mask

            val avg = Core.mean(mat, finalFinalMask)
            return Color.rgb(avg.`val`[0].toInt(), avg.`val`[1].toInt(), avg.`val`[2].toInt())
        } catch (e: Exception) {
            return Color.WHITE
        } finally {
            mat.release(); gray.release(); mask.release(); mPoints.release(); binary.release(); maskAbove.release(); maskBelow.release()
        }
    }

    private fun overlayImageWithSettings(src: Bitmap, poly: EditablePolygon, config: MaskConfig, settings: AppSettings) {
        val overlay = config.overlayImage ?: return
        var rotatedOverlay: Bitmap? = null
        var adjustedOverlay: Bitmap? = null
        var preparedOverlay: Bitmap? = null

        try {
            rotatedOverlay = if (config.rotation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(config.rotation.toFloat())
                Bitmap.createBitmap(overlay, 0, 0, overlay.width, overlay.height, matrix, true)
            } else overlay

            adjustedOverlay = applyColorCorrection(rotatedOverlay!!, config)
            var targetAspect = settings.region.aspectRatio
            if (config.rotation % 180 != 0) targetAspect = 1f / targetAspect
            
            preparedOverlay = if (config.imageFitMode == ImageFitMode.CROP) {
                cropToAspect(adjustedOverlay, targetAspect)
            } else adjustedOverlay

            val canvas = Canvas(src)
            val matrix = android.graphics.Matrix()
            val srcPts = floatArrayOf(
                0f, 0f,
                preparedOverlay!!.width.toFloat(), 0f,
                preparedOverlay.width.toFloat(), preparedOverlay.height.toFloat(),
                0f, preparedOverlay.height.toFloat()
            )
            val destPoints = if (poly.vertices.size >= 4) {
                poly.vertices.take(4).flatMap { listOf(it.x, it.y) }.toFloatArray()
            } else return
            
            matrix.setPolyToPoly(srcPts, 0, destPoints, 0, 4)
            val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true; isDither = true }
            val shader = BitmapShader(preparedOverlay, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matrix)
            paint.setShader(shader)
            canvas.drawPath(polygonToPath(poly), paint)
        } finally {
            if (rotatedOverlay != null && rotatedOverlay != overlay) rotatedOverlay.recycle()
            if (adjustedOverlay != null && adjustedOverlay != rotatedOverlay && adjustedOverlay != overlay) adjustedOverlay.recycle()
            if (preparedOverlay != null && preparedOverlay != adjustedOverlay && preparedOverlay != rotatedOverlay && preparedOverlay != overlay) preparedOverlay.recycle()
        }
    }

    private fun applyColorCorrection(src: Bitmap, config: MaskConfig): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val cm = ColorMatrix()
        val c = config.contrast; val b = config.brightness
        cm.set(floatArrayOf(c, 0f, 0f, 0f, b, 0f, c, 0f, 0f, b, 0f, 0f, c, 0f, b, 0f, 0f, 0f, 1f, 0f))
        val temp = config.colorTemp / 100f
        val tempMatrix = ColorMatrix()
        if (temp > 0) tempMatrix.setScale(1f, 1f - temp * 0.2f, 1f - temp * 0.4f, 1f)
        else { val t = -temp; tempMatrix.setScale(1f - t * 0.4f, 1f - t * 0.2f, 1f, 1f) }
        cm.postConcat(tempMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun cropToAspect(src: Bitmap, aspectRatio: Float): Bitmap {
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        var targetW = src.width; var targetH = src.height; var x = 0; var y = 0
        if (srcAspect > aspectRatio) { targetW = (src.height * aspectRatio).toInt(); x = (src.width - targetW) / 2 }
        else { targetH = (src.width / aspectRatio).toInt(); y = (src.height - targetH) / 2 }
        return Bitmap.createBitmap(src, x, y, targetW, targetH)
    }

    fun processAll(src: Bitmap, polygons: List<EditablePolygon>): Bitmap = processWithSettings(src, polygons, AppSettings())
}
