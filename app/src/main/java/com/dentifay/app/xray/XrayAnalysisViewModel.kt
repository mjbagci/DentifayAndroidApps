package com.dentifay.app.xray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.R
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.obj
import com.dentifay.app.core.str
import com.dentifay.app.theme.Haptics
import com.dentifay.app.trial.GuestTrialClient
import com.dentifay.app.trial.GuestTrialError
import com.dentifay.app.trial.GuestTrialFeature
import com.dentifay.app.trial.GuestTrialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Röntgen Analizi akışı (web "Röntgen Analizi" stüdyosunun mobil karşılığı).
 *
 * Akış: kaynak (rehberli çekim · galeri · dosya · örnek) → röntgen otomatik ayrıştırılır
 * (ekran/negatoskop/arayüzden ayrılır, perspektifi düzeltilir, moiré temizlenir, tonu
 * dengelenir) → önizleme (istenirse köşeler elle düzeltilir) → analiz → diş diş bulgular. İki kip:
 * - `guest`: giriş ekranındaki ücretsiz deneme (`/api/public/trial/xray/analyze`, IP başına hak).
 * - `clinic`: oturumlu klinik — röntgen hastanın dosyasına yüklenir, analiz AI kredisinden düşer.
 */
class XrayAnalysisViewModel(val mode: Mode) {

    /** Hasta özeti (Swift'te `TreatmentPlanViewModel.PatientRef` takma adı; aynı alanlar). */
    data class PatientRef(val id: String, val name: String, val subtitle: String?)

    enum class Mode { guest, clinic }

    sealed interface Phase {
        data object pickSource : Phase
        data object preparing : Phase
        data object adjust : Phase
        data object review : Phase
        data object analyzing : Phase
        data object results : Phase
        data class failed(val message: String) : Phase
    }

    enum class Source { camera, library, file, sample }

    private val scope = MainScope()

    var phase: Phase by mutableStateOf(Phase.pickSource)

    // Görsel
    var original: Bitmap? by mutableStateOf(null)
        private set
    /** Seçilen alan (normalize köşeler). */
    var quad: XrayQuad by mutableStateOf(XrayQuad.full)
    /** Algılama ne buldu (kullanıcı "Otomatik"e dönebilsin). */
    var detectedQuad: XrayQuad? by mutableStateOf(null)
        private set
    var source: Source by mutableStateOf(Source.library)
        private set
    /** Görsel bir fotoğraf mı (ekran/negatoskop çekimi) — dijital dosyada iyileştirme kapalı başlar. */
    var isPhoto: Boolean by mutableStateOf(false)
        private set
    private var _enhance by mutableStateOf(false)
    /** Ton dengesi (fotoğrafta varsayılan açık). */
    var enhance: Boolean
        get() = _enhance
        set(value) {
            val old = _enhance
            _enhance = value
            if (value != old) rebuildPrepared()
        }
    var prepared: Bitmap? by mutableStateOf(null)
        private set
    var isRebuilding: Boolean by mutableStateOf(false)
        private set
    /** Hazırlanan görüntünün kalite uyarıları (bulanık, küçük, parlama). */
    var quality: XrayQualityReport by mutableStateOf(XrayQualityReport())
        private set
    /** Fotoğrafta röntgen bulunamadı — kullanıcı köşeleri elle seçmeli. */
    var needsManualCrop: Boolean by mutableStateOf(false)
        private set

    // Klinik kipi
    var patient: PatientRef? by mutableStateOf(null)
    private var _patientQuery by mutableStateOf("")
    var patientQuery: String
        get() = _patientQuery
        set(value) { _patientQuery = value; searchPatients() }
    var patientResults: List<PatientRef> by mutableStateOf(emptyList())
        private set
    var isSearchingPatients: Boolean by mutableStateOf(false)
        private set

    // Sonuç
    var result: XrayResult? by mutableStateOf(null)
        private set
    var sensitivity: XraySensitivity by mutableStateOf(XraySensitivity.low)
    /** Sağlıklı diş kutuları (dar ekranda kalabalık yapmasın diye varsayılan kapalı). */
    var showHealthy: Boolean by mutableStateOf(false)
    var showAnatomy: Boolean by mutableStateOf(false)
    var selectedToothID: String? by mutableStateOf(null)
    var startedAt: Long? by mutableStateOf(null)
        private set
    var elapsed: Double by mutableDoubleStateOf(0.0)
        private set
    var lastError: Throwable? by mutableStateOf(null)
        private set
    var creditRequired: CreditRequired? by mutableStateOf(null)

    private var work: Job? = null
    private var searchTask: Job? = null
    private var ticker: Job? = null

    fun dispose() { cancel(); searchTask?.cancel(); scope.cancel() }

    // MARK: Türetilmiş

    val findings: List<XrayToothFinding> get() = result?.findings(sensitivity.rawValue) ?: emptyList()
    val visibleFindings: List<XrayToothFinding>
        get() = if (showHealthy) findings else findings.filter {
            it.kind == XrayToothFinding.Kind.finding || it.kind == XrayToothFinding.Kind.existing || it.kind == XrayToothFinding.Kind.missing
        }
    val toothCount: Int get() = findings.count { !it.isSynthetic }
    val findingCount: Int get() = findings.sumOf { it.recs.size }
    val findingTeeth: List<XrayToothFinding>
        get() = findings.filter { it.recs.isNotEmpty() }
            .sortedWith { a, b ->
                val pa = a.recs.minOfOrNull { XrayCatalog.priority[it.cls] ?: 9 } ?: 9
                val pb = b.recs.minOfOrNull { XrayCatalog.priority[it.cls] ?: 9 } ?: 9
                if (pa == pb) a.fdi.compareTo(b.fdi) else pa.compareTo(pb)
            }
    val existingCount: Int get() = findings.sumOf { it.existing.size }
    val selectedTooth: XrayToothFinding? get() = findings.firstOrNull { it.id == selectedToothID }
    /** Az bulgulu, çok dişli sonuç → duyarlılığı düşürmeyi öner (web "Hassasiyeti Düşür"). */
    val suggestsLowerSensitivity: Boolean
        get() = toothCount >= 15 && findingCount <= 2 && sensitivity != XraySensitivity.all
    /** Sonuç görseli (perspektif düzeltilmiş, sunucuya giden). */
    val preparedImage: Bitmap? get() = prepared
    val originalImage: Bitmap? get() = original

    /** Önizlemedeki alan otomatik bulunan mı (elle değiştirilmedi)? */
    val isAutoCropped: Boolean get() = detectedQuad?.let { it == quad } ?: false

    val canAnalyze: Boolean
        get() {
            if (prepared == null || isRebuilding) return false
            if (mode == Mode.clinic) return patient != null
            return GuestTrialStore.shared.remaining(GuestTrialFeature.xray) != 0
        }

    // MARK: - Kaynak

    fun load(image: Bitmap, source: Source) {
        start(source, null) { XrayImageProcessor.normalized(image) }
    }

    /** @param hint kameranın canlı görüntüde bulduğu röntgen alanı. */
    fun load(data: ByteArray, source: Source, hint: XrayQuad? = null) {
        start(source, hint) { XrayImageProcessor.load(data) }
    }

    fun loadSample(context: Context) {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val img = BitmapFactory.decodeResource(context.resources, R.drawable.xray_sample, opts) ?: return
        load(img, Source.sample)
    }

    private fun start(source: Source, hint: XrayQuad?, make: () -> Bitmap) {
        cancel()
        this.source = source
        result = null; prepared = null; original = null; detectedQuad = null; selectedToothID = null
        quality = XrayQualityReport(); needsManualCrop = false
        phase = Phase.preparing
        val fromCamera = source == Source.camera
        work = scope.launch {
            try {
                // Fotoğraf mı dijital dosya mı içerikten anlaşılır (galerideki WhatsApp röntgeni
                // dijitaldir, dosyalardaki ekran fotoğrafı fotoğraftır). Fotoğrafta önce ekran
                // deseni (moiré) temizlenir, sonra röntgen ayrıştırılır.
                val (image, detected, photo) = withContext(Dispatchers.Default) {
                    val raw = make()
                    val photo = fromCamera || XrayImageProcessor.isPhotograph(raw)
                    ensureActive()
                    val cg = if (photo) XrayImageProcessor.removeScreenPattern(raw) else raw
                    ensureActive()
                    Triple(cg, XrayImageProcessor.locate(cg, photo, hint), photo)
                }
                ensureActive()
                original = image
                detectedQuad = detected
                quad = detected ?: XrayQuad.full
                isPhoto = photo
                _enhance = photo
                Haptics.light()
                if (photo && detected == null) {
                    // Fotoğrafta röntgen bulunamadı: tüm fotoğraf analize gitmesin, köşeler elle seçilsin.
                    needsManualCrop = true
                    phase = Phase.adjust
                } else {
                    phase = Phase.review
                    rebuildPrepared()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    // MARK: - Alan seçimi

    fun resetToDetected() { quad = detectedQuad ?: XrayQuad.full; Haptics.selection() }
    fun resetToFull() { quad = XrayQuad.full; Haptics.selection() }

    fun rotate() {
        val original = this.original ?: return
        Haptics.selection()
        work?.cancel()
        val photo = isPhoto
        work = scope.launch {
            val rotated = withContext(Dispatchers.Default) { XrayImageProcessor.rotatedClockwise(original) } ?: return@launch
            if (!isActive) return@launch
            this@XrayAnalysisViewModel.original = rotated
            val q = withContext(Dispatchers.Default) { XrayImageProcessor.locate(rotated, photo) }
            detectedQuad = q
            quad = q ?: XrayQuad.full
        }
    }

    /** Alanı onayla → düzeltilmiş önizleme. */
    fun confirmRegion() {
        if (!(quad.isValid || quad.isFull)) return
        Haptics.medium()
        quadBeforeEdit = null
        needsManualCrop = false
        phase = Phase.review
        rebuildPrepared()
    }

    private fun rebuildPrepared() {
        val original = this.original ?: return
        if (!(phase == Phase.review || phase == Phase.adjust)) return
        val quad = this.quad
        val enhance = this._enhance
        isRebuilding = true
        work?.cancel()
        work = scope.launch {
            try {
                val (out, report) = withContext(Dispatchers.Default) {
                    val out = XrayImageProcessor.prepare(original, quad, enhance)
                    out to XrayImageProcessor.assessQuality(out)
                }
                ensureActive()
                prepared = out
                quality = report
                isRebuilding = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                isRebuilding = false
                fail(e)
            }
        }
    }

    /** Önizlemeden köşe düzenlemeye geçerken alan saklanır ("Vazgeç" geri yükler). */
    private var quadBeforeEdit: XrayQuad? by mutableStateOf(null)

    fun backToAdjust() {
        quadBeforeEdit = quad
        phase = Phase.adjust
    }

    /** Köşe düzenlemeden değişiklik yapmadan önizlemeye dön. */
    fun cancelAdjust() {
        quadBeforeEdit?.let { quad = it }
        quadBeforeEdit = null
        phase = Phase.review
        if (prepared == null) rebuildPrepared()
    }

    /** Köşe düzenleme önizlemeden mi açıldı (geri tuşu önizlemeye döner)? */
    val isEditingCrop: Boolean get() = quadBeforeEdit != null

    // MARK: - Analiz

    fun analyze() {
        val prepared = this.prepared
        if (prepared == null || !canAnalyze) {
            if (mode == Mode.guest && GuestTrialStore.shared.isExhausted(GuestTrialFeature.xray)) {
                fail(GuestTrialError.exhausted(GuestTrialFeature.xray))
            }
            return
        }
        Haptics.medium()
        creditRequired = null
        lastError = null
        startedAt = System.currentTimeMillis()
        elapsed = 0.0
        phase = Phase.analyzing
        startTicker()
        val mode = this.mode
        val patientID = patient?.id
        work = scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { XrayImageProcessor.uploadJPEG(prepared) }
                val result: XrayResult = when (mode) {
                    Mode.guest -> {
                        // Sunucu ulaşılamıyorsa 3 dk zaman aşımı beklenmez.
                        GuestTrialStore.shared.ensureAvailable()?.let { throw it }
                        val (r, quota) = GuestTrialClient().analyzeXray(jpeg)
                        GuestTrialStore.shared.apply(quota, GuestTrialFeature.xray)
                        r
                    }
                    Mode.clinic -> {
                        if (patientID == null) throw XrayClinicClient.ClinicError.noPatient()
                        XrayClinicClient().uploadAndAnalyze(patientID, jpeg)
                    }
                }
                ensureActive()
                ticker?.cancel()
                this@XrayAnalysisViewModel.result = result
                selectedToothID = null
                Haptics.success()
                phase = Phase.results
            } catch (e: CancellationException) {
                ticker?.cancel()
                throw e
            } catch (e: Throwable) {
                ticker?.cancel()
                if ((e as? GuestTrialError)?.isExhausted == true) GuestTrialStore.shared.markExhausted(GuestTrialFeature.xray)
                if (e is CreditRequired) creditRequired = e
                fail(e)
            }
            if (mode == Mode.guest) GuestTrialStore.shared.refresh()
        }
    }

    fun retry() {
        phase = when {
            prepared != null -> Phase.review
            original != null -> Phase.adjust
            else -> Phase.pickSource
        }
    }

    fun reset() {
        cancel()
        original = null; prepared = null; result = null; detectedQuad = null; quad = XrayQuad.full
        selectedToothID = null; creditRequired = null; lastError = null
        quality = XrayQualityReport(); needsManualCrop = false
        phase = Phase.pickSource
    }

    fun cancel() {
        work?.cancel(); work = null
        ticker?.cancel(); ticker = null
    }

    private fun fail(error: Throwable) {
        lastError = error
        Haptics.error()
        val msg = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
        phase = Phase.failed(msg)
    }

    val isTrialExhaustedError: Boolean
        get() = (lastError as? GuestTrialError)?.isExhausted == true

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                startedAt?.let { elapsed = (System.currentTimeMillis() - it) / 1000.0 }
            }
        }
    }

    // MARK: - Hasta arama (klinik kipi)

    private fun searchPatients() {
        searchTask?.cancel()
        val q = patientQuery.trim()
        if (q.length < 2) { patientResults = emptyList(); isSearchingPatients = false; return }
        isSearchingPatients = true
        searchTask = scope.launch {
            delay(300)
            if (!isActive) return@launch
            val found = try {
                ApiClient.searchPatients(q, limit = 8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            if (!isActive) return@launch
            patientResults = found.map {
                PatientRef(id = it.id, name = "${it.firstName} ${it.lastName}".trim(), subtitle = it.phone)
            }
            isSearchingPatients = false
        }
    }

    fun selectPatient(p: PatientRef) {
        patient = p
        patientQuery = ""
        patientResults = emptyList()
        Haptics.selection()
    }
}

// MARK: - Klinik istemcisi (oturum çereziyle)

/** `POST /api/patients/:id/xray` (yükle) → `POST /api/patients/:id/xray/:xid/analyze` (4 kredi). */
class XrayClinicClient {

    sealed class ClinicError(message: String) : Exception(message) {
        class noPatient : ClinicError("Önce hastayı seçin.")
        class sessionExpired : ClinicError("Oturumunuz sona ermiş. Tekrar giriş yapıp deneyin.")
        class forbidden : ClinicError("Bu hesabın röntgen analizi yetkisi yok.")
        class server(m: String?) : ClinicError(m ?: "Röntgen analizi tamamlanamadı. Tekrar deneyin.")
    }

    companion object {
        /** Ortak çerez deposuyla, uzun zaman aşımlı istemci (analiz CPU'da 30–60 sn sürebilir). */
        private val client by lazy {
            ApiClient.http.newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .callTimeout(240, TimeUnit.SECONDS)
                .build()
        }
    }

    suspend fun uploadAndAnalyze(patientID: String, jpeg: ByteArray): XrayResult {
        val safe = URLEncoder.encode(patientID, "UTF-8").replace("+", "%20")
        val base = AppConfig.apiBaseURL + "/patients/$safe/xray"

        // 1) Yükle
        val multipart = MultipartBody.Builder("dentifay-${UUID.randomUUID()}")
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "rontgen.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val up = Request.Builder().url(base).post(multipart).build()
        val upJSON = send(up)
        val xid = upJSON.obj("xray")?.str("id") ?: throw ClinicError.server("Röntgen yüklenemedi.")

        // 2) Analiz
        val an = Request.Builder().url("$base/$xid/analyze")
            .post("{}".toByteArray().toRequestBody(ApiClient.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()
        val anJSON = send(an)
        val result = anJSON.obj("analysis")?.get("result") ?: throw ClinicError.server("Analiz sonucu okunamadı.")
        return try {
            DfyJson.decodeFromJsonElement(XrayResult.serializer(), result)
        } catch (e: Exception) {
            throw ClinicError.server("Analiz sonucu okunamadı.")
        }
    }

    private suspend fun send(req: Request): JsonObject = withContext(Dispatchers.IO) {
        val raw = ApiClient.execute(req, client)
        CreditRequired.parse(raw.status, raw.body)?.let { throw it }
        val json = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        when (raw.status) {
            in 200..299 -> json
            401 -> {
                SessionEvents.report401(raw.body, req.url.encodedPath)
                throw ClinicError.sessionExpired()
            }
            403 -> throw ClinicError.forbidden()
            else -> throw ClinicError.server(json.str("error"))
        }
    }
}
