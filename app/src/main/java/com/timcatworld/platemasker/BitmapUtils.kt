package com.timcatworld.platemasker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max

fun loadBitmapSafely(
    context: Context,
    uri: Uri,
    maxDim: Int = 1600 // メモリ負荷を考慮しつつ解像度も維持するバランス
): Bitmap? {
    return try {
        // 1. 画像のサイズ（境界線）のみを取得
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val (width, height) = options.outWidth to options.outHeight
        if (width <= 0 || height <= 0) return null

        // 2. 適切な inSampleSize を計算
        // 指定した maxDim を超えない範囲で、2のべき乗の縮小率を求める
        var inSampleSize = 1
        if (width > maxDim || height > maxDim) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / inSampleSize) >= maxDim || (halfHeight / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }

        // 3. 実際に縮小して読み込む
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // メモリ不足時に自動でパージされないように設定（最近のOSではデフォルトで安全ですが明示）
                inMutable = true 
            }
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } catch (e: OutOfMemoryError) {
        // メモリ不足時はさらに縮小を試みるなどの対応も可能だが、まずは例外をキャッチ
        System.gc()
        null
    }
}

/**
 * ビットマップをギャラリーまたは指定されたパスに保存し、Uriを返す
 */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String, customPathUri: String? = null): Uri? {
    // カスタムパス（SAFのTreeUri）が指定されている場合
    if (!customPathUri.isNullOrEmpty()) {
        try {
            val treeUri = Uri.parse(customPathUri)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            val file = pickedDir?.createFile("image/jpeg", fileName)
            val uri = file?.uri
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                return uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // デフォルトの保存処理 (MediaStore)
    val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PlateMasker")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = context.contentResolver.insert(imageCollection, contentValues) ?: return null

    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
        return uri
    } catch (e: Exception) {
        e.printStackTrace()
        context.contentResolver.delete(uri, null, null)
        return null
    }
}

/**
 * 画像を共有する
 */
fun shareImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "画像を共有"))
}

/**
 * 外部の編集アプリで画像を開く
 */
fun openInExternalEditor(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "編集アプリを選択"))
}
