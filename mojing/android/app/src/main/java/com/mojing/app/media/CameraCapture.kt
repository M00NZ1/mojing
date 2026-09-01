package com.mojing.app.media

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCapture(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    fun init() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun takePhoto(onSuccess: (Uri) -> Unit, onError: (String) -> Unit) {
        val photoFile = File(
            context.externalMediaDirs.firstOrNull() ?: context.filesDir,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                    onSuccess(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "拍照失败")
                }
            }
        )
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.CAMERA
        )
    }
}

@Composable
fun CameraCaptureView(
    onPhotoTaken: (Uri) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val previewView = this

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.getSurfaceProvider())
                        }
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                                preview,
                                imageCapture
                            )
                        } catch (e: Exception) {
                            onError("相机启动失败: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(onClick = { onDismiss() }) {
                Icon(Icons.Default.PhotoCamera, "关闭")
            }

            FloatingActionButton(
                onClick = {
                    onPhotoTaken(Uri.parse("file:///sdcard/photo.jpg"))
                },
                modifier = Modifier.size(72.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Camera, "拍照")
            }

            FloatingActionButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            }) {
                Icon(Icons.Default.FlipCameraAndroid, "翻转")
            }
        }
    }
}
