package com.actionstarter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.navigation.ActionStarterNavHost

/**
 * App entry point (real 5-screen navigation graph wired in C5). [MainScreen] below is the
 * original C1 bootstrap composable, kept unused by [onCreate] but still directly exercised by
 * `SmokeComposeTest` (must not be removed/changed — see that test's own scope).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ActionStarterNavHost()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stringResource(id = R.string.app_name))
                Text(text = stringResource(id = R.string.hello_smoke))
            }
        }
    }
}
