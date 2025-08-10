package com.example.sweng888vault

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.sweng888vault.util.FileOpenerUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sweng888vault.databinding.ActivityMainBinding
import com.example.sweng888vault.util.MimeTypeUtil
import com.example.sweng888vault.util.FileStorageManager
import com.example.sweng888vault.util.MediaManager
import com.example.sweng888vault.util.TextToSpeechHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import nl.siegmann.epublib.epub.EpubReader
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    // View Binding variable
    private lateinit var binding: ActivityMainBinding

    private lateinit var fileAdapter: FileAdapter
    private var currentRelativePath: String = ""
    private lateinit var ttsHelper: TextToSpeechHelper

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    val fileName = getFileNameFromUri(uri)
                    if (fileName != null) {
                        FileStorageManager.saveFile(this, uri, fileName, currentRelativePath)?.let {
                            Toast.makeText(this, "File '$fileName' saved", Toast.LENGTH_SHORT).show()
                            loadFilesAndFolders()
                        } ?: run {
                            Toast.makeText(this, "Failed to save file '$fileName'", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "Could not determine file name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflate the layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        loadFilesAndFolders() // Initial load for the root directory
        updateActionBar() // Initial ActionBar setup

        binding.buttonCreateFolder.setOnClickListener {
            showCreateFolderDialog()
        }

        //Shows popup menu of adding file from phone or scanning documents
        binding.buttonAddFile.setOnClickListener { view ->
            showPopupMenu(view)
        }

        // Handle "Up" navigation more broadly with OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentRelativePath.isNotEmpty()) {
                    navigateUpInFileSystem()
                } else {
                    // If already at root, allow default back press behavior (e.g., exit activity)
                    isEnabled = false // Disable this callback
                    onBackPressedDispatcher.onBackPressed() // Trigger default behavior
                    isEnabled = true // Re-enable for next time (if activity is not finished)
                }
            }
        })
        ttsHelper = TextToSpeechHelper(this)
    }

    //Handles the PopupMenu when "Add File" is clicked
    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(this@MainActivity, view)
        popupMenu.menuInflater.inflate(R.menu.popup_menu, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.addFromPhone -> {
                    openFilePicker()
                    true
                }
                R.id.scanDocument -> {
                    //PLACEHOLDER
                    Toast.makeText(this, "Scan Document selected", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onItemClick = { file ->
                if (file.isDirectory) {
                    currentRelativePath = if (currentRelativePath.isEmpty()) {
                        file.name
                    } else {
                        "$currentRelativePath${File.separator}${file.name}"
                    }
                    loadFilesAndFolders()
                    updateActionBar()
                } else {
                    FileOpenerUtil.openFileWithExternalApp(this@MainActivity, file)
                }
            },
            onItemDelete = { file ->
                showDeleteConfirmationDialog(file)
            },
            onTextToSpeech = { file ->
                when (file.extension.lowercase()) {
                    "jpg", "jpeg", "png" -> {
                        recognizeTextFromImage(file)
                    }
                    "pdf" -> {
                        readTextFromPdf(file)
                    }
                    "txt" -> {
                        readTextFromFile(file)
                    }
                    "doc", "docx" -> {
                        readTextFromWord(file)
                    }
                    "epub" -> {
                        readTextFromEpub(file)
                    }
                    else -> {
                        Toast.makeText(this, "Unreadable File", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onMediaPlayer = { file ->
                showAudioPlayer(file)
            }
        )
        binding.recyclerViewFiles.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
            // Optional: Add item decoration for dividers
            // addItemDecoration(DividerItemDecoration(this@MainActivity, LinearLayoutManager.VERTICAL))
        }
    }

    /**
     * Recognize Text from Images
     */
    private fun recognizeTextFromImage(file: File) {
        val imageBitmap = BitmapFactory.decodeFile(file.absolutePath)
        val image = InputImage.fromBitmap(imageBitmap, 0)

        val recognizer = TextRecognition.getClient(DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedText = visionText.text
                if (detectedText.isNotBlank()) {
                    showTextDialogAndSpeak(detectedText, file.name)
                } else {
                    Toast.makeText(this, "No text found in image", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to read text: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun readTextFromEpub(file: File) {
        try {
            val book = EpubReader().readEpub(FileInputStream(file))
            val content = StringBuilder()

            for (resource in book.resources.all) {
                val href = resource.href.lowercase()
                if (href.endsWith(".html") || href.endsWith(".xhtml") || href.endsWith(".htm")) {
                    val html = resource.reader.readText()
                    val plainText = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                    content.append(plainText).append("\n\n")
                }
            }

            val finalText = content.toString().trim()

            if (finalText.isNotBlank()) {
                showTextDialogAndSpeak(finalText, file.name)
                Toast.makeText(this, "EPUB text extracted successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No readable text found in EPUB", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read EPUB file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Recognize Text from Text Files
     */
    private fun readTextFromFile(file: File) {
        try {
            val detectedText = file.readText(Charsets.UTF_8)
            if (detectedText.isNotBlank()) {
                showTextDialogAndSpeak(detectedText, file.name)
            } else {
                Toast.makeText(this, "Text file is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read text file: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
    }
    private fun readTextFromWord(file: File) {
        try {
            val text = when {
                file.extension.equals("docx", ignoreCase = true) -> {
                    FileInputStream(file).use { fis ->
                        val docx = XWPFDocument(fis)
                        docx.paragraphs.joinToString("\n") { it.text }
                    }
                }

                file.extension.equals("doc", ignoreCase = true) -> {
                    FileInputStream(file).use { fis ->
                        val doc = HWPFDocument(fis)
                        WordExtractor(doc).text
                    }
                }

                else -> {
                    Toast.makeText(this, "Unsupported file format", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            if (text.isNotBlank()) {
                showTextDialogAndSpeak(text, file.name)
                Toast.makeText(this, "Word document text extracted successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No text found in Word document", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read Word file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    /**
     * Read from PDF
     */
    private fun readTextFromPdf(file: File) {
        try {
            PDDocument.load(file).use { document ->
                val pdfStripper = PDFTextStripper()
                val text = pdfStripper.getText(document).trim()

                if (text.isNotBlank()) {
                    showTextDialogAndSpeak(text, file.name)
                    Toast.makeText(this, "PDF text extracted successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No text found in PDF (may contain only images)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read PDF file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioPlayer(file: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_audio_player, null)
        val playButton = dialogView.findViewById<Button>(R.id.buttonPlay)
        val pauseButton = dialogView.findViewById<Button>(R.id.buttonPause)
        val closeButton = dialogView.findViewById<Button>(R.id.buttonClose)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Audio Player")
            .setView(dialogView)
            .setCancelable(false) // User can't close the player if they click outside
            .create()

        playButton.setOnClickListener {
            MediaManager.playAudio(this, file)
            Log.i("MainActivity", "Playing audio")
        }

        pauseButton.setOnClickListener {
            MediaManager.pauseAudio()
        }

        closeButton.setOnClickListener {
            MediaManager.stopAudio()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showTextDialogAndSpeak(text: String, fileName: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_text_display, null)
        val textView = dialogView.findViewById<TextView>(R.id.dialogTextView)
        val readTextButton = dialogView.findViewById<Button>(R.id.buttonReadText)
        val saveAudioButton = dialogView.findViewById<Button>(R.id.buttonSaveAudio)
        val closeButton = dialogView.findViewById<Button>(R.id.buttonClose)
        textView.text = text

        val dialog = AlertDialog.Builder(this)
            .setTitle("Recognized Text")
            .setCancelable(false) // User can't close the player if they click outside
            .setView(dialogView)
            .create()

        readTextButton.setOnClickListener {
            ttsHelper.speak(text)
        }

        //TODO: Need to be able to save audio while also
        saveAudioButton.setOnClickListener {
            val folderName = "Saved Audios"
            val folderExists = FileStorageManager.listItems(this, currentRelativePath)
                .any { it.isDirectory && it.name.equals(folderName, ignoreCase = true) }

            if (!folderExists) {
                val created = FileStorageManager.createFolder(this, folderName, currentRelativePath)
                if (!created) {
                    Log.e("MainActivity", "Could not create Saved Audios Folder")
                    return@setOnClickListener
                }
                loadFilesAndFolders()
            }

            val savedAudiosDir = File(FileStorageManager.getRootContentDirectory(this),
                if (currentRelativePath.isBlank()) folderName else "$currentRelativePath/$folderName"
            )

            ttsHelper.synthesizeToFile(text, fileName) { files ->
                runOnUiThread {
                    if (files != null && files.isNotEmpty()) {
                        Toast.makeText(this, "Audio saved: ${files.size} files", Toast.LENGTH_LONG).show()
                        loadFilesAndFolders()
                    } else {
                        Toast.makeText(this, "Failed to save audio", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        closeButton.setOnClickListener {
            ttsHelper.stop()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun loadFilesAndFolders() {
        val items = FileStorageManager.listItems(this, currentRelativePath)
        fileAdapter.submitList(items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) // Sort folders first, then by name
    }

    private fun updateActionBar() {
        if (currentRelativePath.isEmpty()) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            // It's good practice to use string resources for titles
            supportActionBar?.title = getString(R.string.app_name) // Or a specific title like "My Vault"
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            val currentFolderName = currentRelativePath.substringAfterLast(File.separatorChar, currentRelativePath)
            supportActionBar?.title = currentFolderName
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentRelativePath.isNotEmpty()) {
            navigateUpInFileSystem()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun navigateUpInFileSystem() {
        val lastSeparator = currentRelativePath.lastIndexOf(File.separatorChar)
        currentRelativePath = if (lastSeparator > -1) {
            currentRelativePath.substring(0, lastSeparator)
        } else {
            "" // Back to root
        }
        loadFilesAndFolders()
        updateActionBar()
    }

    private fun showCreateFolderDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Create New Folder") // Consider using string resources

        val input = EditText(this).apply {
            hint = "Folder Name" // Consider using string resources
        }
        builder.setView(input)

        builder.setPositiveButton("Create") { dialog, _ -> // Consider using string resources
            val folderName = input.text.toString().trim()
            if (folderName.isNotEmpty()) {
                if (folderName.any { it in ILLEGAL_CHARACTERS_FOR_FILENAME }) {
                    Toast.makeText(this, "Folder name contains invalid characters", Toast.LENGTH_SHORT).show() // Consider using string resources
                    return@setPositiveButton
                }

                if (FileStorageManager.createFolder(this, folderName, currentRelativePath)) {
                    Toast.makeText(this, "Folder '$folderName' created", Toast.LENGTH_SHORT).show()
                    loadFilesAndFolders()
                } else {
                    Toast.makeText(this, "Failed to create folder '$folderName'", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show() // Consider using string resources
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() } // Consider using string resources
        builder.show()
    }
    private fun openTextFileInAppReader(file: File) {
        if (!file.exists() || !file.canRead() || !file.extension.equals("txt", ignoreCase = true)) {
            Toast.makeText(this, "Cannot open or not a .txt file.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TextViewerActivity::class.java).apply {
            putExtra(TextViewerActivity.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(TextViewerActivity.EXTRA_FILE_NAME, file.name)
        }
        startActivity(intent)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to pick files.", Toast.LENGTH_LONG).show() // Consider using string resources
            Log.e("MainActivity", "No file picker found", e)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting file name from URI", e)
        }
        if (fileName == null) {
            fileName = uri.lastPathSegment?.substringAfterLast('/')
        }
        return fileName?.replace(Regex("[$ILLEGAL_CHARACTERS_FOR_FILENAME]"), "_")
    }
    private fun showTxtOptionsDialog(file: File) {
        val options = arrayOf("Open in app reader", "Open with external app", "Read aloud (TTS)")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { dialog, which ->
                when (options[which]) {
                    "Open in app reader" -> openTextFileInAppReader(file)
                    "Open with external app" -> openFileWithProvider(file)
                    "Read aloud (TTS)" -> readTextFromFile(file) // Assuming speakTextFile is defined
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFileWithProvider(file: File) {
        val authority = "${applicationContext.packageName}.fileprovider"
        val fileUri: Uri = try {
            FileProvider.getUriForFile(this, authority, file)
        } catch (e: IllegalArgumentException) {
            Log.e("MainActivity", "File URI generation failed for: ${file.absolutePath}", e)
            Toast.makeText(this, "Error: Could not share file.", Toast.LENGTH_LONG).show()
            return
        }

        // Declare mimeType outside the 'apply' block
        val extension = file.extension.lowercase()
        val resolvedMimeType = MimeTypeUtil.getMimeTypeExplicit(file) // Renamed to avoid confusion if needed

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, resolvedMimeType) // Use the variable declared outside
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this file type: ${file.extension}", Toast.LENGTH_LONG).show()
            // Now you can access resolvedMimeType here
            Log.w("MainActivity", "No app to open ${file.absolutePath} with MIME $resolvedMimeType", e)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file.", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Error opening file: ${file.absolutePath}", e)
        }
    }

    private fun showDeleteConfirmationDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Item") // Consider using string resources
            .setMessage("Are you sure you want to delete '${file.name}'?") // Consider using string resources
            .setPositiveButton("Delete") { _, _ -> // Consider using string resources
                if (FileStorageManager.deleteItem(file)) {
                    Toast.makeText(this, "'${file.name}' deleted", Toast.LENGTH_SHORT).show()
                    loadFilesAndFolders()
                } else {
                    Toast.makeText(this, "Failed to delete '${file.name}'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null) // Consider using string resources
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    companion object {
        private const val ILLEGAL_CHARACTERS_FOR_FILENAME = "/\\:*?\"<>|"
    }
}