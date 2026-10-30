package cl.posgo.apiproxyandroid.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DatabaseManager private constructor(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val gson = Gson()

    companion object {
        private const val DATABASE_NAME = "api_proxy.db"
        private const val DATABASE_VERSION = 5

        private const val TABLE_TRANSACTIONS = "transactions"
        private const val TABLE_SESSION_LETTERS = "session_letters"
        private const val TABLE_CACHED_IMAGES = "cached_images"
        private const val TABLE_VOUCHER_SEQUENCES = "internal_voucher_sequences"

        private const val TABLE_CACHED_COMBOS = "cached_combos"
        private const val TABLE_CACHED_COMBO_PRODUCTS = "cached_combo_products"
        private const val TABLE_CACHED_PRODUCT_SUGGESTIONS = "cached_product_suggestions"
        private const val TABLE_CACHED_PRODUCT_BANNERS = "cached_product_banners"
        private const val TABLE_CACHED_CATEGORY_IMAGES = "cached_category_images"
        private const val TABLE_CACHED_COMBO_GROUPS = "cached_combo_groups"

        @Volatile
        private var INSTANCE: DatabaseManager? = null

        fun getInstance(context: Context): DatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_TRANSACTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_data TEXT NOT NULL,
                status TEXT DEFAULT 'pending',
                created_at INTEGER DEFAULT (strftime('%s','now')),
                processed_at INTEGER,
                error_message TEXT,
                folio_dte TEXT,
                dte_response TEXT,
                order_number TEXT,
                order_status TEXT DEFAULT 'pendiente',
                terminal_letter TEXT,
                sequence_number INTEGER,
                status_updated_at INTEGER,
                source TEXT DEFAULT 'autoservicio',
                session_id INTEGER,
                whatsapp_number TEXT,
                is_internal_voucher INTEGER DEFAULT 0,
                internal_voucher_number TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_SESSION_LETTERS (
                session_id INTEGER PRIMARY KEY,
                assigned_letter TEXT NOT NULL,
                terminal_name TEXT,
                created_at INTEGER DEFAULT (strftime('%s','now')),
                last_used_at INTEGER DEFAULT (strftime('%s','now'))
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_IMAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                image_data TEXT NOT NULL,
                mime_type TEXT DEFAULT 'image/jpeg',
                cached_at INTEGER DEFAULT (strftime('%s','now')),
                UNIQUE(product_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_VOUCHER_SEQUENCES (
                prefix TEXT NOT NULL,
                session_id INTEGER NOT NULL,
                last_number INTEGER DEFAULT 0,
                updated_at INTEGER DEFAULT (strftime('%s','now')),
                PRIMARY KEY (prefix, session_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_COMBOS (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                sequence INTEGER DEFAULT 0,
                image_base64 TEXT,
                combo_price REAL NOT NULL,
                regular_price REAL NOT NULL,
                discount_amount REAL NOT NULL,
                discount_percent REAL NOT NULL,
                product_count INTEGER NOT NULL,
                cached_at INTEGER NOT NULL,
                session_id INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_COMBO_PRODUCTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                combo_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                price REAL NOT NULL,
                subtotal REAL NOT NULL,
                image_base64 TEXT,
                FOREIGN KEY(combo_id) REFERENCES $TABLE_CACHED_COMBOS(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_PRODUCT_SUGGESTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                suggestion_type TEXT NOT NULL,
                suggestion_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                price REAL,
                combo_price REAL,
                regular_price REAL,
                discount_percent REAL,
                image_base64 TEXT,
                cached_at INTEGER NOT NULL,
                UNIQUE(product_id, suggestion_type, suggestion_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_PRODUCT_BANNERS (
                product_id INTEGER PRIMARY KEY,
                banner_base64 TEXT NOT NULL,
                cached_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_CATEGORY_IMAGES (
                category_id INTEGER PRIMARY KEY,
                image_base64 TEXT NOT NULL,
                cached_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_CACHED_COMBO_GROUPS (
                product_id INTEGER PRIMARY KEY,
                has_combos INTEGER NOT NULL DEFAULT 0,
                combo_groups_json TEXT NOT NULL DEFAULT '[]',
                cached_at INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_COMBOS (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    sequence INTEGER DEFAULT 0,
                    image_base64 TEXT,
                    combo_price REAL NOT NULL,
                    regular_price REAL NOT NULL,
                    discount_amount REAL NOT NULL,
                    discount_percent REAL NOT NULL,
                    product_count INTEGER NOT NULL,
                    cached_at INTEGER NOT NULL,
                    session_id INTEGER NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_COMBO_PRODUCTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    combo_id INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    price REAL NOT NULL,
                    subtotal REAL NOT NULL,
                    image_base64 TEXT,
                    FOREIGN KEY(combo_id) REFERENCES $TABLE_CACHED_COMBOS(id) ON DELETE CASCADE
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_PRODUCT_SUGGESTIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    suggestion_type TEXT NOT NULL,
                    suggestion_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    price REAL,
                    combo_price REAL,
                    regular_price REAL,
                    discount_percent REAL,
                    image_base64 TEXT,
                    cached_at INTEGER NOT NULL,
                    UNIQUE(product_id, suggestion_type, suggestion_id)
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_PRODUCT_BANNERS (
                    product_id INTEGER PRIMARY KEY,
                    banner_base64 TEXT NOT NULL,
                    cached_at INTEGER NOT NULL
                )
            """)
        }

        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_CATEGORY_IMAGES (
                    category_id INTEGER PRIMARY KEY,
                    image_base64 TEXT NOT NULL,
                    cached_at INTEGER NOT NULL
                )
            """)
        }

        if (oldVersion < 5) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_CACHED_COMBO_GROUPS (
                    product_id INTEGER PRIMARY KEY,
                    has_combos INTEGER NOT NULL DEFAULT 0,
                    combo_groups_json TEXT NOT NULL DEFAULT '[]',
                    cached_at INTEGER NOT NULL
                )
            """)
        }
    }

    fun saveTransactionWithOrder(
        data: Map<*, *>,
        sessionId: Int,
        source: String = "autoservicio",
        terminalName: String? = null,
        whatsappNumber: String? = null,
        dteResponse: Map<String, Any>? = null,
        isInternalVoucher: Boolean = false,
        voucherNumber: String? = null
    ): Map<String, Any> {
        val db = writableDatabase

        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val letter = alphabet.random()
        val sequenceNumber = (0..99).random()
        val orderNumber = "$letter${sequenceNumber.toString().padStart(2, '0')}"

        val values = ContentValues().apply {
            put("transaction_data", gson.toJson(data))
            put("order_number", orderNumber)
            put("order_status", "pendiente")
            put("terminal_letter", letter.toString())
            put("sequence_number", sequenceNumber)
            put("status_updated_at", System.currentTimeMillis() / 1000)
            put("source", source)
            put("session_id", sessionId)
            put("whatsapp_number", whatsappNumber)
            put("is_internal_voucher", if (isInternalVoucher) 1 else 0)
            put("internal_voucher_number", voucherNumber)

            if (dteResponse != null) {
                put("folio_dte", dteResponse["folio"]?.toString())
                put("dte_response", gson.toJson(dteResponse))
            }
        }

        val transactionId = db.insert(TABLE_TRANSACTIONS, null, values)

        return mapOf(
            "transactionId" to transactionId,
            "orderNumber" to orderNumber,
            "letter" to letter.toString(),
            "sequenceNumber" to sequenceNumber
        )
    }

    fun getPendingTransactions(): List<Transaction> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSACTIONS,
            null,
            "status = ?",
            arrayOf("pending"),
            null,
            null,
            "created_at ASC, id ASC"
        )

        val transactions = mutableListOf<Transaction>()
        while (cursor.moveToNext()) {
            transactions.add(
                Transaction(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    transactionData = cursor.getString(cursor.getColumnIndexOrThrow("transaction_data")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    processedAt = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("processed_at")),
                    errorMessage = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("error_message")),
                    folioDte = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("folio_dte")),
                    dteResponse = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("dte_response")),
                    orderNumber = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("order_number")),
                    orderStatus = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("order_status")),
                    terminalLetter = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("terminal_letter")),
                    sequenceNumber = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("sequence_number")),
                    statusUpdatedAt = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("status_updated_at")),
                    source = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source")),
                    sessionId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("session_id")),
                    whatsappNumber = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("whatsapp_number")),
                    isInternalVoucher = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("is_internal_voucher")),
                    internalVoucherNumber = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("internal_voucher_number")),
                    terminalName = null
                )
            )
        }
        cursor.close()
        return transactions
    }

    fun markAsCompleted(id: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "completed")
            put("processed_at", System.currentTimeMillis() / 1000)
        }
        db.update(TABLE_TRANSACTIONS, values, "id = ?", arrayOf(id.toString()))
    }

    fun markAsFailed(id: Int, errorMessage: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "failed")
            put("error_message", errorMessage)
            put("processed_at", System.currentTimeMillis() / 1000)
        }
        db.update(TABLE_TRANSACTIONS, values, "id = ?", arrayOf(id.toString()))
    }

    fun saveDTEResponse(transactionId: Int, folio: String, dteResponse: Map<String, Any>) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("folio_dte", folio)
            put("dte_response", gson.toJson(dteResponse))
        }
        db.update(TABLE_TRANSACTIONS, values, "id = ?", arrayOf(transactionId.toString()))
    }

    fun updateOrderStatus(transactionId: Int, newStatus: String): Int {
        val validStatuses = listOf("pendiente", "en_preparacion", "listo", "entregado")
        if (!validStatuses.contains(newStatus)) {
            throw IllegalArgumentException("Estado inválido: $newStatus")
        }

        val db = writableDatabase
        val values = ContentValues().apply {
            put("order_status", newStatus)
            put("status_updated_at", System.currentTimeMillis() / 1000)
        }
        return db.update(TABLE_TRANSACTIONS, values, "id = ?", arrayOf(transactionId.toString()))
    }

    fun getOrdersForDisplay(): List<Map<String, Any>> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSACTIONS,
            arrayOf("id", "order_number", "order_status", "terminal_letter", "sequence_number", "source", "session_id", "created_at", "status_updated_at", "is_internal_voucher", "internal_voucher_number"),
            "order_number IS NOT NULL AND order_status IN (?, ?, ?)",
            arrayOf("pendiente", "en_preparacion", "listo"),
            null,
            null,
            "created_at DESC"
        )

        val orders = mutableListOf<Map<String, Any>>()
        while (cursor.moveToNext()) {
            orders.add(
                mapOf(
                    "id" to cursor.getInt(0),
                    "order_number" to cursor.getString(1),
                    "order_status" to cursor.getString(2),
                    "terminal_letter" to (cursor.getStringOrNull(3) ?: ""),
                    "sequence_number" to (cursor.getIntOrNull(4) ?: 0),
                    "source" to (cursor.getStringOrNull(5) ?: ""),
                    "session_id" to (cursor.getIntOrNull(6) ?: 0),
                    "created_at" to cursor.getLong(7),
                    "status_updated_at" to (cursor.getLongOrNull(8) ?: 0),
                    "is_internal_voucher" to (cursor.getIntOrNull(9) ?: 0),
                    "internal_voucher_number" to (cursor.getStringOrNull(10) ?: "")
                )
            )
        }
        cursor.close()
        return orders
    }

    fun getDeliveredOrders(limit: Int = 50): List<Map<String, Any>> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSACTIONS,
            arrayOf("id", "order_number", "order_status", "terminal_letter", "sequence_number", "source", "session_id", "created_at", "status_updated_at", "is_internal_voucher", "internal_voucher_number"),
            "order_number IS NOT NULL AND order_status = ?",
            arrayOf("entregado"),
            null,
            null,
            "status_updated_at DESC",
            limit.toString()
        )

        val orders = mutableListOf<Map<String, Any>>()
        while (cursor.moveToNext()) {
            orders.add(
                mapOf(
                    "id" to cursor.getInt(0),
                    "order_number" to cursor.getString(1),
                    "order_status" to cursor.getString(2),
                    "terminal_letter" to (cursor.getStringOrNull(3) ?: ""),
                    "sequence_number" to (cursor.getIntOrNull(4) ?: 0),
                    "source" to (cursor.getStringOrNull(5) ?: ""),
                    "session_id" to (cursor.getIntOrNull(6) ?: 0),
                    "created_at" to cursor.getLong(7),
                    "status_updated_at" to (cursor.getLongOrNull(8) ?: 0),
                    "is_internal_voucher" to (cursor.getIntOrNull(9) ?: 0),
                    "internal_voucher_number" to (cursor.getStringOrNull(10) ?: "")
                )
            )
        }
        cursor.close()
        return orders
    }

    fun getCachedImage(productId: Int): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CACHED_IMAGES,
            arrayOf("image_data"),
            "product_id = ?",
            arrayOf(productId.toString()),
            null,
            null,
            null
        )

        var imageData: String? = null
        if (cursor.moveToFirst()) {
            imageData = cursor.getString(0)
        }
        cursor.close()
        return imageData
    }

    fun cacheImage(productId: Int, imageData: String, mimeType: String = "image/jpeg") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("product_id", productId)
            put("image_data", imageData)
            put("mime_type", mimeType)
            put("cached_at", System.currentTimeMillis() / 1000)
        }
        db.insertWithOnConflict(TABLE_CACHED_IMAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearImageCache() {
        val db = writableDatabase
        db.delete(TABLE_CACHED_IMAGES, null, null)
    }

    fun getNextInternalVoucherNumber(prefix: String, sessionId: Int): String {
        val db = writableDatabase

        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_VOUCHER_SEQUENCES (prefix, session_id, last_number) VALUES (?, ?, 0)",
            arrayOf(prefix, sessionId)
        )

        db.execSQL(
            "UPDATE $TABLE_VOUCHER_SEQUENCES SET last_number = last_number + 1, updated_at = strftime('%s','now') WHERE prefix = ? AND session_id = ?",
            arrayOf(prefix, sessionId)
        )

        val cursor = db.query(
            TABLE_VOUCHER_SEQUENCES,
            arrayOf("last_number"),
            "prefix = ? AND session_id = ?",
            arrayOf(prefix, sessionId.toString()),
            null,
            null,
            null
        )

        var lastNumber = 1
        if (cursor.moveToFirst()) {
            lastNumber = cursor.getInt(0)
        }
        cursor.close()

        val formattedNumber = lastNumber.toString().padStart(4, '0')
        return "$prefix-$sessionId-$formattedNumber"
    }

    fun saveCombosCache(sessionId: Int, combos: List<Map<String, Any>>): Boolean {
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_CACHED_COMBOS, "session_id = ?", arrayOf(sessionId.toString()))

                val currentTime = System.currentTimeMillis()

                combos.forEach { combo ->
                    val comboId = (combo["id"] as? Number)?.toInt() ?: return@forEach

                    val values = ContentValues().apply {
                        put("id", comboId)
                        put("name", combo["name"] as? String ?: "")
                        put("sequence", (combo["sequence"] as? Number)?.toInt() ?: 0)
                        put("image_base64", combo["image_base64"] as? String)
                        put("combo_price", (combo["combo_price"] as? Number)?.toDouble() ?: 0.0)
                        put("regular_price", (combo["regular_price"] as? Number)?.toDouble() ?: 0.0)
                        put("discount_amount", (combo["discount_amount"] as? Number)?.toDouble() ?: 0.0)
                        put("discount_percent", (combo["discount_percent"] as? Number)?.toDouble() ?: 0.0)
                        put("product_count", (combo["product_count"] as? Number)?.toInt() ?: 0)
                        put("cached_at", currentTime)
                        put("session_id", sessionId)
                    }

                    db.insertWithOnConflict(TABLE_CACHED_COMBOS, null, values, SQLiteDatabase.CONFLICT_REPLACE)

                    val products = combo["products"] as? List<*>
                    products?.forEach { productObj ->
                        val product = productObj as? Map<*, *> ?: return@forEach

                        val productValues = ContentValues().apply {
                            put("combo_id", comboId)
                            put("product_id", (product["product_id"] as? Number)?.toInt() ?: 0)
                            put("name", product["name"] as? String ?: "")
                            put("quantity", (product["quantity"] as? Number)?.toInt() ?: 1)
                            put("price", (product["price"] as? Number)?.toDouble() ?: 0.0)
                            put("subtotal", (product["subtotal"] as? Number)?.toDouble() ?: 0.0)
                            put("image_base64", product["image_base64"] as? String)
                        }

                        db.insert(TABLE_CACHED_COMBO_PRODUCTS, null, productValues)
                    }
                }

                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getCombosFromCache(sessionId: Int): List<Map<String, Any>>? {
        return try {
            val db = readableDatabase
            val combos = mutableListOf<Map<String, Any>>()

            val cursor = db.query(
                TABLE_CACHED_COMBOS,
                null,
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null, null,
                "sequence ASC, name ASC"
            )

            cursor.use {
                while (it.moveToNext()) {
                    val comboId = it.getInt(it.getColumnIndexOrThrow("id"))

                    val combo = mutableMapOf<String, Any>(
                        "id" to comboId,
                        "name" to it.getString(it.getColumnIndexOrThrow("name")),
                        "sequence" to it.getInt(it.getColumnIndexOrThrow("sequence")),
                        "combo_price" to it.getDouble(it.getColumnIndexOrThrow("combo_price")),
                        "regular_price" to it.getDouble(it.getColumnIndexOrThrow("regular_price")),
                        "discount_amount" to it.getDouble(it.getColumnIndexOrThrow("discount_amount")),
                        "discount_percent" to it.getDouble(it.getColumnIndexOrThrow("discount_percent")),
                        "product_count" to it.getInt(it.getColumnIndexOrThrow("product_count"))
                    )

                    val imageIndex = it.getColumnIndexOrThrow("image_base64")
                    if (!it.isNull(imageIndex)) {
                        combo["image_base64"] = it.getString(imageIndex)
                    }

                    combo["products"] = getComboProductsFromCache(comboId)
                    combos.add(combo)
                }
            }

            if (combos.isEmpty()) null else combos
        } catch (e: Exception) {
            null
        }
    }

    private fun getComboProductsFromCache(comboId: Int): List<Map<String, Any>> {
        val products = mutableListOf<Map<String, Any>>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_CACHED_COMBO_PRODUCTS,
            null,
            "combo_id = ?",
            arrayOf(comboId.toString()),
            null, null, null
        )

        cursor.use {
            while (it.moveToNext()) {
                val product = mutableMapOf<String, Any>(
                    "product_id" to it.getInt(it.getColumnIndexOrThrow("product_id")),
                    "name" to it.getString(it.getColumnIndexOrThrow("name")),
                    "quantity" to it.getInt(it.getColumnIndexOrThrow("quantity")),
                    "price" to it.getDouble(it.getColumnIndexOrThrow("price")),
                    "subtotal" to it.getDouble(it.getColumnIndexOrThrow("subtotal"))
                )

                val imageIndex = it.getColumnIndexOrThrow("image_base64")
                if (!it.isNull(imageIndex)) {
                    product["image_base64"] = it.getString(imageIndex)
                }

                products.add(product)
            }
        }

        return products
    }

    fun getComboDetailFromCache(comboId: Int): Map<String, Any>? {
        return try {
            val db = readableDatabase

            val cursor = db.query(
                TABLE_CACHED_COMBOS,
                null,
                "id = ?",
                arrayOf(comboId.toString()),
                null, null, null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val combo = mutableMapOf<String, Any>(
                        "id" to comboId,
                        "name" to it.getString(it.getColumnIndexOrThrow("name")),
                        "sequence" to it.getInt(it.getColumnIndexOrThrow("sequence")),
                        "combo_price" to it.getDouble(it.getColumnIndexOrThrow("combo_price")),
                        "regular_price" to it.getDouble(it.getColumnIndexOrThrow("regular_price")),
                        "discount_amount" to it.getDouble(it.getColumnIndexOrThrow("discount_amount")),
                        "discount_percent" to it.getDouble(it.getColumnIndexOrThrow("discount_percent")),
                        "product_count" to it.getInt(it.getColumnIndexOrThrow("product_count"))
                    )

                    val imageIndex = it.getColumnIndexOrThrow("image_base64")
                    if (!it.isNull(imageIndex)) {
                        combo["image_base64"] = it.getString(imageIndex)
                    }

                    combo["products"] = getComboProductsFromCache(comboId)
                    combo
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveProductSuggestionsCache(productId: Int, suggestions: Map<String, Any>): Boolean {
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_CACHED_PRODUCT_SUGGESTIONS, "product_id = ?", arrayOf(productId.toString()))

                val currentTime = System.currentTimeMillis()

                val suggestedProducts = suggestions["suggested_products"] as? List<*>
                suggestedProducts?.forEach { prodObj ->
                    val prod = prodObj as? Map<*, *> ?: return@forEach

                    val values = ContentValues().apply {
                        put("product_id", productId)
                        put("suggestion_type", "product")
                        put("suggestion_id", (prod["id"] as? Number)?.toInt() ?: 0)
                        put("name", prod["name"] as? String ?: "")
                        put("price", (prod["price"] as? Number)?.toDouble())
                        put("image_base64", prod["image_base64"] as? String)
                        put("cached_at", currentTime)
                    }

                    db.insert(TABLE_CACHED_PRODUCT_SUGGESTIONS, null, values)
                }

                val suggestedCombos = suggestions["suggested_combos"] as? List<*>
                suggestedCombos?.forEach { comboObj ->
                    val combo = comboObj as? Map<*, *> ?: return@forEach

                    val values = ContentValues().apply {
                        put("product_id", productId)
                        put("suggestion_type", "combo")
                        put("suggestion_id", (combo["id"] as? Number)?.toInt() ?: 0)
                        put("name", combo["name"] as? String ?: "")
                        put("combo_price", (combo["combo_price"] as? Number)?.toDouble())
                        put("regular_price", (combo["regular_price"] as? Number)?.toDouble())
                        put("discount_percent", (combo["discount_percent"] as? Number)?.toDouble())
                        put("image_base64", combo["image_base64"] as? String)
                        put("cached_at", currentTime)
                    }

                    db.insert(TABLE_CACHED_PRODUCT_SUGGESTIONS, null, values)
                }

                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getProductSuggestionsFromCache(productId: Int): Map<String, Any>? {
        return try {
            val db = readableDatabase

            val cursor = db.query(
                TABLE_CACHED_PRODUCT_SUGGESTIONS,
                null,
                "product_id = ?",
                arrayOf(productId.toString()),
                null, null, null
            )

            val suggestedProducts = mutableListOf<Map<String, Any>>()
            val suggestedCombos = mutableListOf<Map<String, Any>>()

            cursor.use {
                while (it.moveToNext()) {
                    val type = it.getString(it.getColumnIndexOrThrow("suggestion_type"))

                    if (type == "product") {
                        val product = mutableMapOf<String, Any>(
                            "id" to it.getInt(it.getColumnIndexOrThrow("suggestion_id")),
                            "name" to it.getString(it.getColumnIndexOrThrow("name")),
                            "price" to it.getDouble(it.getColumnIndexOrThrow("price"))
                        )

                        val imageIndex = it.getColumnIndexOrThrow("image_base64")
                        if (!it.isNull(imageIndex)) {
                            product["image_base64"] = it.getString(imageIndex)
                        }

                        suggestedProducts.add(product)
                    } else if (type == "combo") {
                        val combo = mutableMapOf<String, Any>(
                            "id" to it.getInt(it.getColumnIndexOrThrow("suggestion_id")),
                            "name" to it.getString(it.getColumnIndexOrThrow("name")),
                            "combo_price" to it.getDouble(it.getColumnIndexOrThrow("combo_price")),
                            "regular_price" to it.getDouble(it.getColumnIndexOrThrow("regular_price")),
                            "discount_percent" to it.getDouble(it.getColumnIndexOrThrow("discount_percent"))
                        )

                        val imageIndex = it.getColumnIndexOrThrow("image_base64")
                        if (!it.isNull(imageIndex)) {
                            combo["image_base64"] = it.getString(imageIndex)
                        }

                        suggestedCombos.add(combo)
                    }
                }
            }

            if (suggestedProducts.isEmpty() && suggestedCombos.isEmpty()) {
                null
            } else {
                mapOf(
                    "suggested_products" to suggestedProducts,
                    "suggested_combos" to suggestedCombos
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveProductBannerCache(productId: Int, bannerBase64: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put("product_id", productId)
                put("banner_base64", bannerBase64)
                put("cached_at", System.currentTimeMillis())
            }

            val db = writableDatabase
            db.insertWithOnConflict(TABLE_CACHED_PRODUCT_BANNERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getProductBannerFromCache(productId: Int): String? {
        return try {
            val db = readableDatabase

            val cursor = db.query(
                TABLE_CACHED_PRODUCT_BANNERS,
                arrayOf("banner_base64"),
                "product_id = ?",
                arrayOf(productId.toString()),
                null, null, null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("banner_base64"))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveCategoryImageCache(categoryId: Int, imageBase64: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put("category_id", categoryId)
                put("image_base64", imageBase64)
                put("cached_at", System.currentTimeMillis())
            }

            val db = writableDatabase
            db.insertWithOnConflict(TABLE_CACHED_CATEGORY_IMAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getCategoryImageFromCache(categoryId: Int): String? {
        return try {
            val db = readableDatabase

            val cursor = db.query(
                TABLE_CACHED_CATEGORY_IMAGES,
                arrayOf("image_base64"),
                "category_id = ?",
                arrayOf(categoryId.toString()),
                null, null, null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("image_base64"))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun deleteCategoryImageCache(categoryId: Int) {
        try {
            writableDatabase.delete(TABLE_CACHED_CATEGORY_IMAGES, "category_id = ?", arrayOf(categoryId.toString()))
        } catch (e: Exception) { /* ignore */ }
    }

    fun saveComboGroupsCache(productId: Int, hasCombos: Boolean, comboGroups: List<*>): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("product_id", productId)
                put("has_combos", if (hasCombos) 1 else 0)
                put("combo_groups_json", gson.toJson(comboGroups))
                put("cached_at", System.currentTimeMillis() / 1000)
            }
            db.insertWithOnConflict(TABLE_CACHED_COMBO_GROUPS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getComboGroupsFromCache(productId: Int): Map<String, Any>? {
        return try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_CACHED_COMBO_GROUPS,
                arrayOf("has_combos", "combo_groups_json"),
                "product_id = ?",
                arrayOf(productId.toString()),
                null, null, null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val hasCombos = it.getInt(0) == 1
                    val groupsJson = it.getString(1)
                    val type = object : TypeToken<List<Any>>() {}.type
                    val groups: List<Any> = gson.fromJson(groupsJson, type) ?: emptyList<Any>()
                    mapOf("success" to true, "has_combos" to hasCombos, "combo_groups" to groups)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearCombosCache(): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_CACHED_COMBOS, null, null)
            db.delete(TABLE_CACHED_COMBO_PRODUCTS, null, null)
            db.delete(TABLE_CACHED_PRODUCT_SUGGESTIONS, null, null)
            db.delete(TABLE_CACHED_PRODUCT_BANNERS, null, null)
            db.delete(TABLE_CACHED_CATEGORY_IMAGES, null, null)
            db.delete(TABLE_CACHED_COMBO_GROUPS, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? {
        return if (isNull(columnIndex)) null else getString(columnIndex)
    }

    private fun android.database.Cursor.getIntOrNull(columnIndex: Int): Int? {
        return if (isNull(columnIndex)) null else getInt(columnIndex)
    }

    private fun android.database.Cursor.getLongOrNull(columnIndex: Int): Long? {
        return if (isNull(columnIndex)) null else getLong(columnIndex)
    }
}