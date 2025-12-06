package com.nfo.tracker.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nfo.tracker.data.remote.SupabaseClient
import com.nfo.tracker.work.ShiftStateHelper
import kotlinx.coroutines.launch

private const val TAG = "LoginScreen"

/**
 * Login screen that authenticates against the Supabase NFOUsers table.
 *
 * @param onLoginSuccess Callback invoked when login succeeds. The caller should navigate away.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Form state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // UI state
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Validation state
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    /**
     * Validates input and attempts login.
     */
    fun attemptLogin() {
        // Clear previous errors
        usernameError = null
        passwordError = null
        errorMessage = null

        // Validate inputs
        val trimmedUsername = username.trim()
        val trimmedPassword = password.trim()

        var hasError = false
        if (trimmedUsername.isEmpty()) {
            usernameError = "Username is required"
            hasError = true
        }
        if (trimmedPassword.isEmpty()) {
            passwordError = "Password is required"
            hasError = true
        }
        if (hasError) return

        // Dismiss keyboard
        focusManager.clearFocus()

        // Attempt login
        isLoading = true
        coroutineScope.launch {
            Log.d(TAG, "Attempting login for username=$trimmedUsername")

            val result = SupabaseClient.loginNfoUser(trimmedUsername, trimmedPassword)

            isLoading = false

            if (result != null) {
                Log.d(TAG, "Login successful, saving user state")
                ShiftStateHelper.setLoggedInUser(
                    context = context,
                    username = result.username,
                    displayName = result.name,
                    homeLocation = result.homeLocation
                )

                // Upload FCM token to Supabase if it already exists
                // (token may have been received before login)
                ShiftStateHelper.uploadFcmTokenIfExists(context, result.username)

                onLoginSuccess()
            } else {
                Log.w(TAG, "Login failed for username=$trimmedUsername")
                errorMessage = "Invalid username or password"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "NFO Tracker",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Sign in to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            // Username field
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    usernameError = null
                    errorMessage = null
                },
                label = { Text("Username") },
                placeholder = { Text("Enter your username") },
                singleLine = true,
                isError = usernameError != null,
                supportingText = usernameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                    errorMessage = null
                },
                label = { Text("Password") },
                placeholder = { Text("Enter your password") },
                singleLine = true,
                isError = passwordError != null,
                supportingText = passwordError?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { attemptLogin() }
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error message (generic login failure)
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Login button
            Button(
                onClick = { attemptLogin() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Login")
                }
            }
        }
    }
}
