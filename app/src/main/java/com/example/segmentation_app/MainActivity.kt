package com.example.segmentation_app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import com.google.mlkit.common.MlKit
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resumeWithException
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import android.graphics.Color


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    MainScreen()

                }
        }

    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current

    // State to hold the input and output images
    val outputImage: MutableState<Bitmap?> = remember { mutableStateOf<Bitmap?>(null) }
    val inputImage: MutableState<Bitmap?> = remember { mutableStateOf(null) }

    // State to hold the filtered images, including the background-removed image
    val filters: MutableState<List<Bitmap>> = remember { mutableStateOf(listOf()) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                inputImage.value = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            } else {
                Log.d("PhotoPicker", "No media selected")
            }
        })

    var loading: Boolean by remember { mutableStateOf(false) }
    var isOriginal: Boolean by remember { mutableStateOf(false) }

    // Apply segmentation and generate filters when input image changes
    LaunchedEffect(inputImage.value) {
        inputImage.value?.let { bitmap ->
            loading = true
            val output = ImageSegmentationHelper.getResult(bitmap)
            outputImage.value = output
            filters.value = generateFilteredImages(bitmap, output)
            loading = false
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
//                .background(Color.White)
                .fillMaxSize()
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxWidth()
            ) {
                Button(onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(text = "Open Gallery")
                }

                Button(onClick = {
                    outputImage.value?.let {
                        saveImageToExternalStorage(context, it)
                    }
                }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Save Image")
                    Text(text = "Save Image")
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                if (outputImage.value != null && inputImage.value != null) {
                    Image(
                        bitmap = if (!isOriginal) outputImage.value!!.asImageBitmap() else inputImage.value!!.asImageBitmap(),
                        contentDescription = "",
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                isOriginal = !isOriginal
                            }
                    )
                }

                if (loading) {
                    CircularProgressIndicator()
                }
            }

            // Horizontal preview gallery at the bottom
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters.value) { filter ->
                    Image(
                        bitmap = filter.asImageBitmap(),
                        contentDescription = "Filtered preview",
                        modifier = Modifier
                            .size(100.dp)
                            .clickable {
                                outputImage.value = filter // Change the main image to the clicked filter
                            }
                    )
                }
            }
        }
    }
}

// Generate some basic filtered images
// Generate some basic filtered images, including background removal
fun generateFilteredImages(original: Bitmap, segmented: Bitmap?): List<Bitmap> {
    val grayscaleBitmap = applyGrayscaleFilter(original)
    val sepiaBitmap = applySepiaFilter(original)
    val backgroundRemovedBitmap = removeBackground(original, segmented)

    // Return a list of filtered bitmaps including the background removed image
    return listOf(grayscaleBitmap, sepiaBitmap, backgroundRemovedBitmap, original)
}

// Example filter: Grayscale
fun applyGrayscaleFilter(original: Bitmap): Bitmap {
    val width = original.width
    val height = original.height
    val grayscaleBitmap = Bitmap.createBitmap(width, height, original.config)

    for (i in 0 until width) {
        for (j in 0 until height) {
            val pixel = original.getPixel(i, j)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val gray = (red + green + blue) / 3
            val newPixel = Color.rgb(gray, gray, gray)
            grayscaleBitmap.setPixel(i, j, newPixel)
        }
    }
    return grayscaleBitmap
}

// Example filter: Sepia
fun applySepiaFilter(original: Bitmap): Bitmap {
    val width = original.width
    val height = original.height
    val sepiaBitmap = Bitmap.createBitmap(width, height, original.config)

    for (i in 0 until width) {
        for (j in 0 until height) {
            val pixel = original.getPixel(i, j)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)

            val tr = (0.393 * red + 0.769 * green + 0.189 * blue).toInt().coerceIn(0, 255)
            val tg = (0.349 * red + 0.686 * green + 0.168 * blue).toInt().coerceIn(0, 255)
            val tb = (0.272 * red + 0.534 * green + 0.131 * blue).toInt().coerceIn(0, 255)

            val newPixel = Color.rgb(tr, tg, tb)
            sepiaBitmap.setPixel(i, j, newPixel)
        }
    }
    return sepiaBitmap
}

// New filter: Remove background using the segmented image
fun removeBackground(original: Bitmap, segmented: Bitmap?): Bitmap {
    if (segmented == null) return original // If no segmented image is available, return the original

    val width = original.width
    val height = original.height
    val backgroundRemovedBitmap = Bitmap.createBitmap(width, height, original.config)

    for (i in 0 until width) {
        for (j in 0 until height) {
            val pixel = segmented.getPixel(i, j)
            // Check if pixel is part of the foreground (not transparent)
            if (Color.alpha(pixel) > 128) {
                backgroundRemovedBitmap.setPixel(i, j, original.getPixel(i, j))
            } else {
                // Set transparent background
                backgroundRemovedBitmap.setPixel(i, j, Color.TRANSPARENT)
            }
        }
    }
    return backgroundRemovedBitmap
}

fun saveImageToExternalStorage(context: Context, bitmap: Bitmap) {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DISPLAY_NAME, "segmented_image_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SegmentedImages")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val resolver = context.contentResolver

    val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        val outputStream: OutputStream? = resolver.openOutputStream(it)
        outputStream?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Toast.makeText(context, "Image saved successfully!", Toast.LENGTH_SHORT).show()
        }
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    } ?: run {
        Toast.makeText(context, "Error saving image!", Toast.LENGTH_SHORT).show()
    }
}

object ImageSegmentationHelper {

    private val options = SubjectSegmenterOptions.Builder()
        .enableForegroundConfidenceMask()
        .enableForegroundBitmap()
        .build()

    private val segmenter = SubjectSegmentation.getClient(options)

    suspend fun getResult(image: Bitmap) = suspendCoroutine {
        // Convert the input Bitmap image to InputImage format
        val inputImage = InputImage.fromBitmap(image, 0)

        // Process input image using SubjectSegmenter
        segmenter.process(inputImage)
            .addOnSuccessListener { result ->
                // Resume coroutine with the fg Bitmap result on success
                it.resume(result.foregroundBitmap)
            }
            .addOnFailureListener {e ->
                // exception
                it.resumeWithException(e)
            }
    }
}