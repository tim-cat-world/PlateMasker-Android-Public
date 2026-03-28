package com.timcatworld.platemasker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.timcatworld.platemasker.ui.theme.PlateMaskerTheme
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OpenCVの初期化。
        if (!OpenCVLoader.initDebug()) {
            // エラー処理
        }
        
        // AdMobの初期化
        MobileAds.initialize(this) {}

        setContent {
            PlateMaskerTheme {
                val context = LocalContext.current
                val detector = remember { LicensePlateDetector(context) }
                val settingsManager = remember { SettingsManager(context) }

                var appSettings by remember { mutableStateOf(settingsManager.loadSettings()) }
                var showSettingsDialog by remember { mutableStateOf(false) }

                var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
                var polygons by remember { mutableStateOf<List<EditablePolygon>?>(null) }
                var isProcessing by remember { mutableStateOf(false) }
                var processingJob by remember { mutableStateOf<Job?>(null) }

                val scope = rememberCoroutineScope()

                val isJa = appSettings.language == AppLanguage.JAPANESE
                val pickImageText = if (isJa) "画像を選択" else "Select Image"
                val settingsText = if (isJa) "設定" else "Settings"
                val appTitle = "PlateMasker"
                val appDescription = if (isJa) "ナンバープレートを自動検出して保護" else "Auto-detect and protect license plates"
                val processingText = if (isJa) "処理中..." else "Processing..."
                val cancelText = if (isJa) "キャンセル" else "Cancel"
                val errorText = if (isJa) "検出に失敗しました" else "Detection failed"

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri ?: return@rememberLauncherForActivityResult

                    processingJob?.cancel()
                    processingJob = scope.launch {
                        try {
                            isProcessing = true
                            
                            // 以前の情報をリセット。
                            // Bitmapのrecycle()は、UIスレッドやバックグラウンドでの競合を避けるため
                            // 直接呼ばず、ガベージコレクタ(GC)に委ねます。
                            originalBitmap = null
                            polygons = null

                            val bitmap = withContext(Dispatchers.IO) { loadBitmapSafely(context, uri) }
                            if (bitmap == null) {
                                isProcessing = false
                                withContext(Dispatchers.Main) { Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show() }
                                return@launch
                            }
                            
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            originalBitmap = bitmap
                            
                            val detectedPolygons = withContext(Dispatchers.Default) {
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
                                    withContext(Dispatchers.Main) { Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show() }
                                }
                                polygons = detectedPolygons
                                isProcessing = false
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            isProcessing = false
                            withContext(Dispatchers.Main) { Toast.makeText(context, "予期せぬエラーが発生しました", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }

                if (showSettingsDialog) {
                    SettingsDialog(
                        currentSettings = appSettings,
                        onDismiss = { showSettingsDialog = false },
                        onSave = { 
                            appSettings = it
                            settingsManager.saveSettings(it)
                            showSettingsDialog = false 
                        }
                    )
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        originalBitmap != null -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (polygons != null) {
                                    ImageEditorScreen(
                                        bitmap = originalBitmap!!,
                                        initialPolygons = polygons!!,
                                        appSettings = appSettings,
                                        onBack = {
                                            processingJob?.cancel()
                                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                            originalBitmap = null
                                            polygons = null
                                            isProcessing = false
                                        },
                                        onResetOrientation = {
                                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                        },
                                        onBitmapChanged = { newBitmap ->
                                            // 回転時などもrecycle()は行わず、参照の更新のみに留める
                                            originalBitmap = newBitmap
                                        }
                                    )
                                } else {
                                    Image(bitmap = originalBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.height(16.dp))
                                            Text(processingText, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                            Spacer(Modifier.height(24.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    processingJob?.cancel()
                                                    originalBitmap = null
                                                    isProcessing = false
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                                            ) {
                                                Text(cancelText)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            LandingScreen(
                                title = appTitle,
                                description = appDescription,
                                buttonText = pickImageText,
                                settingsText = settingsText,
                                onPickImage = { launcher.launch("image/*") },
                                onShowSettings = { showSettingsDialog = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LandingScreen(
    title: String,
    description: String,
    buttonText: String,
    settingsText: String,
    onPickImage: () -> Unit,
    onShowSettings: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
    ) {
        // 設定ボタン
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            FilledTonalButton(
                onClick = onShowSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = settingsText, style = MaterialTheme.typography.labelLarge)
            }
        }

        if (isLandscape) {
            // 横画面：左右に分割
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .verticalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Transparent
                    ) {
                        Image(painter = painterResource(id = R.drawable.ic_app_icon), contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.width(48.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onPickImage,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(text = buttonText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        } else {
            // 縦画面：従来の垂直配置
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(180.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = Color.Transparent
                ) {
                    Image(painter = painterResource(id = R.drawable.ic_app_icon), contentDescription = null, modifier = Modifier.fillMaxSize())
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onPickImage,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = buttonText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
        
        // AdMob Banner at the bottom
        AdMobBanner(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        )
    }
}

@Composable
fun AdMobBanner(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.BANNER_AD_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var language by remember { mutableStateOf(currentSettings.language) }
    var region by remember { mutableStateOf(currentSettings.region) }
    var savePath by remember { mutableStateOf(currentSettings.saveFolderPath) }
    var autoSave by remember { mutableStateOf(currentSettings.autoSaveWithIncrementalName) }
    var askFileName by remember { mutableStateOf(currentSettings.askFileNameBeforeShare) }
    var hitRadius by remember { mutableStateOf(currentSettings.touchHitRadius) }
    var hitOffset by remember { mutableStateOf(currentSettings.touchHitOffset) }
    
    var showOssDialog by remember { mutableStateOf(false) }
    var showHitAdjustment by remember { mutableStateOf(false) }

    val isJa = language == AppLanguage.JAPANESE
    val title = if (isJa) "設定" else "Settings"
    val langLabel = if (isJa) "言語" else "Language"
    val regionLabel = if (isJa) "対象地域" else "Target Region"
    val saveLabel = if (isJa) "保存先パス" else "Save Path"
    val saveMethodLabel = if (isJa) "保存・共有設定" else "Save & Share"
    val autoSaveDesc = if (isJa) "自動で別名保存" else "Auto-save incremental"
    val askFileLabel = if (isJa) "共有前に名前を入力" else "Ask filename before share"
    val hitAdjustLabel = if (isJa) "操作感の調整（当たり判定）" else "Touch Hit Adjustment"
    val ossLabel = if (isJa) "その他" else "Others"
    val viewOssLabel = if (isJa) "オープンソースライセンス" else "Open Source Licenses"
    val cancelLabel = if (isJa) "キャンセル" else "Cancel"
    val saveBtnLabel = if (isJa) "保存" else "Save"

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { savePath = it.toString() }
    }

    if (showOssDialog) {
        OssLicensesDialog(onDismiss = { showOssDialog = false }, isJa = isJa)
    }
    
    if (showHitAdjustment) {
        HitAdjustmentDialog(
            initialRadius = hitRadius,
            initialOffset = hitOffset,
            isJa = isJa,
            onDismiss = { showHitAdjustment = false },
            onConfirm = { r, o -> hitRadius = r; hitOffset = o; showHitAdjustment = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text(langLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach {
                            FilterChip(selected = language == it, onClick = { language = it }, label = { Text(it.label) }, shape = RoundedCornerShape(8.dp))
                        }
                    }
                }
                Column {
                    Text(regionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlateRegion.entries.forEach {
                            FilterChip(selected = region == it, onClick = { region = it }, label = { Text(it.label) }, shape = RoundedCornerShape(8.dp))
                        }
                    }
                }
                Column {
                    Text(saveLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = if (savePath.isEmpty()) (if(isJa) "デフォルト (Pictures/PlateMasker)" else "Default") else savePath,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { folderLauncher.launch(null) }) {
                                Icon(Icons.Default.Folder, contentDescription = "Select Folder")
                            }
                        },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
                Column {
                    Text(saveMethodLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    
                    SettingItemWithDescription(
                        label = autoSaveDesc,
                        checked = autoSave,
                        onCheckedChange = { autoSave = it },
                        description = if (isJa) "「端末に保存」時に、既存のファイルと重ならないよう連番を振って別名で保存します。" else "When saving to gallery, saves as a new file with a serial number to avoid overwriting."
                    )
                    
                    SettingItemWithDescription(
                        label = askFileLabel,
                        checked = askFileName,
                        onCheckedChange = { askFileName = it },
                        description = if (isJa) "「共有」や「他のアプリで編集」を行う直前に、送信する画像の名前を入力するダイアログを表示します。" else "Displays a dialog to enter the image name just before sharing or editing in another app."
                    )
                }
                
                Column {
                    Text(hitAdjustLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showHitAdjustment = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isJa) "当たり判定を調整する" else "Adjust Hit Radius")
                    }
                }

                Column {
                    Text(ossLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showOssDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(viewOssLabel)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(AppSettings(language, region, savePath, autoSave, askFileName, hitRadius, hitOffset)) }, shape = RoundedCornerShape(12.dp)) { Text(saveBtnLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun HitAdjustmentDialog(
    initialRadius: Float,
    initialOffset: Float,
    isJa: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float) -> Unit
) {
    var radius by remember { mutableStateOf(initialRadius) }
    var offset by remember { mutableStateOf(initialOffset) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isJa) "当たり判定の調整" else "Hit Adjustment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (isJa) "指で頂点が隠れないよう、当たり判定を広げたり位置をずらしたりできます。" else "Adjust hit radius and offset to make editing easier.")
                
                // プレビュー: 物理サイズ（1:1）を重視した表示
                Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        
                        // 頂点の「位置」だけをスケーリングし、円の「大きさ」は物理サイズで描く
                        val diagScale = 0.6f 
                        val vertexX = centerX + 100f * diagScale
                        val vertexY = centerY - 100f * diagScale
                        
                        // ポリゴン（背景の図形）
                        drawLine(Color.Gray, Offset(centerX - 150f * diagScale, centerY + 150f * diagScale), Offset(vertexX, vertexY), strokeWidth = 2f)
                        drawLine(Color.Gray, Offset(vertexX, vertexY), Offset(centerX + 150f * diagScale, centerY + 150f * diagScale), strokeWidth = 2f)
                        
                        // 当たり判定の中心計算
                        val dx = vertexX - centerX; val dy = vertexY - centerY
                        val dist = hypot(dx, dy)
                        // offset は物理ピクセルとして扱う
                        val ratio = (dist + offset) / dist
                        val hitX = centerX + dx * ratio
                        val hitY = centerY + dy * ratio
                        
                        // ガイド線（点線）
                        drawLine(
                            color = Color.White.copy(alpha = 0.5f),
                            start = Offset(vertexX, vertexY),
                            end = Offset(hitX, hitY),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                        
                        // 当たり判定領域 (黄色) - スケールをかけずそのまま radius を使う
                        drawCircle(Color.Yellow.copy(alpha = 0.2f), radius = radius, center = Offset(hitX, hitY))
                        drawCircle(Color.Yellow, radius = radius, center = Offset(hitX, hitY), style = Stroke(width = 2f))
                        
                        // 実際の頂点 (赤) - 編集画面の 6px / scale に相当する物理サイズを表示
                        drawCircle(Color.Red, radius = 8f, center = Offset(vertexX, vertexY))
                        
                        // 判定の中心点 (白)
                        drawCircle(Color.White, radius = 4f, center = Offset(hitX, hitY))
                    }
                }
                
                Column {
                    Text("${if (isJa) "判定の半径" else "Hit Radius"}: ${radius.toInt()}px", style = MaterialTheme.typography.labelMedium)
                    Slider(value = radius, onValueChange = { radius = it }, valueRange = 10f..500f)
                }
                
                Column {
                    Text("${if (isJa) "外側へのオフセット" else "Offset Outside"}: ${offset.toInt()}px", style = MaterialTheme.typography.labelMedium)
                    Slider(value = offset, onValueChange = { offset = it }, valueRange = 0f..500f)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(radius, offset) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text(if (isJa) "キャンセル" else "Cancel") }
        }
    )
}

@Composable
fun SettingItemWithDescription(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "info",
                    tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
fun OssLicensesDialog(onDismiss: () -> Unit, isJa: Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isJa) "オープンソースライセンス" else "Open Source Licenses", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LicenseData.OSS_LICENSES.forEach { license ->
                    Column {
                        Text(text = license.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = license.copyright, style = MaterialTheme.typography.bodySmall)
                        Text(text = license.licenseName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        license.description?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(text = license.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        shape = RoundedCornerShape(24.dp)
    )
}
