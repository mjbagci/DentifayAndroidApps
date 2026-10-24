package com.dentifay.app.smile

import androidx.compose.runtime.mutableDoubleStateOf
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder
import com.dentifay.app.theme.Haptics
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Rehberli çekim kamerası. Canlı önizleme, her 3. karede yüz/ağız analizi
 * (`SmileCaptureGuide`) ve koşullar sağlanınca otomatik fotoğraf.
 *
 * Önizleme: `session` (CameraX `Preview`) bir `PreviewView`'a bağlanır —
 * `attach(previewView)` ya da `session.setSurfaceProvider(previewView.surfaceProvider)`.
 */
class SmileCameraController {

    enum class Status { idle, running, denied, unavailable }

    /** `AVCaptureDevice.Position` karşılığı. */
    enum class Position { back, front }

    var status by mutableStateOf(Status.idle)
        private set
    var guide by mutableStateOf(SmileCaptureGuide.State())
        private set
    /** Analiz edilen karenin dik (upright) boyutu — önizleme eşlemesi için. */
    var frameSize by mutableStateOf(Size(3f, 4f))
        private set
    var position by mutableStateOf(Position.back)
        private set
    /** Hazır durumun ne kadar sürdüğü (0–1) — otomatik çekim halkası. */
    var readyProgress by mutableDoubleStateOf(0.0)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var autoCapture by mutableStateOf(true)
    /**
     * Arka kamerada 2× (≈52 mm): ~0,75 m'den çekim, özçekim yakınlığındaki
     * yüz bozulmasını (burun büyük, ağız dar) önler — AACD/ICAO önerisi.
     */
    var zoom2x by mutableStateOf(true)
        private set

    /** Canlı önizleme kullanım durumu (AVCaptureSession karşılığı). */
    val session: Preview = Preview.Builder().build()
    private val engine = CameraEngine(session)
    private var readySince: Long? = null
    private var onPhoto: ((Bitmap) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val autoCaptureDelay: Double = 0.9
    }

    init {
        engine.onFrame = { state, size -> main.post { applyState(state, size) } }
    }

    /** Önizlemeyi bir `PreviewView`'a bağlar. */
    fun attach(previewView: PreviewView) {
        session.setSurfaceProvider(previewView.surfaceProvider)
    }

    /**
     * Kamerayı açar. İzin yoksa sistem izin penceresi gösterilir.
     * `context`/`lifecycleOwner` verilmezse ön plandaki Activity kullanılır.
     */
    suspend fun start(onPhoto: (Bitmap) -> Unit, context: Context? = null, lifecycleOwner: LifecycleOwner? = null) {
        this.onPhoto = onPhoto
        val activity = ActivityHolder.current
        val ctx = context ?: activity ?: Prefs.appContext ?: run { status = Status.unavailable; return }
        val owner = lifecycleOwner ?: (activity as? LifecycleOwner) ?: (ctx as? LifecycleOwner) ?: run { status = Status.unavailable; return }
        if (!hasPermission(ctx)) {
            val granted = (activity ?: ctx as? Activity)?.let { requestPermission(it) } ?: false
            if (!granted) { status = Status.denied; return }
        }
        val ok = engine.configure(ctx, owner, position, if (zoom2x) 2f else 1f)
        status = if (ok) Status.running else Status.unavailable
    }

    fun toggleZoom() {
        zoom2x = !zoom2x
        engine.setZoom(if (zoom2x && position == Position.back) 2f else 1f)
        Haptics.selection()
    }

    fun stop() { engine.stop() }

    /**
     * Ekran dönüşü (`Surface.ROTATION_*`). Tablet/katlanabilir döndürülünce etkinlik yeniden kurulmaz; önizleme, analiz
     * ve çekim bu değerle hizalanır — yatayda kare ve fotoğraf yatay gelir (telefon dikey kilitli: hep 0).
     */
    fun setTargetRotation(rotation: Int) { engine.setTargetRotation(rotation) }

    fun flipCamera() {
        position = if (position == Position.back) Position.front else Position.back
        guide = SmileCaptureGuide.State()
        readySince = null; readyProgress = 0.0
        val zoom = if (zoom2x && position == Position.back) 2f else 1f
        engine.reconfigure(position, zoom)
        Haptics.light()
    }

    fun capture() {
        if (isCapturing || status != Status.running) return
        isCapturing = true
        Haptics.medium()
        engine.capturePhoto { image ->
            main.post {
                isCapturing = false
                if (image != null) onPhoto?.invoke(image)
            }
        }
    }

    private fun applyState(state: SmileCaptureGuide.State, frameSize: Size) {
        val becameReady = state.isReady && !guide.isReady
        guide = state
        this.frameSize = frameSize
        if (becameReady) Haptics.soft()

        if (state.isReady) {
            val now = System.currentTimeMillis()
            val since = readySince ?: now
            readySince = since
            readyProgress = min(1.0, (now - since) / 1000.0 / autoCaptureDelay)
            if (autoCapture && readyProgress >= 1 && !isCapturing) capture()
        } else {
            readySince = null
            readyProgress = 0.0
        }
    }

    // MARK: - İzin

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Sistem izin penceresini açar ve kapanmasını bekler: pencere Activity'yi
     * duraklatır (RESUMED → STARTED); geri dönünce sonuç okunur.
     */
    private suspend fun requestPermission(activity: Activity): Boolean {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 4711)
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (lifecycle == null) {
            delay(1500)
            return hasPermission(activity)
        }
        // Pencere açıldı mı? (Daha önce "bir daha sorma" seçildiyse hiç açılmaz.)
        val paused = withTimeoutOrNull(1200) {
            while (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(50)
            true
        } ?: false
        if (paused) {
            while (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(80)
        }
        return hasPermission(activity)
    }
}

/**
 * CameraX + ML Kit işçisi. Analiz kendi iş parçacığında çalışır; ana iş
 * parçacığına yalnızca `onFrame` ve fotoğraf tamamlama geri çağrılarıyla döner.
 */
class CameraEngine(private val preview: Preview) {

    var onFrame: ((SmileCaptureGuide.State, Size) -> Unit)? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var context: Context? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var guide = SmileCaptureGuide()           // yalnızca analiz iş parçacığında
    @Volatile private var frameCounter = 0
    @Volatile private var mirrored = false
    /** Son bilinen ekran dönüşü; bağlanırken kullanım durumlarına uygulanır. */
    @Volatile private var targetRotation: Int? = null

    /** Canlı karede hız öncelikli algılayıcı (konturlar + göz/burun işaretleri). */
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build(),
        )
    }

    suspend fun configure(context: Context, owner: LifecycleOwner, position: SmileCameraController.Position, zoom: Float): Boolean {
        this.context = context
        this.owner = owner
        val p = provider ?: awaitProvider(context)?.also { provider = it } ?: return false
        return bind(p, owner, position, if (position == SmileCameraController.Position.back) zoom else 1f)
    }

    /** Kamera değiştirme (aynı yaşam döngüsü sahibiyle). */
    fun reconfigure(position: SmileCameraController.Position, zoom: Float) {
        val p = provider ?: return
        val o = owner ?: return
        ContextCompat.getMainExecutor(context ?: return).execute {
            bind(p, o, position, if (position == SmileCameraController.Position.back) zoom else 1f)
        }
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider? = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cont.resume(runCatching { future.get() }.getOrNull())
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner, position: SmileCameraController.Position, zoom: Float): Boolean {
        val selector = if (position == SmileCameraController.Position.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return runCatching {
            if (!p.hasCamera(selector)) return false
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)                 // flaş dişlerde parlama/gölge yapar
                .build()
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            mirrored = position == SmileCameraController.Position.front
            targetRotation?.let { r ->
                preview.targetRotation = r
                capture.targetRotation = r
                analyzer.targetRotation = r
            }
            analyzer.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, selector, preview, capture, analyzer)
            camera = cam
            imageCapture = capture
            analysis = analyzer
            // Yüze odak/pozlama CameraX'te sürekli otomatik; yakınlaştırma ayarla.
            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: zoom
            cam.cameraControl.setZoomRatio(maxOf(1f, min(zoom, maxZoom)))
            analysisExecutor.execute { guide = SmileCaptureGuide(); frameCounter = 0 }
            true
        }.getOrDefault(false)
    }

    /** Ana iş parçacığında çağrılır (Compose ekran dinleyicisi). */
    fun setTargetRotation(rotation: Int) {
        if (targetRotation == rotation) return
        targetRotation = rotation
        runCatching {
            preview.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            analysis?.targetRotation = rotation
        }
        // Rehber yeni yöne göre baştan ölçsün (eski kare koordinatları karışmasın).
        analysisExecutor.execute { guide = SmileCaptureGuide(); frameCounter = 0 }
    }

    fun setZoom(zoom: Float) {
        val cam = camera ?: return
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: zoom
        cam.cameraControl.setZoomRatio(maxOf(1f, min(zoom, maxZoom)))
    }

    fun stop() {
        val ctx = context ?: return
        ContextCompat.getMainExecutor(ctx).execute { provider?.unbindAll(); camera = null }
    }

    fun capturePhoto(completion: (Bitmap?) -> Unit) {
        val capture = imageCapture
        if (capture == null || camera == null) { completion(null); return }
        // Fotoğraf aynalanmaz: klinik fotoğraf, gerçek yön.
        capture.takePicture(photoExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching {
                    val raw = image.toBitmap()
                    val deg = image.imageInfo.rotationDegrees
                    if (deg == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        Matrix().apply { postRotate(deg.toFloat()) }, true)
                }.getOrNull()
                image.close()
                completion(bmp)
            }

            override fun onError(exception: ImageCaptureException) { completion(null) }
        })
    }

    // MARK: - Kareler

    private fun analyze(proxy: ImageProxy) {
        try {
            frameCounter += 1
            if (frameCounter % 3 != 0) return
            val frame = lumaFrame(proxy) ?: return
            // ML Kit NV21 bekler: parlaklık + nötr renk (yüz algılama yalnız Y kullanır)
            val nv21 = ByteArray(frame.width * frame.height * 3 / 2)
            System.arraycopy(frame.bytes, 0, nv21, 0, frame.bytes.size)
            java.util.Arrays.fill(nv21, frame.bytes.size, nv21.size, 128.toByte())
            val input = InputImage.fromByteArray(nv21, frame.width, frame.height, frame.rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
            val faces = runCatching { Tasks.await(detector.process(input)) }.getOrNull().orEmpty()
            val face = faces.maxByOrNull { it.boundingBox.width() }
            val state = guide.evaluate(face, frame)
            onFrame?.invoke(state, frame.uprightSize)
        } finally {
            proxy.close()
        }
    }

    /** YUV_420_888 karesinin Y düzlemini bitişik diziye kopyalar. */
    private fun lumaFrame(proxy: ImageProxy): LumaFrame? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val w = proxy.width; val h = proxy.height
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val out = ByteArray(w * h)
        if (pixelStride == 1 && rowStride == w) {
            buf.rewind(); buf.get(out, 0, min(out.size, buf.remaining()))
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until h) {
                buf.position(y * rowStride)
                val len = min(rowStride, buf.remaining())
                buf.get(row, 0, len)
                for (x in 0 until w) {
                    val i = x * pixelStride
                    if (i < len) out[y * w + x] = row[i]
                }
            }
        }
        return LumaFrame(w, h, out, proxy.imageInfo.rotationDegrees, mirrored)
    }
}
