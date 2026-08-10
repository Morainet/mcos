package com.mcos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcos.runtime.ir.ParseResult
import com.mcos.runtime.parse.DslParser

/**
 * MCOS Android shell — MVP Compose UI.
 * Matches [01-architecture.md §6.1].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                McosShell()
            }
        }
    }
}

@Composable
fun McosShell() {
    var input by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DslResult>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCOS Shell") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            // Input
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Enter command DSL...") },
                placeholder = { Text("e.g. camera.capture()") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Parse button
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val result = DslParser.parse(input)
                        results = results + DslResult(
                            input = input,
                            success = result is ParseResult.Ok,
                            output = when (result) {
                                is ParseResult.Ok -> DslParser.toJson(result.ir)
                                is ParseResult.Err -> "${result.code}: ${result.message} (line ${result.line}, col ${result.column})"
                            }
                        )
                        input = ""
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Run")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Results
            Text("Results", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn {
                items(results.reversed()) { dslResult ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (dslResult.success)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = dslResult.input,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dslResult.output,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

data class DslResult(
    val input: String,
    val success: Boolean,
    val output: String
)
