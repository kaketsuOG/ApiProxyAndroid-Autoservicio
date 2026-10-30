package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.ConfigManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.HttpClient
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PosSessionRoutes(
    private val context: Context,
    private val logger: LoggerService
) {
    private val client = HttpClient.createUnsafeClient()
    private val gson = Gson()
    private val sessionsCache = mutableMapOf<String, Pair<Map<String, Any>, Long>>()
    private val sessionCacheTTL = 60 * 60 * 1000L

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession,
        odooUrl: String
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/pos/sessions/validate" && method == NanoHTTPD.Method.POST ->
                validateSession(session, odooUrl)
            uri == "/api/pos/sessions/close" && method == NanoHTTPD.Method.POST ->
                closeSession(session, odooUrl)
            uri == "/api/pos/sessions/status" && method == NanoHTTPD.Method.GET ->
                getSessionsStatus()
            uri == "/api/pos/sessions/clear-cache" && method == NanoHTTPD.Method.POST ->
                clearCache()
            uri.startsWith("/api/pos/session-state") && method == NanoHTTPD.Method.GET ->
                checkSessionState(session, odooUrl)
            uri == "/api/pos/balanza-rules" && method == NanoHTTPD.Method.GET ->
                getBalanzaRules(odooUrl)
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun validateSession(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            val body = session.getBodyAsMap()
            val pin = body["pin"]?.toString() ?: ""

            if (pin.isEmpty()) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "PIN requerido"))
                )
            }

            val cached = sessionsCache[pin]
            if (cached != null && (System.currentTimeMillis() - cached.second) < sessionCacheTTL) {
                logger.info("Sesión encontrada en cache")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    gson.toJson(mapOf(
                        "success" to true,
                        "data" to cached.first,
                        "source" to "cache"
                    ))
                )
            }

            val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/pos_validate_session")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: "{}"
            val sessionData = gson.fromJson(responseData, Map::class.java) as Map<String, Any>

            if (sessionData["authenticated"] == true) {
                sessionsCache[pin] = Pair(sessionData, System.currentTimeMillis())
                logger.success("Sesión validada y cacheada")
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "data" to sessionData,
                    "source" to "odoo"
                ))
            )
        } catch (e: Exception) {
            logger.error("Error validando sesión: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun closeSession(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            val body = session.getBodyAsMap()

            val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/pos_close_session")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: "{}"

            sessionsCache.clear()
            logger.info("Cache de sesiones limpiado tras cierre")

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                responseData
            )
        } catch (e: Exception) {
            logger.error("Error cerrando sesión: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun checkSessionState(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            // La ruta Odoo /pos_session_state NO existe (devolvía 404 HTML → la app
            // nunca detectaba el cierre). Usamos /check_session_exists, que SÍ existe:
            // recibe el PIN y devuelve {"session_exists": bool}. Lo traducimos a
            // {"is_open": bool} que es lo que la app espera.
            val pin = ConfigManager.getPosPin()
            if (pin.isEmpty()) {
                logger.warn("session-state: POS_PIN vacío, asumo sesión abierta")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "application/json",
                    gson.toJson(mapOf("is_open" to true))
                )
            }

            val reqBody = gson.toJson(mapOf("pin" to pin))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/check_session_exists")
                .post(reqBody)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: "{}"

            // Ante cualquier duda (error de red/parseo) asumimos abierta para no
            // expulsar al usuario por error.
            val isOpen = try {
                val map = gson.fromJson(responseStr, Map::class.java)
                (map?.get("session_exists") as? Boolean) ?: true
            } catch (e: Exception) {
                logger.warn("session-state: respuesta no parseable ($responseStr), asumo abierta")
                true
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json",
                gson.toJson(mapOf("is_open" to isOpen))
            )
        } catch (e: Exception) {
            logger.error("Error verificando estado de sesión: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }

    private fun getSessionsStatus(): NanoHTTPD.Response {
        val sessions = sessionsCache.map { (pin, data) ->
            val ageMinutes = (System.currentTimeMillis() - data.second) / 1000 / 60
            val remainingMinutes = (sessionCacheTTL - (System.currentTimeMillis() - data.second)) / 1000 / 60

            mapOf(
                "pin_masked" to "*".repeat(pin.length),
                "age_minutes" to ageMinutes,
                "remaining_minutes" to maxOf(0, remainingMinutes)
            )
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(mapOf(
                "total_cached_sessions" to sessionsCache.size,
                "sessions" to sessions
            ))
        )
    }

    private fun getBalanzaRules(odooUrl: String): NanoHTTPD.Response {
        return try {
            val request = Request.Builder()
                .url("$odooUrl/pos_balanza_rules")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: """{"success":true,"rules":[]}"""
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", responseBody)
        } catch (e: Exception) {
            logger.error("Error obteniendo reglas de balanza: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                """{"success":true,"rules":[]}"""
            )
        }
    }

    private fun clearCache(): NanoHTTPD.Response {
        val count = sessionsCache.size
        sessionsCache.clear()
        logger.info("Cache de sesiones limpiado manualmente ($count sesiones)")

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(mapOf(
                "success" to true,
                "sessions_removed" to count
            ))
        )
    }
}