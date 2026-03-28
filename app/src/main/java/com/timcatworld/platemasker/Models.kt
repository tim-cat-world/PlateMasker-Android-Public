package com.timcatworld.platemasker

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.*

enum class EditorMode {
    EDIT_POLYGON,
    SELECT_ROI,
    MANUAL_ADD,
    EYEDROPPER
}

enum class MaskType {
    BLUR,
    SOLID_COLOR,
    AVERAGE_COLOR,
    IMAGE
}

enum class ImageFitMode {
    CROP,
    STRETCH
}

enum class AppLanguage(val code: String, val label: String) {
    JAPANESE("ja", "日本語"),
    ENGLISH("en", "English")
}

enum class PlateRegion(val label: String, val aspectRatio: Float) {
    JAPAN("日本 (1:2)", 2.0f),
    EU("EU (1:4.5)", 4.5f),
    US("USA (1:2)", 2.0f),
    SQUARE("Square (3:4)", 1.33f)
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.JAPANESE,
    val region: PlateRegion = PlateRegion.JAPAN,
    val saveFolderPath: String = "", // 空の場合はデフォルト (Pictures/PlateMasker)
    val autoSaveWithIncrementalName: Boolean = true, // 自動で別名保存するか
    val askFileNameBeforeShare: Boolean = false, // 共有/外部編集前にファイル名を入力するか
    val touchHitRadius: Float = 40f, // 頂点の当たり判定の半径 (px)
    val touchHitOffset: Float = 0f   // 頂点から外側へのオフセット距離 (px)
)

data class MaskConfig(
    val type: MaskType = MaskType.AVERAGE_COLOR,
    val color: Int = android.graphics.Color.WHITE,
    val overlayImage: Bitmap? = null,
    val imageFitMode: ImageFitMode = ImageFitMode.CROP,
    val rotation: Int = 0,
    val blurRadius: Float = 25f, // 1-100
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val colorTemp: Float = 0f,
    val applyToAll: Boolean = false
)

data class EditablePolygon(
    val vertices: List<PointF>,
    val config: MaskConfig = MaskConfig()
) {
    fun isValid(): Boolean {
        if (vertices.size < 4) return false
        if (calculateTotalArea() < 100f) return false
        return true
    }

    fun calculateTotalArea(): Float {
        var area = 0f
        for (i in vertices.indices) {
            val j = (i + 1) % vertices.size
            area += vertices[i].x * vertices[j].y
            area -= vertices[j].x * vertices[i].y
        }
        return abs(area) / 2f
    }

    private fun calculateTriangleArea(p1: PointF, p2: PointF, p3: PointF): Float {
        return abs((p2.x - p1.x) * (p3.y - p1.y) - (p3.x - p1.x) * (p2.y - p1.y)) / 2f
    }

    fun calculateInnerAngleAt(index: Int): Float {
        val size = vertices.size
        if (size < 3) return 0f
        val p = vertices[index]
        val prev = vertices[(index - 1 + size) % size]
        val next = vertices[(index + 1) % size]
        val v1x = prev.x - p.x; val v1y = prev.y - p.y
        val v2x = next.x - p.x; val v2y = next.y - p.y
        val angle1 = atan2(v1y.toDouble(), v1x.toDouble())
        val angle2 = atan2(v2y.toDouble(), v2x.toDouble())
        var diff = Math.toDegrees(angle2 - angle1).toFloat()
        while (diff < 0) diff += 360f
        while (diff >= 360) diff -= 360f
        return diff
    }

    fun optimize(): EditablePolygon {
        var current = this
        current = current.simplifyStraightLines()
        var iterations = 0
        while (current.vertices.size > 4 && iterations < 10) {
            val next = current.removeLeastSignificantVertex()
            if (next.vertices.size == current.vertices.size) break
            current = next
            iterations++
        }
        return current
    }

    private fun simplifyStraightLines(): EditablePolygon {
        val toRemove = mutableSetOf<Int>()
        for (i in vertices.indices) {
            val angle = calculateInnerAngleAt(i)
            if (angle > 172f && angle < 188f) toRemove.add(i)
        }
        return if (toRemove.isEmpty()) this else copy(vertices = vertices.filterIndexed { index, _ -> !toRemove.contains(index) })
    }

    private fun removeLeastSignificantVertex(): EditablePolygon {
        if (vertices.size <= 4) return this
        var minArea = Float.MAX_VALUE
        var targetIndex = -1
        for (i in vertices.indices) {
            val prev = vertices[(i - 1 + vertices.size) % vertices.size]
            val curr = vertices[i]
            val next = vertices[(i + 1) % vertices.size]
            val area = calculateTriangleArea(prev, curr, next)
            if (area < minArea) {
                minArea = area
                targetIndex = i
            }
        }
        return if (targetIndex != -1) copy(vertices = vertices.filterIndexed { index, _ -> index != targetIndex }) else this
    }
    
    fun getBoundingBox(): RectF {
        if (vertices.isEmpty()) return RectF()
        var minX = vertices[0].x; var maxX = vertices[0].x
        var minY = vertices[0].y; var maxY = vertices[0].y
        for (v in vertices) {
            minX = min(minX, v.x); maxX = max(maxX, v.x)
            minY = min(minY, v.y); maxY = max(maxY, v.y)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    fun getCenter(): PointF {
        if (vertices.isEmpty()) return PointF(0f, 0f)
        var sumX = 0f; var sumY = 0f
        for (v in vertices) { sumX += v.x; sumY += v.y }
        return PointF(sumX / vertices.size, sumY / vertices.size)
    }

    companion object {
        fun filterDuplicates(polygons: List<EditablePolygon>): List<EditablePolygon> {
            val sorted = polygons.sortedBy { it.vertices.size }
            val result = mutableListOf<EditablePolygon>()
            for (p in sorted) {
                val boxP = p.getBoundingBox()
                val areaP = boxP.width() * boxP.height()
                if (areaP <= 0) continue
                var isDuplicate = false
                for (accepted in result) {
                    val boxA = accepted.getBoundingBox()
                    val interLeft = max(boxP.left, boxA.left)
                    val interTop = max(boxP.top, boxA.top)
                    val interRight = min(boxP.right, boxA.right)
                    val interBottom = min(boxP.bottom, boxA.bottom)
                    if (interRight > interLeft && interBottom > interTop) {
                        val interArea = (interRight - interLeft) * (interBottom - interTop)
                        val areaA = boxA.width() * boxA.height()
                        val minArea = min(areaP, areaA)
                        if (interArea / minArea > 0.5f) {
                            isDuplicate = true
                            break
                        }
                    }
                }
                if (!isDuplicate) result.add(p)
            }
            return result
        }
    }
}
