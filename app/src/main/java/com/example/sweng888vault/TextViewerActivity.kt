package com.example.sweng888vault // Your package

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.io.File


class TextViewerActivity : AppCompatActivity() {

    // Example using ViewBinding (add this to your build.gradle if not already there)
    // buildFeatures { viewBinding = true }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_CONTENT = "extra_file_content" // If you pass content directly
        const val EXTRA_FILE_NAME = "extra_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("TextViewerActivity", "onCreate: Activity started!")

        // --- OR Using findViewById ---
        setContentView(R.layout.activity_text_viewer) // Make sure this matches your XML file name
        val toolbar: Toolbar = findViewById(R.id.toolbar_text_viewer)
        val textContentTextView: TextView = findViewById(R.id.textContentTextView)
        val closeButton: Button = findViewById(R.id.closeButton)
        // --- End findViewById ---

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Optional: Adds back arrow to toolbar

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Text File"
        supportActionBar?.title = fileName

        val fileContent = intent.getStringExtra(EXTRA_FILE_CONTENT)

        Log.d("TextViewerActivity", "Received fileContent: $fileContent")
        Log.d("TextViewerActivity", "Received fileName: $fileName")


        // Option 1: Load content from file path passed via intent
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                // You'd use a similar readTextFileContent function here
                // as in your MainActivity, or pass the content directly.
                // For simplicity, let's assume you have a utility or read it here.
                try {
                    val content = file.readText(Charsets.UTF_8)
                    textContentTextView.text = content
                } catch (e: Exception) {
                    textContentTextView.text = "Error reading file: ${e.message}"
                }
            } else {
                textContentTextView.text = "File not found or unreadable."
            }
        } else {
            // Option 2: Get content directly passed via intent
            val fileContent = intent.getStringExtra(EXTRA_FILE_CONTENT)
            if (fileContent != null) {
                textContentTextView.text = fileContent
            } else {
                textContentTextView.text = "No content to display."
            }
        }


        // Set OnClickListener for the close button
        closeButton.setOnClickListener {
            finish() // This will close the current activity
        }
    }

    // Handle the Up button in the toolbar (if setDisplayHomeAsUpEnabled(true))
    override fun onSupportNavigateUp(): Boolean {
        finish() // Or onBackPressedDispatcher.onBackPressed()
        return true
    }
}