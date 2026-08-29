package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.GuardianApp
import com.example.ui.GuardianViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {
  private val viewModel: GuardianViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        GuardianApp(viewModel = viewModel)
      }
    }
  }
}

