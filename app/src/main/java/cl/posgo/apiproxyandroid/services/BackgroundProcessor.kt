package cl.posgo.apiproxyandroid.services

import android.content.Context
import cl.posgo.apiproxyandroid.ConfigManager
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.database.Transaction
import cl.posgo.apiproxyandroid.utils.Fechas
import cl.posgo.apiproxyandroid.utils.HttpClient
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round

class BackgroundProcessor(
    private val context: Context,
    private val database: DatabaseManager,
    private val logger: LoggerService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessing = false
    private val client = HttpClient.createUnsafeClient()
    private val gson = Gson()

    companion object {
        private val INTERNAL_VOUCHER_METHODS = mapOf(
            7 to "E",
            8 to "P",
            9 to "A"
        )
    }

    fun start() {
        logger.info("Iniciando procesador background (cada 30s)")

        scope.launch {
            delay(5000)

            while (isActive) {
                try {
                    processBackground()
                } catch (e: Exception) {
                    logger.error("Error en procesador background: ${e.message}")
                }
                delay(30000)
            }
        }

        logger.success("Procesador background iniciado")
    }

    fun stop() {
        scope.cancel()
        logger.info("Procesador background detenido")
    }

    private suspend fun processBackground() {
        if (isProcessing) {
            return
        }

        isProcessing = true

        try {
            val pendingTransactions = database.getPendingTransactions()

            if (pendingTransactions.isEmpty()) {
                logger.info("No hay transacciones pendientes")
                return
            }

            logger.info("${pendingTransactions.size} transacciones pendientes")

            for (transaction in pendingTransactions) {
                try {
                    processTransaction(transaction)
                } catch (e: Exception) {
                    logger.error("Error procesando transacción ${transaction.id}: ${e.message}")
                    database.markAsFailed(transaction.id.toInt(), e.message ?: "Error desconocido")
                }
            }
        } finally {
            isProcessing = false
        }
    }

    private fun isInternalVoucherTransaction(transactionData: Map<*, *>): Boolean {
        val saleData = transactionData["sale_data"] as? Map<*, *> ?: return false
        val payments = saleData["payments"] as? List<*> ?: return false

        if (payments.isEmpty()) return false

        val paymentIds = payments.mapNotNull { payment ->
            val p = payment as? Map<*, *>
            (p?.get("id") as? Number)?.toInt()
        }

        if (paymentIds.isEmpty()) return false

        return paymentIds.all { INTERNAL_VOUCHER_METHODS.containsKey(it) }
    }

    private suspend fun processTransaction(transaction: Transaction) {
        logger.info("Procesando transacción ID: ${transaction.id}")

        val transactionData = gson.fromJson(transaction.transactionData, Map::class.java) as Map<*, *>

        val isVoucher = isInternalVoucherTransaction(transactionData)
        var dteResponse: Map<String, Any>? = null
        var voucherNumber: String? = transaction.internalVoucherNumber

        if (isVoucher) {
            logger.info("═══════════════════════════════════════════════════════════")
            logger.info(" VALE INTERNO DETECTADO - NO SE GENERARÁ DTE")
            logger.info("═══════════════════════════════════════════════════════════")

            if (voucherNumber == null) {
                val saleData = transactionData["sale_data"] as? Map<*, *>
                val payments = saleData?.get("payments") as? List<*>
                val firstPayment = payments?.firstOrNull() as? Map<*, *>
                val paymentId = (firstPayment?.get("id") as? Number)?.toInt() ?: 7

                val prefix = INTERNAL_VOUCHER_METHODS[paymentId] ?: "V"
                val sessionData = transactionData["session_data"] as? Map<*, *>
                val sessionId = (sessionData?.get("session_id") as? Number)?.toInt() ?: 0

                voucherNumber = database.getNextInternalVoucherNumber(prefix, sessionId)
                logger.info("Número de vale generado: $voucherNumber")
            } else {
                logger.info("Número de vale existente: $voucherNumber")
            }

            logger.info("═══════════════════════════════════════════════════════════")
        } else {
            // Si el DTE ya fue generado inline por AutoservicioRoutes, reutilizarlo
            if (!transaction.dteResponse.isNullOrEmpty()) {
                logger.info("DTE ya generado inline - Folio: ${transaction.folioDte} - reutilizando sin regenerar")
                @Suppress("UNCHECKED_CAST")
                dteResponse = gson.fromJson(transaction.dteResponse, Map::class.java) as Map<String, Any>
            } else {
                dteResponse = generateDTE(transactionData, transaction)

                if (dteResponse == null) {
                    logger.error("Error generando DTE para transacción ${transaction.id}")
                    database.markAsFailed(transaction.id.toInt(), "Error generando DTE")
                    return
                }

                logger.success("DTE generado - Folio: ${dteResponse["folio"]}")

                database.saveDTEResponse(
                    transactionId = transaction.id.toInt(),
                    folio = (dteResponse["folio"] as? Number)?.toLong()?.toString() ?: dteResponse["folio"].toString(),
                    dteResponse = dteResponse
                )
            }
        }

        val odooResponse = sendToOdoo(transactionData, dteResponse, transaction, isVoucher, voucherNumber)

        if (odooResponse == null) {
            logger.error("Error enviando a Odoo transacción ${transaction.id}")
            database.markAsFailed(transaction.id.toInt(), "Error enviando a Odoo")
            return
        }

        logger.success("Transacción ${transaction.id} enviada a Odoo correctamente")

        database.markAsCompleted(transaction.id.toInt())

        if (isVoucher) {
            logger.success("Transacción ${transaction.id} completada (vale interno $voucherNumber)")
        } else {
            logger.success("Transacción ${transaction.id} completada")
        }
    }

    // created_at de la cola = instante de la venta (epoch en segundos, sin zona horaria).
    private fun saleMillisOf(transaction: Transaction): Long? =
        transaction.createdAt.takeIf { it > 0 }?.let { it * 1000 }

    private fun buildDTEData(
        transactionData: Map<*, *>,
        sessionData: Map<*, *>,
        saleMillis: Long? = null
    ): HashMap<String, Any> {
        val orders = transactionData["orders"] as? List<*> ?: emptyList<Any>()
        val firstOrder = orders.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val products = firstOrder["products"] as? List<*> ?: emptyList<Any>()

        val totalAmount = products.sumOf { product ->
            val p = product as Map<*, *>
            (p["price_subtotal"] as? Number)?.toDouble() ?: 0.0
        }

        val mntNeto = round(totalAmount / 1.19).toInt()
        val iva = round(totalAmount - mntNeto).toInt()
        val mntTotal = round(totalAmount).toInt()

        val companyData = sessionData["company_data"] as? Map<*, *>

        // Fecha Chile del momento de la VENTA (no de cuando la cola genera el DTE) y con zona
        // explícita (antes tomaba la del equipo: en UTC, desde las 21:00 salía el día siguiente).
        val currentDate = Fechas.fechaChile(saleMillis ?: System.currentTimeMillis())

        val detalleItems = products.mapIndexed { index, product ->
            val p = product as Map<*, *>
            val productId = (p["product_id"] as? Number)?.toInt() ?: (999000 + index)
            val qty = (p["qty"] as? Number)?.toInt() ?: 1
            val priceSubtotal = (p["price_subtotal"] as? Number)?.toDouble() ?: 0.0
            val unitPrice = if (qty > 0) round(priceSubtotal / qty).toInt() else 0

            val cdgItem = hashMapOf<String, Any>(
                "TpoCodigo" to "INT1",
                "VlrCodigo" to productId.toString()
            )

            hashMapOf<String, Any>(
                "NroLinDet" to (index + 1),
                "CdgItem" to cdgItem,
                "NmbItem" to (p["name"] ?: "Producto ${index + 1}"),
                "DscItem" to (p["customization"] ?: "N/A"),
                "QtyItem" to qty,
                "PrcItem" to unitPrice,
                "MontoItem" to round(priceSubtotal).toInt()
            )
        }

        val idDoc = hashMapOf<String, Any>(
            "TipoDTE" to 39,
            "FchEmis" to currentDate
        )

        val emisor = hashMapOf<String, Any>(
            "RUTEmisor" to (companyData?.get("vat") ?: "76.000.000-0"),
            "RznSoc" to (companyData?.get("name") ?: "AUTOSERVICIO"),
            "GiroEmis" to (companyData?.get("turn") ?: "COMERCIO"),
            "Acteco" to (companyData?.get("acteco") ?: "471100"),
            "DirOrigen" to (companyData?.get("street") ?: "N/A"),
            "CmnaOrigen" to (companyData?.get("city") ?: "N/A"),
            "CdgVendedor" to "autoservicio"
        )

        val receptor = hashMapOf<String, Any>(
            "RUTRecep" to "66666666-6",
            "RznSocRecep" to "CLIENTE AUTOSERVICIO",
            "DirRecep" to "N/A",
            "CmnaRecep" to "N/A"
        )

        val totales = hashMapOf<String, Any>(
            "MntNeto" to mntNeto,
            "IVA" to iva,
            "MntTotal" to mntTotal
        )

        val encabezado = hashMapOf<String, Any>(
            "IdDoc" to idDoc,
            "Emisor" to emisor,
            "Receptor" to receptor,
            "Totales" to totales
        )

        val paymentMethodType = (firstOrder["payment"] as? List<*>)
            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("payment_method_type") as? String } ?: ""
        val paymentDesc = if (paymentMethodType.contains("debit", ignoreCase = true)) "DEBITO" else "CREDITO"

        val pagoItem = hashMapOf<String, Any>(
            "desc" to paymentDesc,
            "monto" to mntTotal
        )

        val infoPagos = hashMapOf<String, Any>(
            "Propina" to 0,
            "CdgVendedor" to "autoservicio",
            "AjusteSencillo" to 0,
            "Vuelto" to 0,
            "Pagos" to listOf(pagoItem)
        )

        return hashMapOf(
            "Encabezado" to encabezado,
            "infoPagos" to infoPagos,
            "Detalle" to detalleItems,
            "DscRcgGlobal" to emptyList<Any>(),
            "session_id" to ((transactionData["session_id"] as? Number)?.toInt() ?: 0)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun generateDTE(
        transactionData: Map<*, *>,
        transaction: Transaction
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val config = ConfigManager.getConfig()

            val sessionData = transactionData["session_data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

            val dteData = buildDTEData(transactionData, sessionData, saleMillisOf(transaction))

            val requestBody = gson.toJson(dteData).toRequestBody("application/json".toMediaType())

            val url = "${config.xsignUrl}/sign/39?getTED=false&sendDTE=true"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .build()

            logger.info("Generando DTE en XSign...")
            logger.info("URL: $url")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                logger.error("XSign error: $responseBody")
                return@withContext null
            }

            gson.fromJson(responseBody, Map::class.java) as Map<String, Any>

        } catch (e: Exception) {
            logger.error("Error en generateDTE: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun sendToOdoo(
        transactionData: Map<*, *>,
        dteResponse: Map<String, Any>?,
        transaction: Transaction,
        isInternalVoucher: Boolean,
        voucherNumber: String?
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val config = ConfigManager.getConfig()

            logger.info("Enviando a Odoo${if (isInternalVoucher) " (vale interno)" else ""}...")

            val orders = transactionData["orders"] as? List<*> ?: emptyList<Any>()
            val firstOrder = orders.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val products = firstOrder["products"] as? List<*> ?: emptyList<Any>()

            // Descuento combo: prorrateado por línea como % uniforme sobre el precio individual
            val dscRcgGlobal = (firstOrder["dsc_rcg_global"] as? Number)?.toDouble() ?: 0.0
            val totalIndividual = products.sumOf { p ->
                ((p as? Map<*, *>)?.get("price_subtotal") as? Number)?.toDouble() ?: 0.0
            }
            val discountPct = if (dscRcgGlobal > 0.0 && totalIndividual > 0.0)
                (dscRcgGlobal / totalIndividual) * 100.0
            else 0.0

            if (discountPct > 0.0) {
                logger.info("Combo descuento: dscRcgGlobal=$dscRcgGlobal totalIndividual=$totalIndividual discountPct=%.4f%%".format(discountPct))
            }

            val productsForOdoo = products.map { product ->
                val p = product as Map<*, *>
                val priceSubtotalIncl = (p["price_subtotal"] as? Number)?.toDouble() ?: 0.0
                val discountedSubtotalIncl = priceSubtotalIncl * (1.0 - discountPct / 100.0)
                hashMapOf<String, Any?>(
                    "product_id" to (p["product_id"] as? Number)?.toInt(),
                    "name" to p["name"],
                    "qty" to (p["qty"] as? Number)?.toInt(),
                    "price_unit" to priceSubtotalIncl / ((p["qty"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 1.0),
                    "discount" to discountPct,
                    "price_subtotal" to discountedSubtotalIncl,
                    "price_subtotal_incl" to discountedSubtotalIncl
                )
            }

            val rawPayment = firstOrder["payment"] as? List<*> ?: emptyList<Any>()
            // Calcular monto total de productos para inyectarlo si el payment no lo trae
            val totalAmount = (transactionData["amount"] as? Number)?.toDouble()
                ?: products.sumOf { p -> ((p as? Map<*, *>)?.get("price_subtotal") as? Number)?.toDouble() ?: 0.0 }

            val payment = rawPayment.map { pay ->
                val p = pay as? Map<*, *> ?: return@map pay
                if (p.containsKey("monto")) {
                    p
                } else {
                    val withMonto = HashMap<Any?, Any?>(p)
                    withMonto["monto"] = totalAmount
                    logger.info("Inyectando monto=$totalAmount en payment id=${p["id"]}")
                    withMonto
                }
            }

            val dteForlio = if (isInternalVoucher) (voucherNumber ?: "") else {
                val folioRaw = dteResponse?.get("folio")
                if (folioRaw is Number) folioRaw.toLong().toString() else folioRaw?.toString() ?: ""
            }

            val dteRequestJson = if (!isInternalVoucher) {
                val sessionDataForDte = transactionData["session_data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                gson.toJson(buildDTEData(transactionData, sessionDataForDte, saleMillisOf(transaction)))
            } else ""

            val customerComment = (transactionData["orders"] as? List<*>)
                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("customer_comment") as? String } ?: ""

            // data_card lleva los campos res_* de la orden en Odoo (tipo de tarjeta,
            // código de autorización, etc.). Lo completa AutoservicioRoutes con la
            // respuesta de MercadoPago.
            val dataCard = firstOrder["data_card"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

            val orderItem = hashMapOf<String, Any>(
                "products" to productsForOdoo,
                "payment" to payment,
                "data_card" to dataCard,
                "tipo_dte" to if (isInternalVoucher) 0 else 39,
                "dte_folio" to dteForlio,
                "dte_json" to dteRequestJson,
                "is_internal_voucher" to isInternalVoucher,
                "internal_voucher_number" to (voucherNumber ?: ""),
                "customer_comment" to customerComment
            )
            // Hora real de la venta: si la cola sube la orden más tarde, Odoo igual la
            // registra en su fecha (antes quedaba con la hora de subida).
            saleMillisOf(transaction)?.let { orderItem["date_order"] = Fechas.utcOdoo(it) }

            val sessionId = (transactionData["session_id"] as? Number)?.toInt() ?: 0
            val odooPayload = hashMapOf<String, Any>(
                "session_id" to sessionId,
                "orders" to listOf(orderItem)
            )

            logger.info("═══════════════════════════════════════════════════════════")
            logger.info(" PAYLOAD ODOO - session_id: $sessionId")
            logger.info(" tipo_dte: ${orderItem["tipo_dte"]} | dte_folio: $dteForlio")
            logger.info(" is_internal_voucher: $isInternalVoucher | products: ${productsForOdoo.size}")
            logger.info("═══════════════════════════════════════════════════════════")

            val requestBody = gson.toJson(odooPayload).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${config.odooUrl}/create_orders_from_self_service")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                // Si Odoo rechaza por folio duplicado, la orden ya está registrada — tratar como éxito
                if (responseBody.contains("duplicate key") || responseBody.contains("unique constraint")) {
                    logger.warn("Odoo: folio ya existente (duplicate key) — marcando como completada")
                    return@withContext mapOf("already_exists" to true)
                }
                logger.error("Odoo error: $responseBody")
                return@withContext null
            }

            gson.fromJson(responseBody, Map::class.java) as Map<String, Any>

        } catch (e: Exception) {
            logger.error("Error en sendToOdoo: ${e.message}")
            null
        }
    }
}