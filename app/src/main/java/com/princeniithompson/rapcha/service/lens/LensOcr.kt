package com.princeniithompson.rapcha.service.lens

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Latin-script OCR. Other scripts are a known limit of the bundled model.
 * The bitmap is the crop around the lens; boxes are shifted back to screen space.
 */
object LensOcr {
    fun read(bitmap: Bitmap, originLeft: Int, originTop: Int): List<LensCandidate> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = try {
            Tasks.await(recognizer.process(image), 4, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<LensCandidate>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val value = line.text.trim()
                val box = line.boundingBox ?: continue
                if (value.isEmpty()) continue
                out.add(
                    LensCandidate(
                        text = value,
                        bounds = ScreenRect(
                            originLeft + box.left,
                            originTop + box.top,
                            originLeft + box.right,
                            originTop + box.bottom
                        ),
                        source = LensSource.OCR,
                        editable = false,
                        password = false,
                        sensitivePackage = false,
                        secureWindow = false,
                        ownOverlay = false
                    )
                )
            }
        }
        return out
    }
}
