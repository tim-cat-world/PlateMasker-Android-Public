package com.timcatworld.platemasker

import android.graphics.*

fun drawPolygonMasks(
    original: Bitmap,
    polygons: List<List<Point>>
): Bitmap {

    val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)

    val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    polygons.forEach { poly ->
        if (poly.size < 3) return@forEach
        val path = Path()
        poly.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
            else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    return bitmap
}