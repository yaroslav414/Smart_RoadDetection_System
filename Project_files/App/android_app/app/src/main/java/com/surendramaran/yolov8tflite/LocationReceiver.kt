package com.surendramaran.yolov8tflite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log

class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val latitude = intent?.getDoubleExtra("latitude", 0.0) ?: 0.0
        val longitude = intent?.getDoubleExtra("longitude", 0.0) ?: 0.0
        val location = Location("").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        Log.d("LocationReceiver", "Received location update: $latitude, $longitude")
        // Add further processing if needed
    }
}
