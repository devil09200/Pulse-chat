package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.ui.status.UpdatesXmlViewController

@Composable
fun UpdatesTabContent(
    userProfile: UserProfile?,
    myStatuses: List<StatusUpdate>,
    friendsStatuses: List<StatusUpdate>,
    publicProfiles: List<PublicProfile>,
    currentUserId: String,
    searchQuery: String
) {
    val context = LocalContext.current
    var controllerRef by remember { mutableStateOf<UpdatesXmlViewController?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) {
            controllerRef?.onCameraCaptureSuccess("photo")
        }
    }

    val captureVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { saved ->
        if (saved) {
            controllerRef?.onCameraCaptureSuccess("video")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        controllerRef?.onCameraPermissionResult(granted)
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            controllerRef?.onGalleryMediaSelected(uri)
        }
    }

    val controller = remember(context) {
        UpdatesXmlViewController(
            rawContext = context,
            onLaunchCameraPermission = { perm -> cameraPermissionLauncher.launch(perm) },
            onLaunchTakePicture = { uri -> takePictureLauncher.launch(uri) },
            onLaunchCaptureVideo = { uri -> captureVideoLauncher.launch(uri) },
            onLaunchGalleryPicker = { req -> galleryPickerLauncher.launch(req) }
        ).also { controllerRef = it }
    }

    AndroidView(
        factory = { controller.binding.root },
        update = {
            controller.bind(
                userProfile = userProfile,
                myStatuses = myStatuses,
                friendsStatuses = friendsStatuses,
                publicProfiles = publicProfiles,
                currentUserId = currentUserId,
                searchQuery = searchQuery
            )
        },
        modifier = Modifier.fillMaxSize()
    )
}
