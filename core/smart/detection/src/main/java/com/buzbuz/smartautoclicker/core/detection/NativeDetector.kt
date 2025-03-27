/*
 * Copyright (C) 2024 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.annotation.Keep
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


/**
 * Native implementation of the image detector.
 * It uses OpenCv template matching algorithms to achieve condition detection on the screen.
 *
 * Debug flavour of the library is build against build artifacts of OpenCv in the debug folder.
 * Release flavour of the library is build against the sources of the OpenCv project, downloaded from github.
 */
class NativeDetector private constructor() : ImageDetector {

    companion object {
        fun newInstance(): NativeDetector? = try {
            System.loadLibrary("smartautoclicker")
            NativeDetector()
        } catch (ex: UnsatisfiedLinkError) {
            null
        }
    }

    /** The results of the detection. Modified by native code. */
    @Keep
    private val detectionResult = DetectionResult()
    /** Native pointer of the detector object. */
    @Keep
    private var nativePtr: Long = -1

    private val detectionQualityMin: Double = DETECTION_QUALITY_MIN.toDouble()

    private var isClosed: Boolean = false

    override fun init() {
        val downloadDir = getDefaultDownloadPath()

        if (!isTessDataExists(downloadDir, "eng")) {
            downloadTessData(downloadDir, "eng")
        }
        if (!isTessDataExists(downloadDir, "chi_sim")) {
            downloadTessData(downloadDir, "chi_sim")
        }

        val tessPath = File(downloadDir, "tesseract/tessdata").absolutePath
        val language = "eng+chi_sim"

        nativePtr = newDetector(detectionResult, tessPath, language)
    }

    override fun close() {
        if (isClosed) return

        isClosed = true
        deleteDetector()
    }

    override fun setScreenMetrics(metricsKey: String, screenBitmap: Bitmap, detectionQuality: Double) {
        if (isClosed) return

        updateScreenMetrics(
            metricsKey,
            screenBitmap,
            detectionQuality.coerceIn(detectionQualityMin, 10000.0),
        )
    }

    override fun setupDetection(screenBitmap: Bitmap) {
        if (isClosed) return

        setScreenImage(screenBitmap)
    }

    override fun detectCondition(conditionBitmap: Bitmap, threshold: Int): DetectionResult {
        if (isClosed) return detectionResult.copy()

        detect(conditionBitmap, threshold)
        return detectionResult.copy()
    }

    override fun detectCondition(conditionBitmap: Bitmap, position: Rect, threshold: Int): DetectionResult {
        if (isClosed) return detectionResult.copy()

        detectAt(conditionBitmap, position.left, position.top, position.width(), position.height(), threshold, detectionResult)
        return detectionResult.copy()
    }

    override fun detectCondition(conditionBitmap: Bitmap, identifying: String): DetectionResult {
        if (isClosed) return detectionResult.copy()

        detectOCR(conditionBitmap, identifying)
        return detectionResult.copy()
    }

    override fun detectCondition(conditionBitmap: Bitmap, position: Rect, identifying: String): DetectionResult {
        if (isClosed) return detectionResult.copy()

        detectOCRAt(conditionBitmap, position.left, position.top, position.width(), position.height(), identifying, detectionResult)
        return detectionResult.copy()
    }

    /* tesseract provate oprate funtion */
    private fun getDefaultDownloadPath(): String {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return downloadDir.absolutePath;
    }

    private fun isTessDataExists(parentDir: String, language: String): Boolean {
        val tessPath = File(parentDir, "tesseract/tessdata")
        val trainingDataFile = File(tessPath, "$language.traineddata")
        return trainingDataFile.exists()
    }

    private fun downloadTessData(parentDir: String, language: String) {
        val tessPath = File(parentDir, "tesseract/tessdata")
        if (!tessPath.exists()) {
            tessPath.mkdirs()
        }

        val url = URL("https://github.com/tesseract-ocr/tessdata/raw/main/$language.traineddata")
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        connection.connect()

        val inputStream: InputStream = connection.inputStream
        val fileOutputStream = FileOutputStream(File(tessPath, "$language.traineddata"))

        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            fileOutputStream.write(buffer, 0, len)
        }

        fileOutputStream.close()
        inputStream.close()
        connection.disconnect()
    }

    /**
     * Creates the detector. Must be called before any other methods.
     * Call [close] to release resources once the detection process is finished.
     *
     * @return the pointer of the native detector object.
     */
    private external fun newDetector(result: DetectionResult, tessPath: String, language: String): Long

    /**
     * Deletes the native detector.
     * Once called, this object can't be used anymore.
     */
    private external fun deleteDetector()

    /**
     * Native method for screen metrics setup.
     *
     * @param screenBitmap the content of the screen as a bitmap.
     * @param detectionQuality the quality of the detection. The higher the preciser, the lower the faster. Must be
     *                         contained in [DETECTION_QUALITY_MIN] and [DETECTION_QUALITY_MAX].
     */
    private external fun updateScreenMetrics(metricsKey: String, screenBitmap: Bitmap, detectionQuality: Double)

    /**
     * Native method for detection setup.
     *
     * @param screenBitmap the content of the screen as a bitmap.
     */
    private external fun setScreenImage(screenBitmap: Bitmap)

    /**
     * Native method for detecting if the bitmap is in the whole current screen bitmap.
     *
     * @param conditionBitmap the condition to detect in the screen.
     * @param threshold the allowed error threshold allowed for the condition.
     */
    private external fun detect(conditionBitmap: Bitmap, threshold: Int)

    /**
     * Native method for detecting if the bitmap is at a specific position in the current screen bitmap.
     *
     * @param conditionBitmap the condition to detect in the screen.
     * @param x the horizontal position of the condition.
     * @param y the vertical position of the condition.
     * @param width the width of the condition.
     * @param height the height of the condition.
     * @param threshold the allowed error threshold allowed for the condition.
     */
    private external fun detectAt(
        conditionBitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        threshold: Int,
        result: DetectionResult
    )

    /**
     * Native method for detecting if the bitmap is in the whole current screen bitmap.
     *
     * @param conditionBitmap the condition to detect in the screen.
     * @param identifying the recognised information to consider the detection position.
     */
    private external fun detectOCR(conditionBitmap: Bitmap, identifying: String)

    /**
     * Native method for detecting if the bitmap is at a specific position in the current screen bitmap.
     *
     * @param conditionBitmap the condition to detect in the screen.
     * @param x the horizontal position of the condition.
     * @param y the vertical position of the condition.
     * @param width the width of the condition.
     * @param height the height of the condition.
     * @param identifying the recognised information to consider the detection position.
     */
    private external fun detectOCRAt(
        conditionBitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        identifying: String,
        result: DetectionResult
    )
}