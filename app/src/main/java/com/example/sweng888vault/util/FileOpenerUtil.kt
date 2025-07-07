package com.example.sweng888vault.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileOpenerUtil {

    private const val FILE_PROVIDER_TAG = "FileOpenerUtil"

    /**
     * @param context The Context used to start the activity and access app resources.
     * @param file The File object to be opened.
     */
    fun openFileWithExternalApp(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            Log.w(FILE_PROVIDER_TAG, "Attempted to open non-existent file: ${file.absolutePath}")
            return
        }
        if (!file.canRead()) {
            Toast.makeText(context, "Cannot read file: ${file.name}", Toast.LENGTH_SHORT).show()
            Log.w(FILE_PROVIDER_TAG, "Attempted to open unreadable file: ${file.absolutePath}")
            return
        }
        if (file.isDirectory) {
            Toast.makeText(context, "${file.name} is a directory, cannot open.", Toast.LENGTH_SHORT).show()
            Log.w(FILE_PROVIDER_TAG, "Attempted to open directory: ${file.absolutePath}")
            return
        }

        val authority = "${context.applicationContext.packageName}.fileprovider"
        val fileUri: Uri

        try {
            fileUri = FileProvider.getUriForFile(context, authority, file)
            Log.d(FILE_PROVIDER_TAG, "Generated URI: $fileUri for file: ${file.name}")
        } catch (e: IllegalArgumentException) {
            Log.e(
                FILE_PROVIDER_TAG, "File URI generation failed for: ${file.absolutePath}. " +
                    "Check FileProvider authorities and file_paths.xml configuration.", e)
            Toast.makeText(context,
                "Error: Could not create a link to open this file. " +
                        "Please check app configuration (FileProvider).",
                Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Log.e(FILE_PROVIDER_TAG, "An unexpected error occurred while generating URI for ${file.name}", e)
            Toast.makeText(context, "Error preparing file for opening.", Toast.LENGTH_SHORT).show()
            return
        }

        val mimeType = MimeTypeUtil.getMimeTypeExplicit(file)
        Log.d(FILE_PROVIDER_TAG, "Determined MIME type: $mimeType for file: ${file.name}")


        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        }

        try {
            context.startActivity(intent)
            Log.i(FILE_PROVIDER_TAG, "Intent to open ${file.name} (MIME: $mimeType) sent.")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context,
                "No application found to open '${file.name}' (Type: $mimeType)",
                Toast.LENGTH_LONG).show()
            Log.e(FILE_PROVIDER_TAG, "ActivityNotFound for URI: $fileUri, MIME: $mimeType. No app can handle this file type.", e)
        } catch (e: SecurityException) {
            Log.e(
                FILE_PROVIDER_TAG, "SecurityException while trying to open ${file.name}. " +
                    "This could be a URI permission issue or FileProvider misconfiguration.", e)
            Toast.makeText(context, "Could not open file due to a security restriction.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(FILE_PROVIDER_TAG, "An unexpected error occurred while trying to start activity for ${file.name}", e)
            Toast.makeText(context, "Error opening file.", Toast.LENGTH_SHORT).show()
        }
    }
}