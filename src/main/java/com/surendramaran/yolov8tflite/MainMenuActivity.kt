package com.surendramaran.yolov8tflite

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.Constants.WEBSITE_ADDRESS

class MainMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        // Set up the Spinner
        val menuSpinner: Spinner = findViewById(R.id.menu_spinner)
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.spinner_items,
            R.layout.spinner_item
        )
        adapter.setDropDownViewResource(R.layout.spinner_item)
        menuSpinner.adapter = adapter

        menuSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View, position: Int, id: Long) {
                when (position) {
                    1 -> {
                        val homeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_ADDRESS))
                        startActivity(homeIntent)
                    }
                    2 -> {
                        val contactIntent = Intent(Intent.ACTION_VIEW, Uri.parse("$WEBSITE_ADDRESS/contact"))
                        startActivity(contactIntent)
                    }
                    3 -> {
                        val aboutIntent = Intent(Intent.ACTION_VIEW, Uri.parse("$WEBSITE_ADDRESS/team"))
                        startActivity(aboutIntent)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }
        }

        // Set up the buttons
        findViewById<Button>(R.id.btnOpenCamera).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenMap).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
    }
}
