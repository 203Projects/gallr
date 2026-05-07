package com.gallr.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.gallr.app.platform.cropAndCompress
import com.gallr.app.platform.decodeImageBitmap
import com.gallr.app.ui.profile.LocalCropOverlay
import coil3.compose.AsyncImage
import com.gallr.app.platform.rememberImagePicker
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
import com.gallr.shared.repository.ProfileRepository
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    user: GallrUser,
    profile: Profile?,
    profileRepository: ProfileRepository,
    lang: AppLanguage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val initialName = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: user.displayName.takeIf { it.isNotBlank() } ?: ""
    var displayName by remember { mutableStateOf(initialName) }
    var isLoading by remember { mutableStateOf(false) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var avatarUrl by remember {
        mutableStateOf(
            profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: user.avatarUrl
        )
    }
    var cropRawBytes by remember { mutableStateOf<ByteArray?>(null) }
    val cropOverlay = LocalCropOverlay.current

    // Image picker — returns raw bytes, then shows crop overlay at App level
    val pickImage = rememberImagePicker { bytes ->
        if (bytes != null) {
            val bitmap = decodeImageBitmap(bytes)
            if (bitmap != null) {
                cropRawBytes = bytes
                cropOverlay.show(
                    bitmap = bitmap,
                    language = lang,
                    confirm = { cropOffset, cropSize ->
                        val raw = cropRawBytes ?: return@show
                        cropRawBytes = null
                        cropOverlay.dismiss()
                        isUploadingAvatar = true
                        scope.launch {
                            try {
                                val cropped = cropAndCompress(raw, cropOffset, cropSize)
                                if (cropped != null) {
                                    val url = profileRepository.uploadAvatar(user.id, cropped)
                                    avatarUrl = url
                                } else {
                                    error = when (lang) {
                                        AppLanguage.KO -> "사진 처리에 실패했습니다"
                                        AppLanguage.EN -> "Failed to process photo"
                                    }
                                }
                            } catch (e: Exception) {
                                error = e.message ?: when (lang) {
                                    AppLanguage.KO -> "사진 업로드에 실패했습니다"
                                    AppLanguage.EN -> "Failed to upload photo"
                                }
                            } finally {
                                isUploadingAvatar = false
                            }
                        }
                    },
                    cancel = {
                        cropRawBytes = null
                        cropOverlay.dismiss()
                    },
                )
            } else {
                error = when (lang) {
                    AppLanguage.KO -> "이미지를 읽을 수 없습니다"
                    AppLanguage.EN -> "Could not read image"
                }
            }
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.onBackground,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        errorBorderColor = MaterialTheme.colorScheme.onBackground,
        focusedTextColor = MaterialTheme.colorScheme.onBackground,
        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        cursorColor = MaterialTheme.colorScheme.onBackground,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        // ── Back button ─────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Avatar ──────────────────────────────────────────────────
        val avatarDescription = when (lang) {
            AppLanguage.KO -> "프로필 사진"
            AppLanguage.EN -> "Profile photo"
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = avatarDescription },
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = avatarDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                val initial = (displayName.takeIf { it.isNotBlank() } ?: "?").first().uppercase()
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Loading overlay during upload
            if (isUploadingAvatar) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { pickImage() },
            enabled = !isUploadingAvatar,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "사진 변경"
                    AppLanguage.EN -> "Change Photo"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Display name field ──────────────────────────────────────
        Text(
            text = when (lang) {
                AppLanguage.KO -> "이름"
                AppLanguage.EN -> "Display Name"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it; error = null },
            placeholder = {
                Text(when (lang) {
                    AppLanguage.KO -> "이름을 입력하세요"
                    AppLanguage.EN -> "Enter your name"
                })
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            enabled = !isLoading,
            shape = RectangleShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // Error
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "! $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Save button ─────────────────────────────────────────────
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                val name = displayName.trim()
                if (name.isBlank()) {
                    error = when (lang) {
                        AppLanguage.KO -> "이름을 입력해주세요"
                        AppLanguage.EN -> "Name cannot be empty"
                    }
                    return@OutlinedButton
                }
                isLoading = true
                scope.launch {
                    try {
                        profileRepository.updateProfile(user.id, name, profile?.bio ?: "")
                        onBack()
                    } catch (e: Exception) {
                        error = e.message ?: when (lang) {
                            AppLanguage.KO -> "저장에 실패했습니다"
                            AppLanguage.EN -> "Failed to save"
                        }
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RectangleShape,
            enabled = !isLoading && !isUploadingAvatar,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "저장"
                        AppLanguage.EN -> "Save"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
