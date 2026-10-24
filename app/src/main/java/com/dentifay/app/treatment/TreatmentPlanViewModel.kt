@file:Suppress("ClassName", "ConstPropertyName")

package com.dentifay.app.treatment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.agent.*
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.FDI
import com.dentifay.app.core.Yetki
import com.dentifay.app.hasta.BugunHastalari
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.voice.DictationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tedavi Planı modülü — Dentifay Asistan'ın sohbet ucu üzerinden çalışır
 * (`POST /api/ai-assistant/chat`, SSE; `moduleScope: "mobil-tedavi-plani"`).
 *
 * Akış (MOBIL_IS_EMRI M-E1.2): hasta seç → **plan kartları** → "Yeni Plan Oluştur" ya da açık plana
 * ekleme (`hedefPlanId`) → planı yaz ya da söyle → asistan kompozit yazmayı hazırlar (`write_preview` +
 * `steps[]` + `ozet`) → **Sonuç Onayı** → Onayla / düzelt / Tekrar kaydet.
 *
 * Kurallar sunucudadır: plan adı (`sonrakiPlanAdi` — mobil plan adı alanı ve istemci talimatı YOK),
 * kapalı/sistem planı, hekim sorusu, konum. Mesaj yalnız `«hasta» için: "metin"` (sözleşme §6).
 * Kimlik her turda `pick{table:"patients"}` ile gider; sesli okuma yalnız önizleme özetinde (karar 9).
 *
 * `hareketsizlikTavani`: son SSE olayından bu yana bu süre geçerse kopukluk (M-E1.2.8). Test için enjekte.
 * `agent`: sohbet ucunun oturumu — testler ağsız akışlı bir oturum verir (gönderilen turu `agent.messages`tan okur).
 */
class TreatmentPlanViewModel(
    private val hareketsizlikTavani: Duration = 180.seconds,
    val agent: AgentSessionManager = AgentSessionManager(),
) {

    sealed class Phase {
        data object pickPatient : Phase()
        /** Hastanın plan kartları; `not` = hedef plan reddi gibi üstte gösterilecek sunucu cümlesi. */
        data class plans(val not: String?) : Phase()
        data object recording : Phase()
        data object thinking : Phase()
        /** Yapısal önizleme (Sonuç Onayı). */
        data object review : Phase()
        /** Seçim kartı + sunucunun sorusu. */
        data class choosing(val set: AgentChoiceSet, val soru: String) : Phase()
        /** Asistanın SORUSU — HATA DEĞİL. */
        data class asking(val soru: String) : Phase()
        /** Onay turu (kapatma pasif). */
        data object submitting : Phase()
        /** Bekleyen yazma iptal ediliyor (Ek MO.6e). */
        data object cancelling : Phase()
        /** Sunucunun sonuç metni. */
        data class done(val metin: String) : Phase()
        /** Yalnız GERÇEK hata. */
        data class failed(val message: String) : Phase()
    }

    data class PatientRef(val id: String, val name: String, val subtitle: String?)

    // MARK: Durum
    var phase by mutableStateOf<Phase>(Phase.pickPatient)

    // Hasta seçimi
    var loadingLists by mutableStateOf(true)
    /** Bugünkü hastalar iki bölüm — "Benim hastalarım" · "Diğer hekimlerin hastaları" (M-E8.5; kural `BugunHastalari`, tek istek). */
    var todayPatients by mutableStateOf<List<PatientRef>>(emptyList())
    var otherTodayPatients by mutableStateOf<List<PatientRef>>(emptyList())
    /** Oturumdan (görünüm atar): bölümleme ve gün sınırı. */
    var kullaniciId: String? = null
    var saatDilimi: String? = null
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<PatientRef>>(emptyList())
    var isSearching by mutableStateOf(false)
    var selectedPatient by mutableStateOf<PatientRef?>(null)
        private set

    // Plan kartları
    var planListesi by mutableStateOf<PlanListesi?>(null)
        private set
    var planlarYukleniyor by mutableStateOf(false)
        private set
    var planHatasi by mutableStateOf<String?>(null)
        private set
    /** Açık plana ekleme — `null` = yeni plan. */
    var hedefPlan by mutableStateOf<PlanKarti?>(null)
        private set

    // Kayıt (dikte) — TEK ses yığını (agent'ın oturumu).
    val speech: DictationSession get() = agent.speech
    val isListening: Boolean get() = agent.speech.isListening
    /** Kayıt ekranının düzenlenebilir metni — dikte bu alana EKLER, mikrofonsuz da yazılır (M-E1.2.6). */
    var kayitMetni by mutableStateOf("")
    /** Hekimin gönderdiği metin (önizleme ekranında gösterilir). */
    var spokenTranscript by mutableStateOf("")
        private set
    /** `asking` / seçim fazında kullanıcının yazdığı/dikte ettiği cevap. */
    var answerDraft by mutableStateOf("")

    // Agent turu
    /** İlerleme rayı — alanları anlık durumdur ve YERİNDE güncellenir (ray ASLA geri gitmez; kural `ProgressMapper`da). */
    var progress by mutableStateOf(ProgressStep())
        private set
    val progressText: String get() = progress.liveText
    val progressStepIndex: Int get() = progress.index
    val elapsedSeconds: Int get() = agent.elapsedSeconds
    var writePreview by mutableStateOf<AgentWritePreview?>(null)
        private set
    var planSummary by mutableStateOf("")
    /** Tur kredi yüzünden başlamadıysa — hata ekranında kredi kartı çizilir. */
    var creditRequired by mutableStateOf<CreditRequired?>(null)
        private set
    /** Son turun zaman aşımıyla mı düştüğü — "Tekrar dene" o durumda plan listesine döner (M-E1.2.8). */
    var zamanAsimindaKaldi by mutableStateOf(false)
        private set

    /** Önizlemedeki tedavi kalemleri (plan adımı hariç). Önizleme değişene dek önbellekte. */
    val items: List<AgentWriteStep> by derivedStateOf {
        (writePreview?.steps ?: emptyList()).filter { it.entity == "treatment_items" }
    }
    /** Önizlemedeki plan adımı (varsa) — başlığı ekranda gösterilir. */
    val planStep: AgentWriteStep? by derivedStateOf {
        (writePreview?.steps ?: emptyList()).firstOrNull { it.entity == "treatment_plans" }
    }
    /** Plan toplamı — sunucunun `toplamTL`i (COMMIT'in yazacağı TL); yoksa/`null`sa satır çizilmez. Mobil TOPLAMAZ. */
    val toplamTL: Double? get() = writePreview?.toplamTL

    // Hekim seçici (Tedavi Planı) — `?atanabilir=1`; tarihsiz uç KULLANILMAZ.
    var hekimler by mutableStateOf<List<APIDoctorMember>>(emptyList())
        private set
    var hekimlerYukleniyor by mutableStateOf(false)
        private set
    var hekimHatasi by mutableStateOf<String?>(null)
        private set
    /** Onay kapısı vaadi: COMMIT edilecek kayıt sayısı ile gösterilen ayrışıyorsa arayüz bunu AÇIKÇA yazar (sessiz kırpma YASAK). */
    val hiddenStepCount: Int get() = writePreview?.hiddenStepCount ?: 0

    /** Ek MO.4a / MO.4b — kayıt ekranının başlığı. */
    val kayitBasligi: String
        get() {
            hedefPlan?.let { return "${it.baslik} planına ekleniyor" }
            return "Yeni plan · ${planListesi?.sonrakiPlanAdi ?: "Plan"}"
        }

    /** Onay turunda kapatma pasif (M-E1.2.8, Ek MO.6g). */
    val kapatmaSerbest: Boolean get() = TedaviPlaniFazi.kapatmaSerbest(phase)

    // Dahili
    private val scope = MainScope()
    private var searchTask: Job? = null
    private var watchdog: Job? = null
    private var wired = false
    /** Bu turda `soft_fail`/`error` geldi mi — "soru mu hata mı" TAHMİN EDİLMEZ. */
    private var turnFailure: String? = null
    /** Bu turdan ÖNCEKİ önizleme (düzeltme turu). Tur boş dönerse planı KAYBETME, bunu geri yükle. */
    private var previewBeforeTurn: AgentWritePreview? = null
    /** `cancelling` — iptal turu bitince HATA değil kayıt (M-E1.2.7). */
    private var iptalBekleniyor = false
    /** Dikte başlarken alandaki metin — yeni söz bunun ARKASINA eklenir. */
    private var dikteTabani = ""

    /** Önizleme ve örnek ekranlar için kurmaca veriyle açıldıysa ağ ve mikrofon kullanılmaz. */
    var ornekVeriKullaniliyor = false
        private set

    init {
        // Uzun sessizlikte kesme YOK — hekim düşünürken kayıt durmasın.
        agent.speech.autoStopOnSilence = false
        // Dikte sözlüğü: yalnız hekim + tedavi adları (M-E1.2.10).
        agent.speech.sozlukKovalari = AgentClient.tedaviPlaniKovalari
    }

    // MARK: - Yükleme

    fun onAppear() {
        if (ornekVeriKullaniliyor) return
        scope.launch {
            if (!agent.speech.isAuthorized) agent.speech.requestAuthorization()
            loadInitial()
        }
    }

    private suspend fun loadInitial() {
        loadingLists = true
        val bugun = runCatchingNonCancel { BugunHastalari.yukle(kullaniciId = kullaniciId, saatDilimi = saatDilimi) }
        todayPatients = bugun?.let { patients(it.benim) } ?: emptyList()
        otherTodayPatients = bugun?.let { patients(it.diger) } ?: emptyList()
        loadingLists = false
    }

    // MARK: - Arama

    fun searchChanged() {
        searchTask?.cancel()
        val q = searchQuery.trim()
        if (q.length < 2) { searchResults = emptyList(); isSearching = false; return }
        isSearching = true
        searchTask = scope.launch {
            delay(300)
            val results = runCatchingNonCancel { ApiClient.searchPatients(q) } ?: emptyList()
            if (!isActive) return@launch
            searchResults = results.map {
                PatientRef(it.id, "${it.firstName} ${it.lastName}".trim(), it.phone)
            }
            isSearching = false
        }
    }

    // MARK: - Hasta seçimi → plan kartları

    fun selectPatient(p: PatientRef) {
        selectedPatient = p
        wireAgentIfNeeded()
        // 🔴 KİMLİK HER TURDA GİDER. `term` = mesajda AYNEN geçen ad (sözleşme §6 mesaj biçimi).
        agent.stickyPick = AgentClient.PickPayload("patients", p.name, p.id, p.name)
        phase = Phase.plans(null)
        scope.launch { planlariYukle() }
    }

    /** Plan listesini (yeniden) okur. Boş liste ile yükleme hatası ayrı durumlardır (Ek MO.2). */
    suspend fun planlariYukle() {
        val p = selectedPatient ?: return
        if (ornekVeriKullaniliyor) return
        planlarYukleniyor = true
        planHatasi = null
        try {
            planListesi = ApiClient.fetchPatientPlans(p.id)
        } catch (e: CancellationException) {
            planlarYukleniyor = false
            throw e
        } catch (e: Exception) {
            if (Yetki.reddiMi(e)) {
                // Kodsuz 403: kırmızı kart yok — boş liste (M-E7.5).
                planListesi = PlanListesi(plans = emptyList(), pagination = null, sonrakiPlanAdi = null)
                planHatasi = null
            } else if (e is ApiException && e.status == 403) {
                planListesi = null
                planHatasi = e.message ?: "Planlar yüklenemedi."
            } else {
                planListesi = null
                planHatasi = "Planlar yüklenemedi."
            }
        }
        planlarYukleniyor = false
    }

    /** "Planlara dön" — listeyi yeniler (plan yazılmış olabilir). */
    fun planlaraDon(not: String? = null) {
        stopSpeaking()
        stopDictation()
        watchdog?.cancel(); watchdog = null
        writePreview = null; previewBeforeTurn = null
        hedefPlan = null
        agent.hedefPlanId = null
        zamanAsimindaKaldi = false
        phase = Phase.plans(not)
        scope.launch { planlariYukle() }
    }

    /** Plan listesinden hasta seçimine geri. */
    fun hastaSecimineDon() {
        selectedPatient = null
        planListesi = null
        planHatasi = null
        hedefPlan = null
        agent.stickyPick = null
        agent.hedefPlanId = null
        phase = Phase.pickPatient
    }

    /** "Yeni Plan Oluştur" — yeni sohbet, hedef plan yok. */
    fun yeniPlan() {
        hedefPlan = null
        agent.hedefPlanId = null
        agent.newChat()
        beginRecording()
    }

    /** Açık plana dokunuş — aynı kayıt, hedef planla (`hedefPlanId` her istekte). */
    fun plandaDevam(plan: PlanKarti) {
        if (!plan.dokunulabilir) return
        hedefPlan = plan
        agent.newChat()
        agent.hedefPlanId = plan.id
        beginRecording()
    }

    // MARK: - Kayıt (yazarak ya da dikteyle)

    fun beginRecording() {
        writePreview = null; previewBeforeTurn = null; planSummary = ""; spokenTranscript = ""
        turnFailure = null
        iptalBekleniyor = false
        zamanAsimindaKaldi = false
        kayitMetni = ""
        creditRequired = null
        phase = Phase.recording
        startDictation()
    }

    /** Dikte alana EKLER (sohbetin `applyDictationTranscript` deseni). */
    fun toggleDictation() {
        if (agent.speech.isListening) stopDictation() else startDictation()
    }

    private fun startDictation() {
        if (ornekVeriKullaniliyor) return
        dikteTabani = kayitMetni
        agent.speech.onTranscriptChange = { t ->
            if (agent.speech.isListening) kayitMetni = dikteyiEkle(dikteTabani, t)
        }
        scope.launch {
            // Mikrofon yoksa yazarak devam edilir — hata ekranına düşülmez.
            agent.speech.startListening()
        }
    }

    fun stopDictation() {
        agent.speech.stopListening()
        agent.speech.onTranscriptChange = null
    }

    /** Kullanıcı alana yazmaya başlayınca dikte durur (yazılan metin üzerine yazılmasın). */
    fun kullaniciYaziyor() {
        if (agent.speech.isListening) stopDictation()
    }

    /** "Sıfırla" — alanı temizler. */
    fun restartRecording() {
        Haptics.rigid()
        val dinliyordu = agent.speech.isListening
        stopDictation()
        agent.speech.resetTranscript()
        kayitMetni = ""
        if (dinliyordu) startDictation()
    }

    val gonderilebilir: Boolean get() = kayitMetni.isNotBlank()

    /** "Çözümle" — gönderilen metin alanın içeriğidir (STT çıktısı değil). */
    fun finishAndSend() {
        val text = kayitMetni.trim()
        val hasta = selectedPatient?.name
        if (text.isEmpty() || hasta == null) return
        stopDictation()
        Haptics.selection()
        spokenTranscript = text
        send(TedaviPlaniMesaji.ilk(hasta, text))
    }

    // MARK: - `asking` — asistanın sorusuna cevap (AYNEN gider)

    fun submitAnswer() {
        val text = answerDraft.trim()
        if (text.isEmpty()) return
        answerDraft = ""
        stopAnswerDictation()
        Haptics.selection()
        send(text)
    }

    fun toggleAnswerDictation() {
        if (agent.speech.isListening) stopAnswerDictation() else startAnswerDictation()
    }

    private fun startAnswerDictation() {
        if (ornekVeriKullaniliyor) return
        val base = answerDraft
        agent.speech.onTranscriptChange = { t -> answerDraft = dikteyiEkle(base, t) }
        scope.launch { agent.speech.startListening() }
    }

    private fun stopAnswerDictation() {
        agent.speech.stopListening()
        agent.speech.onTranscriptChange = null
    }

    // MARK: - Seçim kartı

    fun chooseOption(choice: AgentChoice) {
        if (phase !is Phase.choosing) return
        if (choice.pick == null) return   // kimliksiz kart dokunulamaz
        Haptics.selection()
        stopSpeaking()
        turnFailure = null
        previewBeforeTurn = null
        writePreview = null
        progress = ProgressStep()
        phase = Phase.thinking
        armWatchdog()
        agent.sendChoice(choice)
    }

    /** "Listede yok" → menüden seçim: kart dokunuşuyla AYNI yol (`pick{service_catalog, term:<olayın term'i>}`). */
    fun menudenSec(oge: MenuSecimYaniti.Oge, kart: AgentChoiceSet) {
        if (phase !is Phase.choosing) return
        Haptics.selection()
        send(oge.name, MenuSecimYaniti.pick(oge, kart))
    }

    /** Seçim ekranında yazılan/dikte edilen cevap AYNEN gider (`asking` ile aynı alan). */
    val cevapGonderilebilir: Boolean get() = answerDraft.isNotBlank()

    /** Önizlemenin geçerlilik süresi geçti mi (Ek O.27C-4 düğmesi; karar sunucunun). */
    fun onizlemeSuresiDoldu(simdi: Instant = Instant.now()): Boolean = OnayIstegi.suresiDoldu(writePreview, simdi)

    // MARK: - Önizleme düzeltmeleri (`…` menüsü) — Ek MO.9 kalıpları, kalemi sunucunun `index`i anar
    //
    // Mobil kalemi kendisi DEĞİŞTİRMEZ; yeni önizleme sunucudan gelir. Kullanıcının serbest metni REGEX'LENMEZ.

    /** Konumu değiştir (diş · üst/alt çene · yarım çene · konumsuz). Dönüş: hata metni (null = gönderildi). */
    fun konumuDegistir(adim: AgentWriteStep, secim: KonumSecimi): String? {
        if (secim is KonumSecimi.dis) {
            val (disler) = secim
            if (disler.isEmpty()) return FDI.hataMetni("")
            disler.firstOrNull { !FDI.gecerliMi(it) }?.let { return FDI.hataMetni(it) }
        }
        sendCorrection(DuzeltmeMesaji.konum(adim, secim))
        return null
    }

    fun changeService(adim: AgentWriteStep, newName: String): String? {
        val n = newName.trim()
        if (n.isEmpty()) return "Tedavi adı boş olamaz."
        sendCorrection(DuzeltmeMesaji.tedavi(adim, n))
        return null
    }

    fun removeItem(adim: AgentWriteStep) {
        sendCorrection(DuzeltmeMesaji.sil(adim))
    }

    /** Kalem ekle — konum isteğe bağlı (aynı seçici). */
    fun addItem(name: String, konum: KonumSecimi?): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Tedavi adı boş olamaz."
        if (konum is KonumSecimi.dis) {
            val (disler) = konum
            disler.firstOrNull { !FDI.gecerliMi(it) }?.let { return FDI.hataMetni(it) }
        }
        sendCorrection(DuzeltmeMesaji.ekle(n, konum))
        return null
    }

    /** Hekim listesi (`?atanabilir=1`). Hata Ek MO.8. */
    suspend fun hekimleriYukle() {
        if (ornekVeriKullaniliyor) { hekimler = OrnekTedaviPlani.hekimler; return }
        hekimlerYukleniyor = true
        hekimHatasi = null
        try {
            hekimler = ApiClient.fetchAssignableDoctors()
        } catch (e: CancellationException) {
            hekimlerYukleniyor = false
            throw e
        } catch (e: Exception) {
            hekimHatasi = if (Yetki.reddiMi(e)) Yetki.EYLEM_YETKISIZ_METNI else "Hekim listesi yüklenemedi."
        }
        hekimlerYukleniyor = false
    }

    /** Kalemin hekimi — Ek MO.9f + aynı istekte `pick{table:"users"}` (26E.6). Fiyat değişmez (sunucu). */
    fun kalemHekiminiDegistir(adim: AgentWriteStep, hekim: APIDoctorMember) {
        val ad = hekim.user.name ?: ""
        sendCorrection(DuzeltmeMesaji.hekim(adim, ad), DuzeltmeMesaji.hekimPick(hekim.userId, ad))
    }

    /** Planın hekimi (yalnız yeni planda) — Ek MO.9g; hangi kalemin değişeceğine sunucunun `hekimKarari` karar verir. */
    fun planHekiminiDegistir(hekim: APIDoctorMember) {
        if (hedefPlan != null) return
        val ad = hekim.user.name ?: ""
        sendCorrection(DuzeltmeMesaji.planHekimi(ad), DuzeltmeMesaji.hekimPick(hekim.userId, ad))
    }

    private fun sendCorrection(text: String, pick: AgentClient.PickPayload? = null) {
        Haptics.selection()
        stopSpeaking()
        send(text, pick)
    }

    // MARK: - Onay / iptal

    fun confirm() {
        val pv = writePreview ?: return
        stopSpeaking()
        turnFailure = null
        progress = ProgressStep(liveText = "Kaydediliyor…")
        // `submitting`te bekçi KURULMAZ (sunucu turu kendi bütçesiyle biter) ve kapatma pasiftir.
        watchdog?.cancel(); watchdog = null
        if (!agent.confirmWrite(pv.requiresDoubleConfirm)) {
            phase = Phase.review   // süresi dolmuş önizleme: onay gönderilmez
            return
        }
        phase = Phase.submitting
    }

    /** Bir asistan turunun sonucu bekleniyor mu (düşünme · iptal · onay turu). */
    private val turBekleniyor: Boolean
        get() = TedaviPlaniFazi.bekciKurulur(phase) || phase == Phase.submitting

    /** Bekleyen yazma var mı (önizleme ya da düzeltme turu öncesindeki önizleme). */
    val bekleyenYazmaVar: Boolean get() = writePreview != null || previewBeforeTurn != null

    /**
     * Üç giriş — "Tekrar Kaydet" · önizleme geri oku · hata ekranı "Tekrar dene": bekleyen yazma varsa
     * önce iptal turu (`cancelling`), bitince kayıt; yoksa doğrudan kayıt (M-E1.2.7).
     */
    fun retryDictation() {
        stopSpeaking()
        if (!bekleyenYazmaVar) { beginRecording(); return }
        iptalBekleniyor = true
        turnFailure = null
        writePreview = null
        progress = ProgressStep()
        phase = Phase.cancelling
        armWatchdog()
        agent.cancelWrite()
    }

    fun cancelThinking() {
        watchdog?.cancel(); watchdog = null
        agent.stop()
        beginRecording()
    }

    /** Hata ekranındaki "Tekrar dene": zaman aşımından sonra plan listesine döner (plan yazılmış olabilir). */
    fun hataSonrasiTekrar() {
        if (zamanAsimindaKaldi) { planlaraDon(); return }
        retryDictation()
    }

    /** `asking` / önizleme geri oku. */
    fun backToRecording() = retryDictation()

    // MARK: - TTS (tek yığın)

    /** "Dinle" — önizleme özetini (`write_preview.ozet`) yeniden okur. */
    fun speakSummary() {
        val ozet = writePreview?.ozet ?: return
        Haptics.light()
        agent.speech.speak(ozet)
    }

    fun stopSpeaking() = agent.speech.stopSpeaking()

    // MARK: - Agent köprüsü

    private fun wireAgentIfNeeded() {
        if (wired) return
        wired = true
        // Sunucu-sahipli odak (tek enum id; serbest prompt DEĞİL).
        agent.moduleScope = "mobil-tedavi-plani"
        agent.onWritePreview = { pv -> writePreview = pv }
        agent.onAssistantComplete = { msg -> handleTurnComplete(msg) }
        // Ray ASLA geri gitmez — kural `ProgressMapper`ın içinde; adım YERİNDE güncellenir (Swift `inout`).
        agent.onProgress = { p -> ProgressMapper.apply(p, progress) }
        // Her SSE olayı bekçiyi yeniden kurar — hareketsizlik tabanlı (M-E1.2.8).
        agent.onActivity = { armWatchdog() }
        agent.onSoftFail = { msg -> turnFailure = msg }
        agent.onChoiceDispatched = { _ -> armWatchdog() }
    }

    private fun send(text: String, pick: AgentClient.PickPayload? = null) {
        turnFailure = null
        // 🔴 BAYAT ÖNİZLEME DÜŞÜRÜLÜR (düzeltme turu soruyla dönerse eski önizleme onaylanmasın);
        //    boş dönerse geri yükleyebilmek için önce SAKLA.
        previewBeforeTurn = writePreview
        writePreview = null
        progress = ProgressStep()
        phase = Phase.thinking
        armWatchdog()
        agent.send(text, pick)   // pick yoksa stickyPick (seçili hasta) — HER turda
    }

    /** Hareketsizlik bekçisi: yalnız `thinking` / `cancelling`te; her olayda yeniden kurulur. */
    private fun armWatchdog() {
        watchdog?.cancel()
        if (!TedaviPlaniFazi.bekciKurulur(phase)) { watchdog = null; return }
        val timeout = hareketsizlikTavani
        watchdog = scope.launch {
            delay(timeout)
            if (!TedaviPlaniFazi.bekciKurulur(phase)) return@launch
            agent.stop()
            iptalBekleniyor = false
            zamanAsimindaKaldi = true
            phase = Phase.failed(TedaviPlaniFazi.zamanAsimi)
        }
    }

    /** Faz kararı saf `TedaviPlaniFazi.turSonu`dadır (M-E1.2.11); VM yalnız yan etkileri uygular. */
    private fun handleTurnComplete(msg: AgentMessage) {
        // Yalnız beklenen turun sonu işlenir: "İptal et" ya da bekçi akışı kestikten SONRA gelen kapanış (Android'de
        // iptal edilen turun kapanışı her zaman geç gelir) kayda dönmüş ekranı hata ekranına çevirmesin.
        if (!turBekleniyor) return
        watchdog?.cancel(); watchdog = null

        // Kredi: tur hiç başlamadı ya da yarıda kaldı → kredi kartlı hata ekranı.
        msg.creditRequired?.let { credit ->
            creditRequired = credit
            iptalBekleniyor = false
            if (credit.yarida) writePreview = null   // yarıda kalan turun önizlemesi çizilmez
            Haptics.warning()
            phase = Phase.failed(credit.message)
            return
        }

        val karar = TedaviPlaniFazi.turSonu(
            mesaj = msg, onizleme = writePreview, oncekiOnizleme = previewBeforeTurn,
            iptalBekleniyor = iptalBekleniyor, turHatasi = turnFailure,
        )
        iptalBekleniyor = false
        when (karar) {
            is Phase.plans -> planlaraDon(karar.not)
            Phase.recording -> beginRecording()
            Phase.review -> {
                val pv = writePreview
                val prev = previewBeforeTurn
                if (pv == null && prev != null) {
                    // Düzeltme turu boş döndü → önceki önizleme geri gelir.
                    writePreview = prev
                    Haptics.error()
                    phase = Phase.review
                } else if (pv != null) {
                    planSummary = pv.ozet ?: ozetYedegi
                    Haptics.light()
                    phase = Phase.review
                    // `ozet` bir kez kendiliğinden okunur; "Dinle" yeniden okur.
                    if (pv.ozet != null) agent.speech.speak(planSummary)
                }
            }
            is Phase.done -> {
                planSummary = karar.metin
                Haptics.success()
                phase = karar
            }
            is Phase.asking -> {
                answerDraft = ""
                phase = karar
            }
            else -> phase = karar
        }
    }

    fun teardown() {
        searchTask?.cancel()
        watchdog?.cancel(); watchdog = null
        // Bağlar akış kesilmeden ÖNCE çözülür — kesilen turun kapanışı kapanan ekrana dokunmasın.
        agent.onWritePreview = null; agent.onAssistantComplete = null
        agent.onProgress = null; agent.onSoftFail = null; agent.onChoiceDispatched = null
        agent.onActivity = null
        agent.stop()
        agent.stickyPick = null
        agent.hedefPlanId = null
        agent.speech.onTranscriptChange = null
        agent.releaseAudio()
    }

    /** Ekran kapanınca (Android): teardown + ses yığınını, ajanın ve bu modelin iş kapsamlarını bırakır. */
    fun dispose() {
        teardown()
        agent.speech.dispose()
        agent.dispose()
        scope.cancel()
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Ek MO.7c — önizlemede `ozet` yoksa ekranda gösterilen metin (okunmaz). */
        const val ozetYedegi = "Plan hazırlandı. Onaylarsanız kaydedilecek."

        private val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("tr-TR")).withZone(ZoneId.systemDefault())

        /** Kayıtlı hasta kartları (misafir randevusunun hasta kaydı yok — plan açılamaz, listelenmez). */
        private fun patients(kartlar: List<BugunHastalari.Kart>): List<PatientRef> = kartlar.mapNotNull { k ->
            val id = k.hastaId ?: return@mapNotNull null
            val parts = mutableListOf(timeFormatter.format(k.saat))
            k.telefon?.takeIf { it.isNotEmpty() }?.let { parts += it }
            PatientRef(id, k.hastaAdi, parts.joinToString(" · "))
        }

        /** Dikte alana EKLER: taban + (gerekirse boşluk) + söz. */
        fun dikteyiEkle(taban: String, soz: String): String {
            if (taban.isEmpty()) return soz
            if (soz.isEmpty()) return taban
            val sep = if (taban.endsWith(" ")) "" else " "
            return taban + sep + soz
        }

        /**
         * Önizleme ve örnek ekranlar için kurmaca veriyle kurulmuş model (Swift `ornek(_:)`, `UITEST_PRESENT=tp-*`).
         * Ağ ve mikrofon KULLANILMAZ. `hedef`: tp-planlar · tp-plansiz · tp-onizleme · tp-secim · tp-secim-bos · tp-kayit ·
         * tp-soru · tp-bitti · tp-hata · tp-kaydediliyor · tp-hasta.
         */
        fun ornek(hedef: String): TreatmentPlanViewModel {
            val vm = TreatmentPlanViewModel()
            vm.ornekVeriKullaniliyor = true
            vm.loadingLists = false
            val hasta = PatientRef("ornek-hasta", "Deneme Hasta", "10:30")
            vm.todayPatients = listOf(hasta, PatientRef("ornek-2", "Örnek Kişi", "11:15 · 0532 000 00 00"))
            vm.otherTodayPatients = listOf(PatientRef("ornek-3", "Deneme Hasta İki", "14:00"))
            vm.selectedPatient = hasta
            vm.planListesi = OrnekTedaviPlani.planListesi
            when (hedef) {
                "tp-hasta" -> { vm.selectedPatient = null; vm.phase = Phase.pickPatient }
                "tp-planlar" -> vm.phase = Phase.plans(null)
                "tp-plansiz" -> {
                    vm.planListesi = PlanListesi(plans = emptyList(), pagination = PlanListesi.Sayfalama(total = 0), sonrakiPlanAdi = "Plan 1")
                    vm.phase = Phase.plans(null)
                }
                "tp-onizleme" -> {
                    vm.writePreview = OrnekTedaviPlani.onizleme
                    vm.planSummary = OrnekTedaviPlani.onizleme.ozet ?: ""
                    vm.spokenTranscript = "36'ya dolgu, üst çeneye zirkonyum, 46'ya kanal"
                    vm.phase = Phase.review
                }
                "tp-secim" -> vm.phase = Phase.choosing(OrnekTedaviPlani.secim, "Hangi dolgu tedavisini kastettiniz?")
                "tp-secim-bos" -> vm.phase = Phase.choosing(OrnekTedaviPlani.secimBos, "«Ozon terapi» tedavi menüsünde bulunamadı.")
                "tp-kayit" -> {
                    vm.phase = Phase.recording
                    vm.kayitMetni = "36'ya dolgu, 46'ya kanal tedavisi"
                }
                "tp-soru" -> vm.phase = Phase.asking("Kanal tedavisini hangi hekime atayalım?")
                "tp-bitti" -> vm.phase = Phase.done("**Plan 4** kaydedildi: 3 tedavi eklendi.")
                "tp-hata" -> vm.phase = Phase.failed(TedaviPlaniFazi.zamanAsimi)
                "tp-kaydediliyor" -> {
                    vm.progress = ProgressStep(liveText = "Kaydediliyor…")
                    vm.phase = Phase.submitting
                }
                else -> vm.phase = Phase.plans(null)
            }
            return vm
        }
    }
}

/**
 * Kurmaca örnek veri ("Deneme Hasta", "Deneme A", "Prof. Dr. B") — gerçek hasta/personel verisi YOK (M0-8).
 * Önizleme ve SSE biçimi sunucunun yayınladığı JSON'dan aynı çözücülerle kurulur.
 */
object OrnekTedaviPlani {
    val planListesi = PlanListesi(
        plans = listOf(
            PlanKarti(
                id = "p1", title = "Plan 1", status = "IN_PROGRESS", doctor = PlanKarti.Hekim("d1", "Deneme A"),
                kalemSayisi = 3,
                ilkKalemler = listOf(
                    PlanKarti.IlkKalem("Kompozit Dolgu", "36"),
                    PlanKarti.IlkKalem("Kanal Tedavisi", "46"),
                    PlanKarti.IlkKalem("Detartraj", null),
                ),
                sistemPlani = false, kapali = false, onayBekliyor = false,
            ),
            PlanKarti(
                id = "p2", title = "Plan 2", status = "DRAFT", doctor = null, kalemSayisi = 1,
                ilkKalemler = listOf(PlanKarti.IlkKalem("Zirkonyum Kaplama", "Üst çene")),
                sistemPlani = false, kapali = false, onayBekliyor = false,
            ),
            PlanKarti(
                id = "p0", title = "Doğrudan Eklenen İşlemler", status = "IN_PROGRESS", doctor = null, kalemSayisi = 2,
                ilkKalemler = listOf(PlanKarti.IlkKalem("Muayene", null)),
                sistemPlani = true, kapali = false, onayBekliyor = false,
            ),
            PlanKarti(
                id = "p3", title = "Plan 3", status = "COMPLETED", doctor = PlanKarti.Hekim("d2", "Prof. Dr. B"),
                kalemSayisi = 2,
                ilkKalemler = listOf(PlanKarti.IlkKalem("Diş Çekimi", "18")),
                sistemPlani = false, kapali = true, onayBekliyor = false,
            ),
        ),
        pagination = PlanListesi.Sayfalama(total = 4),
        sonrakiPlanAdi = "Plan 4",
    )

    private fun nesne(json: String): Map<String, JSONValue> = JSONValue.parse(json)?.objectValue ?: emptyMap()

    val secim: AgentChoiceSet by lazy {
        AgentChoiceSet.parse(
            nesne(
                """{"type":"entity_choices","table":"service_catalog","term":"dolgu","priceListId":"menu-1","elleSecim":true,
                   "choices":[{"id":"s1","name":"Kompozit Dolgu","detail":"1.800,00 TL"},
                              {"id":"s4","name":"Amalgam Dolgu","detail":"1.200,00 TL"}]}"""
            )
        )
    }

    val secimBos: AgentChoiceSet by lazy {
        AgentChoiceSet.parse(
            nesne("""{"type":"entity_choices","table":"service_catalog","term":"ozon terapi","priceListId":"menu-1","elleSecim":true,"choices":[]}""")
        )
    }

    val hekimler: List<APIDoctorMember> = listOf(
        APIDoctorMember(id = "m1", userId = "d1", role = "OWNER", user = com.dentifay.app.core.APIDoctorUser(id = "d1", name = "Deneme A", email = "a@ornek.test")),
        APIDoctorMember(id = "m2", userId = "d2", role = "DOCTOR", user = com.dentifay.app.core.APIDoctorUser(id = "d2", name = "Prof. Dr. B", email = "b@ornek.test")),
    )

    /** Plan adımı + üç kalem (dişli · "Üst çene" · ABD Doları fiyatlı); kalem hekimleri unvansız "Deneme A" (kuralla
     *  "Dt. Deneme A") / "Prof. Dr. B" (aynen); klinik adı. */
    val onizleme: AgentWritePreview by lazy {
        AgentWritePreview.parse(
            nesne(
                """{"type":"write_preview","operation":"create","entity":"treatment_items","risk":"low","affectedCount":4,
                   "requiresDoubleConfirm":false,
                   "steps":[
                     {"index":0,"entity":"treatment_plans","operation":"insert","title":"Plan 4","affectedCount":1,
                      "fields":{"doctorUserId":{"raw":"d1","label":"Deneme A"}}},
                     {"index":1,"entity":"treatment_items","operation":"insert","title":"Kompozit Dolgu","linkedToStep":0,
                      "affectedCount":1,"tutarTL":1800,"toothCodes":["36"],
                      "fields":{"tooth":{"raw":"36","label":"36"},"catalogServiceId":{"raw":"s1","label":"Kompozit Dolgu"},
                                "price":{"raw":"1800","label":"1.800,00 TL"},"assignedDoctorId":{"raw":"d1","label":"Deneme A"}}},
                     {"index":2,"entity":"treatment_items","operation":"insert","title":"Zirkonyum Kaplama","linkedToStep":0,
                      "affectedCount":1,"tutarTL":12000,
                      "fields":{"jawType":{"raw":"UPPER","label":"Üst çene"},"catalogServiceId":{"raw":"s2","label":"Zirkonyum Kaplama"},
                                "price":{"raw":"12000","label":"12.000,00 TL"},"assignedDoctorId":{"raw":"d2","label":"Prof. Dr. B"}}},
                     {"index":3,"entity":"treatment_items","operation":"insert","title":"Kanal Tedavisi","linkedToStep":0,
                      "affectedCount":1,"tutarTL":8240,"toothCodes":["46"],
                      "fields":{"tooth":{"raw":"46","label":"46"},"catalogServiceId":{"raw":"s3","label":"Kanal Tedavisi"},
                                "price":{"raw":"200","label":"200,00 USD (kur 41,20)"},"assignedDoctorId":{"raw":"d2","label":"Prof. Dr. B"}}}
                   ],
                   "ozet":"Deneme Hasta için Plan 4 açılacak ve 3 tedavi eklenecek. Onaylıyor musunuz?",
                   "toplamTL":22040,
                   "klinik":{"ad":"Deneme Klinik"}}"""
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 500)
@Composable
private fun TreatmentPlanViewModelPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-onizleme") }
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(vm.kayitBasligi, style = sys(13), color = DS.Colors.cyan400)
            Text(vm.planSummary, style = sys(15), color = DS.Colors.onSurface)
            vm.items.forEach { Text("${it.index}. ${it.title}", style = sys(13), color = DS.Colors.onSurfaceVariant) }
            vm.todayPatients.forEach { Text("${it.name} · ${it.subtitle}", style = sys(13), color = DS.Colors.outline) }
        }
    }
}
