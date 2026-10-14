package com.andyyhope.featureflag.sample.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme

/** Shows [DemoScreen] over the model DemoApplication built in onCreate. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as DemoApplication).model
        setContent {
            MaterialTheme {
                DemoScreen(model)
            }
        }
    }
}
