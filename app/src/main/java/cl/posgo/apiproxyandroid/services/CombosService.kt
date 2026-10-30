package cl.posgo.apiproxyandroid.services

import android.content.Context
import cl.posgo.apiproxyandroid.ConfigManager
import cl.posgo.apiproxyandroid.database.DatabaseManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.OutputStreamWriter
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CombosService(private val context: Context) {
    private val logger = LoggerService(context)
    private val database = DatabaseManager.getInstance(context)
    private val gson = Gson()

    companion object {
        private const val CACHE_VALIDITY_HOURS = 24
    }

    private fun getOdooUrl(): String {
        return ConfigManager.getConfig().odooUrl
    }

    private fun openSslConnection(urlStr: String): HttpsURLConnection {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())

        val connection = URL(urlStr).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        return connection
    }

    fun getCombos(sessionId: Int, forceRefresh: Boolean = false): Map<String, Any> {
        return try {
            if (!forceRefresh) {
                val cachedCombos = database.getCombosFromCache(sessionId)
                if (cachedCombos != null) {
                    logger.info("Combos obtenidos desde caché: ${cachedCombos.size}")
                    return mapOf(
                        "success" to true,
                        "combos" to cachedCombos,
                        "count" to cachedCombos.size,
                        "from_cache" to true
                    )
                }
            }

            logger.info("Consultando combos desde Odoo...")
            val odooResponse = fetchCombosFromOdoo(sessionId)

            if (odooResponse["success"] == true) {
                val combos = odooResponse["combos"] as? List<Map<String, Any>> ?: emptyList()
                if (combos.isNotEmpty()) {
                    database.saveCombosCache(sessionId, combos)
                }
                logger.info("Combos obtenidos desde Odoo: ${combos.size}")
                return mapOf(
                    "success" to true,
                    "combos" to combos,
                    "count" to combos.size,
                    "from_cache" to false
                )
            } else {
                logger.error("Error en respuesta de Odoo: ${odooResponse["error"]}")
                return mapOf("success" to false, "error" to (odooResponse["error"] ?: "Error desconocido"))
            }
        } catch (e: Exception) {
            logger.error("Error obteniendo combos: ${e.message}")
            return mapOf("success" to false, "error" to "Error interno: ${e.message}")
        }
    }

    private fun fetchCombosFromOdoo(sessionId: Int): Map<String, Any> {
        return try {
            val connection = openSslConnection("${getOdooUrl()}/get_combos")
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 30000
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(gson.toJson(mapOf("session_id" to sessionId)))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()

            if (responseCode == 200) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(responseBody, type)
            } else {
                mapOf("success" to false, "error" to "HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            logger.error("Error consultando Odoo: ${e.message}")
            mapOf("success" to false, "error" to e.message.orEmpty())
        }
    }

    fun getComboDetail(comboId: Int, forceRefresh: Boolean = false): Map<String, Any> {
        return try {
            if (!forceRefresh) {
                val cachedCombo = database.getComboDetailFromCache(comboId)
                if (cachedCombo != null) {
                    logger.info("Combo $comboId obtenido desde caché")
                    return mapOf("success" to true, "combo" to cachedCombo, "from_cache" to true)
                }
            }

            logger.info("Consultando detalle de combo $comboId desde Odoo...")
            val odooResponse = fetchComboDetailFromOdoo(comboId)

            if (odooResponse["success"] == true) {
                logger.info("Detalle de combo $comboId obtenido desde Odoo")
                return mapOf(
                    "success" to true,
                    "combo" to (odooResponse["combo"] ?: emptyMap<String, Any>()),
                    "from_cache" to false
                )
            } else {
                logger.error("Error en respuesta de Odoo: ${odooResponse["error"]}")
                return mapOf("success" to false, "error" to (odooResponse["error"] ?: "Error desconocido"))
            }
        } catch (e: Exception) {
            logger.error("Error obteniendo detalle de combo: ${e.message}")
            return mapOf("success" to false, "error" to "Error interno: ${e.message}")
        }
    }

    private fun fetchComboDetailFromOdoo(comboId: Int): Map<String, Any> {
        return try {
            val connection = openSslConnection("${getOdooUrl()}/get_combo_detail")
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 30000
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(gson.toJson(mapOf("combo_id" to comboId)))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()

            if (responseCode == 200) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(responseBody, type)
            } else {
                mapOf("success" to false, "error" to "HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            logger.error("Error consultando Odoo: ${e.message}")
            mapOf("success" to false, "error" to e.message.orEmpty())
        }
    }

    fun getProductSuggestions(productId: Int, forceRefresh: Boolean = false): Map<String, Any> {
        return try {
            if (!forceRefresh) {
                val cachedSuggestions = database.getProductSuggestionsFromCache(productId)
                if (cachedSuggestions != null) {
                    logger.info("Sugerencias de producto $productId obtenidas desde caché")
                    return mapOf(
                        "success" to true,
                        "suggested_products" to cachedSuggestions["suggested_products"]!!,
                        "suggested_combos" to cachedSuggestions["suggested_combos"]!!,
                        "from_cache" to true
                    )
                }
            }

            logger.info("Consultando sugerencias de producto $productId desde Odoo...")
            val odooResponse = fetchProductSuggestionsFromOdoo(productId)

            if (odooResponse["success"] == true) {
                database.saveProductSuggestionsCache(productId, odooResponse)
                logger.info("Sugerencias de producto $productId obtenidas desde Odoo")
                return mapOf(
                    "success" to true,
                    "suggested_products" to (odooResponse["suggested_products"] ?: emptyList<Any>()),
                    "suggested_combos" to (odooResponse["suggested_combos"] ?: emptyList<Any>()),
                    "from_cache" to false
                )
            } else {
                logger.error("Error en respuesta de Odoo: ${odooResponse["error"]}")
                return mapOf("success" to false, "error" to (odooResponse["error"] ?: "Error desconocido"))
            }
        } catch (e: Exception) {
            logger.error("Error obteniendo sugerencias: ${e.message}")
            return mapOf("success" to false, "error" to "Error interno: ${e.message}")
        }
    }

    private fun fetchProductSuggestionsFromOdoo(productId: Int): Map<String, Any> {
        return try {
            val connection = openSslConnection("${getOdooUrl()}/get_product_suggestions")
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 30000
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(gson.toJson(mapOf("product_id" to productId)))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()

            if (responseCode == 200) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(responseBody, type)
            } else {
                mapOf("success" to false, "error" to "HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            logger.error("Error consultando Odoo: ${e.message}")
            mapOf("success" to false, "error" to e.message.orEmpty())
        }
    }

    fun getProductComboGroups(productId: Int, forceRefresh: Boolean = false): Map<String, Any> {
        return try {
            if (!forceRefresh) {
                val cached = database.getComboGroupsFromCache(productId)
                if (cached != null) {
                    logger.info("Combo groups de producto $productId obtenidos desde caché")
                    return cached
                }
            }

            logger.info("Consultando combo groups de producto $productId desde Odoo...")
            val connection = openSslConnection("${getOdooUrl()}/get_product_combo_groups")
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(gson.toJson(mapOf("product_id" to productId)))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()

            if (responseCode == 200) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val result: Map<String, Any> = gson.fromJson(responseBody, type)
                if (result["success"] == true) {
                    val hasCombos = result["has_combos"] == true
                    val comboGroups = result["combo_groups"] as? List<*> ?: emptyList<Any>()
                    database.saveComboGroupsCache(productId, hasCombos, comboGroups)
                }
                logger.info("Combo groups de producto $productId obtenidos desde Odoo")
                result
            } else {
                mapOf("success" to false, "error" to "HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            logger.error("Error consultando combo groups: ${e.message}")
            mapOf("success" to false, "error" to e.message.orEmpty())
        }
    }

    fun getProductBanner(productId: Int, forceRefresh: Boolean = false): Map<String, Any> {
        return try {
            if (!forceRefresh) {
                val cachedBanner = database.getProductBannerFromCache(productId)
                if (cachedBanner != null) {
                    logger.info("Banner de producto $productId obtenido desde caché")
                    return mapOf(
                        "success" to true,
                        "product_id" to productId,
                        "banner_base64" to cachedBanner,
                        "from_cache" to true
                    )
                }
            }

            logger.info("Consultando banner de producto $productId desde Odoo...")
            val odooResponse = fetchProductBannerFromOdoo(productId)

            if (odooResponse["success"] == true) {
                val bannerBase64 = odooResponse["banner_base64"] as? String
                if (bannerBase64 != null) {
                    database.saveProductBannerCache(productId, bannerBase64)
                }
                logger.info("Banner de producto $productId obtenido desde Odoo")
                return mapOf(
                    "success" to true,
                    "product_id" to productId,
                    "banner_base64" to (bannerBase64 ?: ""),
                    "from_cache" to false
                )
            } else {
                logger.error("Error en respuesta de Odoo: ${odooResponse["error"]}")
                return mapOf("success" to false, "error" to (odooResponse["error"] ?: "Error desconocido"))
            }
        } catch (e: Exception) {
            logger.error("Error obteniendo banner: ${e.message}")
            return mapOf("success" to false, "error" to "Error interno: ${e.message}")
        }
    }

    private fun fetchProductBannerFromOdoo(productId: Int): Map<String, Any> {
        return try {
            val connection = openSslConnection("${getOdooUrl()}/get_product_banner")
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 30000
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(gson.toJson(mapOf("product_id" to productId)))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()

            if (responseCode == 200) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(responseBody, type)
            } else {
                mapOf("success" to false, "error" to "HTTP $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            logger.error("Error consultando Odoo: ${e.message}")
            mapOf("success" to false, "error" to e.message.orEmpty())
        }
    }

    fun clearCache(): Map<String, Any> {
        return try {
            val success = database.clearCombosCache()
            mapOf(
                "success" to success,
                "message" to if (success) "Caché limpiado" else "Error limpiando caché"
            )
        } catch (e: Exception) {
            logger.error("Error limpiando caché: ${e.message}")
            mapOf("success" to false, "error" to "Error interno: ${e.message}")
        }
    }
}