package com.surendramaran.yolov8tflite

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.surendramaran.yolov8tflite.Constants.IP_ADDRESS
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.absoluteValue

@Suppress("DEPRECATION")
class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private val markers = mutableListOf<Marker>()
    private val proximityThreshold = 20 // meters
    private lateinit var locationReceiver: BroadcastReceiver
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }

        locationReceiver = LocationReceiver()
        val intentFilter = IntentFilter("com.surendramaran.yolov8tflite.LOCATION_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(locationReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            setupLocationServices()
        }

        startService(Intent(this, LocationService::class.java))
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        enableMyLocation()
        fetchCoordinates()  // Fetch new coordinates and add new markers
    }

    private fun fetchCoordinates() {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL(IP_ADDRESS)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val gpsLog = jsonResponse.getJSONArray("gps_log")
                    val accelerometerLog = jsonResponse.getJSONArray("accelerometer_log")
                    val gyroscopeLog = jsonResponse.getJSONArray("gyroscope_log")
                    runOnUiThread {
                        updateMarkers(gpsLog, accelerometerLog, gyroscopeLog)
                    }
                } else {
                    Log.e("MapActivity", "Error fetching coordinates")
                }
            } catch (e: Exception) {
                Log.e("MapActivity", "Exception: ${e.message}")
            }
        }
    }

    private fun updateMarkers(gpsLog: JSONArray, accelerometerLog: JSONArray, gyroscopeLog: JSONArray) {
        val newMarkers = mutableListOf<Marker>()
        // Aggregate sensor values by detection ID
        val aggregatedSensorData = mutableMapOf<String, MutableList<JSONObject>>()

        for (i in 0 until accelerometerLog.length()) {
            val accObject = accelerometerLog.optJSONObject(i) ?: continue
            val detectionId = accObject.optString("detection_id", "")
            if (detectionId.isNotEmpty()) {
                aggregatedSensorData.getOrPut(detectionId) { mutableListOf() }.add(accObject)
            }
        }

        for (i in 0 until gyroscopeLog.length()) {
            val gyroObject = gyroscopeLog.optJSONObject(i) ?: continue
            val detectionId = gyroObject.optString("detection_id", "")
            if (detectionId.isNotEmpty()) {
                aggregatedSensorData.getOrPut(detectionId) { mutableListOf() }.add(gyroObject)
            }
        }

        // Process GPS logs and match with sensor data
        for (i in 0 until gpsLog.length()) {
            val gpsObject = gpsLog.optJSONObject(i) ?: continue
            val lat = gpsObject.optDouble("latitude", Double.NaN)
            val lng = gpsObject.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            val detectionId = gpsObject.optString("detection_id", "")
            if (detectionId.isEmpty()) continue
            val location = LatLng(lat, lng)

            val sensorDataList = aggregatedSensorData[detectionId] ?: continue

            val maxAccel = sensorDataList
                .filter { it.optString("type") == "accelerometer" }
                .flatMap {
                    listOf(
                        it.optDouble("x", 0.0),
                        it.optDouble("y", 0.0) - 8,
                        it.optDouble("z", 0.0) - 3.5
                    )
                }.maxOfOrNull { it.absoluteValue } ?: 0.0

            val maxGyro = sensorDataList
                .filter { it.optString("type") == "gyroscope" }
                .flatMap {
                    listOf(
                        it.optDouble("x", 0.0),
                        it.optDouble("y", 0.0),
                        it.optDouble("z", 0.0)
                    )
                }.maxOfOrNull { it.absoluteValue } ?: 0.0

            val markerTitle = classifyPothole(maxAccel.toFloat(), maxGyro.toFloat())
            Log.d("MapActivity", "Marker $i: $markerTitle (maxAccel=$maxAccel, maxGyro=$maxGyro)")

            val marker = map.addMarker(MarkerOptions()
                .position(location)
                .title(markerTitle)
            )
            marker?.let { newMarkers.add(it) }

            if (i == 0) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 10f))
            }
        }

        // Update the markers list
        markers.clear()
        markers.addAll(newMarkers)
    }

    private fun classifyPothole(maxAccel: Float, maxGyro: Float): String {
        val maxReading = maxOf(maxAccel, maxGyro)

        return when {
            maxReading > 25 -> "Severe Pothole : $maxAccel , $maxGyro"
            maxReading > 13 && maxReading <= 25 -> "Moderate Pothole : $maxAccel , $maxGyro"
            else -> "Minor Pothole : $maxAccel , $maxGyro"
        }
    }

    private fun checkProximityToMarkers(location: Location) {
        for (marker in markers) {
            val markerLocation = Location("").apply {
                latitude = marker.position.latitude
                longitude = marker.position.longitude
            }

            val distance = location.distanceTo(markerLocation)
            if (distance < proximityThreshold) {
                Toast.makeText(this, "Warning: You are close to a marker!", Toast.LENGTH_LONG).show()
                break
            }
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupLocationServices() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize location request with higher frequency updates
        locationRequest = LocationRequest.create().apply {
            interval = 1000 // Update interval in milliseconds (1 second)
            fastestInterval = 500 // Fastest update interval in milliseconds (0.5 seconds)
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                val locations = locationResult.locations
                if (locations.isNotEmpty()) {
                    val lastLocation = locations.last()
                    if (lastLocation.accuracy <= 20) {
                        Log.d("LocationUpdate", "Location: ${lastLocation.latitude}, ${lastLocation.longitude}")
                        checkProximityToMarkers(lastLocation)
                    }
                }
            }
        }

        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    private fun stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        unregisterReceiver(locationReceiver)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                enableMyLocation()
                setupLocationServices()
                startService(Intent(this, LocationService::class.java))
            } else {
                Toast.makeText(this, "Permission denied to access location", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
