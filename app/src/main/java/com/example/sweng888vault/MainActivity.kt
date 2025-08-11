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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sweng888vault.databinding.ActivityMainBinding
import com.example.sweng888vault.util.MimeTypeUtil
import com.example.sweng888vault.util.Fileprocessing
import com.example.sweng888vault.util.FileStorageManager
import com.example.sweng888vault.util.MediaManager
import com.example.sweng888vault.util.TextToSpeechHelper
import com.example.sweng888vault.util.DialogsUtil
import java.io.File
import com.example.sweng888vault.util.ExportUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity(), DialogsUtil.DialogListener {

    // View Binding variable
    private var exportPassword: CharArray? = null
    private val exportDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    // Retrieve the password from the class property
                    exportPassword?.let { password ->
                        exportFiles(uri, password)
                        // Clear the password from memory for security
                        exportPassword = null
                    } ?: run {
                        Toast.makeText(this, "Error: Password not available.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Export cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
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
            DialogsUtil.showCreateFolderDialog(this, this)
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

        binding.buttonExport.setOnClickListener {
            startExportProcess()
        }
    }

    override fun onDestroy() {
        ttsHelper.shutdown()
        super.onDestroy()
    }

    override fun onFolderCreate(folderName: String) {
        if (folderName.any { it in ILLEGAL_CHARACTERS_FOR_FILENAME }) {
            Toast.makeText(this, "Folder name contains invalid characters", Toast.LENGTH_SHORT).show()
            return
        }

        if (FileStorageManager.createFolder(this, folderName, currentRelativePath)) {
            Toast.makeText(this, "Folder '$folderName' created", Toast.LENGTH_SHORT).show()
            loadFilesAndFolders()
        } else {
            Toast.makeText(this, "Failed to create folder '$folderName'", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDeleteConfirmed(file: File) {
        if (FileStorageManager.deleteItem(file)) {
            Toast.makeText(this, "'${file.name}' deleted", Toast.LENGTH_SHORT).show()
            loadFilesAndFolders()
        } else {
            Toast.makeText(this, "Failed to delete '${file.name}'", Toast.LENGTH_SHORT).show()
        }
    }

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
                    openFileWithProvider(file) // Use FileProvider for opening
                }
            },
            onItemDelete = { file ->
                DialogsUtil.showDeleteConfirmationDialog(this, file, this)
            },
            onTextToSpeech = { file ->
                val onTextExtracted: (text: String?, fileName: String) -> Unit = { text, fileName ->
                    if (text != null) {
                        showTextDialogAndSpeak(text, fileName)
                    }
                }

                when (file.extension.lowercase()) {
                    "jpg", "jpeg", "png" -> Fileprocessing.recognizeTextFromImage(file, this, onTextExtracted)
                    "pdf" -> Fileprocessing.readTextFromPdf(file, this, onTextExtracted)
                    "txt" -> Fileprocessing.readTextFromFile(file, this, onTextExtracted)
                    "doc", "docx" -> Fileprocessing.readTextFromWord(file, this, onTextExtracted)
                    "epub" -> Fileprocessing.readTextFromEpub(file, this, onTextExtracted)
                    else -> Toast.makeText(this, "Unreadable File", Toast.LENGTH_SHORT).show()
                }
            },
            onMediaPlayer = { file ->
                showAudioPlayer(file)
            }
        )
        binding.recyclerViewFiles.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
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
            .setCancelable(false)
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
            .setCancelable(false)
            .setView(dialogView)
            .create()

        readTextButton.setOnClickListener {
            ttsHelper.speak(text)
        }

        saveAudioButton.setOnClickListener {
            val textToSpeak = textView.text.toString()
            val baseOutputFileName = fileName

            val targetFolderName = "Saved Audios"
            val parentOfTargetFolderRelativePath = currentRelativePath

            val folderOperationSuccessful: Boolean = FileStorageManager.createFolder(
                this,
                folderName = targetFolderName,
                parentRelativePath = parentOfTargetFolderRelativePath
            )

            if (!folderOperationSuccessful) {
                Log.e("MainActivity", "Failed to create or access '$targetFolderName' folder in '$parentOfTargetFolderRelativePath'")
                Toast.makeText(this, "Error setting up audio save location.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val appRootContentDir: File = FileStorageManager.getRootContentDirectory(this)
            val actualTargetDirectoryFile: File = if (parentOfTargetFolderRelativePath.isEmpty()) {
                File(appRootContentDir, targetFolderName)
            } else {
                File(File(appRootContentDir, parentOfTargetFolderRelativePath), targetFolderName)
            }

            if (!actualTargetDirectoryFile.exists() || !actualTargetDirectoryFile.isDirectory) {
                Log.e("MainActivity", "Post-creation check failed: ${actualTargetDirectoryFile.absolutePath} is not a valid directory.")
                Toast.makeText(this, "Internal error finding save location.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ttsHelper.synthesizeToFile(textToSpeak, baseOutputFileName) { cachedFiles ->
                runOnUiThread {
                    if (cachedFiles != null && cachedFiles.isNotEmpty()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            var allCopiedSuccessfully = true
                            val copiedFileDisplayNames = mutableListOf<String>()

                            for (cachedFile in cachedFiles) {
                                if (!cachedFile.exists()) continue

                                val destinationFile = File(actualTargetDirectoryFile, cachedFile.name)

                                try {
                                    cachedFile.copyTo(destinationFile, overwrite = true)
                                    Log.i("MainActivity", "Copied ${cachedFile.name} to ${destinationFile.absolutePath}")
                                    copiedFileDisplayNames.add(cachedFile.name)
                                } catch (e: IOException) {
                                    Log.e("MainActivity", "Failed to copy ${cachedFile.name} to ${actualTargetDirectoryFile.absolutePath}", e)
                                    allCopiedSuccessfully = false
                                    break
                                }
                            }

                            withContext(Dispatchers.Main) {
                                val message: String
                                if (allCopiedSuccessfully && copiedFileDisplayNames.isNotEmpty()) {
                                    message = "Audio saved to '$targetFolderName': ${copiedFileDisplayNames.joinToString()}"
                                } else if (copiedFileDisplayNames.isNotEmpty()) {
                                    message = "Some audio files saved to '$targetFolderName': ${copiedFileDisplayNames.joinToString()}"
                                } else if (!allCopiedSuccessfully) {
                                    message = "Failed to copy one or more audio files."
                                }
                                else {
                                    message = "No audio files were processed for saving."
                                }
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                loadFilesAndFolders()
                            }
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to generate audio (TTS error or no text).", Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch(Dispatchers.IO) {
            val items = FileStorageManager.listItems(this@MainActivity, currentRelativePath)
            val sortedItems =
                items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            withContext(Dispatchers.Main) {
                fileAdapter.submitList(sortedItems)
            }
        }
    }

    private fun updateActionBar() {
        if (currentRelativePath.isEmpty()) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = getString(R.string.app_name)
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
            ""
        }
        loadFilesAndFolders()
        updateActionBar()
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
            Toast.makeText(this, "No app found to pick files.", Toast.LENGTH_LONG).show()
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
                    "Read aloud (TTS)" -> Fileprocessing.readTextFromFile(file, this) { text, _ ->
                        if (text != null) {
                            showTextDialogAndSpeak(text, file.name)
                        }
                    }
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

        val resolvedMimeType = MimeTypeUtil.getMimeTypeExplicit(file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, resolvedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this file type: ${file.extension}", Toast.LENGTH_LONG).show()
            Log.w("MainActivity", "No app to open ${file.absolutePath} with MIME $resolvedMimeType", e)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file.", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Error opening file: ${file.absolutePath}", e)
        }
    }

    private fun exportFiles(exportUri: Uri, password: CharArray) {
        val filesToExport = ExportUtil.getAllFileDetailsForExport(this)

        Toast.makeText(this, "Starting export...", Toast.LENGTH_LONG).show()

        // Pass the password to the performExport function
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ExportUtil.performExport(this@MainActivity, exportUri, filesToExport, password)
            }
        }
    }

    private fun showPasswordDialog(onPasswordEntered: (CharArray) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.for_encrypt_pw, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)

        AlertDialog.Builder(this)
            .setTitle("Enter Password for Export")
            .setView(dialogView)
            .setPositiveButton("Export") { dialog, _ ->
                val password = passwordEditText.text.toString().toCharArray()
                if (password.isNotEmpty()) {
                    onPasswordEntered(password)
                } else {
                    Toast.makeText(this, "Password cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun startExportProcess() {
        val filesToExport = ExportUtil.getAllFileDetailsForExport(this)

        if (filesToExport.isEmpty()) {
            Toast.makeText(this, "No files found to export.", Toast.LENGTH_SHORT).show()
            return
        }
        showPasswordDialog { password ->
            exportPassword = password
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "vault_export_${System.currentTimeMillis()}.zip")
            }
            exportDocumentLauncher.launch(intent) // Pass the password to the launcher
        }
    }

    companion object {
        private const val ILLEGAL_CHARACTERS_FOR_FILENAME = "/\\:*?\"<>|"
    }
}