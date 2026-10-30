package cl.posgo.apiproxyandroid.routes

import cl.posgo.apiproxyandroid.ConfigManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsString
import cl.posgo.apiproxyandroid.utils.HttpClient
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reenvía a Get It los códigos que la caja escaneó y no encontró en el catálogo,
 * para que ellos los den de alta. El registro queda del lado de Get It: acá no se
 * guarda nada ni se toca Odoo.
 *
 * La caja manda el JSON tal cual lo pide la API de Get It; el proxy solo le agrega
 * la `X-Api-Key` (que vive en la configuración, editable desde la pantalla del proxy)
 * y lo despacha. Le responde OK a la caja de inmediato, sin esperar a Get It: si la
 * API está caída o no hay internet, la venta no se entera.
 *
 * Documentación de la API: `API_Getit_escaneo_no_encontrado.md` (contacto: Martín).
 */
class ScanReportRoutes(
    private val logger: LoggerService
) {
    private val gson = Gson()

    /**
     * Cliente con timeouts cortos: el compartido espera hasta 180s (pensado para las
     * cargas grandes de Odoo) y no queremos hilos colgados 3 minutos por un aviso.
     */
    private val client = HttpClient.createUnsafeClient().newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession
    ): NanoHTTPD.Response {
        if (uri != "/api/scan-no-encontrado" || method != NanoHTTPD.Method.POST) {
            return json(NanoHTTPD.Response.Status.NOT_FOUND, mapOf("error" to "Ruta no encontrada"))
        }

        val body = session.getBodyAsString()
        if (body.isBlank()) {
            return json(NanoHTTPD.Response.Status.BAD_REQUEST, mapOf("success" to false, "error" to "Cuerpo vacío"))
        }

        val config = ConfigManager.getConfig()
        if (config.scanReportKey.isBlank() || config.scanReportUrl.isBlank()) {
            logger.warn("Aviso de código no encontrado descartado: falta SCAN_REPORT_KEY o SCAN_REPORT_URL")
            return json(NanoHTTPD.Response.Status.OK, mapOf("success" to true, "enviado" to false))
        }

        val request = Request.Builder()
            .url(config.scanReportUrl)
            .addHeader("X-Api-Key", config.scanReportKey)
            .post(body.toRequestBody(JSON))
            .build()

        logger.info("Avisando a Get It código no encontrado: ${body.take(200)}")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.warn("No se pudo avisar el código no encontrado: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) logger.success("Código no encontrado avisado a Get It")
                    else logger.warn("Get It rechazó el aviso: HTTP ${it.code}")
                }
            }
        })

        return json(NanoHTTPD.Response.Status.OK, mapOf("success" to true, "enviado" to true))
    }

    private fun json(status: NanoHTTPD.Response.Status, data: Any): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", gson.toJson(data))

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
