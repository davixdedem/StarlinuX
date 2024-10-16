package com.magix.pistarlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.magix.pistarlink.ui.home.MyVpnServiceCallback
import com.magix.pistarlink.ui.home.WgTunnel
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException

class MyVpnService : Service() {

    private var tunnel: WgTunnel? = null
    private var callback: MyVpnServiceCallback? = null
    private lateinit var dbHandler: DbHandler
    private val binder = MyBinder()

    inner class MyBinder : Binder() {
        fun getService(): MyVpnService = this@MyVpnService
    }

    companion object {
        private var serviceCallback: MyVpnServiceCallback? = null

        fun setCallback(cb: MyVpnServiceCallback) {
            serviceCallback = cb
        }

        fun clearCallback() {
            serviceCallback = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHandler = DbHandler(this)
        tunnel = WgTunnel()  // Initialize the tunnel here
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWireguard()
        return START_STICKY
    }

    private fun connectWireguard() {
        Log.d("Wireguard-Handler", "Connecting Wireguard as a foreground service")
        startForegroundService()

        val intentPrepare: Intent? = GoBackend.VpnService.prepare(this)
        if (intentPrepare != null) {
            startActivity(intentPrepare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return  // Wait for the result before proceeding
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = retrieveVPNConfiguration()
                if (config != null) {
                    setupTunnel(config)
                    notifyConnectionSuccess()
                } else {
                    notifyConnectionFailure("Configuration retrieval failed.")
                }
            } catch (e: Exception) {
                Log.e("Wireguard-Handler", "Failed to establish VPN connection: ${e.message}")
                notifyConnectionFailure(e.message ?: "Unknown error")
            }
        }
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(1, notification)
    }

    private suspend fun retrieveVPNConfiguration(): Config? {
        val privateKey = dbHandler.getConfiguration("PrivateKey") ?: ""
        val addresses = dbHandler.getConfiguration("Address")?.split(",")?.map { it.trim() } ?: listOf()
        val dns = dbHandler.getConfiguration("DNS") ?: ""
        val publicKey = dbHandler.getConfiguration("PublicKey") ?: ""
        val presharedKey = dbHandler.getConfiguration("PresharedKey") ?: ""
        val endpoint = dbHandler.getConfiguration("Endpoint") ?: ""
        val allowedIPs = dbHandler.getConfiguration("AllowedIPs") ?: "0.0.0.0/0"
        val persistentKeepalive = dbHandler.getConfiguration("PersistentKeepalive")?.toIntOrNull() ?: 25

        return try {
            Config.Builder()
                .setInterface(
                    Interface.Builder()
                        .addAddress(InetNetwork.parse(addresses.getOrNull(0) ?: ""))
                        .addAddress(InetNetwork.parse(addresses.getOrNull(1) ?: ""))
                        .parsePrivateKey(privateKey)
                        .addDnsServer(InetAddress.getByName(dns))
                        .build()
                )
                .addPeer(
                    Peer.Builder()
                        .addAllowedIp(InetNetwork.parse(allowedIPs))
                        .setEndpoint(InetEndpoint.parse(endpoint))
                        .parsePublicKey(publicKey)
                        .parsePreSharedKey(presharedKey)
                        .setPersistentKeepalive(persistentKeepalive)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            Log.e("Wireguard-Handler", "Error parsing VPN configuration: ${e.message}")
            null
        }
    }

    private fun setupTunnel(config: Config) {
        tunnel?.let {
            GoBackend(this).setState(it, Tunnel.State.UP, config)
            Log.d("Wireguard-Handler", "VPN connected successfully.")
        }
    }

    private suspend fun notifyConnectionSuccess() {
        withContext(Dispatchers.Main) {
            dbHandler.addConfiguration("lastVPNUsed", "Wireguard")
            callback?.onVpnStatusUpdated(true)
        }
    }

    private suspend fun notifyConnectionFailure(message: String) {
        withContext(Dispatchers.Main) {
            Log.e("Wireguard-Handler", message)
            callback?.onVpnStatusUpdated(false)
        }
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "WireguardForegroundService"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Wireguard VPN Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Wireguard VPN")
            .setContentText("Wireguard VPN is running")
            .setSmallIcon(R.drawable.wg_icon)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    fun disconnectWireguard() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tunnel?.let {
                    Log.d("Wireguard-Handler", "Attempting to disconnect VPN...")
                    val newState = GoBackend(this@MyVpnService).setState(it, Tunnel.State.DOWN, null)
                    if (newState == Tunnel.State.DOWN) {
                        this.coroutineContext.cancelChildren()
                        withContext(Dispatchers.Main) {
                            Log.d("Wireguard-Handler", "VPN disconnected successfully.")
                            stopForeground(true)
                            stopSelf()
                        }
                    } else {
                        Log.e("Wireguard-Handler", "Failed to change VPN state to DOWN.")
                    }
                } ?: run {
                    Log.e("Wireguard-Handler", "Tunnel is null. Cannot disconnect VPN.")
                }
            } catch (e: CancellationException) {
                Log.d("Wireguard-Handler", "Disconnection was interrupted.")
            } catch (e: Exception) {
                Log.e("Wireguard-Handler", "Failed to disconnect the VPN: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        disconnectWireguard()
        super.onDestroy()
    }

    fun setCallback(callback: MyVpnServiceCallback) {
        this.callback = callback
    }
}
