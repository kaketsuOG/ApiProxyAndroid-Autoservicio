package cl.posgo.apiproxyandroid

import android.content.Context
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.routes.*
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import cl.posgo.apiproxyandroid.services.BackgroundProcessor
import cl.posgo.apiproxyandroid.services.LoggerService
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.IOException

class ApiServer(
    private val context: Context,
    private val port: Int = 9000
) : NanoHTTPD(port) {

    companion object {
        var instance: ApiServer? = null
            private set
    }

    private val gson = Gson()
    private val database = DatabaseManager.getInstance(context)
    val logger = LoggerService(context)
    private val backgroundProcessor = BackgroundProcessor(context, database, logger)

    private val autoservicioRoutes = AutoservicioRoutes(context, database, logger)
    private val proxyRoutes = ProxyRoutes(context, logger)
    private val posSessionRoutes = PosSessionRoutes(context, logger)
    private val ordersRoutes = OrdersRoutes(context, database, logger)
    private val logsRoutes = LogsRoutes(context, logger)
    private val imagesRoutes = ImagesRoutes(context, database, logger)
    private val criticalErrorsRoutes = CriticalErrorsRoutes(context, logger)
    private val syncStatusRoutes = SyncStatusRoutes(context, database, logger)
    private val transactionsRoutes = TransactionsRoutes(context, database, logger)
    private val combosRoutes = CombosRoutes(context)
    private val promotionsRoutes = PromotionsRoutes(logger)
    private val scanReportRoutes = ScanReportRoutes(logger)

    private var odooUrl = ""
    private var xsignUrl = ""
    private var kdsUrl = ""
    private var mpUrl = ""

    init {
        ConfigManager.initialize(context)
        loadConfig()
        // Si quedó un servidor anterior escuchando, deténlo antes de tomar su lugar.
        // (Evita "Address already in use" al reocupar el puerto $port.)
        instance?.let { old ->
            if (old !== this) {
                try { old.stopServer() } catch (_: Exception) {}
            }
        }
        instance = this
    }

    private fun loadConfig() {
        val config = ConfigManager.getConfig()
        odooUrl = config.odooUrl
        xsignUrl = config.xsignUrl
        kdsUrl = config.kdsUrl
        mpUrl = config.mpUrl
    }

    private fun addCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCors(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
        }

        if (!uri.contains("/getWeight") &&
            !uri.contains("/api/logs/stream") &&
            !uri.contains("/display/") &&
            !uri.contains("/images/")) {
            logger.info("${method.name} $uri")
        }

        return addCors(when {
            uri == "/" && method == Method.GET -> handleRoot()
            uri == "/health" && method == Method.GET -> handleHealth()

            uri == "/authenticate_user" && method == Method.POST ->
                proxyRoutes.authenticateUser(session, odooUrl)
            uri == "/pos_validate_session" && method == Method.POST ->
                proxyRoutes.posValidateSession(session, odooUrl)
            uri == "/check_session_exists" && method == Method.POST ->
                proxyRoutes.checkSessionExists(session, odooUrl)
            uri == "/pos_close_session" && method == Method.POST ->
                proxyRoutes.posCloseSession(session, odooUrl)
            uri == "/pos/cash_drawer_password" && method == Method.GET ->
                proxyRoutes.getCashDrawerPassword(session, odooUrl)

            uri.startsWith("/api/autoservicio/") ->
                runBlocking { autoservicioRoutes.handle(uri, method, session, xsignUrl, kdsUrl, mpUrl) }
            uri.startsWith("/api/pos/") ->
                posSessionRoutes.handle(uri, method, session, odooUrl)
            uri.startsWith("/api/orders/") ->
                ordersRoutes.handle(uri, method, session)
            uri.startsWith("/api/logs/") ->
                logsRoutes.handle(uri, method, session)
            uri.startsWith("/images/") ->
                imagesRoutes.handle(uri, method, session, odooUrl)
            uri.startsWith("/api/critical-errors") ->
                criticalErrorsRoutes.handle(uri, method, session)
            uri.startsWith("/api/sync-status") ->
                syncStatusRoutes.handle(uri, method, session)
            uri.startsWith("/api/transactions") ->
                transactionsRoutes.handle(uri, method, session)
            uri.startsWith("/api/promotions") ->
                promotionsRoutes.handle(uri, method, session, odooUrl)
            uri == "/api/scan-no-encontrado" ->
                scanReportRoutes.handle(uri, method, session)

            uri == "/api/config" && method == Method.GET -> handleGetConfig()
            uri == "/api/config" && method == Method.POST -> handlePostConfig(session)

            uri == "/api/combos/list" && method == Method.GET ->
                combosRoutes.handleGetCombosList(session)
            uri == "/api/combos/refresh" && method == Method.POST ->
                combosRoutes.handleRefreshCache(session)
            uri.matches(Regex("/api/combos/\\d+")) && method == Method.GET -> {
                val comboId = uri.removePrefix("/api/combos/")
                combosRoutes.handleGetComboDetail(session, comboId)
            }
            uri.matches(Regex("/api/products/\\d+/combo-groups")) && method == Method.GET -> {
                val productId = uri.split("/")[3]
                combosRoutes.handleGetProductComboGroups(session, productId)
            }
            uri.matches(Regex("/api/products/\\d+/suggestions")) && method == Method.GET -> {
                val productId = uri.split("/")[3]
                combosRoutes.handleGetProductSuggestions(session, productId)
            }
            uri.matches(Regex("/api/products/\\d+/banner")) && method == Method.GET -> {
                val productId = uri.split("/")[3]
                combosRoutes.handleGetProductBanner(session, productId)
            }
            uri.matches(Regex("/api/products/\\d+/banner")) && method == Method.POST -> {
                val productId = uri.split("/")[3]
                combosRoutes.handleCacheProductBanner(session, productId)
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Endpoint not found"))
            )
        })
    }

    private fun handleGetConfig(): Response {
        val config = ConfigManager.getConfig()
        val localPin = config.posPin
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(mapOf(
                "ODOO_URL" to config.odooUrl,
                "XSIGN_URL" to config.xsignUrl,
                "KDS_URL" to config.kdsUrl,
                "PRINTER_TICKET_NAME" to config.printerName,
                "N8N_WEBHOOK_URL" to config.n8nWebhookUrl,
                "MP_URL" to config.mpUrl,
                "POS_PIN" to if (localPin.isNotEmpty()) "*".repeat(localPin.length) else "",
                // Ni la URL ni la key se exponen acá: la caja no las necesita (el proxy
                // agrega la key al reenviar) y este endpoint es público en la red local.
                // Solo se informa si el aviso está configurado o no.
                "SCAN_REPORT_URL" to if (config.scanReportUrl.isNotEmpty()) "***" else "",
                "SCAN_REPORT_KEY" to if (config.scanReportKey.isNotEmpty()) "***" else ""
            ))
        )
    }

    private fun handlePostConfig(session: IHTTPSession): Response {
        return try {
            @Suppress("UNCHECKED_CAST")
            val bodyMap = session.getBodyAsMap() as? Map<String, Any> ?: emptyMap()
            val allowed = setOf("ODOO_URL", "XSIGN_URL", "KDS_URL", "PRINTER_TICKET_NAME", "N8N_WEBHOOK_URL", "MP_URL", "POS_PIN", "SCAN_REPORT_URL", "SCAN_REPORT_KEY")
            val updates = bodyMap.filterKeys { it in allowed }
                .mapValues { it.value.toString() }
                // "***" es lo que devuelve el GET para los campos ocultos: si alguien
                // reenvía la config leída, no queremos guardar la máscara como valor.
                .filterValues { it != "***" }

            if (updates.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "No se enviaron campos válidos"))
                )
            }

            ConfigManager.updateConfig(
                odooUrl = updates["ODOO_URL"],
                xsignUrl = updates["XSIGN_URL"],
                kdsUrl = updates["KDS_URL"],
                printerName = updates["PRINTER_TICKET_NAME"],
                n8nWebhookUrl = updates["N8N_WEBHOOK_URL"],
                mpUrl = updates["MP_URL"],
                posPin = updates["POS_PIN"],
                scanReportUrl = updates["SCAN_REPORT_URL"],
                scanReportKey = updates["SCAN_REPORT_KEY"]
            )
            loadConfig()
            logger.success("Configuración actualizada: ${updates.keys.joinToString()}")

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(mapOf("success" to true, "updated" to updates.keys.toList()))
            )
        } catch (e: Exception) {
            logger.error("Error actualizando config: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun handleRoot(): Response {
        val endpoints = mapOf(
            "name" to "API Intermedia Autoservicio Android",
            "version" to "1.0.0",
            "status" to "running",
            "endpoints" to listOf(
                "POST /authenticate_user",
                "POST /pos_validate_session",
                "POST /check_session_exists",
                "POST /pos_close_session",
                "POST /api/autoservicio/create-order",
                "POST /api/autoservicio/create-order-im30",
                "GET /api/autoservicio/printers/list",
                "GET /api/orders/display",
                "POST /api/orders/update-status",
                "POST /api/orders/pos",
                "GET /api/orders/stats",
                "GET /api/orders/history",
                "GET /api/orders/delivered",
                "POST /api/pos/sessions/validate",
                "POST /api/pos/sessions/close",
                "GET /api/pos/sessions/status",
                "POST /api/pos/sessions/clear-cache",
                "GET /api/logs/history",
                "GET /api/logs/stats",
                "POST /api/logs/test",
                "GET /api/sync-status",
                "GET /api/sync-status/debug",
                "GET /api/critical-errors",
                "POST /api/critical-errors/clear/:id",
                "POST /api/critical-errors/clear-all",
                "GET /images/products/:productId",
                "DELETE /images/cache",
                "GET /images/cache/stats",
                "GET /images/categories/:categoryId",
                "POST /images/cache/categories/:categoryId",
                "GET /api/combos/list?session_id={id}",
                "GET /api/combos/{id}",
                "GET /api/products/{id}/combo-groups",
                "GET /api/products/{id}/suggestions",
                "GET /api/products/{id}/banner",
                "POST /api/products/{id}/banner",
                "POST /api/combos/refresh",
                "GET /api/promotions?pos_config_id={id}",
                "GET /api/promotions/refresh?pos_config_id={id}",
                "POST /api/scan-no-encontrado",
                "GET /api/config",
                "POST /api/config",
                "GET /health"
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(endpoints))
    }

    private fun handleHealth(): Response {
        val health = mapOf(
            "status" to "healthy",
            "timestamp" to System.currentTimeMillis()
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(health))
    }

    fun startServer() {
        try {
            loadConfig()

            // Si esta misma instancia ya estaba escuchando, ciérrala antes de reabrir.
            if (isAlive) {
                try { stop() } catch (_: Exception) {}
                Thread.sleep(300)
            }

            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: java.net.BindException) {
                // El puerto sigue ocupado por un socket previo: esperar y reintentar una vez.
                logger.warn("Puerto $port ocupado, liberando y reintentando...")
                Thread.sleep(800)
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }

            backgroundProcessor.start()
            logger.success("API Intermedia iniciada en puerto $port")
            logger.info("URL: http://localhost:$port")
            logger.info("ODOO_URL: $odooUrl")
            logger.info("XSIGN_URL: $xsignUrl")
            logger.info("KDS_URL: $kdsUrl")
            logger.info("MP_URL: $mpUrl")
        } catch (e: IOException) {
            logger.error("Error iniciando servidor: ${e.message}")
        }
    }

    fun stopServer() {
        backgroundProcessor.stop()
        stop()
        logger.info("Servidor detenido")
    }
}