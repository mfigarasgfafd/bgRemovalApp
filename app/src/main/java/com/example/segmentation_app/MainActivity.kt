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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.Color
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


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    ImageSegmenterScreen()


                }
        }

    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSegmenterScreen() {
    val context = LocalContext.current

    // State to hold the output bitmap after segmentation
    val outputImage: MutableState<Bitmap?> = remember { mutableStateOf<Bitmap?>(null) }

    // State to hold the input bitmap before segmentation
    val inputImage: MutableState<Bitmap?> = remember { mutableStateOf(null) }

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

    LaunchedEffect(key1 = inputImage.value) {
        inputImage.value?.let { bitmap ->
            loading = true
            val output = ImageSegmentationHelper.getResult(bitmap)
            outputImage.value = output
            loading = false
        }
    }

    Scaffold { paddingValues ->
        Box(modifier = Modifier.background(Color.White)) {
            Row(
                horizontalArrangement = Arrangement.End,
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
                    .fillMaxSize()
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
        }
    }
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