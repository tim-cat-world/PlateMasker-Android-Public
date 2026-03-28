package com.timcatworld.platemasker

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    bitmap: Bitmap,
    initialPolygons: List<EditablePolygon>,
    appSettings: AppSettings,
    onBack: () -> Unit,
    onResetOrientation: () -> Unit = {},
    onBitmapChanged: (Bitmap) -> Unit = {}
) {
    val context = LocalContext.current
    val detector = remember { LicensePlateDetector(context) }
    
    var polygons by remember { mutableStateOf(initialPolygons) }
    var activePolyIndex by remember { mutableStateOf(0) }
    var activeVertexIndex by remember { mutableStateOf<Int?>(null) }
    var activeLineIndex by remember { mutableStateOf<Int?>(null) }
    var editorMode by remember { mutableStateOf(EditorMode.EDIT_POLYGON) }
    
    var isPreviewMode by remember { mutableStateOf(false) }
    
    var lastCommittedPolygons by remember { mutableStateOf(initialPolygons) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var currentInferenceJob by remember { mutableStateOf<Job?>(null) }
    var currentMaskJob by remember { mutableStateOf<Job?>(null) }

    var undoStack by remember { mutableStateOf(listOf<List<EditablePolygon>>()) }
    var redoStack by remember { mutableStateOf(listOf<List<EditablePolygon>>()) }
    var polygonsAtActionStart by remember { mutableStateOf<List<EditablePolygon>?>(null) }

    var showShareSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val isJa = appSettings.language == AppLanguage.JAPANESE
    val labelEdit = if (isJa) "編集" else "Edit"
    val types = listOf(
        MaskType.BLUR to (if (isJa) "ぼかし" else "Blur"),
        MaskType.AVERAGE_COLOR to (if (isJa) "平均" else "Avg"),
        MaskType.SOLID_COLOR to (if (isJa) "単色" else "Color"),
        MaskType.IMAGE to (if (isJa) "画像" else "Image")
    )

    LaunchedEffect(polygons, bitmap, appSettings) {
        currentMaskJob?.cancel()
        currentMaskJob = scope.launch {
            delay(100) // 連続入力時の負荷軽減
            val nextBitmap = withContext(Dispatchers.Default) {
                if (!isActive) return@withContext null
                MaskProcessor.processWithSettings(bitmap, polygons, appSettings)
            }
            if (isActive && nextBitmap != null) {
                // Composeの再描画とrecycle()が競合するとクラッシュするため、
                // processedBitmapの古いオブジェクトに対してはrecycle()を呼ばず、GCに任せるのが安全。
                processedBitmap = nextBitmap
            }
        }
    }

    fun commitChanges(newList: List<EditablePolygon>) {
        if (newList != lastCommittedPolygons) {
            undoStack = undoStack + listOf(lastCommittedPolygons)
            redoStack = emptyList()
            polygons = newList
            lastCommittedPolygons = newList
        }
    }

    fun updateActivePolygonConfig(newConfig: MaskConfig, isCommit: Boolean) {
        val updatedList = if (newConfig.applyToAll) {
            polygons.map { it.copy(config = newConfig) }
        } else {
            polygons.toMutableList().apply {
                if (activePolyIndex in indices) {
                    this[activePolyIndex] = this[activePolyIndex].copy(config = newConfig)
                }
            }
        }
        if (isCommit) commitChanges(updatedList) else polygons = updatedList
    }

    fun rotateImage(degrees: Float) {
        scope.launch {
            isProcessing = true
            val oldW = bitmap.width
            val oldH = bitmap.height
            val rotatedBitmap = withContext(Dispatchers.Default) {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            
            val rotatedPolygons = polygons.map { poly ->
                poly.copy(vertices = poly.vertices.map { p ->
                    when (degrees) {
                        90f -> PointF(oldH - p.y, p.x)
                        -90f -> PointF(p.y, oldW - p.x)
                        else -> p
                    }
                })
            }
            
            polygons = rotatedPolygons
            lastCommittedPolygons = rotatedPolygons
            onBitmapChanged(rotatedBitmap)
            // ここでも古いbitmapのrecycle()は呼び出し元(MainActivity)に任せるか、
            // 描画が確実に終わるタイミングで行う必要がある。
            undoStack = emptyList() 
            redoStack = emptyList()
            isProcessing = false
        }
    }

    fun runFullDetection() {
        currentInferenceJob?.cancel()
        currentInferenceJob = scope.launch {
            isProcessing = true
            val detectedPolygons = withContext(Dispatchers.Default) {
                if (!isActive) return@withContext emptyList<EditablePolygon>()
                val rects = detector.detect(bitmap)
                val found = mutableListOf<EditablePolygon>()
                for (rect in rects) {
                    if (!isActive) break
                    val refined = PlateRefinement.detectPlateQuad(bitmap, rect)
                    if (refined != null) {
                        val poly = EditablePolygon(refined.map { PointF(it.x.toFloat(), it.y.toFloat()) }).optimize()
                        if (poly.isValid()) found.add(poly)
                    }
                }
                EditablePolygon.filterDuplicates(found)
            }
            
            if (isActive) {
                if (detectedPolygons.isEmpty()) {
                    Toast.makeText(context, if (isJa) "検出されませんでした" else "No plates detected", Toast.LENGTH_SHORT).show()
                } else {
                    commitChanges(EditablePolygon.filterDuplicates(polygons + detectedPolygons))
                }
                isProcessing = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(it)
                val selected = BitmapFactory.decodeStream(stream)
                if (selected != null) {
                    withContext(Dispatchers.Main) {
                        if (activePolyIndex in polygons.indices) {
                            val updatedConfig = polygons[activePolyIndex].config.copy(overlayImage = selected, type = MaskType.IMAGE)
                            updateActivePolygonConfig(updatedConfig, true)
                        }
                    }
                }
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    (processedBitmap ?: bitmap).compress(Bitmap.CompressFormat.JPEG, 95, out)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, if (isJa) "保存しました" else "Saved", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }
            }
        }
    }

    val userScale = remember { Animatable(1f) }
    val userOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var containerWidth by remember { mutableStateOf(0f) }
    var containerHeight by remember { mutableStateOf(0f) }

    val editorContent = @Composable {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).onSizeChanged { containerWidth = it.width.toFloat(); containerHeight = it.height.toFloat() }) {
            if (containerWidth > 0 && containerHeight > 0) {
                val baseScale by remember(containerWidth, containerHeight, bitmap) {
                    derivedStateOf { minOf(containerWidth / bitmap.width, containerHeight / bitmap.height) }
                }
                val baseOffsetX by remember(containerWidth, baseScale, bitmap) {
                    derivedStateOf { (containerWidth - bitmap.width * baseScale) / 2f }
                }
                val baseOffsetY by remember(containerHeight, baseScale, bitmap) {
                    derivedStateOf { (containerHeight - bitmap.height * baseScale) / 2f }
                }

                PolygonEditor(
                    bitmap = processedBitmap ?: bitmap,
                    polygons = polygons,
                    activePolyIndex = activePolyIndex,
                    scale = baseScale * userScale.value,
                    offsetX = baseOffsetX + userOffset.value.x,
                    offsetY = baseOffsetY + userOffset.value.y,
                    activeVertexIndex = activeVertexIndex,
                    activeLineIndex = activeLineIndex,
                    editorMode = editorMode,
                    hideGuidelines = isPreviewMode,
                    onPolygonsChanged = { updatedList, pIdx, vIdx, isCommit ->
                        if (editorMode == EditorMode.EYEDROPPER) {
                            vIdx?.let { idx ->
                                val point = updatedList[pIdx].vertices[idx]
                                val px = point.x.toInt().coerceIn(0, bitmap.width - 1); val py = point.y.toInt().coerceIn(0, bitmap.height - 1)
                                if (!bitmap.isRecycled) {
                                    val color = bitmap.getPixel(px, py)
                                    if (activePolyIndex in polygons.indices) {
                                        val newConfig = polygons[activePolyIndex].config.copy(color = color, type = MaskType.SOLID_COLOR)
                                        updateActivePolygonConfig(newConfig, isCommit)
                                    }
                                }
                                if (isCommit) editorMode = EditorMode.EDIT_POLYGON
                            }
                        } else {
                            if (isCommit) { 
                                polygonsAtActionStart?.let { start -> if (updatedList != start) commitChanges(updatedList) }
                                polygonsAtActionStart = null
                                lastCommittedPolygons = updatedList
                            }
                            else { 
                                if (polygonsAtActionStart == null) polygonsAtActionStart = polygons
                                polygons = updatedList
                            }
                            activePolyIndex = pIdx; activeVertexIndex = vIdx
                        }
                    },
                    onLineSelected = { pIdx, lIdx -> activePolyIndex = pIdx; activeLineIndex = lIdx },
                    onTransform = { zoom, pan, centroid ->
                        scope.launch {
                            val pivot = centroid - Offset(baseOffsetX, baseOffsetY)
                            val newScale = (userScale.value * zoom).coerceIn(1f, 10f)
                            val proposedOffset = pivot - (pivot - userOffset.value) * (newScale / userScale.value) + pan
                            val imgW = bitmap.width * baseScale * newScale; val imgH = bitmap.height * baseScale * newScale
                            val horizLimit = maxOf(100f, imgW * 0.2f); val vertLimit = maxOf(100f, imgH * 0.2f)
                            val constrainedOffset = Offset(proposedOffset.x.coerceIn(-baseOffsetX - imgW + horizLimit, containerWidth - baseOffsetX - horizLimit), proposedOffset.y.coerceIn(-baseOffsetY - imgH + vertLimit, containerHeight - baseOffsetY - vertLimit))
                            userScale.snapTo(newScale); userOffset.snapTo(constrainedOffset)
                        }
                    },
                    onRoiSelected = { roi ->
                        currentInferenceJob?.cancel()
                        currentInferenceJob = scope.launch {
                            isProcessing = true
                            val points = withContext(Dispatchers.Default) {
                                yield()
                                if (!isActive || bitmap.isRecycled) return@withContext null
                                val roiW = roi.width().toInt().coerceIn(1, bitmap.width)
                                val roiH = roi.height().toInt().coerceIn(1, bitmap.height)
                                val roiBitmap = Bitmap.createBitmap(bitmap, roi.left.toInt().coerceIn(0, bitmap.width - roiW), roi.top.toInt().coerceIn(0, bitmap.height - roiH), roiW, roiH)
                                try {
                                    detector.detect(roiBitmap).firstOrNull()?.let { 
                                        PlateRefinement.detectPlateQuad(bitmap, RectF(it.left + roi.left, it.top + roi.top, it.right + roi.left, it.bottom + roi.top)) 
                                    }
                                } finally {
                                    roiBitmap.recycle()
                                }
                            }
                            if (isActive) {
                                val newList = polygons.toMutableList()
                                newList.add(EditablePolygon(points?.map { PointF(it.x.toFloat(), it.y.toFloat()) } ?: listOf(PointF(roi.left, roi.top), PointF(roi.right, roi.top), PointF(roi.right, roi.bottom), PointF(roi.left, roi.bottom))).optimize())
                                commitChanges(EditablePolygon.filterDuplicates(newList)); activePolyIndex = polygons.size - 1; editorMode = EditorMode.EDIT_POLYGON; isProcessing = false
                            }
                        }
                    },
                    onManualRectAdded = { r ->
                        val newPoly = EditablePolygon(listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom)))
                        commitChanges(EditablePolygon.filterDuplicates(polygons + newPoly)); activePolyIndex = polygons.size - 1; editorMode = EditorMode.EDIT_POLYGON
                    },
                    appSettings = appSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (isProcessing) CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
        }
    }

    val controlsContent = @Composable {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if(isJa) "ツール" else "Tools", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))

                IconButton(onClick = { runFullDetection() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }

                if (activeVertexIndex != null && polygons.getOrNull(activePolyIndex)?.vertices?.size ?: 0 > 3) {
                    IconButton(onClick = {
                        val poly = polygons[activePolyIndex]
                        val newVertices = poly.vertices.toMutableList().apply { removeAt(activeVertexIndex!!) }
                        val newList = polygons.toMutableList().apply { set(activePolyIndex, poly.copy(vertices = newVertices)) }
                        commitChanges(newList)
                        activeVertexIndex = null
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
                if (activeLineIndex != null) {
                    IconButton(onClick = {
                        val poly = polygons[activePolyIndex]
                        val v1 = poly.vertices[activeLineIndex!!]
                        val v2 = poly.vertices[(activeLineIndex!! + 1) % poly.vertices.size]
                        val newV = PointF((v1.x + v2.x) / 2f, (v1.y + v2.y) / 2f)
                        val newVertices = poly.vertices.toMutableList().apply { add(activeLineIndex!! + 1, newV) }
                        val newList = polygons.toMutableList().apply { set(activePolyIndex, poly.copy(vertices = newVertices)) }
                        commitChanges(newList)
                        activeVertexIndex = activeLineIndex!! + 1
                        activeLineIndex = null
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.AddCircleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }

                IconButton(onClick = { if (polygons.isNotEmpty()) { val nl = polygons.toMutableList(); nl.removeAt(activePolyIndex); commitChanges(nl); activePolyIndex = 0 } }, enabled = polygons.isNotEmpty(), modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = editorMode == EditorMode.EDIT_POLYGON, onClick = { editorMode = EditorMode.EDIT_POLYGON }, label = { Text(labelEdit, fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(14.dp)) })
                FilterChip(selected = editorMode == EditorMode.SELECT_ROI, onClick = { editorMode = EditorMode.SELECT_ROI }, label = { Text(if(isJa) "範囲" else "ROI", fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(14.dp)) })
                FilterChip(selected = editorMode == EditorMode.MANUAL_ADD, onClick = { editorMode = EditorMode.MANUAL_ADD }, label = { Text(if(isJa) "追加" else "Add", fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Default.AspectRatio, null, Modifier.size(14.dp)) })
            }

            if (polygons.isNotEmpty()) {
                val currentPoly = polygons[activePolyIndex]; val config = currentPoly.config
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                    Text(if(isJa) "マスク設定" else "Mask Settings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(if(isJa) "一括" else "All", style = MaterialTheme.typography.labelSmall)
                    Checkbox(checked = config.applyToAll, onCheckedChange = { b -> 
                        val currentConfig = polygons[activePolyIndex].config.copy(applyToAll = b)
                        updateActivePolygonConfig(currentConfig, true) 
                    }, modifier = Modifier.scale(0.8f))
                }
                
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    types.forEachIndexed { i, (t, l) ->
                        SegmentedButton(
                            selected = config.type == t,
                            onClick = {
                                val poly = polygons[activePolyIndex]
                                var newConfig = poly.config.copy(type = t)
                                if (t == MaskType.SOLID_COLOR) {
                                    newConfig = newConfig.copy(color = MaskProcessor.calcAverageColor(bitmap, poly))
                                }
                                updateActivePolygonConfig(newConfig, true)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                            label = { Text(l, fontSize = 10.sp) }
                        )
                    }
                }
                
                Spacer(Modifier.height(4.dp))
                
                when (config.type) {
                    MaskType.BLUR -> {
                        CompactSlider(
                            label = if (isJa) "ぼかし強度" else "Blur Intensity",
                            value = config.blurRadius,
                            range = 1f..100f,
                            defaultValue = 25f,
                            onValueChangeFinished = {
                                val newConfig = polygons[activePolyIndex].config
                                updateActivePolygonConfig(newConfig, true)
                            }
                        ) { newVal ->
                            val newConfig = polygons[activePolyIndex].config.copy(blurRadius = newVal)
                            updateActivePolygonConfig(newConfig, false)
                        }
                    }
                    MaskType.IMAGE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f).height(36.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (isJa) "画像選択" else "Pick", fontSize = 12.sp) }
                            IconButton(onClick = { 
                                val nextRot = (config.rotation + 90) % 360
                                val newConfig = config.copy(rotation = nextRot)
                                updateActivePolygonConfig(newConfig, true)
                            }) { Icon(Icons.Default.RotateRight, null) }
                            OutlinedButton(onClick = { 
                                val mode = if (config.imageFitMode == ImageFitMode.CROP) ImageFitMode.STRETCH else ImageFitMode.CROP
                                val newConfig = config.copy(imageFitMode = mode)
                                updateActivePolygonConfig(newConfig, true)
                            }, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (config.imageFitMode == ImageFitMode.CROP) (if (isJa) "比率" else "Crop") else (if (isJa) "引延" else "Fill"), fontSize = 12.sp) }
                        }
                    }
                    MaskType.SOLID_COLOR -> {
                        FullColorPicker(
                            initialColor = Color(config.color),
                            onColorChanged = { newColor ->
                                val newConfig = polygons[activePolyIndex].config.copy(color = newColor.toArgb())
                                updateActivePolygonConfig(newConfig, false)
                            },
                            onColorConfirmed = { finalColor ->
                                val newConfig = polygons[activePolyIndex].config.copy(color = finalColor.toArgb())
                                updateActivePolygonConfig(newConfig, true)
                            },
                            isEyedropperActive = editorMode == EditorMode.EYEDROPPER,
                            onEyedropperClick = { editorMode = if (editorMode == EditorMode.EYEDROPPER) EditorMode.EDIT_POLYGON else EditorMode.EYEDROPPER },
                            language = appSettings.language
                        )
                    }
                    else -> {}
                }
            }
            Spacer(Modifier.height(100.dp))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (isJa) "編集" else "Edit", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isPreviewMode = !isPreviewMode }) {
                        Icon(
                            imageVector = if (isPreviewMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Preview",
                            tint = if (isPreviewMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    
                    IconButton(onClick = { rotateImage(-90f) }) { Icon(Icons.Default.RotateLeft, null) }
                    IconButton(onClick = { rotateImage(90f) }) { Icon(Icons.Default.RotateRight, null) }

                    IconButton(onClick = { 
                        val l = undoStack.last(); redoStack = redoStack + listOf(polygons); polygons = l; lastCommittedPolygons = l; undoStack = undoStack.dropLast(1) 
                    }, enabled = undoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                    }
                    IconButton(onClick = { 
                        val n = redoStack.last(); undoStack = undoStack + listOf(polygons); polygons = n; lastCommittedPolygons = n; redoStack = redoStack.dropLast(1) 
                    }, enabled = redoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Redo, null)
                    }
                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(Icons.Default.IosShare, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        }
    ) { innerPadding ->
        if (isLandscape) { Row(Modifier.fillMaxSize().padding(innerPadding)) { Box(Modifier.weight(1f)) { editorContent() }; Surface(Modifier.width(320.dp).fillMaxHeight(), tonalElevation = 2.dp) { controlsContent() } } }
        else { Column(Modifier.fillMaxSize().padding(innerPadding)) { Box(Modifier.weight(1f)) { editorContent() }; Surface(Modifier.fillMaxWidth().heightIn(max = 300.dp), tonalElevation = 2.dp) { controlsContent() } } }
        
        if (showShareSheet) {
            ModalBottomSheet(onDismissRequest = { showShareSheet = false }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
                ShareActionsContent(
                    processedBitmap = processedBitmap ?: bitmap,
                    onDismiss = { showShareSheet = false },
                    onComplete = { onBack() },
                    appSettings = appSettings,
                    onManualSave = { createDocumentLauncher.launch("PlateMasker_${System.currentTimeMillis()}.jpg") },
                    onResetOrientation = onResetOrientation
                )
            }
        }
    }
}

@Composable
fun ShareActionsContent(
    processedBitmap: Bitmap,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    appSettings: AppSettings,
    onManualSave: () -> Unit,
    onResetOrientation: () -> Unit = {}
) {
    val context = LocalContext.current
    val isJa = appSettings.language == AppLanguage.JAPANESE
    
    var showFileNameDialog by remember { mutableStateOf(false) }
    var fileNameInput by remember { mutableStateOf(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val handleAction = { action: (String) -> Unit ->
        if (appSettings.askFileNameBeforeShare) {
            pendingAction = { action(fileNameInput) }
            showFileNameDialog = true
        } else {
            action("PlateMasker_${System.currentTimeMillis()}")
        }
    }

    if (showFileNameDialog) {
        AlertDialog(
            onDismissRequest = { showFileNameDialog = false },
            title = { Text(if (isJa) "ファイル名を入力" else "Enter Filename") },
            text = {
                OutlinedTextField(
                    value = fileNameInput,
                    onValueChange = { fileNameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isJa) "ファイル名" else "Filename") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    showFileNameDialog = false
                    pendingAction?.invoke()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showFileNameDialog = false }) { Text(if (isJa) "キャンセル" else "Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
        Text(if (isJa) "完了した画像をどうしますか？" else "What to do with the result?", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 20.dp))
        
        ListItem(
            headlineContent = { Text(if (isJa) "端末に保存" else "Save to Gallery") },
            leadingContent = { Icon(Icons.Default.SaveAlt, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                if (appSettings.autoSaveWithIncrementalName) {
                    val uri = saveBitmapToGallery(context, processedBitmap, "PlateMasker_${System.currentTimeMillis()}", appSettings.saveFolderPath)
                    if (uri != null) {
                        Toast.makeText(context, if (isJa) "保存しました" else "Saved to gallery", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                } else {
                    onManualSave()
                }
            }
        )
        ListItem(
            headlineContent = { Text(if (isJa) "他のアプリで共有" else "Share to other apps") },
            leadingContent = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                handleAction { name ->
                    val uri = saveBitmapToGallery(context, processedBitmap, name)
                    if (uri != null) {
                        onResetOrientation()
                        shareImage(context, uri)
                        onDismiss()
                    }
                }
            }
        )
        ListItem(
            headlineContent = { Text(if (isJa) "他のエディタで開く" else "Open in other editor") },
            leadingContent = { Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                handleAction { name ->
                    val uri = saveBitmapToGallery(context, processedBitmap, name)
                    if (uri != null) {
                        onResetOrientation()
                        openInExternalEditor(context, uri)
                        onDismiss()
                    }
                }
            }
        )
        
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (isJa) "キャンセル" else "Cancel")
        }
    }
}

@Composable
fun FullColorPicker(
    initialColor: Color,
    onColorChanged: (Color) -> Unit,
    onColorConfirmed: (Color) -> Unit,
    isEyedropperActive: Boolean,
    onEyedropperClick: () -> Unit,
    language: AppLanguage
) {
    val hsv = remember(initialColor) {
        val floatArray = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), floatArray)
        floatArray
    }
    var hue by remember(hsv[0]) { mutableStateOf(hsv[0]) }
    var saturation by remember(hsv[1]) { mutableStateOf(if (hsv[1] == 0f && hsv[2] == 1f) 0.5f else hsv[1]) }
    var value by remember(hsv[2]) { mutableStateOf(hsv[2]) }

    val updateColor = { h: Float, s: Float, v: Float, isFinal: Boolean ->
        val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        val color = Color(colorInt)
        if (isFinal) onColorConfirmed(color) else onColorChanged(color)
    }

    Column(Modifier.padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            FilterChip(
                selected = isEyedropperActive,
                onClick = onEyedropperClick,
                label = { Text(if (language == AppLanguage.JAPANESE) "スポイト" else "Pick", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Colorize, null, Modifier.size(12.dp)) },
                modifier = Modifier.height(28.dp)
            )
            Box(Modifier.size(20.dp).background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))), RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
        }
        
        val hueBrush = Brush.horizontalGradient(colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
        CompactSlider(
            label = if (language == AppLanguage.JAPANESE) "色相" else "Hue",
            value = hue,
            range = 0f..360f,
            defaultValue = 0f,
            backgroundBrush = hueBrush,
            onValueChangeFinished = { updateColor(hue, saturation, value, true) }
        ) { hue = it; updateColor(hue, saturation, value, false) }
        
        val satBrush = Brush.horizontalGradient(colors = listOf(Color.Gray, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))))
        CompactSlider(
            label = if (language == AppLanguage.JAPANESE) "彩度" else "Sat",
            value = saturation,
            range = 0f..1f,
            defaultValue = 0.5f,
            backgroundBrush = satBrush,
            onValueChangeFinished = { updateColor(hue, saturation, value, true) }
        ) { saturation = it; updateColor(hue, saturation, value, false) }
        
        val valBrush = Brush.horizontalGradient(colors = listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))))
        CompactSlider(
            label = if (language == AppLanguage.JAPANESE) "明度" else "Val",
            value = value,
            range = 0f..1f,
            defaultValue = 1f,
            backgroundBrush = valBrush,
            onValueChangeFinished = { updateColor(hue, saturation, value, true) }
        ) { value = it; updateColor(hue, saturation, value, false) }
    }
}

@Composable
fun CompactSlider(
    label: String, 
    value: Float, 
    range: ClosedFloatingPointRange<Float>, 
    defaultValue: Float,
    backgroundBrush: Brush? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
            IconButton(onClick = { onValueChange(defaultValue); onValueChangeFinished?.invoke() }, modifier = Modifier.size(16.dp)) { Icon(Icons.Outlined.RestartAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp)) }
        }
        Box(modifier = Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
            if (backgroundBrush != null) {
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 8.dp).background(backgroundBrush, RoundedCornerShape(2.dp)))
            }
            Slider(
                value = value, 
                onValueChange = onValueChange, 
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range, 
                modifier = Modifier.fillMaxWidth(),
                colors = if (backgroundBrush != null) SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = MaterialTheme.colorScheme.primary
                ) else SliderDefaults.colors()
            )
        }
    }
}
