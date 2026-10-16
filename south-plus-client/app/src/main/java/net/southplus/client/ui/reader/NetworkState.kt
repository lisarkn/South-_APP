package net.southplus.client.ui.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

@Composable
internal fun rememberWifiState(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) {
        callbackFlow {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun publish() {
                trySend(manager.getNetworkCapabilities(manager.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish()
                override fun onLost(network: Network) = publish()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            publish()
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.conflate().flowOn(Dispatchers.IO)
    }
    return flow.collectAsStateWithLifecycle(initialValue = false)
}
