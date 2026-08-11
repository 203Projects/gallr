package com.gallr.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.platform.cropAndCompress
import com.gallr.app.platform.decodeImageBitmap
import com.gallr.app.platform.rememberImagePicker
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditProfileError
import com.gallr.app.viewmodel.EditProfileViewModel
import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    viewModel: EditProfileViewModel,
    lang: AppLanguage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var cropRawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isProcessingImage by remember { mutableStateOf(false) }
    val cropOverlay = LocalCropOverlay.current

    LaunchedEffect(uiState.saveSucceeded) {
        if (uiState.saveSucceeded) {
            viewModel.consumeSaveSuccess()
            onBack()
        }
    }

    // Image picker — returns raw bytes, then shows crop overlay at App level
    val pickImage =
        rememberImagePicker { bytes ->
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
                            isProcessingImage = true
                            scope.launch {
                                val cropped = cropAndCompress(raw, cropOffset, cropSize)
                                if (cropped != null) {
                                    viewModel.uploadAvatar(cropped)
                                } else {
                                    viewModel.showError(EditProfileError.IMAGE_PROCESSING_FAILED)
                                }
                                isProcessingImage = false
                            }
                        },
                        cancel = {
                            cropRawBytes = null
                            cropOverlay.dismiss()
                        },
                    )
                } else {
                    viewModel.showError(EditProfileError.IMAGE_READ_FAILED)
                }
            }
        }

    val textFieldColors =
        OutlinedTextFieldDefaults.colors(
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
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(horizontal = GallrSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(GallrSpacing.md))

        // ── Back button ─────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(GallrSpacing.lg))

        // ── Avatar ──────────────────────────────────────────────────
        val avatarDescription =
            when (lang) {
                AppLanguage.KO -> "프로필 사진"
                AppLanguage.EN -> "Profile photo"
            }
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = avatarDescription },
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.avatarUrl != null) {
                AsyncImage(
                    model = uiState.avatarUrl,
                    contentDescription = avatarDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                val initial = (uiState.displayName.takeIf { it.isNotBlank() } ?: "?").first().uppercase()
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Loading overlay during upload
            if (isProcessingImage || uiState.isUploadingAvatar) {
                Box(
                    modifier =
                        Modifier
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

        Spacer(Modifier.height(GallrSpacing.sm))
        TextButton(
            onClick = { pickImage() },
            enabled = !isProcessingImage && !uiState.isUploadingAvatar,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        ) {
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "사진 변경"
                        AppLanguage.EN -> "Change Photo"
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(GallrSpacing.xl))

        // ── Display name field ──────────────────────────────────────
        Text(
            text =
                when (lang) {
                    AppLanguage.KO -> "이름"
                    AppLanguage.EN -> "Display Name"
                },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = viewModel::updateDisplayName,
            placeholder = {
                Text(
                    when (lang) {
                        AppLanguage.KO -> "이름을 입력하세요"
                        AppLanguage.EN -> "Enter your name"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            enabled = !uiState.isSaving,
            isError = uiState.error == EditProfileError.NAME_REQUIRED,
            shape = RectangleShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // Error
        uiState.error?.let { error ->
            Spacer(Modifier.height(GallrSpacing.xs))
            GallrErrorMessage(
                message = profileErrorMessage(error, lang),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(GallrSpacing.lg))

        // ── Save button ─────────────────────────────────────────────
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                viewModel.saveProfile()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RectangleShape,
            enabled = !uiState.isSaving && !isProcessingImage && !uiState.isUploadingAvatar,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "저장"
                            AppLanguage.EN -> "Save"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun profileErrorMessage(
    error: EditProfileError,
    lang: AppLanguage,
): String =
    when (error) {
        EditProfileError.NAME_REQUIRED -> {
            when (lang) {
                AppLanguage.KO -> "이름을 입력해주세요"
                AppLanguage.EN -> "Name cannot be empty"
            }
        }

        EditProfileError.IMAGE_READ_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "이미지를 읽을 수 없습니다"
                AppLanguage.EN -> "Could not read image"
            }
        }

        EditProfileError.IMAGE_PROCESSING_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "사진 처리에 실패했습니다"
                AppLanguage.EN -> "Failed to process photo"
            }
        }

        EditProfileError.AVATAR_UPLOAD_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "사진 업로드에 실패했습니다"
                AppLanguage.EN -> "Failed to upload photo"
            }
        }

        EditProfileError.SAVE_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "저장에 실패했습니다"
                AppLanguage.EN -> "Failed to save"
            }
        }
    }
