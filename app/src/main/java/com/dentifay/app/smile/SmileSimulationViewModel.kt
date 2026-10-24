package com.dentifay.app.smile

import androidx.compose.runtime.mutableIntStateOf
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.exifinterface.media.ExifInterface
import com.dentifay.app.BuildConfig
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.ApiException
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.theme.Haptics
import com.dentifay.app.trial.GuestTrialError
import com.dentifay.app.trial.GuestTrialFeature
import com.dentifay.app.trial.GuestTrialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Gülüş simülasyonu · Before / After.
 *
 * Akış: rehberli çekim / galeri → ağız dikdörtgeni ve diş maskesi otomatik
 * seçilir → hekim tek bir tasarım ve kalite kademesi seçer (Orta · Orta İyi ·
 * İyi · Çok İyi), isterse ince ayar yapar → yalnızca ağız dikdörtgeni yüklenir
 * (bir kez) → sonuç cihazda hizalanıp yalnızca diş bölgesi fotoğrafa
 * yerleştirilir. Model ağzı kaydırırsa maskeli modelle OTOMATİK bir kez daha
 * denenir (ücretsiz). Her üretim kliniğin kredisinden düşer (sunucu yolu).
 */
class SmileSimulationViewModel(guest: Boolean = false) {

    sealed class Phase {
        object pickPhoto : Phase()
        object analyzing : Phase()
        object configure : Phase()
        object results : Phase()
        data class failed(val message: String) : Phase()
    }

    /** Tek tasarımın üretimi. */
    data class Job(
        val id: UUID = UUID.randomUUID(),
        val title: String,
        val icon: String,
        val design: SmileDesign,
        var state: State = State.queued,
        var result: Bitmap? = null,
        var startedAt: Instant = Instant.now(),
        var finishedIn: Double? = null,
        /** Ör. "Yerleşim maskeli modelle düzeltildi". */
        var note: String? = null,
        var placementScore: Double? = null,
        /** Hata kredi yetersizliğinden mi (→ "Kredi Al")? */
        var needsCredits: Boolean = false,
        /** Ücretsiz deneme hakkı bitti mi (→ "İletişime geçin")? */
        var needsContact: Boolean = false,
        /** Kalite kademesi adı (Orta, İyi…). */
        var tierTitle: String = "",
    ) {
        sealed class State {
            object queued : State()
            object uploading : State()
            object generating : State()
            object compositing : State()
            object done : State()
            data class failed(val message: String) : State()

            val label: String
                get() = when (this) {
                    queued -> "Sırada"
                    uploading -> "Ağız bölgesi gönderiliyor"
                    generating -> "Tasarım hazırlanıyor"
                    compositing -> "Fotoğrafa yerleştiriliyor"
                    done -> "Hazır"
                    is failed -> "Hata"
                }

            val isRunning: Boolean
                get() = this == queued || this == uploading || this == generating || this == compositing
        }

        override fun equals(other: Any?): Boolean =
            other is Job && other.id == id && other.state == state && other.result === result

        override fun hashCode(): Int = id.hashCode()
    }

    /** Elle maske düzenleyicinin girdisi. */
    data class MaskEditorInput(val crop: Bitmap, val mask: Bitmap, val auto: Bitmap)

    // MARK: Durum
    /**
     * Giriş ekranındaki ücretsiz deneme: oturum yok, istekler `/api/public/trial/smile`ye
     * gider, her üretim IP başına deneme hakkından düşer; kalite kademesi sunucunun izin
     * verdiği varsayılan kademedir.
     */
    val isGuest: Boolean = guest

    private val scope: CoroutineScope = MainScope()

    var phase by mutableStateOf<Phase>(Phase.pickPhoto)
    var original by mutableStateOf<Bitmap?>(null)
        private set
    /** Analiz edilmiş fotoğraf (gerekirse düzleştirilmiş çalışma kopyasıyla). */
    var photo by mutableStateOf<SmilePhoto?>(null)
        private set
    val region: MouthRegion? get() = photo?.region
    /** Yapay zekaya gidecek görüntünün önizlemesi (seçili modele göre). */
    var uploadPreview by mutableStateOf<Bitmap?>(null)
        private set
    /** Diş açıklığı maskesi (orijinal fotoğraf boyutunda, alfa = maske). */
    var maskPreview by mutableStateOf<Bitmap?>(null)
        private set

    // Seçim — tek tasarım (her üretim ayrı kredi harcar)
    /** Seçili hazır tasarım; null = hekim ince ayar yaptı ("Özel Tasarım"). */
    var presetID by mutableStateOf<String?>(SmilePreset.all[0].id)
        private set
    private var _design by mutableStateOf(SmilePreset.all[0].design)
    /** Üretilecek tasarım: hazır tasarımdan gelir, ince ayarla değişir. */
    var design: SmileDesign
        get() = _design
        set(value) {
            val old = _design
            _design = value
            if (value != old && !applyingPreset) presetID = null
        }
    private var applyingPreset = false
    val preset: SmilePreset? get() = SmilePreset.all.firstOrNull { it.id == presetID }
    val designTitle: String get() = preset?.title ?: "Özel Tasarım"
    /** Kalite kademesi (Orta · Orta İyi · İyi · Çok İyi). */
    var model by mutableStateOf(SmileAIModel.default)
    /**
     * KVKK: hasta, ağız bölgesi görüntüsünün yapay zeka ile işlenmesine onay
     * verdi. Her yeni fotoğrafta sıfırlanır.
     */
    var patientConsent by mutableStateOf(false)
    private var _maskExpansion by mutableStateOf(SmileImageProcessor.defaultMaskExpansion.toDouble())
    /** Maskenin dudak içine doğru genişlemesi (ağız genişliğine oran). */
    var maskExpansion: Double
        get() = _maskExpansion
        set(value) {
            _maskExpansion = value
            refreshMaskPreview(); refreshUploadPreview()
        }
    private var _maskScope by mutableStateOf(MaskScope.all)
    /** Hangi çene değişecek (tümü / üst / alt). */
    var maskScope: MaskScope
        get() = _maskScope
        set(value) {
            val old = _maskScope
            _maskScope = value
            if (value != old) { refreshMaskPreview(); refreshUploadPreview() }
        }
    /** Hekimin fırça/silgiyle düzenlediği maske (kırpım boyutu, gri). null = otomatik. */
    var customMask by mutableStateOf<Bitmap?>(null)
        private set
    val isMaskEdited: Boolean get() = customMask != null
    /** Hekim maskeyi değiştirdi mi (otomatik + tüm çene dışında bir şey)? */
    private val usesMaskOverride: Boolean get() = customMask != null || maskScope != MaskScope.all
    private var maskRevision = 0

    // Üretim
    var jobs by mutableStateOf<List<Job>>(emptyList())
        private set
    var activeJobID by mutableStateOf<UUID?>(null)
    /** İlerleme süresi göstergesi için. */
    var tick by mutableIntStateOf(0)
        private set

    /** Seçili kademenin kredisi — YALNIZ sunucunun `tiers`ı (M-E3.1); bilinmiyorsa `null` (sayı uydurulmaz). */
    val estimatedCredits: Double? get() = SmileCreditsStore.shared.price(model)
    /** Krediyle çalışan yolda kademenin bedeli bilinmiyor mu? (O zaman üretim başlatılamaz — "—".) */
    val bedelBilinmiyor: Boolean
        get() = SmileSimulationViewModel.bedelBilinmiyor(misafir = isGuest, olculu = SmileCreditsStore.shared.isMetered, kredi = estimatedCredits)
    /** Kademe seçilebilir mi (M-E3.1): krediyle çalışan yolda yalnız sunucunun listesindeki kademe. */
    fun secilebilir(m: SmileAIModel): Boolean =
        !SmileSimulationViewModel.bedelBilinmiyor(
            misafir = isGuest, olculu = SmileCreditsStore.shared.isMetered, kredi = SmileCreditsStore.shared.price(m),
        )
    val activeJob: Job? get() = jobs.firstOrNull { it.id == activeJobID } ?: jobs.lastOrNull()
    val isGenerating: Boolean get() = jobs.any { it.state.isRunning }
    val canGenerate: Boolean get() = photo != null && patientConsent && !isGenerating && !trialExhausted && !bedelBilinmiyor
    /** Kalan kredi bu kademeye yetmiyor mu (bilinmiyorsa false — sunucu yine denetler)? */
    val lacksCredits: Boolean
        get() {
            if (isGuest) return false
            val kredi = estimatedCredits ?: return false
            return SmileCreditsStore.shared.canAfford(kredi) == false
        }
    /** Ücretsiz deneme hakkı bitti mi (bilinmiyorsa false — sunucu yine denetler)? */
    val trialExhausted: Boolean get() = isGuest && GuestTrialStore.shared.isExhausted(GuestTrialFeature.smile)

    private val endpoint: KieImageEditClient.Endpoint
        get() {
            if (isGuest) {
                // Geliştirme sürümü: deneme sunucusu kullanılamıyorsa (bilgisayardaki yerel sunucu kapalı /
                // kie anahtarı yok, ya da uç henüz yayında değil) cihazdaki anahtarla doğrudan kie.ai
                // (Secrets.properties). Önbellekteki hak sayısı "sunucu var" demek değil.
                if (BuildConfig.DEBUG && !GuestTrialStore.shared.isAvailable(GuestTrialFeature.smile)) {
                    val e = SmileSimulationConfig.endpoint
                    if (e is KieImageEditClient.Endpoint.direct) return e
                }
                return KieImageEditClient.Endpoint.guest(baseURL = AppConfig.apiBaseURL + "/public/trial/smile")
            }
            return SmileSimulationConfig.endpoint
        }
    /** Fotoğraf karşıdan mı çekilmiş (yandan çekimde uyarı gösterilir). */
    val isFrontal: Boolean get() = photo?.region?.isFrontal ?: true

    // MARK: Dahili
    /** Yükleme önbelleği: aynı kırpım/maske tüm tasarımlarda tekrar kullanılır. */
    private var uploadTasks = mutableMapOf<String, Deferred<String>>()
    private var work: kotlinx.coroutines.Job? = null
    private var ticker: kotlinx.coroutines.Job? = null
    /** Son maske önizlemesi işi — kaydırıcı hızlı oynarken eski sonuç yenisinin üstüne yazılmasın. */
    private var maskPreviewJob: kotlinx.coroutines.Job? = null
    private val maxParallel = 3
    /** Karşılaştırma için tutulan en fazla sonuç (bellek). */
    private val maxHistory = 6

    companion object {
        /** İşleme çözünürlüğü sınırı — 48 MP fotoğraflarda bellek patlamasın. */
        private const val maxLongEdge: Float = 3024f

        /**
         * Galeri/kamera dosyasını EXIF yönüne göre dik olarak çözer (UIImage `imageOrientation`
         * karşılığı). Görünümler `PickVisualMedia` sonucunu bununla yükler.
         */
        fun decodeImage(context: Context, uri: Uri): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val long = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (long / (sample * 2) >= maxLongEdge) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: return@runCatching null
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return@runCatching bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrNull()

        /** Yönü dik kabul eder (Bitmap'te EXIF yok), sRGB 8-bit yazılım kopyası ve çözünürlük sınırı. */
        private fun normalized(image: Bitmap): Bitmap? = runCatching {
            val long = max(image.width, image.height).toFloat()
            val scale = min(1f, maxLongEdge / max(long, 1f))
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(android.graphics.Color.BLACK)   // opak
                drawBitmap(image.softwareCopy(), null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            out
        }.getOrNull()

        /** Krediyle çalışan yolda kademenin bedeli bilinmiyor mu (saf — test edilebilir)? */
        fun bedelBilinmiyor(misafir: Boolean, olculu: Boolean, kredi: Double?): Boolean = !misafir && olculu && kredi == null

        /** Maske önizlemesinin en uzun kenarı (px) — ekranda ölçeklenerek çizilir, tam boy gerekmez. */
        private const val maskPreviewMaxEdge = 1600

        private fun isCreditError(error: Throwable): Boolean = error is KieImageEditClient.KieError.clinicCreditsExhausted

        private fun isTrialExhausted(error: Throwable): Boolean {
            if (error !is KieImageEditClient.KieError.trialExhausted) return false
            GuestTrialStore.shared.markExhausted(GuestTrialFeature.smile)
            return true
        }
    }

    // MARK: - Seçim

    /** Hazır tasarımı seç (tek seçim); ince ayar alanları bu tasarımla dolar. */
    fun selectPreset(p: SmilePreset) {
        applyingPreset = true
        design = p.design
        presetID = p.id
        applyingPreset = false
        Haptics.selection()
    }

    // MARK: - Maske seçimi (hekim)

    /** Düzenleyiciye verilecek başlangıç: mevcut etkin maske (gri bitmap) + çalışma kırpımı. */
    fun maskEditorInput(): MaskEditorInput? {
        val photo = photo ?: return null
        return makeMaskEditorInput(photo, maskExpansion.toFloat(), customMask)
    }

    /** `maskEditorInput()` ana iş parçacığı dışında (kırpım + maske hesapları dokunuşta takılma yapmasın). */
    suspend fun maskEditorInputAsync(): MaskEditorInput? {
        val photo = photo ?: return null
        val exp = maskExpansion.toFloat()
        val custom = customMask
        return withContext(Dispatchers.Default) { makeMaskEditorInput(photo, exp, custom) }
    }

    private fun makeMaskEditorInput(photo: SmilePhoto, exp: Float, custom: Bitmap?): MaskEditorInput? {
        val crop = SmileImageProcessor.crop(photo.working, photo.region) ?: return null
        val r = photo.region
        val current = SmileImageProcessor.maskBitmap(
            SmileImageProcessor.effectiveMask(r, exp, MaskScope.all, custom), r,
        ) ?: return null
        val auto = SmileImageProcessor.maskBitmap(SmileImageProcessor.mouthMask(r, exp), r) ?: return null
        return MaskEditorInput(crop, current, auto)
    }

    /** Düzenleyiciden dönen maskeyi uygula. */
    fun applyEditedMask(mask: Bitmap) {
        customMask = mask
        maskRevision += 1
        refreshMaskPreview()
        refreshUploadPreview()
        Haptics.success()
    }

    /** Otomatik maskeye dön. */
    fun resetMask() {
        customMask = null
        _maskScope = MaskScope.all
        maskRevision += 1
        refreshMaskPreview()
        refreshUploadPreview()
        Haptics.light()
    }

    /** Etkin maske (kırpım koordinatları); otomatik + tüm çene ise null (hassas otomatik yol). */
    private fun maskOverride(photo: SmilePhoto, expansion: Float): MaskImage? {
        if (!usesMaskOverride) return null
        return SmileImageProcessor.effectiveMask(photo.region, expansion, maskScope, customMask)
    }

    // MARK: - Fotoğraf yükleme

    fun load(image: Bitmap) {
        cancelAll()
        jobs = emptyList(); activeJobID = null
        original = null; photo = null; uploadPreview = null; maskPreview = null; uploadTasks = mutableMapOf()
        customMask = null; _maskScope = MaskScope.all; maskRevision += 1
        patientConsent = false
        phase = Phase.analyzing
        Haptics.light()

        work = scope.launch {
            try {
                val photo = withContext(Dispatchers.Default) {
                    val cg = normalized(image) ?: throw SmileImageProcessor.ProcessingError.imageEncodingFailed
                    SmileImageProcessor.prepare(cg)       // eğikse düzleştirir
                }
                ensureActive()
                this@SmileSimulationViewModel.photo = photo
                original = photo.original
                refreshUploadPreview()
                refreshMaskPreview()
                phase = Phase.configure
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // MARK: - Üretim

    /**
     * Seçili tasarımı üretir ve sonuç geçmişine ekler; önceki sonuçlar
     * karşılaştırma için kalır. Kırpım yalnızca ilk üretimde yüklenir.
     */
    fun generate() {
        val photo = photo ?: return
        if (!canGenerate) return
        val job = Job(title = designTitle, icon = preset?.icon ?: "slider.horizontal.3", design = design, tierTitle = model.title)
        var list = jobs + job
        if (list.size > maxHistory) list = list.drop(list.size - maxHistory)
        jobs = list
        activeJobID = job.id
        phase = Phase.results
        Haptics.medium()
        startTicker()
        runJobs(listOf(job.id), photo)
    }

    /** Tek bir tasarımı yeni tohumla yeniden üretir. */
    fun regenerate(id: UUID) {
        val photo = photo ?: return
        if (jobs.none { it.id == id }) return
        val tier = model.title
        update(id) {
            it.state = Job.State.queued; it.result = null; it.startedAt = Instant.now()
            it.finishedIn = null; it.note = null; it.placementScore = null; it.needsCredits = false; it.needsContact = false
            it.tierTitle = tier
        }
        startTicker()
        runJobs(listOf(id), photo)
    }

    private fun runJobs(ids: List<UUID>, photo: SmilePhoto) {
        val model = if (isGuest) SmileAIModel.default else this.model
        // Ekranda gösterilen bedel — sunucu farklı bulursa 409 döner ve görev açılmaz (M-E3.2).
        val beklenenKredi = if (isGuest) null else estimatedCredits
        val expansion = maskExpansion.toFloat()
        val override = maskOverride(photo, expansion)
        val scope = maskScope
        val previous = work
        work = this.scope.launch {
            previous?.join()
            try {
                // Deneme: sunucu önce kısa bir yoklamayla denetlenir; ulaşılamıyorsa istek 1 dk
                // zaman aşımı beklemez (geliştirmede doğrudan kie.ai'ye düşer, canlıda hemen hata).
                if (isGuest) {
                    val reason = GuestTrialStore.shared.ensureAvailable()
                    if (endpoint is KieImageEditClient.Endpoint.guest) {
                        if (reason != null) throw reason
                        if (!GuestTrialStore.shared.isAvailable(GuestTrialFeature.smile)) throw GuestTrialError.unavailable(null)
                    }
                }
                val client = KieImageEditClient(endpoint)
                // 1) Ağız kırpımını (ve maskeli modelde maskeyi) bir kez yükle
                for (id in ids) update(id) { it.state = Job.State.uploading }
                val url = uploadImage(photo, client)
                val maskURL = if (model.usesMask) uploadMask(expansion, override, photo, client) else null
                ensureActive()

                // 2) Tasarımları paralel üret (en fazla `maxParallel` aynı anda)
                val gate = Semaphore(maxParallel)
                coroutineScope {
                    for (id in ids) {
                        launch {
                            gate.withPermit {
                                runOne(id, url, maskURL, client, model, beklenenKredi, photo, expansion, override, scope)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                val msg = message(e)
                val credits = isCreditError(e)
                val contact = isTrialExhausted(e)
                for (id in ids) update(id) {
                    if (it.state.isRunning) { it.state = Job.State.failed(msg); it.needsCredits = credits; it.needsContact = contact }
                }
                Haptics.error()
            }
            if (!isGenerating) ticker?.cancel()
            runCatching {
                if (isGuest) GuestTrialStore.shared.refresh() else SmileCreditsStore.shared.refresh()
            }
        }
    }

    private suspend fun runOne(
        id: UUID,
        url: String,
        maskURL: String?,
        client: KieImageEditClient,
        model: SmileAIModel,
        beklenenKredi: Double?,
        photo: SmilePhoto,
        expansion: Float,
        override: MaskImage?,
        scope: MaskScope,
    ) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        update(id) { it.state = Job.State.generating; it.startedAt = Instant.now() }
        try {
            val first = render(job.design, model, url, maskURL, client, photo, expansion, override, scope, fixOf = null,
                beklenenKredi = beklenenKredi)
            var result = first.first
            update(id) { it.state = Job.State.compositing }
            var note: String? = null
            // Güvenlik ağı (her zaman açık): model ağzı/pozu yeniden çizdiyse maskeli
            // modelle bir kez daha dene. Sunucu yolunda ücretsizdir (asıl göreve bağlı).
            // Düzeltme başarısız olursa asıl sonuç korunur — iş düşmez.
            if (!result.isWellPlaced && !model.usesMask) {
                update(id) { it.state = Job.State.generating; it.note = "Yerleşim düzeltiliyor · ücretsiz" }
                try {
                    val fURL = uploadImage(photo, client)   // önbellekte
                    val fMask = uploadMask(expansion, override, photo, client)
                    val retry = render(job.design, SmileAIModel.fixModel, fURL, fMask, client, photo, expansion,
                        override, scope, fixOf = first.second)
                    if (retry.first.placementScore > result.placementScore) {
                        result = retry.first
                        note = "Yerleşim otomatik düzeltildi"
                    } else {
                        note = "Yerleşim sınırda — yeniden üretmeyi deneyin"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = "Yerleşim sınırda — yeniden üretmeyi deneyin"
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val finalResult = result
            update(id) {
                it.result = finalResult.image
                it.placementScore = finalResult.placementScore
                it.note = note
                it.state = Job.State.done
                it.finishedIn = (Instant.now().toEpochMilli() - it.startedAt.toEpochMilli()) / 1000.0
            }
            if (activeJob?.result == null) activeJobID = id
            // Deneme sunucu yerine cihazdan (doğrudan kie.ai) yapıldıysa hakkı burada düş.
            if (isGuest && client.endpoint is KieImageEditClient.Endpoint.direct) GuestTrialStore.shared.recordLocalUse(GuestTrialFeature.smile)
            Haptics.success()
        } catch (e: CancellationException) {
        } catch (e: Exception) {
            // 409: sunucunun güncel bedeli karta yazılır; görev açılmadı, yeniden dokunuş yeni bedelle gider (M-E3.2).
            if (e is KieImageEditClient.KieError.priceChanged) SmileCreditsStore.shared.bedelGuncellendi(model, e.credits)
            val credits = isCreditError(e)
            val contact = isTrialExhausted(e)
            val msg = message(e)
            update(id) { it.state = Job.State.failed(msg); it.needsCredits = credits; it.needsContact = contact }
            Haptics.error()
        }
    }

    /** Tek model çağrısı + cihazda birleştirme. Sonuç: (birleştirme, görev kimliği). */
    private suspend fun render(
        design: SmileDesign,
        model: SmileAIModel,
        url: String,
        maskURL: String?,
        client: KieImageEditClient,
        photo: SmilePhoto,
        expansion: Float,
        override: MaskImage?,
        scope: MaskScope,
        fixOf: String?,
        beklenenKredi: Double? = null,
    ): Pair<SmileComposite, String> {
        val prompt = SmilePrompt.build(design, maxLength = model.maxPromptLength,
            maskedContext = false, masked = model.usesMask,
            scope = scope, maskedOnly = false, frontal = photo.region.isFrontal)
        val generation = client.generate(model, url, maskURL, prompt,
            seed = Random.nextInt(1, 1_000_000), fixOf = fixOf, beklenenKredi = beklenenKredi)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val data = generation.data
        val composite = withContext(Dispatchers.Default) {
            val ai = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw KieImageEditClient.KieError.badResponse
            SmileImageProcessor.composite(photo, ai, expansion, maskOverride = override)
        }
        return composite to generation.taskId
    }

    // MARK: - Yükleme önbelleği

    /**
     * Modele giden görüntü: ağız dikdörtgeni (gözler ve yüzün geri kalanı
     * hariç, metadata'sız). Tüm tasarımlarda aynı kırpım tekrar kullanılır.
     */
    private suspend fun uploadImage(photo: SmilePhoto, client: KieImageEditClient): String =
        cachedUpload("crop", make = {
            withContext(Dispatchers.Default) {
                val crop = SmileImageProcessor.crop(photo.working, photo.region)
                    ?: throw SmileImageProcessor.ProcessingError.imageEncodingFailed
                SmileImageProcessor.uploadJPEG(crop)
            }
        }, upload = { client.upload(it) })

    private suspend fun uploadMask(expansion: Float, override: MaskImage?, photo: SmilePhoto, client: KieImageEditClient): String {
        val key = if (override == null) "mask-${(expansion * 1000).toInt()}"
        else "mask-edit-$maskRevision-${maskScope.rawValue}-${(expansion * 1000).toInt()}"
        return cachedUpload(key, make = {
            withContext(Dispatchers.Default) {
                if (override != null) SmileImageProcessor.maskPNG(mask = override, region = photo.region)
                else SmileImageProcessor.maskPNG(photo.region, expansion = expansion + 0.03f)
            }
        }, upload = { client.upload(it, fileExtension = "png", mime = "image/png") })
    }

    private suspend fun cachedUpload(
        key: String,
        make: suspend () -> ByteArray,
        upload: suspend (ByteArray) -> String,
    ): String {
        uploadTasks[key]?.let { return it.await() }
        val t = scope.async { upload(make()) }
        uploadTasks[key] = t
        try {
            return t.await()
        } catch (e: Exception) {
            if (uploadTasks[key] === t) uploadTasks.remove(key)
            throw e
        }
    }

    private fun update(id: UUID, change: (Job) -> Unit) {
        val i = jobs.indexOfFirst { it.id == id }
        if (i < 0) return
        val copy = jobs[i].copy()
        change(copy)
        jobs = jobs.toMutableList().also { it[i] = copy }
    }

    // MARK: - Gezinme

    fun backToConfigure() {
        phase = Phase.configure
    }

    fun reset() {
        cancelAll()
        original = null; photo = null; uploadPreview = null; maskPreview = null
        uploadTasks = mutableMapOf(); jobs = emptyList(); activeJobID = null; customMask = null; _maskScope = MaskScope.all
        phase = Phase.pickPhoto
    }

    fun teardown() { cancelAll() }

    /** Ekran kapanınca: işler ve kapsam iptal edilir. */
    fun dispose() {
        cancelAll()
        scope.cancel()
    }

    // MARK: - Paylaşım görselleri

    /** Ağız bölgesine yakınlaştırılmış kare (kırpımın biraz genişi). */
    fun zoomRect(): Rect? {
        val region = region ?: return null
        val c = region.cropRect
        val r = c.insetBy(-c.width * 0.15f, -c.height * 0.15f)
        val i = r.intersect(Rect(0f, 0f, region.imageSize.width, region.imageSize.height))
        if (i.width <= 0 || i.height <= 0) return null
        return i.integral()
    }

    /**
     * Hastaya gönderilecek içerik: tam kare önce/sonra + gülüş yakın planı
     * (`SmileShareComposer` yan yana, lüks düzende çizer).
     */
    fun shareContent(job: Job): SmileShareContent? {
        val before = original ?: return null
        val after = job.result ?: return null
        return SmileShareContent(
            before = before, after = after, zoomRect = zoomRect(),
            designTitle = job.title, designSummary = job.design.summary,
            tierTitle = if (job.tierTitle.isEmpty()) model.title else job.tierTitle,
            createdAt = Instant.now(),
        )
    }

    // MARK: - Yardımcılar

    private fun refreshMaskPreview() {
        val photo = photo ?: return
        val expansion = maskExpansion.toFloat()
        val scope = maskScope
        val custom = customMask
        maskPreviewJob?.cancel()
        maskPreviewJob = this.scope.launch {
            val img = withContext(Dispatchers.Default) {
                runCatching {
                    val mask = SmileImageProcessor.effectiveMask(photo.region, expansion, scope, custom)
                    val gray = SmileImageProcessor.maskPreview(photo, mask = mask) ?: return@runCatching null
                    // Gri maske → alfa (Compose maskesi alfa kanalını kullanır): beyaz, alfa = gri. Önizleme ekranda
                    // ölçeklenerek çizildiği için tek geçişte küçültülür (bellek + GPU yüklemesi).
                    val k = min(1f, maskPreviewMaxEdge.toFloat() / max(gray.width, gray.height).coerceAtLeast(1))
                    val w = (gray.width * k).roundToInt().coerceAtLeast(1)
                    val h = (gray.height * k).roundToInt().coerceAtLeast(1)
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cm = ColorMatrix(floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        1f, 0f, 0f, 0f, 0f,
                    ))
                    Canvas(out).drawBitmap(
                        gray, null, android.graphics.Rect(0, 0, w, h),
                        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
                    )
                    gray.recycle()
                    out
                }.getOrNull()
            }
            maskPreview = img
        }
    }

    private fun refreshUploadPreview() {
        val photo = photo ?: return
        if (uploadPreview != null) return
        scope.launch {
            val img = withContext(Dispatchers.Default) { SmileImageProcessor.crop(photo.working, photo.region) }
            uploadPreview = img
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                if (!isActive) return@launch
                tick += 1
            }
        }
    }

    private fun cancelAll() {
        work?.cancel(); work = null
        ticker?.cancel(); ticker = null
        maskPreviewJob?.cancel(); maskPreviewJob = null
    }

    private fun message(error: Throwable): String = when (error) {
        is KieImageEditClient.KieError -> error.message
        is SmileImageProcessor.ProcessingError -> error.message ?: ""
        is ApiException.NetworkError, is IOException -> "İnternet bağlantısı kurulamadı. Bağlantınızı kontrol edip tekrar deneyin."
        else -> error.message ?: error.toString()
    }

    private fun fail(error: Throwable) {
        Haptics.error()
        phase = Phase.failed(message(error))
    }
}
