package com.gallr.app.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.SignInError
import com.gallr.app.viewmodel.SignInMode
import com.gallr.app.viewmodel.SignInViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.repository.OAuthProvider
import com.gallr.shared.util.Validators

@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

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

    // Verification sent / Reset sent screens
    if (uiState.mode == SignInMode.VERIFICATION_SENT || uiState.mode == SignInMode.RESET_SENT) {
        VerificationScreen(
            email = uiState.verifiedEmail,
            isResetMode = uiState.mode == SignInMode.RESET_SENT,
            isLoading = uiState.isLoading,
            error = uiState.error,
            lang = lang,
            onBackToSignIn = viewModel::backToSignIn,
            onResend = viewModel::resend,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "gallr",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                when (lang) {
                    AppLanguage.KO -> "취향으로 발견하는 전시"
                    AppLanguage.EN -> "discover exhibitions through taste"
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(64.dp))

        // ── Email field ──────────────────────────────────────────────
        OutlinedTextField(
            value = uiState.email,
            onValueChange = viewModel::updateEmail,
            placeholder = {
                Text(
                    when (lang) {
                        AppLanguage.KO -> "이메일"
                        AppLanguage.EN -> "Email"
                    },
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction =
                        if (uiState.mode == SignInMode.FORGOT_PASSWORD) {
                            ImeAction.Done
                        } else {
                            ImeAction.Next
                        },
                ),
            singleLine = true,
            enabled = !uiState.isLoading,
            isError = uiState.error == SignInError.INVALID_EMAIL,
            shape = RectangleShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Password field (hidden in forgot-password mode) ──────────
        if (uiState.mode != SignInMode.FORGOT_PASSWORD) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::updatePassword,
                placeholder = {
                    Text(
                        when (lang) {
                            AppLanguage.KO -> "비밀번호"
                            AppLanguage.EN -> "Password"
                        },
                    )
                },
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                enabled = !uiState.isLoading,
                isError = uiState.error == SignInError.PASSWORD_TOO_SHORT,
                shape = RectangleShape,
                colors = textFieldColors,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            text = if (passwordVisible) "◉" else "○",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Error message ────────────────────────────────────────────
        uiState.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            GallrErrorMessage(
                message = signInErrorMessage(error, lang),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Primary action button ────────────────────────────────────
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                viewModel.submit()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RectangleShape,
            enabled = !uiState.isLoading,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text =
                        when (uiState.mode) {
                            SignInMode.SIGN_IN -> {
                                when (lang) {
                                    AppLanguage.KO -> "로그인"
                                    AppLanguage.EN -> "Sign In"
                                }
                            }

                            SignInMode.SIGN_UP -> {
                                when (lang) {
                                    AppLanguage.KO -> "회원가입"
                                    AppLanguage.EN -> "Sign Up"
                                }
                            }

                            SignInMode.FORGOT_PASSWORD -> {
                                when (lang) {
                                    AppLanguage.KO -> "재설정 링크 보내기"
                                    AppLanguage.EN -> "Send Reset Link"
                                }
                            }

                            else -> {
                                ""
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Toggle link (Sign In ↔ Sign Up) ─────────────────────────
        if (uiState.mode != SignInMode.FORGOT_PASSWORD) {
            TextButton(
                onClick = viewModel::toggleSignInMode,
                enabled = !uiState.isLoading,
            ) {
                Text(
                    text =
                        when (uiState.mode) {
                            SignInMode.SIGN_IN -> {
                                when (lang) {
                                    AppLanguage.KO -> "계정이 없으신가요? 회원가입"
                                    AppLanguage.EN -> "Don't have an account? Sign Up"
                                }
                            }

                            SignInMode.SIGN_UP -> {
                                when (lang) {
                                    AppLanguage.KO -> "이미 계정이 있으신가요? 로그인"
                                    AppLanguage.EN -> "Already have an account? Sign In"
                                }
                            }

                            else -> {
                                ""
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Forgot password link (sign-in mode only)
            if (uiState.mode == SignInMode.SIGN_IN) {
                TextButton(
                    onClick = viewModel::showForgotPassword,
                    enabled = !uiState.isLoading,
                ) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "비밀번호를 잊으셨나요?"
                                AppLanguage.EN -> "Forgot password?"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            TextButton(
                onClick = viewModel::backToSignIn,
                enabled = !uiState.isLoading,
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "로그인으로 돌아가기"
                            AppLanguage.EN -> "Back to Sign In"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Divider ─────────────────────────────────────────────────
        if (uiState.mode != SignInMode.FORGOT_PASSWORD) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "  또는  "
                            AppLanguage.EN -> "  or  "
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Google Sign-In ──────────────────────────────────────
            OutlinedButton(
                onClick = { viewModel.signInWithOAuth(OAuthProvider.GOOGLE) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RectangleShape,
                enabled = !uiState.isLoading,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "Google로 계속하기"
                            AppLanguage.EN -> "Continue with Google"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Apple Sign-In ───────────────────────────────────────
            OutlinedButton(
                onClick = { viewModel.signInWithOAuth(OAuthProvider.APPLE) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RectangleShape,
                enabled = !uiState.isLoading,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "Apple로 계속하기"
                            AppLanguage.EN -> "Continue with Apple"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Verification / Reset Sent Screen ────────────────────────────────────────

@Composable
private fun VerificationScreen(
    email: String,
    isResetMode: Boolean,
    isLoading: Boolean,
    error: SignInError?,
    lang: AppLanguage,
    onBackToSignIn: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = GallrSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "gallr",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(32.dp))

        Text(
            text =
                if (isResetMode) {
                    when (lang) {
                        AppLanguage.KO -> "비밀번호 재설정 링크를 보냈습니다"
                        AppLanguage.EN -> "Check your email"
                    }
                } else {
                    when (lang) {
                        AppLanguage.KO -> "이메일을 확인해주세요"
                        AppLanguage.EN -> "Check your email"
                    }
                },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text =
                if (isResetMode) {
                    when (lang) {
                        AppLanguage.KO -> "$email 으로 비밀번호 재설정 링크를 보냈습니다."
                        AppLanguage.EN -> "We sent a password reset link to $email."
                    }
                } else {
                    when (lang) {
                        AppLanguage.KO -> "$email 으로 인증 링크를 보냈습니다."
                        AppLanguage.EN -> "We sent a verification link to $email."
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        error?.let {
            GallrErrorMessage(
                message = signInErrorMessage(it, lang),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(GallrSpacing.sm))
        }

        TextButton(
            onClick = onResend,
            enabled = !isLoading,
        ) {
            Text(
                text =
                    if (isLoading) {
                        "..."
                    } else {
                        when (lang) {
                            AppLanguage.KO -> "메일을 받지 못하셨나요? 다시 보내기"
                            AppLanguage.EN -> "Didn't receive it? Resend"
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBackToSignIn,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RectangleShape,
        ) {
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "로그인으로 돌아가기"
                        AppLanguage.EN -> "Back to Sign In"
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun signInErrorMessage(
    error: SignInError,
    lang: AppLanguage,
): String =
    when (error) {
        SignInError.INVALID_EMAIL -> {
            when (lang) {
                AppLanguage.KO -> "유효한 이메일을 입력해주세요"
                AppLanguage.EN -> "Please enter a valid email"
            }
        }

        SignInError.PASSWORD_TOO_SHORT -> {
            when (lang) {
                AppLanguage.KO -> "비밀번호는 ${Validators.MIN_PASSWORD_LENGTH}자 이상이어야 합니다"
                AppLanguage.EN -> "Password must be at least ${Validators.MIN_PASSWORD_LENGTH} characters"
            }
        }

        SignInError.INVALID_CREDENTIALS -> {
            when (lang) {
                AppLanguage.KO -> "이메일 또는 비밀번호가 올바르지 않습니다"
                AppLanguage.EN -> "Invalid email or password"
            }
        }

        SignInError.EMAIL_NOT_CONFIRMED -> {
            when (lang) {
                AppLanguage.KO -> "이메일이 인증되지 않았습니다. 받은편지함을 확인해주세요."
                AppLanguage.EN -> "Email not verified. Check your inbox."
            }
        }

        SignInError.EMAIL_ALREADY_REGISTERED -> {
            when (lang) {
                AppLanguage.KO -> "이미 등록된 이메일입니다. 로그인해주세요."
                AppLanguage.EN -> "Email already registered. Try signing in."
            }
        }

        SignInError.RATE_LIMITED -> {
            when (lang) {
                AppLanguage.KO -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
                AppLanguage.EN -> "Too many attempts. Please try again later."
            }
        }

        SignInError.SIGNUPS_DISABLED -> {
            when (lang) {
                AppLanguage.KO -> "계정 만들기를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
                AppLanguage.EN -> "Account creation is temporarily unavailable. Try again later."
            }
        }

        SignInError.GOOGLE_SIGN_IN_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "Google 로그인에 실패했습니다"
                AppLanguage.EN -> "Google sign-in failed"
            }
        }

        SignInError.APPLE_SIGN_IN_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "Apple 로그인에 실패했습니다"
                AppLanguage.EN -> "Apple sign-in failed"
            }
        }

        SignInError.RESEND_FAILED -> {
            when (lang) {
                AppLanguage.KO -> "메일을 다시 보내지 못했습니다"
                AppLanguage.EN -> "Couldn’t resend the email"
            }
        }

        SignInError.GENERIC -> {
            when (lang) {
                AppLanguage.KO -> "오류가 발생했습니다"
                AppLanguage.EN -> "An error occurred"
            }
        }
    }
