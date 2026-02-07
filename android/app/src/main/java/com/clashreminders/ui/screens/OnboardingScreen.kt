package com.clashreminders.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    tagInput: String,
    onTagChange: (String) -> Unit,
    onRegister: () -> Unit,
    loading: Boolean,
    error: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to ClashReminders!",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = tagInput,
            onValueChange = onTagChange,
            label = { Text("Enter Player Tag (e.g., #2PP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRegister,
            enabled = tagInput.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Register")
            }
        }
    }
}

@Preview
@Composable
fun OnboardingScreenPreview() {
    OnboardingScreen(
        tagInput = "#2PP",
        onTagChange = {},
        onRegister = {},
        loading = false,
        error = null
    )
}
