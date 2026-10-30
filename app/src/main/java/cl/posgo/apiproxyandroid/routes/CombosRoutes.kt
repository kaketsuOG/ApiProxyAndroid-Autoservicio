package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.CombosService
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class CombosRoutes(private val context: Context) {

    private val logger = LoggerService(context)
    private val combosService = CombosService(context)
    private val gson = Gson()

    fun handleGetCombosList(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val params = session.parameters
            val sessionIdStr = params["session_id"]?.firstOrNull()
            val refreshStr = params["refresh"]?.firstOrNull() ?: "false"

            if (sessionIdStr == null) {
                logger.warn("session_id no proporcionado en /api/combos/list")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf(
                        "success" to false,
                        "error" to "session_id es requerido"
                    )
                )
            }

            val sessionId = try {
                sessionIdStr.toInt()
            } catch (e: NumberFormatException) {
                logger.error("session_id inválido: $sessionIdStr")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf(
                        "success" to false,
                        "error" to "session_id debe ser un número"
                    )
                )
            }

            val forceRefresh = refreshStr.toBoolean()

            logger.info("GET /api/combos/list - session_id=$sessionId, refresh=$forceRefresh")

            val result = combosService.getCombos(sessionId, forceRefresh)

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                newJsonResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, result)
            }
        } catch (e: Exception) {
            logger.error("Error en /api/combos/list: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleGetComboDetail(session: NanoHTTPD.IHTTPSession, comboId: String): NanoHTTPD.Response {
        return try {
            val params = session.parameters
            val refreshStr = params["refresh"]?.firstOrNull() ?: "false"

            val comboIdInt = try {
                comboId.toInt()
            } catch (e: NumberFormatException) {
                logger.error("combo_id inválido: $comboId")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("success" to false, "error" to "combo_id debe ser un número")
                )
            }

            val forceRefresh = refreshStr.toBoolean()

            logger.info("GET /api/combos/$comboId - refresh=$forceRefresh")

            val result = combosService.getComboDetail(comboIdInt, forceRefresh)

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                val status = if (result["error"]?.toString()?.contains("no encontrado") == true) {
                    NanoHTTPD.Response.Status.NOT_FOUND
                } else {
                    NanoHTTPD.Response.Status.INTERNAL_ERROR
                }
                newJsonResponse(status, result)
            }
        } catch (e: Exception) {
            logger.error("Error en /api/combos/$comboId: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleGetProductComboGroups(session: NanoHTTPD.IHTTPSession, productId: String): NanoHTTPD.Response {
        return try {
            val productIdInt = productId.toIntOrNull() ?: return newJsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                mapOf("success" to false, "error" to "product_id debe ser un número")
            )
            val forceRefresh = session.parameters["refresh"]?.firstOrNull()?.toBoolean() ?: false

            logger.info("GET /api/products/$productId/combo-groups - refresh=$forceRefresh")

            val result = combosService.getProductComboGroups(productIdInt, forceRefresh)

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                val status = if (result["error"]?.toString()?.contains("no encontrado") == true) {
                    NanoHTTPD.Response.Status.NOT_FOUND
                } else {
                    NanoHTTPD.Response.Status.INTERNAL_ERROR
                }
                newJsonResponse(status, result)
            }
        } catch (e: Exception) {
            logger.error("Error en /api/products/$productId/combo-groups: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleGetProductSuggestions(session: NanoHTTPD.IHTTPSession, productId: String): NanoHTTPD.Response {
        return try {
            val params = session.parameters
            val refreshStr = params["refresh"]?.firstOrNull() ?: "false"

            val productIdInt = try {
                productId.toInt()
            } catch (e: NumberFormatException) {
                logger.error("product_id inválido: $productId")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("success" to false, "error" to "product_id debe ser un número")
                )
            }

            val forceRefresh = refreshStr.toBoolean()

            logger.info("GET /api/products/$productId/suggestions - refresh=$forceRefresh")

            val result = combosService.getProductSuggestions(productIdInt, forceRefresh)

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                val status = if (result["error"]?.toString()?.contains("no encontrado") == true) {
                    NanoHTTPD.Response.Status.NOT_FOUND
                } else {
                    NanoHTTPD.Response.Status.INTERNAL_ERROR
                }
                newJsonResponse(status, result)
            }
        } catch (e: Exception) {
            logger.error("Error en /api/products/$productId/suggestions: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleGetProductBanner(session: NanoHTTPD.IHTTPSession, productId: String): NanoHTTPD.Response {
        return try {
            val params = session.parameters
            val refreshStr = params["refresh"]?.firstOrNull() ?: "false"

            val productIdInt = try {
                productId.toInt()
            } catch (e: NumberFormatException) {
                logger.error("product_id inválido: $productId")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("success" to false, "error" to "product_id debe ser un número")
                )
            }

            val forceRefresh = refreshStr.toBoolean()

            logger.info("GET /api/products/$productId/banner - refresh=$forceRefresh")

            val result = combosService.getProductBanner(productIdInt, forceRefresh)

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                // Siempre 200 cuando no hay banner — evita logs de error en el browser
                newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to false, "banner" to null))
            }
        } catch (e: Exception) {
            logger.error("Error en /api/products/$productId/banner: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleCacheProductBanner(session: NanoHTTPD.IHTTPSession, productId: String): NanoHTTPD.Response {
        return try {
            val productIdInt = productId.toIntOrNull() ?: return newJsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                mapOf("success" to false, "error" to "product_id debe ser un número")
            )

            val body = session.getBodyAsMap()
            val bannerBase64 = body["banner_base64"] as? String ?: ""

            if (bannerBase64.isEmpty()) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("success" to false, "error" to "banner_base64 es requerido")
                )
            }

            val db = DatabaseManager.getInstance(context)
            val saved = db.saveProductBannerCache(productIdInt, bannerBase64)
            logger.info("Banner de producto $productIdInt cacheado desde frontend")

            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to saved))
        } catch (e: Exception) {
            logger.error("Error cacheando banner: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    fun handleRefreshCache(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            logger.info("POST /api/combos/refresh - Limpiando caché de combos")

            val result = combosService.clearCache()

            if (result["success"] == true) {
                newJsonResponse(NanoHTTPD.Response.Status.OK, result)
            } else {
                newJsonResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, result)
            }
        } catch (e: Exception) {
            logger.error("Error en /api/combos/refresh: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("success" to false, "error" to "Error interno: ${e.message}")
            )
        }
    }

    private fun newJsonResponse(status: NanoHTTPD.Response.Status, data: Any): NanoHTTPD.Response {
        val jsonString = gson.toJson(data)
        val response = NanoHTTPD.newFixedLengthResponse(status, "application/json", jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}