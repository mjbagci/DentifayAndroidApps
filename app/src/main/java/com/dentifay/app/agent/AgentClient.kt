package com.dentifay.app.agent

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import com.dentifay.app.core.serverSentence
import com.dentifay.app.theme.sharedCacheFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Backend konuşmalı yapay zekâ ajanı istemcisi: POST /api/ai-assistant/chat (Server-Sent Events)
 * + sohbet/sağlık/indirme yardımcıları. Oturum çerezini ApiClient ile paylaşır (aynı CookieJar).
 */
class AgentClient private constructor() {

    companion object {
        val shared = AgentClient()
    }

    private val baseURL: String get() = AppConfig.apiBaseURL

    /**
     * Ajan turları yavaş yerel LLM çalıştırabilir (çok adımlı analitik sorgular dakikalar sürebilir).
     * Çok sabırlı ol: `readTimeout` baytlar arası boşta kalma sınırı, `callTimeout` toplam tavan.
     * Bağlantıyı BEKLEME: backend erişilemezse hızlı ve görünür hata (sessiz dakikalarca askı değil).
     */
    private val session: OkHttpClient = ApiClient.http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(1800, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    sealed class AgentError(message: String) : Exception(message) {
        class invalidURL : AgentError("Geçersiz adres")
        class http(val code: Int, val msg: String?) : AgentError(msg ?: "Sunucu hatası ($code)")
        class offline : AgentError("AI servisi şu an yanıt vermiyor")

        val errorDescription: String? get() = message
    }

    /**
     * Belirsizlik seçimi (hasta dışı tablolar). Backend zod: { table, term, id, name? }.
     * ⚠️ `term` backend'de ZORUNLU ve boşsa pick DÜŞER → her zaman dolu gönderilir.
     */
    data class PickPayload(val table: String, val term: String, val id: String, val name: String?) {
        fun toJson(): JsonElement = buildJsonObject {
            put("table", table); put("term", term); put("id", id)
            if (name != null) put("name", name)
        }
    }

    /** Hasta belirsizlik seçimi. Backend: { id, name }. */
    data class PatientPick(val id: String, val name: String) {
        fun toJson(): JsonElement = buildJsonObject { put("id", id); put("name", name) }
    }

    /** ⚠️ null alanlar JSON'a KONMAZ (backend zod .optional() — null gereksiz risk). */
    private fun chatBody(message: String, conversationId: String?, mode: String?, pick: PickPayload?, patientPick: PatientPick?): String {
        val obj = buildJsonObject {
            put("message", message)
            if (conversationId != null) put("conversationId", conversationId)
            // Sesli mod hızlı yolu ("voice"): yalnız RAG'ı daraltır, iterasyon tavanını düşürür, `speak`i öne alır.
            if (mode != null) put("mode", mode)
            if (pick != null) put("pick", pick.toJson())
            if (patientPick != null) put("patientPick", patientPick.toJson())
        }
        return DfyJson.encodeToString(JsonElement.serializer(), obj)
    }

    // MARK: - Streaming chat (SSE)

    /**
     * Bir ajan turu için çözülmüş SSE olaylarını akıtır. Toplayan coroutine iptal edilirse istek de iptal olur.
     * `mode = "voice"` sesli hızlı yolu açar; `pick`/`patientPick` belirsizlik kartı seçimini id ile taşır.
     * Kredi yetmezse ya da aylık limit dolduysa sunucu akışı AÇMADAN 402 döner → akış `CreditRequired`
     * ile biter. Başarılı turda `{type:"credits"}` olayı `end`ten hemen önce gelir; tanınmayan olaylar yok sayılır.
     */
    fun streamChat(
        message: String,
        conversationId: String?,
        mode: String? = null,
        pick: PickPayload? = null,
        patientPick: PatientPick? = null,
    ): Flow<AgentEvent> = flow {
        val url = (baseURL + "/ai-assistant/chat").toHttpUrlOrNull() ?: throw AgentError.invalidURL()
        val req = Request.Builder()
            .url(url)
            .post(chatBody(message, conversationId, mode, pick, patientPick).toRequestBody(ApiClient.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        val call = session.newCall(req)
        // Toplayıcı iptal edilince bloklayan okuma da kesilsin.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: java.io.IOException) {
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
                    throw AgentError.http(resp.code, serverSentence(bytes))
                }
                // Her SSE çerçevesi `data: {json}\n\n`; satır satır okununca `data:` satırı doğrudan gelir.
                val source = body.source()
                while (true) {
                    if (!currentCoroutineContext().isActive) break
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: java.io.IOException) {
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

    // MARK: - Health

    /** Ajan (model) ulaşılabilir ve hazırsa true. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val url = (baseURL + "/ai-assistant/health").toHttpUrlOrNull() ?: return@withContext false
        val client = session.newBuilder().callTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val raw = runCatching { ApiClient.execute(Request.Builder().url(url).get().build(), client) }.getOrNull()
            ?: return@withContext false
        if (raw.status != 200) return@withContext false
        val obj = JSONValue.parse(raw.body.toString(Charsets.UTF_8))?.objectValue ?: return@withContext true
        // Yaygın hazır olma biçimlerini kabul et.
        obj["ok"]?.boolValue?.let { return@withContext it }
        obj["ready"]?.boolValue?.let { return@withContext it }
        obj["status"]?.stringValue?.let { s ->
            val l = s.lowercase()
            return@withContext l != "down" && l != "offline"
        }
        true
    }

    /** GET isteği → 200 ise gövde (JSONValue), değilse null. */
    private suspend fun getJSON(path: String): JSONValue? = withContext(Dispatchers.IO) {
        val url = (baseURL + path).toHttpUrlOrNull() ?: return@withContext null
        val raw = runCatching { ApiClient.execute(Request.Builder().url(url).get().build(), session) }.getOrNull()
            ?: return@withContext null
        if (raw.status != 200) return@withContext null
        JSONValue.parse(raw.body.toString(Charsets.UTF_8))
    }

    // MARK: - STT lexicon (biasing)

    /**
     * GET /api/ai-assistant/stt-lexicon → yönlendirme sözlüğü. `initialPrompt` KULLANILMAZ (Whisper biçimi);
     * ham diziler `doctors → services → inventory → accounts` önceliğiyle, en çok 100 ifade döndürülür.
     */
    suspend fun fetchSTTLexicon(): List<String> {
        val obj = getJSON("/ai-assistant/stt-lexicon")?.objectValue ?: return emptyList()
        fun strings(key: String): List<String> = (obj[key]?.arrayValue ?: emptyList()).mapNotNull { item ->
            item.stringValue ?: item.objectValue?.get("name")?.stringValue
        }
        val ordered = strings("doctors") + strings("services") + strings("inventory") + strings("accounts")
        // Tekilleştir, boşları at, 100 ile sınırla.
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (s in ordered) {
            val t = s.trim()
            if (t.isEmpty() || !seen.add(t)) continue
            result += t
            if (result.size >= 100) break
        }
        return result
    }

    // MARK: - Conversations

    suspend fun fetchConversations(): List<AgentConversation> {
        val value = getJSON("/ai-assistant/conversations") ?: return emptyList()
        // Yanıt dizi ya da { conversations: [...] } olabilir.
        val arr = value.arrayValue ?: value.objectValue?.get("conversations")?.arrayValue ?: emptyList()
        return arr.mapNotNull { item ->
            val o = item.objectValue ?: return@mapNotNull null
            val id = o["id"]?.stringValue ?: return@mapNotNull null
            val title = o["title"]?.stringValue ?: "Sohbet"
            AgentConversation(id, title, o["updatedAt"]?.stringValue ?: o["createdAt"]?.stringValue)
        }
    }

    /** Kayıtlı bir sohbetin mesajlarını (rol, içerik) çiftleri olarak yükler. */
    suspend fun fetchConversationMessages(id: String): List<Pair<AgentRole, String>> {
        val value = getJSON("/ai-assistant/conversations/$id") ?: return emptyList()
        val root = value.objectValue
        val direct = root?.get("messages")?.arrayValue
        val nested = root?.get("conversation")?.objectValue?.get("messages")?.arrayValue
        val messagesArr = direct ?: nested ?: emptyList()
        return messagesArr.mapNotNull { m ->
            val o = m.objectValue ?: return@mapNotNull null
            val roleStr = o["role"]?.stringValue ?: return@mapNotNull null
            val content = o["content"]?.stringValue?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            (if (roleStr == "assistant") AgentRole.assistant else AgentRole.user) to content
        }
    }

    suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) {
            val url = (baseURL + "/ai-assistant/conversations/$id").toHttpUrlOrNull() ?: return@withContext
            runCatching { ApiClient.execute(Request.Builder().url(url).delete().build(), session) }
        }
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
        val raw = ApiClient.execute(Request.Builder().url(url).get().build(), session)
        if (raw.status !in 200..299) throw AgentError.http(raw.status, null)
        val safeName = filename.ifEmpty { "rapor.xlsx" }
        val ctx = Prefs.appContext ?: throw AgentError.invalidURL()
        val tmp = sharedCacheFile(ctx, safeName)
        runCatching { tmp.delete() }
        tmp.writeBytes(raw.body)
        tmp
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentClientPreview() {
    // Ağ çağrısı yapılmaz; yalnız hata metinlerini gösterir.
    PreviewEnv {
        Text(
            listOf(AgentClient.AgentError.invalidURL(), AgentClient.AgentError.http(503, null), AgentClient.AgentError.offline())
                .joinToString("\n") { it.errorDescription ?: "" },
            Modifier.padding(16.dp), style = sys(14), color = DS.Colors.onSurface,
        )
    }
}
