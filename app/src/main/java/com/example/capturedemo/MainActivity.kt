package com.example.capturedemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.capturedemo.databinding.ActivityMainBinding
import com.example.capturedemo.CapturedImageActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val cameraOpenCloseLock = Semaphore(1)
    private var previewSize: Size? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize background thread and handler
        startBackgroundThread()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        binding.textureView.surfaceTextureListener = textureListener

        binding.captureButton.setOnClickListener {
            // Start delay, capture image, and navigate to the next screen
            it.isEnabled = false
            Handler().postDelayed({
                captureImageWithImageReader()
            }, 3000)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.get(0)

            imageReader = ImageReader.newInstance(previewSize!!.width, previewSize!!.height, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
                return
            }
            cameraManager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = binding.textureView.surfaceTexture
            texture!!.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            val surface = Surface(texture)
            val surfaces = mutableListOf(surface, imageReader.surface)

            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            // 设置自动对焦模式为连续对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            // 设置其他需要的参数，例如曝光、白平衡等
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    if (cameraDevice == null) return

                    this@MainActivity.cameraCaptureSession = cameraCaptureSession
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Configuration changed", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun captureImageWithImageReader() {
        try {
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader.surface)
            cameraCaptureSession.capture(captureRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        Log.d("Main", "get available callback")
        val image = reader.acquireLatestImage()
        val imagePath = saveImageToCache(image)

        if (imagePath != null) {
            val intent = Intent(this, CapturedImageActivity::class.java)
            intent.putExtra("image_path", imagePath)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToCache(image: Image): String? {
        // 获取图像的缓冲区
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // 创建文件名并获取缓存路径
        val fileName = "captured_image_${System.currentTimeMillis()}.jpg"
        val file = File(cacheDir, fileName)

        var outputStream: FileOutputStream? = null
        try {
            outputStream = FileOutputStream(file)
            outputStream.write(bytes)
            outputStream.flush()
            println("Image saved to cache: ${file.absolutePath}")
            return file.absolutePath // 返回文件的绝对路径
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return null // 如果保存失败，返回 null
    }

    override fun onPause() {
        super.onPause()
//        closeCamera()
//        stopBackgroundThread()
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession.close()
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
        }
    }
}