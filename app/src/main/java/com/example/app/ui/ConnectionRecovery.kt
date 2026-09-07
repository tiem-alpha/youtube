package com.example.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Retry only on a validated connection or a return to the foreground. */
@Composable
fun rememberRecoveryTrigger(): Int {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var trigger by remember { mutableIntStateOf(0) }
    DisposableEffect(context, owner) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val handler = Handler(Looper.getMainLooper())
        var active = true
        var connected = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val valid = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                handler.post {
                    if (active) {
                        if (valid && !connected) trigger++
                        connected = valid
                    }
                }
            }
            override fun onLost(network: Network) { handler.post { if (active) connected = false } }
        }
        manager.registerDefaultNetworkCallback(callback)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) trigger++
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            active = false
            manager.unregisterNetworkCallback(callback)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return trigger
}
