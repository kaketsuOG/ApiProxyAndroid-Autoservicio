package cl.posgo.apiproxyandroid

import android.content.Context
import android.content.SharedPreferences

object ConfigManager {

    private const val PREFS_NAME = "api_config"

    private const val KEY_ODOO_URL = "ODOO_URL"
    private const val KEY_XSIGN_URL = "XSIGN_URL"
    private const val KEY_KDS_URL = "KDS_URL"
    private const val KEY_PRINTER_NAME = "PRINTER_TICKET_NAME"
    private const val KEY_N8N_WEBHOOK = "N8N_WEBHOOK_URL"
    private const val KEY_MP_URL = "MP_URL"
    private const val KEY_POS_PIN = "POS_PIN"
    private const val KEY_SCAN_REPORT_URL = "SCAN_REPORT_URL"
    private const val KEY_SCAN_REPORT_KEY = "SCAN_REPORT_KEY"

    private const val DEFAULT_ODOO_URL = "https://getit.posgo.cl"
    private const val DEFAULT_XSIGN_URL = "http://localhost:5999"
    private const val DEFAULT_KDS_URL = "http://192.168.7.172:9001"
    private const val DEFAULT_PRINTER_NAME = "POS"
    private const val DEFAULT_MP_URL = "http://localhost:8001"

    // API de Get It para avisar códigos escaneados que no están en el catálogo.
    // Vienen precargadas: solo hay que tocarlas si Get It cambia la URL o rota la key.
    // Dejar la key vacía desactiva el aviso (el proxy responde OK igual y no reenvía).
    private const val DEFAULT_SCAN_REPORT_URL = "https://prod-apps.getitchile.cl/admin/api/posgo/scan-no-encontrado"
    private const val DEFAULT_SCAN_REPORT_KEY = "0feaea051c55694c9f6f741bc1865f06e3fe47475025d09c"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Config(
        val odooUrl: String,
        val xsignUrl: String,
        val kdsUrl: String,
        val printerName: String,
        val n8nWebhookUrl: String,
        val mpUrl: String,
        val posPin: String,
        val scanReportUrl: String,
        val scanReportKey: String
    )

    fun getConfig(): Config {
        return Config(
            odooUrl = prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL) ?: DEFAULT_ODOO_URL,
            xsignUrl = prefs.getString(KEY_XSIGN_URL, DEFAULT_XSIGN_URL) ?: DEFAULT_XSIGN_URL,
            kdsUrl = prefs.getString(KEY_KDS_URL, DEFAULT_KDS_URL) ?: DEFAULT_KDS_URL,
            printerName = prefs.getString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME) ?: DEFAULT_PRINTER_NAME,
            n8nWebhookUrl = prefs.getString(KEY_N8N_WEBHOOK, "") ?: "",
            mpUrl = prefs.getString(KEY_MP_URL, DEFAULT_MP_URL) ?: DEFAULT_MP_URL,
            posPin = prefs.getString(KEY_POS_PIN, "") ?: "",
            scanReportUrl = prefs.getString(KEY_SCAN_REPORT_URL, DEFAULT_SCAN_REPORT_URL) ?: DEFAULT_SCAN_REPORT_URL,
            scanReportKey = prefs.getString(KEY_SCAN_REPORT_KEY, DEFAULT_SCAN_REPORT_KEY) ?: DEFAULT_SCAN_REPORT_KEY
        )
    }

    fun getPosPin(): String = prefs.getString(KEY_POS_PIN, "") ?: ""

    fun updateConfig(
        odooUrl: String? = null,
        xsignUrl: String? = null,
        kdsUrl: String? = null,
        printerName: String? = null,
        n8nWebhookUrl: String? = null,
        mpUrl: String? = null,
        posPin: String? = null,
        scanReportUrl: String? = null,
        scanReportKey: String? = null
    ) {
        prefs.edit().apply {
            odooUrl?.let { putString(KEY_ODOO_URL, it) }
            xsignUrl?.let { putString(KEY_XSIGN_URL, it) }
            kdsUrl?.let { putString(KEY_KDS_URL, it) }
            printerName?.let { putString(KEY_PRINTER_NAME, it) }
            n8nWebhookUrl?.let { putString(KEY_N8N_WEBHOOK, it) }
            mpUrl?.let { putString(KEY_MP_URL, it) }
            posPin?.let { putString(KEY_POS_PIN, it) }
            scanReportUrl?.let { putString(KEY_SCAN_REPORT_URL, it) }
            scanReportKey?.let { putString(KEY_SCAN_REPORT_KEY, it) }
            apply()
        }
    }

    fun isConfigured(): Boolean {
        val config = getConfig()
        return config.odooUrl.isNotEmpty()
    }

    fun resetToDefaults() {
        prefs.edit().apply {
            putString(KEY_ODOO_URL, DEFAULT_ODOO_URL)
            putString(KEY_XSIGN_URL, DEFAULT_XSIGN_URL)
            putString(KEY_KDS_URL, DEFAULT_KDS_URL)
            putString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME)
            putString(KEY_N8N_WEBHOOK, "")
            putString(KEY_MP_URL, DEFAULT_MP_URL)
            putString(KEY_SCAN_REPORT_URL, DEFAULT_SCAN_REPORT_URL)
            putString(KEY_SCAN_REPORT_KEY, DEFAULT_SCAN_REPORT_KEY)
            apply()
        }
    }
}