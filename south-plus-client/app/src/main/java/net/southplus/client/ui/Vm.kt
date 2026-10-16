package net.southplus.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** 轻量 ViewModel 构造（配合 AppGraph 手工装配）。 */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(noinline create: () -> VM): VM {
    val factory = remember { viewModelFactory { initializer { create() } } }
    return viewModel(factory = factory)
}
