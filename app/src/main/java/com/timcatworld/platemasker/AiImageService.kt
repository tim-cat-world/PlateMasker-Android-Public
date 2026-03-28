package com.timcatworld.platemasker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiImageService {
    suspend fun generateImage(prompt: String, apiKey: String): Bitmap? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        try {
            val url = URL("https://api.openai.com/v1/images/generations")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true

            val jsonParam = JSONObject().apply {
                put("model", "dall-e-3")
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
            }

            conn.outputStream.use { it.write(jsonParam.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val imageUrl = jsonResponse.getJSONArray("data").getJSONObject(0).getString("url")
                
                // 画像をダウンロード
                val imgUrl = URL(imageUrl)
                val imgConn = imgUrl.openConnection() as HttpURLConnection
                imgConn.doInput = true
                imgConn.connect()
                val inputStream = imgConn.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
