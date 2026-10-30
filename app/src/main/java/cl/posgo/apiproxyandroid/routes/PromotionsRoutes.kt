package cl.posgo.apiproxyandroid.routes

import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.HttpClient
import fi.iki.elonen.NanoHTTPD
import okhttp3.Request

class PromotionsRoutes(
    private val logger: LoggerService
) {
    private val client = HttpClient.createUnsafeClient()
    private val promotionsCache = mutableMapOf<String, Pair<String, Long>>()
    private val CACHE_TTL = 60 * 60 * 1000L // 1 hora

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession,
        odooUrl: String
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/promotions" && method == NanoHTTPD.Method.GET ->
                handleGetPromotions(session, odooUrl)
            uri == "/api/promotions/refresh" && method == NanoHTTPD.Method.GET ->
                handleRefreshPromotions(session, odooUrl)
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                "{\"error\": \"Ruta de promociones no encontrada\"}"
            )
        }
    }

    private fun handleGetPromotions(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            val posConfigId = session.parameters["pos_config_id"]?.firstOrNull()
            if (posConfigId.isNullOrEmpty()) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"success\": false, \"error\": \"pos_config_id es requerido\"}")
            }

            val cached = promotionsCache[posConfigId]
            if (cached != null && (System.currentTimeMillis() - cached.second) < CACHE_TTL) {
                logger.info("Promotions desde cache (config: $posConfigId)")
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", cached.first)
            }

            logger.info("Fetching promotions desde Odoo (config: $posConfigId)")
            val request = Request.Builder()
                .url("$odooUrl/xsolution_loyalty/promotions?pos_config_id=$posConfigId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: "[]"

            if (response.isSuccessful) {
                promotionsCache[posConfigId] = Pair(responseData, System.currentTimeMillis())
            }

            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", responseData)
        } catch (error: Exception) {
            logger.error("Error obteniendo promotions: ${error.message}")
            val cached = promotionsCache[session.parameters["pos_config_id"]?.firstOrNull() ?: ""]
            if (cached != null) {
                logger.warn("Devolviendo cache expirado como fallback")
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", cached.first)
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"success\": false, \"error\": \"${error.message}\"}")
        }
    }

    private fun handleRefreshPromotions(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            val posConfigId = session.parameters["pos_config_id"]?.firstOrNull()
            if (posConfigId.isNullOrEmpty()) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", "{\"success\": false, \"error\": \"pos_config_id es requerido\"}")
            }

            logger.info("Refresh forzado de promotions (config: $posConfigId)")
            val request = Request.Builder()
                .url("$odooUrl/xsolution_loyalty/promotions?pos_config_id=$posConfigId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: "[]"

            if (response.isSuccessful) {
                promotionsCache[posConfigId] = Pair(responseData, System.currentTimeMillis())
                logger.success("Promotions actualizadas (config: $posConfigId)")
            }

            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{\"success\": true, \"message\": \"Cache actualizado\", \"data\": $responseData}")
        } catch (error: Exception) {
            logger.error("Error en refresh de promotions: ${error.message}")
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", "{\"success\": false, \"error\": \"${error.message}\"}")
        }
    }
}