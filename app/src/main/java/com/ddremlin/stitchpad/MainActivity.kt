package com.ddremlin.stitchpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ddremlin.stitchpad.ui.EditorScreen
import com.ddremlin.stitchpad.ui.EditorViewModel

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StitchTheme { EditorScreen(vm) } }
    }

    override fun onStop() {
        super.onStop()
        vm.autosave()
    }
}

private val Rose = Color(0xFFB0415A)

@Composable
fun StitchTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFFB1C1), primaryContainer = Color(0xFF7A2940))
    } else {
        lightColorScheme(primary = Rose, primaryContainer = Color(0xFFFFD9DF))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
