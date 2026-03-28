package com.timcatworld.platemasker

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object PlateRefinement {

    /**
     * ナンバープレート領域を多角形で取得し、頂点を精緻化する
     */
    fun detectPlateQuad(
        original: Bitmap,
        roi: RectF
    ): List<Point>? {

        val x = roi.left.toInt().coerceAtLeast(0)
        val y = roi.top.toInt().coerceAtLeast(0)
        val w = roi.width().toInt().coerceAtMost(original.width - x)
        val h = roi.height().toInt().coerceAtMost(original.height - y)

        if (w <= 0 || h <= 0) return null

        // 1. 画像全体ではなく、ROI（関心領域）のみを切り出す
        // これにより、OpenCV(Mat)への変換時のメモリ消費を大幅に削減します
        val croppedBitmap = try {
            Bitmap.createBitmap(original, x, y, w, h)
        } catch (e: OutOfMemoryError) {
            return null
        }

        val roiMat = Mat()
        val gray = Mat()
        val processed = Mat()
        val hierarchy = Mat()
        val hullIndices = MatOfInt()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Utils.bitmapToMat(croppedBitmap, roiMat)
            // Mat変換後は中間Bitmapを即座に解放
            croppedBitmap.recycle()
            
            Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_BGR2GRAY)

            Imgproc.GaussianBlur(gray, processed, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(processed, processed, 75.0, 200.0)

            // 2. 輪郭抽出
            Imgproc.findContours(processed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var bestApprox: MatOfPoint2f? = null
            var maxArea = -1.0

            for (c in contours) {
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val currentApprox = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, currentApprox, 0.025 * peri, true)

                val area = Imgproc.contourArea(currentApprox)
                if (currentApprox.total() >= 4 && area > maxArea) {
                    bestApprox?.release()
                    bestApprox = currentApprox
                    maxArea = area
                } else {
                    currentApprox.release()
                }
                c2f.release()
                // 各輪郭（Mat）もこのループ内で解放するのが理想ですが、最後に一括で行います
            }

            if (bestApprox == null) return null

            // 形状を整える
            Imgproc.convexHull(MatOfPoint(*bestApprox!!.toArray()), hullIndices)
            val bestList = bestApprox!!.toList()
            val hullPoints = hullIndices.toList().map { bestList[it] }
            val hullMat = MatOfPoint2f(*hullPoints.toTypedArray())

            // 頂点の精緻化
            val dynamicWinSize = (h * 0.08).coerceIn(5.0, 25.0)
            val winSize = Size(dynamicWinSize, dynamicWinSize)
            val zeroZone = Size(-1.0, -1.0)
            val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 40, 0.001)

            try {
                Imgproc.cornerSubPix(gray, hullMat, winSize, zeroZone, criteria)
            } catch (e: Exception) {
                Log.e("PlateRefine", "cornerSubPix failed", e)
            }

            // 元の座標系に戻す
            val result = hullMat.toArray().map { p -> 
                Point(p.x + x, p.y + y)
            }

            bestApprox?.release()
            hullMat.release()
            
            return result

        } catch (e: Exception) {
            Log.e("PlateRefine", "Error in detectPlateQuad", e)
            return null
        } finally {
            // 3. すべてのネイティブメモリ(Mat)を確実に解放
            roiMat.release()
            gray.release()
            processed.release()
            hierarchy.release()
            hullIndices.release()
            // 重要：findContoursで生成されたすべてのMatを解放
            contours.forEach { it.release() }
            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
        }
    }
}
