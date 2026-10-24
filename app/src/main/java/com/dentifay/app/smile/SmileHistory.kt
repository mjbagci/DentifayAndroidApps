package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** `UUID` ↔ metin. */
object UUIDAsStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SmileUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** `Instant` ↔ ISO-8601 metin. */
object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SmileInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** `Rect` ↔ "x,y,w,h" (CGRect Codable karşılığı). */
object RectAsStringSerializer : KSerializer<Rect> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SmileRect", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Rect) =
        encoder.encodeString("${value.left},${value.top},${value.width},${value.height}")
    override fun deserialize(decoder: Decoder): Rect {
        val v = decoder.decodeString().split(",").map { it.trim().toFloat() }
        return Rect(v[0], v[1], v[0] + v[2], v[1] + v[3])
    }
}

/**
 * Kaydedilen gülüş simülasyonları (Geçmiş).
 *
 * Hasta saklanmasına onay verdiğinde hekim "Kaydet"e basar; önce ve sonra
 * fotoğrafları (tam kare) cihazda uygulamanın kendi alanında tutulur
 * (`noBackupFilesDir`: yedeğe/buluta gitmez). İsteğe bağlı olarak aynı görsel
 * hastanın Dentifay dosyasına da yüklenir (`SmileCreditsClient.uploadToPatientFile`).
 */
@Serializable
data class SmileHistoryItem(
    @Serializable(with = UUIDAsStringSerializer::class) val id: UUID,
    @Serializable(with = InstantAsStringSerializer::class) val createdAt: Instant,
    var designTitle: String,
    var designSummary: String,
    var tierTitle: String,
    var patientName: String? = null,
    var patientId: String? = null,
    var savedToPatientFile: Boolean = false,
    /** Gülüş yakın planı için kırpım (tam fotoğraf koordinatlarında). */
    @Serializable(with = RectAsStringSerializer::class) var zoomRect: Rect? = null,
) {
    val displayName: String
        get() {
            val n = patientName?.trim() ?: ""
            return if (n.isEmpty()) "İsimsiz hasta" else n
        }
}

class SmileHistoryStore private constructor() {

    companion object {
        val shared = SmileHistoryStore()

        // MARK: Dosyalar

        private val root: File
            get() {
                val ctx = Prefs.appContext ?: throw StoreError.encodingFailed
                return File(ctx.noBackupFilesDir, "SmileHistory")
            }
        private val indexURL: File get() = File(root, "index.json")
        private fun folder(id: UUID): File = File(root, id.toString().uppercase())

        private fun prepareRoot() {
            val url = root
            if (!url.exists() && !url.mkdirs()) throw StoreError.encodingFailed
        }

        /** Atomik yazma: geçici dosya + yeniden adlandırma. */
        private fun write(data: ByteArray, url: File) {
            val tmp = File(url.parentFile, url.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(url)) {
                url.delete()
                if (!tmp.renameTo(url)) throw StoreError.encodingFailed
            }
        }

        private fun jpeg(bitmap: Bitmap, quality: Float): ByteArray? {
            val out = ByteArrayOutputStream()
            return if (bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), out)) out.toByteArray() else null
        }

        /** Liste önizlemesi: gülüş bölgesine yakın, en uzun kenar 600 px. */
        private fun makeThumbnail(image: Bitmap, zoomRect: Rect?): Bitmap? = runCatching {
            var source = image
            if (zoomRect != null) {
                val x = max(0, zoomRect.left.roundToInt()); val y = max(0, zoomRect.top.roundToInt())
                val w = min(image.width - x, zoomRect.width.roundToInt()); val h = min(image.height - y, zoomRect.height.roundToInt())
                if (w > 0 && h > 0) source = Bitmap.createBitmap(image.softwareCopy(), x, y, w, h)
            }
            val long = max(source.width, source.height).toFloat()
            val scale = min(1f, 600f / max(long, 1f))
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(source.softwareCopy(), null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            out
        }.getOrNull()
    }

    var items by mutableStateOf<List<SmileHistoryItem>>(emptyList())
        private set
    private var loaded = false
    private val thumbs = mutableMapOf<UUID, Bitmap?>()

    sealed class StoreError : Exception() {
        object encodingFailed : StoreError()

        val errorDescription: String get() = message
        override val message: String get() = "Simülasyon kaydedilemedi. Cihazda yer olduğundan emin olun."
    }

    /** Tam çözünürlüklü önce/sonra. */
    data class Images(val before: Bitmap, val after: Bitmap)

    // MARK: Okuma

    fun load() {
        if (loaded) return
        loaded = true
        val list = runCatching {
            DfyJson.decodeFromString(ListSerializer(SmileHistoryItem.serializer()), indexURL.readText())
        }.getOrNull()
        if (list == null) { items = emptyList(); return }
        // Dosyası silinmiş kayıtlar listelenmez.
        items = list.filter { File(folder(it.id), "after.jpg").exists() }.sortedByDescending { it.createdAt }
    }

    /** Tam çözünürlüklü önce/sonra. */
    suspend fun images(item: SmileHistoryItem): Images? {
        val dir = runCatching { folder(item.id) }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            val b = BitmapFactory.decodeFile(File(dir, "before.jpg").path)
            val a = BitmapFactory.decodeFile(File(dir, "after.jpg").path)
            if (b == null || a == null) null else Images(b, a)
        }
    }

    fun thumbnail(item: SmileHistoryItem): Bitmap? {
        if (thumbs.containsKey(item.id)) thumbs[item.id]?.let { return it }
        val t = runCatching { BitmapFactory.decodeFile(File(folder(item.id), "thumb.jpg").path) }.getOrNull()
        thumbs[item.id] = t
        return t
    }

    // MARK: Yazma

    /** Simülasyonu kaydeder (tam kare önce/sonra + küçük önizleme). */
    suspend fun save(
        before: Bitmap,
        after: Bitmap,
        designTitle: String,
        designSummary: String,
        tierTitle: String,
        patientName: String?,
        patientId: String?,
        zoomRect: Rect?,
    ): SmileHistoryItem {
        load()
        val item = SmileHistoryItem(
            id = UUID.randomUUID(), createdAt = Instant.now(), designTitle = designTitle,
            designSummary = designSummary, tierTitle = tierTitle,
            patientName = patientName, patientId = patientId,
            savedToPatientFile = false, zoomRect = zoomRect,
        )
        val thumb = withContext(Dispatchers.IO) {
            val b = jpeg(before, 0.9f) ?: throw StoreError.encodingFailed
            val a = jpeg(after, 0.9f) ?: throw StoreError.encodingFailed
            val t = makeThumbnail(after, zoomRect) ?: throw StoreError.encodingFailed
            val td = jpeg(t, 0.8f) ?: throw StoreError.encodingFailed
            prepareRoot()
            val dir = folder(item.id)
            if (!dir.exists() && !dir.mkdirs()) throw StoreError.encodingFailed
            write(b, File(dir, "before.jpg"))
            write(a, File(dir, "after.jpg"))
            write(td, File(dir, "thumb.jpg"))
            t
        }
        thumbs[item.id] = thumb
        items = listOf(item) + items
        persist()
        return item
    }

    fun markSavedToPatientFile(id: UUID, patientId: String, patientName: String?) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val updated = items[i].copy(
            savedToPatientFile = true,
            patientId = patientId,
            patientName = if (!patientName.isNullOrEmpty()) patientName else items[i].patientName,
        )
        items = items.toMutableList().also { it[i] = updated }
        runCatching { persist() }
    }

    fun delete(item: SmileHistoryItem) {
        items = items.filter { it.id != item.id }
        thumbs.remove(item.id)
        runCatching { folder(item.id).deleteRecursively() }
        runCatching { persist() }
    }

    private fun persist() {
        prepareRoot()
        val data = DfyJson.encodeToString(ListSerializer(SmileHistoryItem.serializer()), items).toByteArray()
        write(data, indexURL)
    }
}
