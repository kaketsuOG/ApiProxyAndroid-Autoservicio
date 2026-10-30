package cl.posgo.apiproxyandroid.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fechas de venta y de boleta, con zona horaria explícita.
 *
 * Antes se usaba SimpleDateFormat sin zona, que toma la del equipo: si la terminal está en
 * UTC (muy común en Android), desde las 21:00 hora Chile la boleta salía con fecha del día
 * siguiente. Además la orden llegaba a Odoo sin hora de venta y quedaba con la hora en que
 * la cola lograba subirla.
 */
object Fechas {
    private val CHILE: TimeZone = TimeZone.getTimeZone("America/Santiago")
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** Fecha "yyyy-MM-dd" en hora Chile (FchEmis de la boleta). */
    fun fechaChile(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = CHILE }.format(Date(millis))

    /** Hora "HH:mm" en hora Chile (la que se imprime en la boleta). */
    fun horaChile(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = CHILE }.format(Date(millis))

    /** Fecha y hora en UTC con el formato que Odoo espera en date_order. */
    fun utcOdoo(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = UTC }.format(Date(millis))
}
