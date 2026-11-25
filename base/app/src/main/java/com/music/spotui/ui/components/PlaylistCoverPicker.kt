package com.music.spotui.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Opens the system photo picker for a playlist cover: the modern picker where the phone
 * has one, a plain image chooser where it does not. No storage permission either way —
 * the picked image is copied into the app's files by the caller
 * (see com.music.spotui.data.local.PlaylistCovers).
 */
@Composable
fun rememberPlaylistCoverPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val context = LocalContext.current
    val modern = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) current(uri)
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) current(uri)
    }
    return {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            modern.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            legacy.launch("image/*")
        }
    }
}
