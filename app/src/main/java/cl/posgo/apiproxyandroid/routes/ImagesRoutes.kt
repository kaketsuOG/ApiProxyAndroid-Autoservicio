package cl.posgo.apiproxyandroid.routes

import android.content.Context
import android.util.Base64
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import cl.posgo.apiproxyandroid.utils.HttpClient
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import okhttp3.Request

class ImagesRoutes(
    private val context: Context,
    private val database: DatabaseManager,
    private val logger: LoggerService
) {
    private val gson = Gson()
    private val client = HttpClient.createUnsafeClient()

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession,
        odooUrl: String = ""
    ): NanoHTTPD.Response {
        if (method == NanoHTTPD.Method.OPTIONS) {
            return newOptionsResponse()
        }
        return when {
            uri.startsWith("/images/products/") && method == NanoHTTPD.Method.GET ->
                getProductImage(uri, odooUrl)
            uri == "/images/cache" && method == NanoHTTPD.Method.DELETE ->
                clearCache()
            uri == "/images/cache/stats" && method == NanoHTTPD.Method.GET ->
                getCacheStats()
            uri.startsWith("/images/cache/categories/") && method == NanoHTTPD.Method.DELETE ->
                deleteCategoryImageCache(uri)
            uri.startsWith("/images/cache/categories/") && method == NanoHTTPD.Method.POST ->
                cacheCategoryImage(uri, session)
            uri.startsWith("/images/cache/") && method == NanoHTTPD.Method.POST ->
                cacheImage(uri, session)
            uri.startsWith("/images/categories/") && method == NanoHTTPD.Method.GET ->
                getCategoryImage(uri, odooUrl)
            else -> newJsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                mapOf("error" to "Not found")
            )
        }
    }

    private fun getProductImage(uri: String, odooUrl: String = ""): NanoHTTPD.Response {
        return try {
            val productId = uri.substringAfterLast("/").toIntOrNull() ?: 0

            if (productId == 0) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "Invalid product ID")
                )
            }

            // 1) Cache local
            var base64 = database.getCachedImage(productId)

            // 2) Si no está en cache, se pide a Odoo y se cachea (igual que categorías)
            if (base64 == null && odooUrl.isNotEmpty()) {
                logger.info("IMG $productId: CACHE MISS → fetching desde Odoo")
                base64 = fetchProductImageFromOdoo(odooUrl, productId)
                if (base64 != null) {
                    database.cacheImage(productId, base64, "image/jpeg")
                    logger.info("IMG $productId: Odoo OK → cacheado")
                }
            }

            if (base64 != null) {
                val imageBytes = Base64.decode(base64, Base64.DEFAULT)
                val response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "image/jpeg",
                    imageBytes.inputStream() as java.io.InputStream,
                    imageBytes.size.toLong()
                )
                response.addHeader("Cache-Control", "public, max-age=86400")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type")
                return response
            }

            logger.info("Imagen $productId no disponible")
            newJsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                mapOf("error" to "Imagen no disponible")
            )
        } catch (e: Exception) {
            logger.error("Error sirviendo imagen: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("error" to e.message)
            )
        }
    }

    private fun fetchProductImageFromOdoo(odooUrl: String, productId: Int): String? {
        return try {
            val request = Request.Builder()
                .url("$odooUrl/web/image/product.product/$productId/image_256")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                logger.info("Odoo no tiene imagen para producto $productId (${response.code})")
                return null
            }
            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.startsWith("image/")) {
                logger.info("Odoo devolvió contenido no-imagen para producto $productId ($contentType)")
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            logger.error("Error obteniendo imagen de producto $productId desde Odoo: ${e.message}")
            null
        }
    }

    private fun cacheImage(uri: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val productId = uri.substringAfterLast("/").toIntOrNull() ?: 0

            if (productId == 0) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "Invalid product ID")
                )
            }

            val body = session.getBodyAsMap()
            val imageData = body["imageData"] as? String ?: ""
            val mimeType = body["mimeType"] as? String ?: "image/jpeg"

            if (imageData.isEmpty()) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "imageData is required")
                )
            }

            database.cacheImage(productId, imageData, mimeType)
            logger.info("Imagen $productId cacheada")

            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to true))
        } catch (e: Exception) {
            logger.error("Error cacheando imagen: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("error" to e.message)
            )
        }
    }

    private fun getCategoryImage(uri: String, odooUrl: String): NanoHTTPD.Response {
        return try {
            val categoryId = uri.substringAfterLast("/").toIntOrNull() ?: 0

            if (categoryId == 0) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "Invalid category ID")
                )
            }

            val cached = database.getCategoryImageFromCache(categoryId)

            if (cached != null) {
                logger.info("CAT_IMG $categoryId: CACHE HIT (${cached.length} chars)")
                return newJsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    mapOf("success" to true, "image_base64" to cached)
                )
            }

            if (odooUrl.isNotEmpty()) {
                val odooImgUrl = "$odooUrl/web/image/product.category/$categoryId/category_image"
                logger.info("CAT_IMG $categoryId: CACHE MISS → fetching $odooImgUrl")
                val imageBase64 = fetchCategoryImageFromOdoo(odooUrl, categoryId)
                if (imageBase64 != null) {
                    database.saveCategoryImageCache(categoryId, imageBase64)
                    logger.info("CAT_IMG $categoryId: Odoo OK (${imageBase64.length} chars) → cacheado")
                    return newJsonResponse(
                        NanoHTTPD.Response.Status.OK,
                        mapOf("success" to true, "image_base64" to imageBase64)
                    )
                }
                logger.warn("CAT_IMG $categoryId: Odoo no devolvió imagen")
            }

            logger.warn("CAT_IMG $categoryId: NO DISPONIBLE")
            newJsonResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                mapOf("error" to "Imagen de categoría no disponible")
            )
        } catch (e: Exception) {
            logger.error("CAT_IMG error: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("error" to e.message)
            )
        }
    }

    private fun fetchCategoryImageFromOdoo(odooUrl: String, categoryId: Int): String? {
        return try {
            val request = Request.Builder()
                .url("$odooUrl/web/image/product.category/$categoryId/category_image")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.info("Odoo no tiene imagen para categoría $categoryId (${response.code})")
                return null
            }

            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.startsWith("image/")) {
                logger.info("Odoo devolvió contenido no-imagen para categoría $categoryId ($contentType) — posible redirect a login")
                return null
            }

            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            logger.error("Error obteniendo imagen de categoría $categoryId desde Odoo: ${e.message}")
            null
        }
    }

    private fun cacheCategoryImage(uri: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val categoryId = uri.substringAfterLast("/").toIntOrNull() ?: 0

            if (categoryId == 0) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "Invalid category ID")
                )
            }

            val body = session.getBodyAsMap()
            val imageBase64 = body["imageData"] as? String ?: ""

            if (imageBase64.isEmpty()) {
                return newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "imageData is required")
                )
            }

            database.saveCategoryImageCache(categoryId, imageBase64)
            logger.info("Imagen categoría $categoryId cacheada")

            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to true))
        } catch (e: Exception) {
            logger.error("Error cacheando imagen de categoría: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("error" to e.message)
            )
        }
    }

    private fun deleteCategoryImageCache(uri: String): NanoHTTPD.Response {
        return try {
            val categoryId = uri.substringAfterLast("/").toIntOrNull() ?: 0
            if (categoryId == 0) return newJsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, mapOf("error" to "Invalid category ID"))
            database.deleteCategoryImageCache(categoryId)
            logger.info("CAT_IMG $categoryId: CACHE DELETED")
            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to true))
        } catch (e: Exception) {
            logger.error("CAT_IMG delete error: ${e.message}")
            newJsonResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    private fun clearCache(): NanoHTTPD.Response {
        return try {
            database.clearImageCache()
            logger.info("Cache de imágenes limpiado")
            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("success" to true, "message" to "Cache limpiado"))
        } catch (e: Exception) {
            logger.error("Error limpiando cache: ${e.message}")
            newJsonResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    private fun getCacheStats(): NanoHTTPD.Response {
        return try {
            newJsonResponse(NanoHTTPD.Response.Status.OK, mapOf("cached_images" to 0, "total_size_mb" to 0))
        } catch (e: Exception) {
            logger.error("Error obteniendo stats: ${e.message}")
            newJsonResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    private fun newJsonResponse(status: NanoHTTPD.Response.Status, data: Any): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, "application/json", gson.toJson(data))
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }

    private fun newOptionsResponse(): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{}")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}