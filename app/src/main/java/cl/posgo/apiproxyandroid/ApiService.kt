package cl.posgo.apiproxyandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ApiService : Service() {

    private var apiServer: ApiServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "api_proxy_channel"

        fun start(context: Context) {
            val intent = Intent(context, ApiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ApiService::class.java)
            context.stopService(intent)
        }

        fun getLogger() = ApiServer.instance?.logger
    }

    override fun onCreate() {
        super.onCreate()
        ConfigManager.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Iniciando..."))

        // Evita que dos arranques simultáneos intenten enlazar el puerto a la vez.
        if (starting.compareAndSet(false, true)) {
            serviceScope.launch {
                try {
                    initializeService()
                } catch (e: Exception) {
                    updateNotification("Error: ${e.message}", isError = true)
                } finally {
                    starting.set(false)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        apiServer?.stopServer()
        super.onDestroy()
    }

    private suspend fun initializeService() {
        try {
            // Detener cualquier servidor anterior (local o el global) para liberar el puerto 9000.
            try { apiServer?.stopServer() } catch (_: Exception) {}
            try { ApiServer.instance?.stopServer() } catch (_: Exception) {}
            apiServer = null

            updateNotification("Iniciando servidor...")

            delay(800)

            apiServer = ApiServer(applicationContext, 9000)
            apiServer?.startServer()

            delay(1000)

            updateNotification("Servicio activo - localhost:9000")

        } catch (e: Exception) {
            updateNotification("Error: ${e.message}", isError = true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicio API Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "API Proxy para Autoservicio"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String, isError: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("API Proxy Android")
            .setContentText(message)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String, isError: Boolean = false) {
        val notification = createNotification(message, isError)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}