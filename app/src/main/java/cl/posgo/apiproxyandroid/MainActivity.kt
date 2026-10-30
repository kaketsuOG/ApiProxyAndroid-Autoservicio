package cl.posgo.apiproxyandroid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cl.posgo.apiproxyandroid.services.LoggerService
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var configStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var toggleConfigButton: Button
    private lateinit var saveConfigButton: Button
    private lateinit var configFieldsLayout: LinearLayout
    private lateinit var clearLogsButton: Button
    private lateinit var logsTextView: TextView
    private lateinit var logsScrollView: ScrollView

    private lateinit var odooUrlInput: TextInputEditText
    private lateinit var xsignUrlInput: TextInputEditText
    private lateinit var kdsUrlInput: TextInputEditText
    private lateinit var printerNameInput: TextInputEditText
    private lateinit var n8nWebhookInput: TextInputEditText
    private lateinit var posPinInput: TextInputEditText
    private lateinit var scanReportUrlInput: TextInputEditText
    private lateinit var scanReportKeyInput: TextInputEditText

    private var configVisible = false
    private var lastLogCount = 0

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.i("ApiProxyAndroid", "Permiso de notificación concedido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.initialize(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        initializeViews()
        setupListeners()
        loadConfigToUI()
        updateConfigStatus()

        startApiService()
        startLogsPolling()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        configStatusText = findViewById(R.id.configStatusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        toggleConfigButton = findViewById(R.id.toggleConfigButton)
        saveConfigButton = findViewById(R.id.saveConfigButton)
        configFieldsLayout = findViewById(R.id.configFieldsLayout)
        clearLogsButton = findViewById(R.id.clearLogsButton)
        logsTextView = findViewById(R.id.logsTextView)
        logsScrollView = findViewById(R.id.logsScrollView)

        odooUrlInput = findViewById(R.id.odooUrlInput)
        xsignUrlInput = findViewById(R.id.xsignUrlInput)
        kdsUrlInput = findViewById(R.id.kdsUrlInput)
        printerNameInput = findViewById(R.id.printerNameInput)
        n8nWebhookInput = findViewById(R.id.n8nWebhookInput)
        posPinInput = findViewById(R.id.posPinInput)
        scanReportUrlInput = findViewById(R.id.scanReportUrlInput)
        scanReportKeyInput = findViewById(R.id.scanReportKeyInput)
    }

    private fun setupListeners() {
        startButton.setOnClickListener {
            startApiService()
        }

        stopButton.setOnClickListener {
            stopApiService()
        }

        toggleConfigButton.setOnClickListener {
            toggleConfigVisibility()
        }

        saveConfigButton.setOnClickListener {
            saveConfiguration()
        }

        clearLogsButton.setOnClickListener {
            ApiService.getLogger()?.let { log ->
                // Accedemos al buffer y lo vaciamos indirectamente reseteando el contador
                lastLogCount = log.getRealtimeBuffer().size
            }
            logsTextView.text = ""
            lastLogCount = 0
        }
    }

    private fun loadConfigToUI() {
        val config = ConfigManager.getConfig()
        odooUrlInput.setText(config.odooUrl)
        xsignUrlInput.setText(config.xsignUrl)
        kdsUrlInput.setText(config.kdsUrl)
        printerNameInput.setText(config.printerName)
        n8nWebhookInput.setText(config.n8nWebhookUrl)
        posPinInput.setText(config.posPin)
        scanReportUrlInput.setText(config.scanReportUrl)
        scanReportKeyInput.setText(config.scanReportKey)
    }

    private fun updateConfigStatus() {
        val config = ConfigManager.getConfig()
        val pinStatus = if (config.posPin.isNotEmpty()) "Configurado (${"*".repeat(config.posPin.length)})" else "Sin restricción"
        configStatusText.text = """
            Odoo: ${config.odooUrl}
            XSign: ${config.xsignUrl}
            KDS: ${config.kdsUrl}
            Impresora: ${config.printerName}
            PIN POS: $pinStatus
            Avisos código no encontrado: ${if (config.scanReportKey.isNotEmpty()) "Activos" else "Desactivados"}
        """.trimIndent()
    }

    private fun toggleConfigVisibility() {
        configVisible = !configVisible
        configFieldsLayout.visibility = if (configVisible) View.VISIBLE else View.GONE
        toggleConfigButton.text = if (configVisible) "Ocultar" else "Editar"
    }

    private fun saveConfiguration() {
        ConfigManager.updateConfig(
            odooUrl = odooUrlInput.text.toString(),
            xsignUrl = xsignUrlInput.text.toString(),
            kdsUrl = kdsUrlInput.text.toString(),
            printerName = printerNameInput.text.toString(),
            n8nWebhookUrl = n8nWebhookInput.text.toString(),
            posPin = posPinInput.text.toString(),
            scanReportUrl = scanReportUrlInput.text.toString(),
            scanReportKey = scanReportKeyInput.text.toString()
        )

        updateConfigStatus()
        configVisible = false
        configFieldsLayout.visibility = View.GONE
        toggleConfigButton.text = "Editar"

        statusText.text = "Configuración guardada\nReinicie el servicio para aplicar cambios"
    }

    private fun startApiService() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "Iniciando servicio..."
            ApiService.start(this@MainActivity)
            delay(2000)
            statusText.text = "Servidor ejecutándose en:\nhttp://localhost:9000"
            startButton.isEnabled = false
            stopButton.isEnabled = true
        }
    }

    private fun stopApiService() {
        ApiService.stop(this)
        statusText.text = "Servidor detenido"
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun startLogsPolling() {
        CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1500)
                refreshLogs()
            }
        }
    }

    private fun refreshLogs() {
        val logger = ApiService.getLogger() ?: return
        val entries = logger.getRealtimeBuffer()
        if (entries.size == lastLogCount) return

        val sb = SpannableStringBuilder()
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

        for (entry in entries) {
            val time = try {
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).parse(entry.timestamp)
                if (parsed != null) fmt.format(parsed) else "--:--:--"
            } catch (e: Exception) { "--:--:--" }

            val color = when (entry.level) {
                "success" -> Color.parseColor("#4CAF50")
                "warn"    -> Color.parseColor("#FFC107")
                "error"   -> Color.parseColor("#F44336")
                else      -> Color.parseColor("#B0BEC5")
            }

            val line = "[$time] ${entry.message}\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        lastLogCount = entries.size
        logsTextView.text = sb
        logsScrollView.post { logsScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}