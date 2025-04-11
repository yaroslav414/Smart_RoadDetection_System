package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.surendramaran.yolov8tflite.Constants.IP_ADDRESS
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), Detector.DetectorListener, SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private val PERMISSIONS_FINE_LOCATION: Int = 99
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallBack: LocationCallback

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var accelerometerActive: Boolean = false
    private var gyroscopeActive: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    private var currentDetectionId: Int = 0

    private var detectionCounter = 0
    private var isTimerActive = false
    private val detectionLimit = 3

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize detection counter from SharedPreferences
        currentDetectionId = getDetectionCounter()

        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 500
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            maxWaitTime = 2000
        }

        locationCallBack = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    if (location.accuracy <= 20) {
                        Log.d("LocationUpdate", "Location: ${location.latitude}, ${location.longitude}")
                        sendLocationToServer(location, currentDetectionId)
                    }
                }
            }
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        updateGPS()

        binding.btnOpenMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation
        imageCapture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .build()

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onStop() {
        super.onStop()
        stopCamera()

    }
    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        detector.clear()
        cameraExecutor.shutdown()
        sensorManager.unregisterListener(this)
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        // Additional clean-up if necessary
    }
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).toTypedArray()
        private const val DETECTION_COUNTER_PREF = "detection_counter"
        private const val DETECTION_COUNTER_KEY = "counter"
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallBack, null)
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateGPS() {
        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(this@MainActivity)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this) { location ->
                if (location != null) {
                    Log.d(
                        "InitialLocation",
                        "Location: ${location.latitude}, ${location.longitude}"
                    )

                    sendLocationToServer(location, currentDetectionId)
                }
            }
            startLocationUpdates()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSIONS_FINE_LOCATION
            )
        }
    }

    private fun getUniqueDetectionId(): Int {
        currentDetectionId += 1
        saveDetectionCounter(currentDetectionId)
        return currentDetectionId

    }

    private fun saveDetectionCounter(counter: Int) {
        val sharedPreferences = getSharedPreferences(DETECTION_COUNTER_PREF, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putInt(DETECTION_COUNTER_KEY, counter)
            apply()
        }
    }

    private fun getDetectionCounter(): Int {
        val sharedPreferences = getSharedPreferences(DETECTION_COUNTER_PREF, Context.MODE_PRIVATE)
        return sharedPreferences.getInt(DETECTION_COUNTER_KEY, 0)
    }

    private fun resetDetectionCounter() {
        currentDetectionId = 0
        saveDetectionCounter(currentDetectionId)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun sendLocationToServer(location: Location, detectionId: Int) {
        if (isNetworkAvailable()) {
            Thread {
                try {
                    val url = URL("$IP_ADDRESS/upload")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Detection-ID", detectionId.toString())
                    conn.doOutput = true

                    val json = "{\"detection_id\": \"$detectionId\", \"latitude\": ${location.latitude}, \"longitude\": ${location.longitude}}"

                    val os = conn.outputStream
                    os.write(json.toByteArray())
                    os.flush()

                    val responseCode = conn.responseCode
                    Log.d("ServerResponse", "Response Code: $responseCode")

                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("ServerError", "Error sending location to server", e)
                }
            }.start()
        } else {
            Log.e("NetworkError", "No network connection available")
        }
    }


    private fun takePhoto(detectionId: Int) {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pothole-image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                    output.savedUri?.let { uri -> uploadPhotoToServer(uri, detectionId) }
                }
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun uploadPhotoToServer(photoUri: Uri, detectionId: Int) {
        if (isNetworkAvailable()) {
            Thread {
                try {
                    val url = URL("$IP_ADDRESS/upload")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "image/jpeg")
                    conn.setRequestProperty("Detection-ID", detectionId.toString())
                    conn.doOutput = true

                    contentResolver.openInputStream(photoUri)?.use { inputStream ->
                        val outputStream: OutputStream = conn.outputStream
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    }

                    val responseCode = conn.responseCode
                    Log.d("ServerResponse", "Response Code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        deletePhotoFromStorage(photoUri)
                    }

                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("ServerError", "Error uploading photo to server", e)
                }
            }.start()
        } else {
            Log.e("NetworkError", "No network connection available")
        }
    }
    private fun deletePhotoFromStorage(photoUri: Uri) {
        try {
            val resolver = contentResolver
            resolver.delete(photoUri, null, null)
            Log.d(TAG, "Photo deleted from storage: $photoUri")

            // Delete from MediaStore to remove from gallery
            val photoPath = arrayOf(photoUri.path)
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.DATA + "=?", photoPath)
            Log.d(TAG, "Photo removed from gallery: $photoUri")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting photo from storage and gallery: $photoUri", e)
        }
    }
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (isTimerActive) {
            return
        }

        detectionCounter++
        if (detectionCounter > detectionLimit) {
            isTimerActive = true
            detectionCounter = 0

            handler.postDelayed({
                isTimerActive = false
            }, 1000)
            return
        }

        currentDetectionId = getUniqueDetectionId()

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
                updateGPS()
            }
            takePhoto(currentDetectionId)
        }

        if (!accelerometerActive) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            accelerometerActive = true

            handler.postDelayed({
                sensorManager.unregisterListener(this, accelerometer)
                accelerometerActive = false
            }, 5000)
        }

        if (!gyroscopeActive) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL)
            gyroscopeActive = true

            handler.postDelayed({
                sensorManager.unregisterListener(this, gyroscope)
                gyroscopeActive = false
            }, 5000)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                Log.d("Accelerometer", "x: $x, y: $y, z: $z")
                sendAccelerometerDataToServer(x, y, z, currentDetectionId)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                Log.d("Gyroscope", "X: $x, Y: $y, Z: $z")
                sendGyroscopeDataToServer(x, y, z, currentDetectionId)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Implement if needed
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun sendAccelerometerDataToServer(x: Float, y: Float, z: Float, detectionId: Int) {
        if (isNetworkAvailable()) {
            Thread {
                try {
                    val url = URL("$IP_ADDRESS/upload")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Detection-ID", detectionId.toString())
                    conn.doOutput = true

                    val json = "{\"type\": \"accelerometer\", \"detection_id\": \"$detectionId\", \"x\": $x, \"y\": $y, \"z\": $z}"

                    val os = conn.outputStream
                    os.write(json.toByteArray())
                    os.flush()

                    val responseCode = conn.responseCode
                    Log.d("ServerResponse", "Response Code: $responseCode")

                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("ServerError", "Error sending accelerometer data to server", e)
                }
            }.start()
        } else {
            Log.e("NetworkError", "No network connection available")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun sendGyroscopeDataToServer(x: Float, y: Float, z: Float, detectionId: Int) {
        if (isNetworkAvailable()) {
            Thread {
                try {
                    val url = URL("$IP_ADDRESS/upload")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Detection-ID", detectionId.toString())
                    conn.doOutput = true

                    val json = "{\"type\": \"gyroscope\", \"detection_id\": \"$detectionId\", \"x\": $x, \"y\": $y, \"z\": $z}"

                    val os = conn.outputStream
                    os.write(json.toByteArray())
                    os.flush()

                    val responseCode = conn.responseCode
                    Log.d("ServerResponse", "Response Code: $responseCode")

                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("ServerError", "Error sending gyroscope data to server", e)
                }
            }.start()
        } else {
            Log.e("NetworkError", "No network connection available")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
