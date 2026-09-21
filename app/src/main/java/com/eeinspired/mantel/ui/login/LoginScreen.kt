package com.eeinspired.mantel.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.eeinspired.mantel.data.LoginOutcome
import com.eeinspired.mantel.data.Messages
import com.eeinspired.mantel.data.SessionRepository
import com.eeinspired.mantel.ui.common.SecureScreen
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    repo: SessionRepository,
    initialMessage: String?,
    onAuthenticated: () -> Unit,
) {
    SecureScreen()

    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var appPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialMessage) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sign in to your frames",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Use an app password, not your Nextcloud login password — " +
                "ask your admin if you don't have one.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("Username") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        PasswordField(
            value = appPassword,
            onValueChange = { appPassword = it; error = null },
            enabled = !busy,
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible },
        )

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    error = attemptLogin(repo, username, appPassword, onAuthenticated)
                    busy = false
                }
            },
            enabled = !busy && username.isNotBlank() && appPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("App password") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
}

/** Returns null on success (and has already called [onAuthenticated]), or an error message. */
private suspend fun attemptLogin(
    repo: SessionRepository,
    username: String,
    appPassword: String,
    onAuthenticated: () -> Unit,
): String? = when (val outcome = repo.logIn(username, appPassword)) {
    is LoginOutcome.Success -> {
        onAuthenticated()
        null
    }
    LoginOutcome.InvalidCredentials -> Messages.INVALID_CREDENTIALS
    LoginOutcome.Unreachable -> Messages.NO_CONNECTION
    is LoginOutcome.ServerProblem -> Messages.serverError(outcome.code)
}
