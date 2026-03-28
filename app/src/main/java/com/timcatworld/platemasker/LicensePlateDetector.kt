package com.timcatworld.platemasker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

class LicensePlateDetector(context: Context) {

    private val interpreter: Interpreter
    private val inputSize = 320
    private val inputBuffer: ByteBuffer
    private val pixels = IntArray(inputSize * inputSize)
    
    private val mutex = Mutex()

    init {
        val model = loadModelFile(context, "license_plate.tflite")
        
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        
        interpreter = Interpreter(model, options)
        
        inputBuffer = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    /**
     * 推論処理。共有バッファを使用するため、Mutexで排他制御を行う。
     */
    suspend fun detect(bitmap: Bitmap, scoreThreshold: Float = 0.3f): List<RectF> = mutex.withLock {
        if (bitmap.isRecycled) return@withLock emptyList()

        val scale = min(inputSize / bitmap.width.toFloat(), inputSize / bitmap.height.toFloat())
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val paddedBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        
        return try {
            val offsetX = (inputSize - newW) / 2
            val offsetY = (inputSize - newH) / 2
            val canvas = Canvas(paddedBitmap)
            canvas.drawBitmap(resizedBitmap, offsetX.toFloat(), offsetY.toFloat(), null)

            paddedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            inputBuffer.rewind()
            // RGB順に正規化して格納
            for (p in pixels) inputBuffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            for (p in pixels) inputBuffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            for (p in pixels) inputBuffer.putFloat((p and 0xFF) / 255.0f)

            val outputBuffer = Array(1) { Array(5) { FloatArray(2100) } }
            interpreter.run(inputBuffer, outputBuffer)

            val detections = mutableListOf<RectF>()
            val scaleX = bitmap.width / newW.toFloat()
            val scaleY = bitmap.height / newH.toFloat()

            for (i in 0 until 2100) {
                val score = outputBuffer[0][4][i]
                if (score < scoreThreshold) continue

                val cx = outputBuffer[0][0][i]
                val cy = outputBuffer[0][1][i]
                val w = outputBuffer[0][2][i]
                val h = outputBuffer[0][3][i]

                val left = ((cx - w / 2 - offsetX) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                val top = ((cy - h / 2 - offsetY) * scaleY).coerceIn(0f, bitmap.height.toFloat())
                val right = ((cx + w / 2 - offsetX) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                val bottom = ((cy + h / 2 - offsetY) * scaleY).coerceIn(0f, bitmap.height.toFloat())

                detections.add(RectF(left, top, right, bottom))
            }
            detections
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            if (resizedBitmap != bitmap) resizedBitmap.recycle()
            paddedBitmap.recycle()
        }
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        val channel = FileInputStream(fd.fileDescriptor).channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        interpreter.close()
    }
}
