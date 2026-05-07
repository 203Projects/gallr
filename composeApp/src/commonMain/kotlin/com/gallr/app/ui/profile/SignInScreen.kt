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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.util.Validators
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch

private enum class AuthMode { SIGN_IN, SIGN_UP, FORGOT_PASSWORD, VERIFICATION_SENT, RESET_SENT }

@Composable
fun SignInScreen(
    supabaseClient: SupabaseClient,
    authRepository: AuthRepository,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifiedEmail by remember { mutableStateOf("") }

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

    // Verification sent / Reset sent screens
    if (mode == AuthMode.VERIFICATION_SENT || mode == AuthMode.RESET_SENT) {
        VerificationScreen(
            email = verifiedEmail,
            isResetMode = mode == AuthMode.RESET_SENT,
            lang = lang,
            onBackToSignIn = {
                mode = AuthMode.SIGN_IN
                error = null
            },
            onResend = {
                scope.launch {
                    try {
                        if (mode == AuthMode.RESET_SENT) {
                            authRepository.resetPassword(verifiedEmail)
                        } else {
                            authRepository.signUpWithEmail(verifiedEmail, password)
                        }
                    } catch (_: Exception) {}
                }
            },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "gallr",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (lang) {
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
            value = email,
            onValueChange = { email = it; error = null },
            placeholder = {
                Text(
                    when (lang) {
                        AppLanguage.KO -> "이메일"
                        AppLanguage.EN -> "Email"
                    },
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = if (mode == AuthMode.FORGOT_PASSWORD) ImeAction.Done else ImeAction.Next,
            ),
            singleLine = true,
            enabled = !isLoading,
            shape = RectangleShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Password field (hidden in forgot-password mode) ──────────
        if (mode != AuthMode.FORGOT_PASSWORD) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                placeholder = {
                    Text(
                        when (lang) {
                            AppLanguage.KO -> "비밀번호"
                            AppLanguage.EN -> "Password"
                        },
                    )
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                enabled = !isLoading,
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
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "! $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Primary action button ────────────────────────────────────
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                error = null

                // Client-side validation
                if (!Validators.isValidEmail(email)) {
                    error = when (lang) {
                        AppLanguage.KO -> "유효한 이메일을 입력해주세요"
                        AppLanguage.EN -> "Please enter a valid email"
                    }
                    return@OutlinedButton
                }

                if (mode == AuthMode.FORGOT_PASSWORD) {
                    isLoading = true
                    scope.launch {
                        try {
                            authRepository.resetPassword(email.trim())
                            verifiedEmail = email.trim()
                            mode = AuthMode.RESET_SENT
                        } catch (e: Exception) {
                            error = e.message ?: when (lang) {
                                AppLanguage.KO -> "오류가 발생했습니다"
                                AppLanguage.EN -> "An error occurred"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                    return@OutlinedButton
                }

                if (!Validators.isValidPassword(password)) {
                    error = when (lang) {
                        AppLanguage.KO -> "비밀번호는 ${Validators.MIN_PASSWORD_LENGTH}자 이상이어야 합니다"
                        AppLanguage.EN -> "Password must be at least ${Validators.MIN_PASSWORD_LENGTH} characters"
                    }
                    return@OutlinedButton
                }

                isLoading = true
                scope.launch {
                    try {
                        if (mode == AuthMode.SIGN_UP) {
                            authRepository.signUpWithEmail(email.trim(), password)
                            verifiedEmail = email.trim()
                            mode = AuthMode.VERIFICATION_SENT
                        } else {
                            authRepository.signInWithEmail(email.trim(), password)
                            // Success: auth state change triggers navigation automatically
                        }
                    } catch (e: Exception) {
                        error = parseAuthError(e, mode, lang)
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RectangleShape,
            enabled = !isLoading,
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
                    text = when (mode) {
                        AuthMode.SIGN_IN -> when (lang) {
                            AppLanguage.KO -> "로그인"
                            AppLanguage.EN -> "Sign In"
                        }
                        AuthMode.SIGN_UP -> when (lang) {
                            AppLanguage.KO -> "회원가입"
                            AppLanguage.EN -> "Sign Up"
                        }
                        AuthMode.FORGOT_PASSWORD -> when (lang) {
                            AppLanguage.KO -> "재설정 링크 보내기"
                            AppLanguage.EN -> "Send Reset Link"
                        }
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Toggle link (Sign In ↔ Sign Up) ─────────────────────────
        if (mode != AuthMode.FORGOT_PASSWORD) {
            TextButton(
                onClick = {
                    mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                    error = null
                },
                enabled = !isLoading,
            ) {
                Text(
                    text = when (mode) {
                        AuthMode.SIGN_IN -> when (lang) {
                            AppLanguage.KO -> "계정이 없으신가요? 회원가입"
                            AppLanguage.EN -> "Don't have an account? Sign Up"
                        }
                        AuthMode.SIGN_UP -> when (lang) {
                            AppLanguage.KO -> "이미 계정이 있으신가요? 로그인"
                            AppLanguage.EN -> "Already have an account? Sign In"
                        }
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Forgot password link (sign-in mode only)
            if (mode == AuthMode.SIGN_IN) {
                TextButton(
                    onClick = { mode = AuthMode.FORGOT_PASSWORD; error = null },
                    enabled = !isLoading,
                ) {
                    Text(
                        text = when (lang) {
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
                onClick = { mode = AuthMode.SIGN_IN; error = null },
                enabled = !isLoading,
            ) {
                Text(
                    text = when (lang) {
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
        if (mode != AuthMode.FORGOT_PASSWORD) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = when (lang) {
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
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        try {
                            supabaseClient.auth.signInWith(Google) {
                                queryParams["prompt"] = "select_account"
                            }
                        } catch (e: Exception) {
                            error = e.message ?: when (lang) {
                                AppLanguage.KO -> "Google 로그인 실패"
                                AppLanguage.EN -> "Google sign-in failed"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RectangleShape,
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "Google로 계속하기"
                        AppLanguage.EN -> "Continue with Google"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Apple Sign-In ───────────────────────────────────────
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        try {
                            supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.Apple)
                        } catch (e: Exception) {
                            error = e.message ?: when (lang) {
                                AppLanguage.KO -> "Apple 로그인 실패"
                                AppLanguage.EN -> "Apple sign-in failed"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RectangleShape,
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Text(
                    text = when (lang) {
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
    lang: AppLanguage,
    onBackToSignIn: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "gallr",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(32.dp))

        Text(
            text = if (isResetMode) {
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
            text = if (isResetMode) {
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

        TextButton(onClick = onResend) {
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "메일을 받지 못하셨나요? 다시 보내기"
                    AppLanguage.EN -> "Didn't receive it? Resend"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBackToSignIn,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RectangleShape,
        ) {
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "로그인으로 돌아가기"
                    AppLanguage.EN -> "Back to Sign In"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ── Error parsing ────────────────────────────────────────────────────────────

private fun parseAuthError(e: Exception, mode: AuthMode, lang: AppLanguage): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        "invalid login credentials" in msg || "invalid_credentials" in msg -> when (lang) {
            AppLanguage.KO -> "이메일 또는 비밀번호가 올바르지 않습니다"
            AppLanguage.EN -> "Invalid email or password"
        }
        "email not confirmed" in msg -> when (lang) {
            AppLanguage.KO -> "이메일이 인증되지 않았습니다. 받은편지함을 확인해주세요."
            AppLanguage.EN -> "Email not verified. Check your inbox."
        }
        "user already registered" in msg || "already registered" in msg -> when (lang) {
            AppLanguage.KO -> "이미 등록된 이메일입니다. 로그인해주세요."
            AppLanguage.EN -> "Email already registered. Try signing in."
        }
        "rate limit" in msg || "too many requests" in msg -> when (lang) {
            AppLanguage.KO -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
            AppLanguage.EN -> "Too many attempts. Please try again later."
        }
        else -> e.message ?: when (lang) {
            AppLanguage.KO -> "오류가 발생했습니다"
            AppLanguage.EN -> "An error occurred"
        }
    }
}
