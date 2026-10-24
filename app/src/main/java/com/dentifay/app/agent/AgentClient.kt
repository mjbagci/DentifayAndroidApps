@file:Suppress("ClassName")

package com.dentifay.app.agent

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import com.dentifay.app.core.RawResponse
import com.dentifay.app.core.YetkiReddi
import com.dentifay.app.core.govde
import com.dentifay.app.core.hataCumlesi
import com.dentifay.app.core.serverCode
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sharedCacheFile
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Dentifay Asistan istemcisi: `POST /api/ai-assistant/chat` (Server-Sent Events) + sohbet listesi/ayrıntısı, Excel
 * indirme/yeniden üretim ve dikte sözlüğü. Oturum çerezini ApiClient ile paylaşır (aynı CookieJar).
 */
class AgentClient private constructor() {

    companion object {
        val shared = AgentClient()

        /** Sohbetin dört kovası (sözleşme §9 önceliği). */
        val tumKovalar: List<String> = listOf("doctors", "services", "inventory", "accounts")

        /** Tedavi Planı yalnız hekim ve tedavi adlarını alır — stok ve cari adları gürültüdür (M-E1.2.10). */
        val tedaviPlaniKovalari: List<String> = listOf("doctors", "services")
    }

    private val baseURL: String get() = AppConfig.apiBaseURL

    /**
     * Uzak akıl yürütme turları birkaç dakika sürebilir (çok adımlı analitik sorular). `readTimeout` baytlar arası
     * sessizlik sınırı, `callTimeout` toplam tavan. Bağlantıyı BEKLEME: sunucuya ulaşılamıyorsa hızlı ve görünür hata.
     */
    private val session: OkHttpClient = ApiClient.http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(1800, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Swift `AgentClient.AgentError`. `http`: durum · sunucunun cümlesi · sunucunun kodu (`code`, ekrana basılmaz — M0-7).
     * Kodsuz 403 yetki reddi sayılır (`Yetki.reddiMi` → `YetkiReddi`).
     */
    sealed class AgentError(message: String) : Exception(message), YetkiReddi {
        class invalidURL : AgentError("Geçersiz adres")
        class http(val code: Int, val msg: String?, val kod: String? = null) : AgentError(msg ?: "Bir hata oluştu.")

        /** Sunucu kodu (ör. `E_TARGET_PLAN_INVALID`). */
        val serverCode: String? get() = (this as? http)?.kod

        val errorDescription: String? get() = message

        override val yetkiReddiMi: Boolean get() = (this as? http)?.let { it.code == 403 && it.kod == null } ?: false
    }

    /**
     * Belirsizlik seçimi. Backend zod: { table, term, id, name? }.
     * ⚠️ `term` backend'de ZORUNLU ve boşsa pick DÜŞER → her zaman dolu gönderilir.
     */
    @Immutable
    data class PickPayload(val table: String, val term: String, val id: String, val name: String?) {
        fun toJson(): JsonObject = buildJsonObject {
            put("table", table); put("term", term); put("id", id)
            if (name != null) put("name", name)
        }
    }

    /** Deterministik onay (sözleşme §2, §4): `islem` ∈ onayla | vazgec. `conversationId` ZORUNLU. */
    @Immutable
    data class OnayPayload(val islem: String, val onizlemeId: String) {
        fun toJson(): JsonObject = buildJsonObject { put("islem", islem); put("onizlemeId", onizlemeId) }
    }

    /**
     * Sohbet gövdesi (sözleşme §2, `.strict()`). Kaldırılan `mode` alanı YOKTUR (gönderilirse 400 `E_FIELD_REMOVED`).
     * ⚠️ `patientPick` YOLU KALDIRILDI: her tabloda `pick` gönderilir (web istemcisi de öyle).
     */
    data class ChatRequest(
        val message: String,
        val conversationId: String?,
        /** Belirsizlik kartından seçim (varsa) — backend id ile çözer, yeniden aramaz. */
        val pick: PickPayload?,
        /** Sunucu-sahipli modül odağı (tek ENUM id; serbest prompt DEĞİL). Yetki/kiracı kapsamını GENİŞLETMEZ. */
        val moduleScope: String?,
        /** Tedavi Planı: kalemler bu açık plana eklenir (sözleşme §2; 400 `E_TARGET_PLAN_INVALID`, 409 `E_PLAN_CLOSED`). */
        val hedefPlanId: String? = null,
        /** Onay/vazgeç düğmesinin deterministik alanı (model çağrılmaz). */
        val onay: OnayPayload? = null,
    ) {
        /** ⚠️ null alanlar JSON'a KONMAZ (backend zod .optional() — null gereksiz risk). */
        fun toJson(): JsonObject = buildJsonObject {
            put("message", message)
            if (conversationId != null) put("conversationId", conversationId)
            if (pick != null) put("pick", pick.toJson())
            if (moduleScope != null) put("moduleScope", moduleScope)
            if (hedefPlanId != null) put("hedefPlanId", hedefPlanId)
            // `conversationId`siz `onay` sunucuda 400 — gönderilmez.
            if (conversationId != null && onay != null) put("onay", onay.toJson())
        }
    }

    // MARK: - Streaming chat (SSE)

    /**
     * Bir ajan turu için çözülmüş SSE olaylarını akıtır. Toplayan coroutine iptal edilirse istek de iptal olur.
     * `pick` belirsizlik kartı / sabitlenmiş kimlik seçimini id ile taşır. Kredi yetmezliği sohbette HTTP 200 + `error`
     * olayıdır (sözleşme §2); akış açılmadan dönen hata gövdeleri düz JSON'dur. Tanınmayan olaylar sessizce yok sayılır.
     */
    fun streamChat(
        message: String,
        conversationId: String?,
        pick: PickPayload? = null,
        moduleScope: String? = null,
        hedefPlanId: String? = null,
        onay: OnayPayload? = null,
    ): Flow<AgentEvent> = flow {
        val url = (baseURL + "/ai-assistant/chat").toHttpUrlOrNull() ?: throw AgentError.invalidURL()
        val govde = ChatRequest(message, conversationId, pick, moduleScope, hedefPlanId, onay).toJson()
        val req = Request.Builder()
            .url(url)
            .post(DfyJson.encodeToString(JsonElement.serializer(), govde).toRequestBody(ApiClient.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        val call = session.newCall(req)
        // Toplayıcı iptal edilince bloklayan okuma da kesilsin.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                throw e
            }
            response.use { resp ->
                val body = resp.body ?: throw AgentError.http(resp.code, null)
                if (resp.code !in 200..299) {
                    // Hata yanıtı SSE değil, düz JSON'dur (402 kredi dahil; akış AÇILMAZ) — gövdeyi sınırlı oku.
                    val source = body.source()
                    source.request(64L * 1024)
                    val bytes = source.buffer.readByteArray(minOf(source.buffer.size, 64L * 1024))
                    CreditRequired.parse(resp.code, bytes)?.let { throw it }
                    throw AgentError.http(resp.code, hataCumlesi(resp.code, bytes), serverCode(bytes))
                }
                // Her SSE çerçevesi `data: {json}\n\n`; satır satır okununca `data:` satırı doğrudan gelir.
                val source = body.source()
                while (true) {
                    if (!currentCoroutineContext().isActive) break
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                        throw e
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty()) continue
                    AgentEvent.parse(json)?.let { emit(it) }
                }
            }
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    // MARK: - Ortak istek yardımcıları

    private suspend fun calistir(req: Request): RawResponse = ApiClient.execute(req, session)

    private fun hata(raw: RawResponse): AgentError.http =
        AgentError.http(raw.status, hataCumlesi(raw.status, raw.body), serverCode(raw.body))

    private fun jsonOku(raw: RawResponse): JSONValue =
        JSONValue.parse(raw.body.toString(Charsets.UTF_8)) ?: throw ApiException.DecodingError(IllegalStateException("Yanıt çözülemedi"))

    // MARK: - STT lexicon (biasing)

    /**
     * GET /api/ai-assistant/stt-lexicon → yönlendirme sözlüğü. `initialPrompt` YOKTUR; diziler verilen kova sırasıyla,
     * en çok 100 ifade döndürülür. Hata → boş liste.
     */
    suspend fun fetchSTTLexicon(kovalar: List<String> = tumKovalar): List<String> = withContext(Dispatchers.IO) {
        val url = (baseURL + "/ai-assistant/stt-lexicon").toHttpUrlOrNull() ?: return@withContext emptyList()
        val raw = runCatching { calistir(Request.Builder().url(url).get().build()) }.getOrNull() ?: return@withContext emptyList()
        if (raw.status != 200) return@withContext emptyList()
        val obj = JSONValue.parse(raw.body.toString(Charsets.UTF_8))?.objectValue ?: return@withContext emptyList()
        fun strings(key: String): List<String> = (obj[key]?.arrayValue ?: emptyList()).mapNotNull { item ->
            item.stringValue ?: item.objectValue?.get("name")?.stringValue
        }
        val ordered = kovalar.flatMap { strings(it) }
        // Tekilleştir, boşları at, 100 ile sınırla.
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (s in ordered) {
            val t = s.trim()
            if (t.isEmpty() || !seen.add(t)) continue
            result += t
            if (result.size >= 100) break
        }
        result
    }

    // MARK: - Conversations

    /** Sohbet listesinin bir sayfası (sözleşme §8: `{items, pagination}`). */
    @Immutable
    data class SohbetSayfasi(val items: List<AgentConversation>, val page: Int, val pages: Int) {
        val dahaVar: Boolean get() = page < pages

        companion object {
            /** Yalnız `{items, pagination}` biçimi; kök dizi ya da başka biçim BOŞ liste döner (sunucu tek biçim gönderir). */
            fun parse(value: JSONValue): SohbetSayfasi {
                val root = value.objectValue
                val arr = root?.get("items")?.arrayValue ?: return SohbetSayfasi(emptyList(), 1, 1)
                val items = arr.mapNotNull { item ->
                    val o = item.objectValue ?: return@mapNotNull null
                    val id = o["id"]?.stringValue ?: return@mapNotNull null
                    AgentConversation(
                        id = id,
                        title = o["title"]?.stringValue ?: "Sohbet",
                        lastMessageAt = SohbetTarihi.cozumle(o["lastMessageAt"]?.stringValue),
                        createdAt = SohbetTarihi.cozumle(o["createdAt"]?.stringValue),
                    )
                }
                val pg = root["pagination"]?.objectValue
                return SohbetSayfasi(
                    items = items,
                    page = pg?.get("page")?.doubleValue?.toInt() ?: 1,
                    pages = pg?.get("pages")?.doubleValue?.toInt() ?: 1,
                )
            }
        }
    }

    /**
     * `GET /ai-assistant/conversations?page=&limit=25` — hata FIRLATIR (boş liste ile hata aynı görünmez).
     * `?odak=` gönderilmez: liste odaksız sohbetleri döndürür (27C.3).
     */
    suspend fun fetchConversations(page: Int): SohbetSayfasi = withContext(Dispatchers.IO) {
        val url = (baseURL + "/ai-assistant/conversations").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("page", "$page")
            ?.addQueryParameter("limit", "25")
            ?.build() ?: throw AgentError.invalidURL()
        val raw = calistir(Request.Builder().url(url).get().build())
        if (raw.status != 200) throw hata(raw)
        SohbetSayfasi.parse(jsonOku(raw))
    }

    /**
     * Kaydedilmiş sohbetin tam yükü (sözleşme §8). Bekleyen yazma `write_preview` ile AYNI alanları ve AYNI ayrıştırıcıyı
     * kullanır; mesajların artifact'leri (grafik / Excel) canlı akışın kurucularıyla çözülür.
     */
    @Immutable
    data class ConversationDetail(val messages: List<Message>, val pendingWrite: AgentWritePreview?) {
        @Immutable
        data class Message(
            val id: String?,
            val role: AgentRole,
            val content: String,
            val charts: List<AgentChartSpec> = emptyList(),
            val excelFiles: List<AgentExcelArtifact> = emptyList(),
        )

        /** Ekran mesajları — bekleyen yazma YALNIZ listenin SON mesajı asistansa ona bağlanır (web ile aynı). */
        fun ekranMesajlari(): List<AgentMessage> {
            val out = messages.map { m ->
                AgentMessage(role = m.role, content = m.content, serverId = m.id, charts = m.charts, excelFiles = m.excelFiles)
            }.toMutableList()
            val son = out.lastIndex
            if (pendingWrite != null && son >= 0 && out[son].role == AgentRole.assistant) {
                out[son] = out[son].copy(writePreview = pendingWrite)
            }
            return out
        }

        companion object {
            /** Ayrıntı yanıtının saf çözümü (test edilebilir). İçeriği boş ama artifact taşıyan asistan mesajı ATILMAZ. */
            fun parse(value: JSONValue): ConversationDetail {
                val root = value.objectValue
                val messagesArr = root?.get("messages")?.arrayValue ?: emptyList()
                val messages = messagesArr.mapNotNull { m ->
                    val o = m.objectValue ?: return@mapNotNull null
                    val roleStr = o["role"]?.stringValue ?: return@mapNotNull null
                    val id = o["id"]?.stringValue
                    val charts = mutableListOf<AgentChartSpec>()
                    val excel = mutableListOf<AgentExcelArtifact>()
                    (o["artifacts"]?.arrayValue ?: emptyList()).forEachIndexed { i, a ->
                        val ao = a.objectValue ?: return@forEachIndexed
                        val data = ao["data"]?.objectValue ?: return@forEachIndexed
                        when (ao["kind"]?.stringValue) {
                            "chart" -> AgentChartSpec.from(data)?.let { charts += it }
                            "excel" -> AgentExcelArtifact.from(data)?.let { excel += it.copy(messageId = id, artifactIndex = i) }
                            else -> Unit
                        }
                    }
                    val content = o["content"]?.stringValue ?: ""
                    if (content.isEmpty() && charts.isEmpty() && excel.isEmpty()) return@mapNotNull null
                    Message(
                        id = id,
                        role = if (roleStr == "assistant") AgentRole.assistant else AgentRole.user,
                        content = content,
                        charts = charts,
                        excelFiles = excel,
                    )
                }
                val pending = root?.get("pendingWrite")?.objectValue?.let { AgentWritePreview.parse(it) }
                return ConversationDetail(messages, pending)
            }
        }
    }

    /** 404 → `AgentError.http(404, …, "E_CONVERSATION_NOT_FOUND"?)` fırlatır. */
    suspend fun fetchConversationDetail(id: String): ConversationDetail = withContext(Dispatchers.IO) {
        val url = (baseURL + "/ai-assistant/conversations/$id").toHttpUrlOrNull() ?: throw AgentError.invalidURL()
        val raw = calistir(Request.Builder().url(url).get().build())
        if (raw.status != 200) throw hata(raw)
        ConversationDetail.parse(jsonOku(raw))
    }

    /** Süresi dolmuş Excel'i yeniden üretir (sözleşme §8) — dönen artifact'in `downloadUrl`iyle yeniden indirilir. */
    suspend fun regenerateExcel(messageId: String, artifactIndex: Int): AgentExcelArtifact = withContext(Dispatchers.IO) {
        val url = (baseURL + "/ai-assistant/regenerate-excel").toHttpUrlOrNull() ?: throw AgentError.invalidURL()
        val govde = govde("messageId" to messageId, "artifactIndex" to artifactIndex)
        val req = Request.Builder()
            .url(url)
            .post(DfyJson.encodeToString(JsonElement.serializer(), govde).toRequestBody(ApiClient.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()
        val raw = calistir(req)
        if (raw.status !in 200..299) throw hata(raw)
        val o = jsonOku(raw).objectValue ?: throw AgentError.http(raw.status, null)
        val x = AgentExcelArtifact.from(o) ?: throw AgentError.http(raw.status, null)
        x.copy(messageId = messageId, artifactIndex = artifactIndex)
    }

    suspend fun deleteConversation(id: String) {
        val url = (baseURL + "/ai-assistant/conversations/$id").toHttpUrlOrNull() ?: return
        runCatching { calistir(Request.Builder().url(url).delete().build()) }
    }

    // MARK: - Artifact download (xlsx)

    /** Excel çıktısını paylaşılabilir geçici dosyaya indirir, yerel dosyayı döndürür. */
    suspend fun downloadArtifact(downloadUrl: String, filename: String): File = withContext(Dispatchers.IO) {
        // downloadUrl mutlak ya da /api-göreli bir yol olabilir.
        val full = when {
            downloadUrl.startsWith("http") -> downloadUrl
            downloadUrl.startsWith("/api") -> baseURL.replace("/api", "") + downloadUrl
            else -> baseURL + (if (downloadUrl.startsWith("/")) downloadUrl else "/$downloadUrl")
        }
        val url = full.toHttpUrlOrNull() ?: throw AgentError.invalidURL()
        val raw = calistir(Request.Builder().url(url).get().build())
        if (raw.status !in 200..299) throw hata(raw)
        // Dosya adı sunucudan — yol ayırıcıları atılır (önbellek klasörünün dışına yazılmasın).
        val safeName = filename.ifEmpty { "rapor.xlsx" }.replace('/', '_').replace('\\', '_')
        val ctx = Prefs.appContext ?: throw AgentError.invalidURL()
        val tmp = sharedCacheFile(ctx, safeName)
        runCatching { tmp.delete() }
        tmp.writeBytes(raw.body)
        tmp
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun AgentClientPreview() {
    // Ağ çağrısı yapılmaz; yalnız hata metinlerini ve gövdeyi gösterir.
    val govde = AgentClient.ChatRequest("Bugünkü randevular", "c1", null, null, onay = AgentClient.OnayPayload("onayla", "o1")).toJson()
    PreviewEnv {
        Text(
            listOf(AgentClient.AgentError.invalidURL(), AgentClient.AgentError.http(409, "Sohbet şu an meşgul.", "CONVERSATION_BUSY"))
                .joinToString("\n") { it.errorDescription ?: "" } + "\n" + govde,
            Modifier.padding(16.dp), style = sys(14), color = DS.Colors.onSurface,
        )
    }
}
