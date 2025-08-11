package com.example.sweng888vault.util

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.example.sweng888vault.R
import java.io.File

object DialogsUtil {
    interface DialogListener {
        fun onFolderCreate(folderName: String)
        fun onDeleteConfirmed(file: File)
    }

    /**
     * Shows a dialog to create a new folder.
     */
    fun showCreateFolderDialog(context: Context, listener: DialogListener) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Create New Folder")
        val input = EditText(context).apply { hint = "Folder Name" }
        builder.setView(input)

        builder.setPositiveButton("Create") { dialog, _ ->
            val folderName = input.text.toString().trim()
            if (folderName.isNotEmpty()) {
                listener.onFolderCreate(folderName)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    /**
     * Shows a confirmation dialog for deleting a file or folder.
     */
    fun showDeleteConfirmationDialog(context: Context, file: File, listener: DialogListener) {
        AlertDialog.Builder(context)
            .setTitle("Delete Item")
            .setMessage("Are you sure you want to delete '${file.name}'?")
            .setPositiveButton("Delete") { _, _ -> listener.onDeleteConfirmed(file) }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
}