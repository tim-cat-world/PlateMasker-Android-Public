package com.timcatworld.platemasker

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*

@Composable
fun PolygonEditor(
    bitmap: Bitmap,
    polygons: List<EditablePolygon>,
    activePolyIndex: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    activeVertexIndex: Int?,
    activeLineIndex: Int?,
    editorMode: EditorMode,
    hideGuidelines: Boolean = false, // 追加: プレビュー用フラグ
    onPolygonsChanged: (List<EditablePolygon>, Int, Int?, Boolean) -> Unit,
    onLineSelected: (Int, Int?) -> Unit,
    onTransform: (Float, Offset, Offset) -> Unit,
    onRoiSelected: (RectF) -> Unit,
    onManualRectAdded: (RectF) -> Unit,
    appSettings: AppSettings = AppSettings(),
    modifier: Modifier = Modifier
) {
    // 外部ステートを rememberUpdatedState で保持
    val currentPolygons by rememberUpdatedState(polygons)
    val currentActivePolyIdx by rememberUpdatedState(activePolyIndex)
    val currentScale by rememberUpdatedState(scale)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentEditorMode by rememberUpdatedState(editorMode)
    val currentSettings by rememberUpdatedState(appSettings)
    val currentHideGuidelines by rememberUpdatedState(hideGuidelines)
    
    val currentOnPolygonsChanged by rememberUpdatedState(onPolygonsChanged)
    val currentOnLineSelected by rememberUpdatedState(onLineSelected)
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnRoiSelected by rememberUpdatedState(onRoiSelected)
    val currentOnManualRectAdded by rememberUpdatedState(onManualRectAdded)

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    
    var isDraggingVertex by remember { mutableStateOf(false) }
    var touchDiffX by remember { mutableStateOf(0f) }
    var touchDiffY by remember { mutableStateOf(0f) }

    fun getHitPoint(vertex: PointF, poly: EditablePolygon, settings: AppSettings, currentScale: Float): PointF {
        if (settings.touchHitOffset == 0f) return vertex
        val center = poly.getCenter()
        val dx = vertex.x - center.x
        val dy = vertex.y - center.y
        val dist = hypot(dx, dy)
        if (dist == 0f) return vertex
        val offsetOnImage = settings.touchHitOffset / currentScale
        val ratio = (dist + offsetOnImage) / dist
        return PointF(center.x + dx * ratio, center.y + dy * ratio)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    
                    // プレビュー中はズーム・パン以外を無効化
                    if (currentHideGuidelines && currentEditorMode != EditorMode.EDIT_POLYGON) {
                        // シンプルな移動・拡大のみを許可する処理へ（または何もしない）
                    }

                    val imgX = (down.position.x - currentOffsetX) / currentScale
                    val imgY = (down.position.y - currentOffsetY) / currentScale
                    
                    var vertexIndex: Int? = null
                    var polyIndex: Int = -1
                    
                    if (!currentHideGuidelines && currentEditorMode == EditorMode.EDIT_POLYGON) {
                        for (pIdx in currentPolygons.indices) {
                            val poly = currentPolygons[pIdx]
                            val vIdx = poly.vertices.indexOfFirst { v ->
                                val hitP = getHitPoint(v, poly, currentSettings, currentScale)
                                hypot(hitP.x - imgX, hitP.y - imgY) < (currentSettings.touchHitRadius / currentScale)
                            }.takeIf { it >= 0 }
                            
                            if (vIdx != null) {
                                polyIndex = pIdx
                                vertexIndex = vIdx
                                touchDiffX = poly.vertices[vIdx].x - imgX
                                touchDiffY = poly.vertices[vIdx].y - imgY
                                break
                            }
                        }
                        if (polyIndex != -1) {
                            currentOnPolygonsChanged(currentPolygons, polyIndex, vertexIndex, false)
                            currentOnLineSelected(polyIndex, null)
                        }
                    } else if (!currentHideGuidelines && currentEditorMode == EditorMode.EYEDROPPER) {
                        val tempPolygons = currentPolygons.toMutableList()
                        if (currentActivePolyIdx in tempPolygons.indices) {
                            val poly = tempPolygons[currentActivePolyIdx]
                            tempPolygons[currentActivePolyIdx] = poly.copy(vertices = listOf(PointF(imgX, imgY)))
                            currentOnPolygonsChanged(tempPolygons, currentActivePolyIdx, 0, false)
                        }
                    } else {
                        dragStart = down.position
                        dragEnd = down.position
                    }

                    var isMultiTouch = false
                    var hasMoved = false
                    
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes
                        
                        if (pointers.size > 1) {
                            isMultiTouch = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (zoom != 1f || pan != Offset.Zero) {
                                currentOnTransform(zoom, pan, centroid)
                            }
                            dragStart = null; dragEnd = null
                            pointers.forEach { it.consume() }
                        } else if (!isMultiTouch) {
                            val change = pointers.first()
                            if (change.pressed) {
                                val curImgX = (change.position.x - currentOffsetX) / currentScale
                                val curImgY = (change.position.y - currentOffsetY) / currentScale

                                if (!currentHideGuidelines && (currentEditorMode == EditorMode.SELECT_ROI || currentEditorMode == EditorMode.MANUAL_ADD)) {
                                    if (dragEnd != change.position) {
                                        dragEnd = change.position
                                        change.consume()
                                    }
                                } else if (!currentHideGuidelines && currentEditorMode == EditorMode.EYEDROPPER) {
                                    val tempPolygons = currentPolygons.toMutableList()
                                    if (currentActivePolyIdx in tempPolygons.indices) {
                                        val poly = tempPolygons[currentActivePolyIdx]
                                        tempPolygons[currentActivePolyIdx] = poly.copy(vertices = listOf(PointF(curImgX, curImgY)))
                                        currentOnPolygonsChanged(tempPolygons, currentActivePolyIdx, 0, false)
                                    }
                                    change.consume()
                                } else {
                                    if (!currentHideGuidelines && polyIndex != -1 && vertexIndex != null) {
                                        val newX = curImgX + touchDiffX
                                        val newY = curImgY + touchDiffY
                                        
                                        if (abs(currentPolygons[polyIndex].vertices[vertexIndex].x - newX) > 0.1f || 
                                            abs(currentPolygons[polyIndex].vertices[vertexIndex].y - newY) > 0.1f) {
                                            hasMoved = true
                                            isDraggingVertex = true
                                            val newList = currentPolygons.toMutableList()
                                            newList[polyIndex] = newList[polyIndex].copy(
                                                vertices = newList[polyIndex].vertices.mapIndexed { index, p ->
                                                    if (index == vertexIndex) PointF(newX, newY) else p
                                                }
                                            )
                                            currentOnPolygonsChanged(newList, polyIndex, vertexIndex, false)
                                        }
                                        change.consume()
                                    } else {
                                        val pan = event.calculatePan()
                                        if (pan != Offset.Zero) {
                                            currentOnTransform(1f, pan, change.position)
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    
                    if (!isMultiTouch && !currentHideGuidelines) {
                        isDraggingVertex = false
                        if (currentEditorMode == EditorMode.EYEDROPPER) {
                            val finalX = (dragEnd?.x ?: down.position.x - currentOffsetX) / currentScale
                            val finalY = (dragEnd?.y ?: down.position.y - currentOffsetY) / currentScale
                            val tempPolygons = currentPolygons.toMutableList()
                            if (currentActivePolyIdx in tempPolygons.indices) {
                                val poly = tempPolygons[currentActivePolyIdx]
                                tempPolygons[currentActivePolyIdx] = poly.copy(vertices = listOf(PointF(finalX, finalY)))
                                currentOnPolygonsChanged(tempPolygons, currentActivePolyIdx, 0, true)
                            }
                        } else {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null && hypot(start.x - end.x, start.y - end.y) > 10f) {
                                val rect = RectF(
                                    (min(start.x, end.x) - currentOffsetX) / currentScale,
                                    (min(start.y, end.y) - currentOffsetY) / currentScale,
                                    (max(start.x, end.x) - currentOffsetX) / currentScale,
                                    (max(start.y, end.y) - currentOffsetY) / currentScale
                                )
                                if (currentEditorMode == EditorMode.SELECT_ROI) currentOnRoiSelected(rect)
                                else if (currentEditorMode == EditorMode.MANUAL_ADD) currentOnManualRectAdded(rect)
                            } else if (hasMoved && polyIndex != -1 && vertexIndex != null) {
                                currentOnPolygonsChanged(currentPolygons, polyIndex, vertexIndex, true)
                            }
                        }
                    }
                    dragStart = null; dragEnd = null
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        if (!currentHideGuidelines && currentEditorMode == EditorMode.EDIT_POLYGON) {
                            val imgX = (pos.x - currentOffsetX) / currentScale
                            val imgY = (pos.y - currentOffsetY) / currentScale
                            
                            var foundP = -1
                            var foundV: Int? = null
                            for (pIdx in currentPolygons.indices) {
                                val poly = currentPolygons[pIdx]
                                val vIdx = poly.vertices.indexOfFirst { v ->
                                    val hitP = getHitPoint(v, poly, currentSettings, currentScale)
                                    hypot(hitP.x - imgX, hitP.y - imgY) < (currentSettings.touchHitRadius / currentScale)
                                }.takeIf { it >= 0 }
                                if (vIdx != null) { foundP = pIdx; foundV = vIdx; break }
                            }
                            if (foundP != -1) {
                                currentOnPolygonsChanged(currentPolygons, foundP, foundV, false)
                                currentOnLineSelected(foundP, null)
                            } else {
                                var foundL = -1; var foundLP = -1
                                for (pIdx in currentPolygons.indices) {
                                    val vertices = currentPolygons[pIdx].vertices
                                    for (i in vertices.indices) {
                                        val p1 = vertices[i]; val p2 = vertices[(i + 1) % vertices.size]
                                        if (isTapNearLine(imgX, imgY, p1.x, p1.y, p2.x, p2.y, 40f / currentScale)) {
                                            foundL = i; foundLP = pIdx; break
                                        }
                                    }
                                    if (foundL != -1) break
                                }
                                if (foundL != -1) {
                                    foundLP.let { lp ->
                                        currentOnLineSelected(lp, foundL)
                                        currentOnPolygonsChanged(currentPolygons, lp, null, false)
                                    }
                                }
                            }
                        }
                    }
                )
            }
    ) {
        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawImage(imageBitmap)
            
            // プレビュー中は枠線や頂点を描画しない
            if (!currentHideGuidelines) {
                currentPolygons.forEachIndexed { pIdx, poly ->
                    val polyAlpha = if (editorMode != EditorMode.EDIT_POLYGON) 0.3f else 0.8f
                    val isPolyActive = pIdx == activePolyIndex
                    
                    poly.vertices.forEachIndexed { i, p ->
                        val nextP = poly.vertices[(i + 1) % poly.vertices.size]
                        val isLineActive = isPolyActive && i == activeLineIndex
                        drawLine(
                            color = (if (isLineActive) Color.Cyan else if (isPolyActive) Color.Red else Color.Gray).copy(alpha = polyAlpha),
                            start = Offset(p.x, p.y), end = Offset(nextP.x, nextP.y),
                            strokeWidth = (if (isLineActive) 6f else 3f) / scale
                        )
                    }
                    
                    poly.vertices.forEachIndexed { vIdx, p ->
                        val isSelected = isPolyActive && vIdx == activeVertexIndex
                        val color = if (isSelected) Color.Cyan else if (isPolyActive) Color.Yellow else Color.LightGray
                        
                        if (isPolyActive && currentEditorMode == EditorMode.EDIT_POLYGON && !isDraggingVertex) {
                            val hitP = getHitPoint(p, poly, currentSettings, currentScale)
                            val hitRadiusOnCanvas = currentSettings.touchHitRadius / scale
                            
                            drawCircle(
                                color = Color.Yellow.copy(alpha = 0.15f),
                                radius = hitRadiusOnCanvas,
                                center = Offset(hitP.x, hitP.y)
                            )
                            drawCircle(
                                color = Color.Yellow.copy(alpha = 0.3f),
                                radius = hitRadiusOnCanvas,
                                center = Offset(hitP.x, hitP.y),
                                style = Stroke(width = 1f / scale)
                            )
                        }
                        
                        drawCircle(color = Color.Black.copy(alpha = polyAlpha * 0.6f), radius = 20f / scale, center = Offset(p.x, p.y), style = Stroke(width = 4f / scale))
                        drawCircle(color = color.copy(alpha = polyAlpha), radius = 6f / scale, center = Offset(p.x, p.y))
                    }
                }
            }
        }
        
        if (!currentHideGuidelines) {
            val start = dragStart; val end = dragEnd
            if (editorMode != EditorMode.EDIT_POLYGON && editorMode != EditorMode.EYEDROPPER && start != null && end != null) {
                val rectTopLeft = Offset(min(start.x, end.x), min(start.y, end.y))
                val rectSize = Size(abs(end.x - start.x), abs(end.y - start.y))
                val color = if (editorMode == EditorMode.MANUAL_ADD) Color.Green else Color.Cyan
                drawRect(color = color, topLeft = rectTopLeft, size = rectSize, style = Stroke(width = 4f))
                drawRect(color = color.copy(alpha = 0.2f), topLeft = rectTopLeft, size = rectSize)
            }
        }
    }
}

fun isTapNearLine(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float, threshold: Float): Boolean {
    val lineLenSq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    if (lineLenSq == 0f) return hypot(px - x1, py - y1) < threshold
    val t = (((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / lineLenSq).coerceIn(0f, 1f)
    val nearestX = x1 + t * (x2 - x1)
    val nearestY = y1 + t * (y2 - y1)
    return hypot(px - nearestX, py - nearestY) < threshold
}
