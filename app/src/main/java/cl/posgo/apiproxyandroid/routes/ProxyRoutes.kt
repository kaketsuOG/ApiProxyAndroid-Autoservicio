package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.ConfigManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.HttpClient
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsString
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProxyRoutes(
    private val context: Context,
    private val logger: LoggerService
) {
    private val client = HttpClient.createUnsafeClient()
    private val gson = Gson()

    /**
     * Reenvía a Odoo la clave diaria de caja (módulo pos_cash_drawer_password).
     * La usa el autoservicio para autorizar productos con alcohol. El endpoint de
     * Odoo es público (auth='public'); el proxy lo expone internamente para que la
     * tablet no necesite conocer la URL de Odoo.
     */
    fun getCashDrawerPassword(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            logger.info("Proxy: /pos/cash_drawer_password → Odoo")
            val companyId = session.parameters["company_id"]?.firstOrNull()
            val url = if (companyId.isNullOrBlank()) {
                "$odooUrl/pos/cash_drawer_password"
            } else {
                "$odooUrl/pos/cash_drawer_password?company_id=$companyId"
            }
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val responseBody = response.body
            if (responseBody != null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseBody.byteStream(),
                    responseBody.contentLength()
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
            }
        } catch (e: Exception) {
            logger.error("Error en proxy /pos/cash_drawer_password: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    fun authenticateUser(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            logger.info("Proxy: /authenticate_user → Odoo")
            val body = session.getBodyAsString()

            val requestBody = body.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/authenticate_user")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body
            return if (responseBody != null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseBody.byteStream(),
                    responseBody.contentLength()
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
            }
        } catch (e: Exception) {
            logger.error("Error en proxy /authenticate_user: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    fun posValidateSession(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            logger.info("Proxy: /pos_validate_session → verificando PIN local")
            val body = session.getBodyAsString()

            // Verificar PIN local antes de forwarding a Odoo
            val localPin = ConfigManager.getPosPin()
            if (localPin.isNotEmpty()) {
                val bodyMap = gson.fromJson(body, Map::class.java) as? Map<*, *>
                val pinIngresado = bodyMap?.get("pin")?.toString()?.trim() ?: ""
                logger.info("PIN local configurado: ${"*".repeat(localPin.length)}")
                if (pinIngresado != localPin) {
                    logger.error("PIN rechazado por validación local")
                    return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.UNAUTHORIZED,
                        "application/json",
                        gson.toJson(mapOf("success" to false, "error" to "PIN incorrecto"))
                    )
                }
                logger.info("PIN local verificado correctamente")
            }

            logger.info("Proxy: /pos_validate_session → Odoo")
            val requestBody = body.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/pos_validate_session")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body
            return if (responseBody != null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseBody.byteStream(),
                    responseBody.contentLength()
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
            }
        } catch (e: Exception) {
            logger.error("Error en proxy /pos_validate_session: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    fun checkSessionExists(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            logger.info("Proxy: /check_session_exists → Odoo")
            val body = session.getBodyAsString()

            val requestBody = body.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/check_session_exists")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body
            return if (responseBody != null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseBody.byteStream(),
                    responseBody.contentLength()
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
            }
        } catch (e: Exception) {
            logger.error("Error en proxy /check_session_exists: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf("session_exists" to false))
            )
        }
    }

    fun posCloseSession(session: NanoHTTPD.IHTTPSession, odooUrl: String): NanoHTTPD.Response {
        return try {
            logger.info("Proxy: /pos_close_session → Odoo")
            val body = session.getBodyAsString()

            val requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder()
                .url("$odooUrl/pos_close_session")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body
            return if (responseBody != null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    responseBody.byteStream(),
                    responseBody.contentLength()
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
            }
        } catch (e: Exception) {
            logger.error("Error en proxy /pos_close_session: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }
}