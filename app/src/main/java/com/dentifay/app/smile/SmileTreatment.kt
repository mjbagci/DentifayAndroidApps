package com.dentifay.app.smile

import kotlinx.serialization.Serializable

// Gülüş tasarımı: hekimin seçtiği işlemler + diş formu + boyut + renk.
// Prompt'lar İngilizce (modeller en tutarlı sonucu İngilizcede veriyor) ve
// dijital gülüş tasarımı (DSD) kurallarına göre kurulur.

/** Klinik işlem — aynı tasarımda birden fazlası seçilebilir. */
@Serializable
enum class SmileProcedure {
    whitening, veneers, bonding, alignment, gapClosure, implants, gumContour;

    val rawValue: String get() = name
    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            whitening -> "Beyazlatma"
            veneers -> "Lamina"
            bonding -> "Kırık onarımı"
            alignment -> "Hizalama"
            gapClosure -> "Aralık kapatma"
            implants -> "Eksik diş"
            gumContour -> "Diş eti"
        }

    val subtitle: String
        get() = when (this) {
            whitening -> "Renk açılır, şekil aynı kalır"
            veneers -> "Ön dişler porselen kaplanır"
            bonding -> "Kırık, aşınma, küçük kusurlar (bonding)"
            alignment -> "Çapraşık dişler düzelir (ortodonti)"
            gapClosure -> "Diş arası boşluklar kapanır"
            implants -> "Eksik veya çürük dişler tamamlanır"
            gumContour -> "Diş eti çizgisi düzelir (gummy smile)"
        }

    val icon: String
        get() = when (this) {
            whitening -> "sun.max.fill"
            veneers -> "sparkles"
            bonding -> "bandage.fill"
            alignment -> "align.horizontal.center.fill"
            gapClosure -> "arrow.right.and.line.vertical.and.arrow.left"
            implants -> "plus.square.dashed"
            gumContour -> "scribble.variable"
        }

    /**
     * Prompt cümlesi. `redesign`: dişler yeniden tasarlanıyor (lamina, form,
     * boyut); değilse hastanın kendi dişleri korunur, yalnız kusur giderilir.
     */
    internal fun instruction(redesign: Boolean): String = when (this) {
        whitening -> if (redesign)
            "Bring all visible teeth, upper and lower, to the shade given below."
        else
            "Whiten the teeth evenly to the shade given below, as after professional bleaching; their shape, edges and arrangement stay exactly as they are."
        veneers -> "Restore the visible upper front teeth (about canine to canine, plus the premolars where they show) with porcelain veneers: new, well-shaped, evenly aligned teeth that look like healthy natural enamel."
        bonding -> "Repair chipped, worn, uneven or broken edges and small defects with tooth-colored bonding so every edge looks whole and smooth."
        alignment -> "Straighten crowded, rotated or tilted teeth into a smooth, even arch; remove braces, brackets or wires if present."
        gapClosure -> "Close small spaces between the teeth with tight, natural contacts by making the neighbouring teeth slightly wider."
        implants -> "Where a tooth is missing, broken or badly decayed, replace it with a natural-looking tooth that matches its neighbours in size, shape and shade."
        gumContour -> "Even out the gum line: reduce excessive gum display so only a thin band of healthy pink gum shows above the upper teeth, with symmetric scalloped margins and papillae filling the spaces between the teeth; the teeth look proportionally longer."
    }

    companion object {
        val allCases: List<SmileProcedure> get() = entries.toList()
    }
}

/** Diş formu (DSD şablonları). */
@Serializable
enum class ToothForm {
    natural, oval, square, triangular;

    val rawValue: String get() = name
    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            natural -> "Doğal"
            oval -> "Oval"
            square -> "Kare"
            triangular -> "Üçgen"
        }

    val caption: String
        get() = when (this) {
            natural -> "Hastanın formu"
            oval -> "Yumuşak hatlar"
            square -> "Güçlü köşeler"
            triangular -> "Dinamik, genç"
        }

    val icon: String
        get() = when (this) {
            natural -> "circle.dashed"
            oval -> "capsule.portrait"
            square -> "square"
            triangular -> "triangle"
        }

    internal val instruction: String
        get() = when (this) {
            natural -> "keep the patient's own tooth outlines, only refined"
            oval -> "oval outlines with softly rounded incisal corners"
            square -> "squarish outlines with flat incisal edges and well-defined corners"
            triangular -> "tapering outlines, narrower toward the gum, with more open incisal embrasures"
        }

    /** Özet cümlesi için kısa sıfat. */
    internal val adjective: String?
        get() = when (this) {
            natural -> null
            oval -> "oval"
            square -> "squarish"
            triangular -> "tapering"
        }

    companion object {
        val allCases: List<ToothForm> get() = entries.toList()
    }
}

/** Kademeli boyut ayarı (−2…+2). Uzunluk ve genişlik için ayrı kullanılır. */
@Serializable
enum class ToothScale(val rawValue: Int) {
    minus2(-2), minus1(-1), zero(0), plus1(1), plus2(2);

    val id: Int get() = rawValue

    val lengthTitle: String
        get() = when (this) {
            minus2 -> "Kısa"
            minus1 -> "Biraz kısa"
            zero -> "Doğal"
            plus1 -> "Biraz uzun"
            plus2 -> "Uzun"
        }

    val widthTitle: String
        get() = when (this) {
            minus2 -> "Dar"
            minus1 -> "Biraz dar"
            zero -> "Doğal"
            plus1 -> "Biraz geniş"
            plus2 -> "Geniş"
        }

    // Oranlar mevcut dişlere göre: mutlak ölçü (mm) verilince model dişleri büyütüyordu.
    internal val lengthInstruction: String?
        get() = when (this) {
            minus2 -> "upper front teeth about 20% shorter than now"
            minus1 -> "upper front teeth about 10% shorter than now"
            zero -> null
            plus1 -> "upper front teeth about 10% longer than now"
            plus2 -> "upper front teeth about 20% longer than now, with prominent central incisors"
        }

    internal val widthInstruction: String?
        get() = when (this) {
            minus2 -> "about 20% narrower than now, slender"
            minus1 -> "about 10% narrower than now"
            zero -> null
            plus1 -> "about 10% wider than now, a fuller smile"
            plus2 -> "about 20% wider than now, a broad smile that reaches the mouth corners"
        }

    internal val lengthAdjective: String?
        get() = when (this) {
            minus2 -> "clearly shorter"
            minus1 -> "slightly shorter"
            zero -> null
            plus1 -> "slightly longer"
            plus2 -> "clearly longer"
        }

    internal val widthAdjective: String?
        get() = when (this) {
            minus2 -> "clearly narrower"
            minus1 -> "slightly narrower"
            zero -> null
            plus1 -> "slightly wider"
            plus2 -> "clearly wider"
        }

    companion object {
        val allCases: List<ToothScale> get() = entries.toList()
        fun of(rawValue: Int): ToothScale? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** VITA renk skalası üzerinden hedef diş rengi (koyu → açık). */
@Serializable
enum class ToothShade {
    keep, a2, a1, b1, bl3, bl1;

    val rawValue: String get() = name
    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            keep -> "Mevcut"
            a2 -> "A2"
            a1 -> "A1"
            b1 -> "B1"
            bl3 -> "BL3"
            bl1 -> "BL1"
        }

    val caption: String
        get() = when (this) {
            keep -> "Aynı"
            a2 -> "Doğal"
            a1 -> "Açık"
            b1 -> "Parlak"
            bl3 -> "Beyaz"
            bl1 -> "Çok beyaz"
        }

    /** Chip'teki renk örneği (yaklaşık). */
    val swatchHex: String
        get() = when (this) {
            keep -> "c9b79a"
            a2 -> "e6dcc4"
            a1 -> "efe8d6"
            b1 -> "f3efe2"
            bl3 -> "f7f5ee"
            bl1 -> "fdfcf9"
        }

    /**
     * Renk tarifi. Saf beyaz "yapay zeka" görünümünün başlıca nedeni olduğu için
     * en açık tonlarda bile saydam kenar ve gölgeli köşe istenir.
     */
    internal val instruction: String
        get() = when (this) {
            keep -> "keep the current tooth color and brightness; any repaired or replaced tooth matches it"
            a2 -> "VITA A2, a natural healthy ivory: clean, but clearly not white"
            a1 -> "VITA A1, a light natural shade: clean and bright, yet still a slightly warm ivory, not white"
            b1 -> "VITA B1, the lightest natural shade: a bright, clean off-white"
            bl3 -> "VITA 0M3 (BL3), the white of professionally bleached teeth, still with translucent edges and a slightly warmer tone near the gums"
            bl1 -> "VITA 0M1 (BL1), the brightest bleached white used for Hollywood veneers; even so, each tooth keeps soft shading, a slightly translucent edge and darker tones in the shadowed corners, never flat paper white"
        }

    /** Özet cümlesi için kısa ad. */
    internal val shortName: String?
        get() = when (this) {
            keep -> null
            a2 -> "a natural ivory shade"
            a1 -> "a light ivory shade"
            b1 -> "a bright off-white shade"
            bl3 -> "bleached white"
            bl1 -> "bright Hollywood white"
        }

    companion object {
        val allCases: List<ToothShade> get() = entries.toList()
    }
}

/** Gülüş karakteri (ticari DSD kütüphanelerindeki stil aileleri). */
@Serializable
enum class SmileCharacter {
    natural, youthful, soft, dominant, hollywood, mature;

    val rawValue: String get() = name
    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            natural -> "Doğal"
            youthful -> "Genç"
            soft -> "Yumuşak"
            dominant -> "Güçlü"
            hollywood -> "Hollywood"
            mature -> "Olgun"
        }

    internal val instruction: String?
        get() = when (this) {
            natural -> null
            youthful -> "a youthful character: slightly longer rounded central incisors, shorter rounded laterals, translucent edges and a convex smile line"
            soft -> "a soft, feminine character: rounded contours and gently curved edges"
            dominant -> "a strong, masculine character: broad squarish centrals, shorter laterals and well-defined canines with pointed tips"
            hollywood -> "a Hollywood character: a full, even, balanced upper arch with squarish centrals"
            mature -> "a mature character: flatter incisal edges with slight wear and less upper tooth display"
        }

    internal val adjective: String?
        get() = when (this) {
            natural -> null
            youthful -> "youthful"
            soft -> "soft, feminine"
            dominant -> "strong, masculine"
            hollywood -> "full, even Hollywood"
            mature -> "mature"
        }

    companion object {
        val allCases: List<SmileCharacter> get() = entries.toList()
    }
}

/** Tek bir simülasyonun tam tarifi. */
@Serializable
data class SmileDesign(
    val procedures: Set<SmileProcedure>,
    val form: ToothForm = ToothForm.natural,
    val length: ToothScale = ToothScale.zero,
    val width: ToothScale = ToothScale.zero,
    val shade: ToothShade = ToothShade.a1,
    val character: SmileCharacter = SmileCharacter.natural,
) {
    /**
     * Dişler yeniden tasarlanıyor mu (lamina, form, boyut, karakter)? Değilse
     * hastanın kendi dişleri korunur: boyut, şekil ve dizilim aynı kalır.
     */
    val isRedesign: Boolean
        get() = procedures.contains(SmileProcedure.veneers) || form != ToothForm.natural ||
            length != ToothScale.zero || width != ToothScale.zero || character != SmileCharacter.natural

    /** Kısa Türkçe özet (sonuç ekranı ve paylaşım için). */
    val summary: String
        get() {
            val parts = SmileProcedure.allCases.filter { procedures.contains(it) }.map { it.title }.toMutableList()
            if (form != ToothForm.natural) parts.add(form.title)
            if (length != ToothScale.zero) parts.add(length.lengthTitle)
            if (width != ToothScale.zero) parts.add(width.widthTitle)
            parts.add(shade.title)
            return parts.joinToString(" · ")
        }
}

/**
 * Tek dokunuşla seçilen hazır tasarım (bir seferde bir tane üretilir; ince
 * ayar alanları seçilen tasarımla dolar).
 */
data class SmilePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val design: SmileDesign,
) {
    companion object {
        val all: List<SmilePreset> = listOf(
            SmilePreset("natural", "Doğal Estetik", "Kusurlar giderilir, karakter korunur", "leaf.fill",
                SmileDesign(setOf(SmileProcedure.bonding, SmileProcedure.gapClosure, SmileProcedure.implants), shade = ToothShade.a1)),
            SmilePreset("hollywood", "Hollywood", "Tam lamina, simetrik, çok beyaz", "star.fill",
                SmileDesign(setOf(SmileProcedure.veneers, SmileProcedure.implants, SmileProcedure.alignment, SmileProcedure.gapClosure),
                    form = ToothForm.square, length = ToothScale.plus1, width = ToothScale.plus1, shade = ToothShade.bl1,
                    character = SmileCharacter.hollywood)),
            SmilePreset("whitening", "Sadece Beyazlatma", "Şekil ve dizilim aynen kalır", "sun.max.fill",
                SmileDesign(setOf(SmileProcedure.whitening), shade = ToothShade.bl3)),
            SmilePreset("youthful", "Genç & Parlak", "Uzun santraller, yuvarlak köşeler", "sparkle",
                SmileDesign(setOf(SmileProcedure.veneers, SmileProcedure.implants, SmileProcedure.gapClosure),
                    form = ToothForm.oval, length = ToothScale.plus1, shade = ToothShade.b1, character = SmileCharacter.youthful)),
            SmilePreset("soft", "Yumuşak / Feminen", "Oval hatlar, zarif kenarlar", "heart.fill",
                SmileDesign(setOf(SmileProcedure.veneers, SmileProcedure.implants, SmileProcedure.gapClosure),
                    form = ToothForm.oval, shade = ToothShade.bl3, character = SmileCharacter.soft)),
            SmilePreset("dominant", "Güçlü / Maskülen", "Kare form, belirgin kaninler", "bolt.fill",
                SmileDesign(setOf(SmileProcedure.veneers, SmileProcedure.implants, SmileProcedure.gapClosure),
                    form = ToothForm.square, width = ToothScale.plus1, shade = ToothShade.a1, character = SmileCharacter.dominant)),
            SmilePreset("ortho", "Ortodonti Sonrası", "Hizalama, renk ve form korunur", "align.horizontal.center.fill",
                SmileDesign(setOf(SmileProcedure.alignment, SmileProcedure.gapClosure), shade = ToothShade.keep)),
            SmilePreset("full", "Tam Rehabilitasyon", "Eksik + hizalama + lamina", "cross.case.fill",
                SmileDesign(setOf(SmileProcedure.implants, SmileProcedure.alignment, SmileProcedure.veneers, SmileProcedure.gapClosure,
                    SmileProcedure.whitening), shade = ToothShade.b1)),
            SmilePreset("gummy", "Diş Eti + Lamina", "Gummy smile düzeltme", "scribble.variable",
                SmileDesign(setOf(SmileProcedure.gumContour, SmileProcedure.veneers, SmileProcedure.implants),
                    length = ToothScale.plus1, shade = ToothShade.bl3)),
            SmilePreset("minimal", "Minimal Dokunuş", "Bonding + hafif beyazlatma", "wand.and.stars",
                SmileDesign(setOf(SmileProcedure.bonding, SmileProcedure.whitening), shade = ToothShade.a1)),
        )
    }
}

/**
 * Yapay zekaya giden tarif. Görüntü düzenleme modelleri (Gemini / Nano Banana
 * ailesi) anahtar kelime listesinden çok, düz cümleyle anlatılan sahneyi ve
 * "yalnızca X'i değiştir, gerisini aynen koru" kalıbını izliyor; prompt bu
 * yüzden paragraf paragraf kurulur:
 * 1. Görev ve korunacaklar  2. Tedavi  3. Tasarım (yalnız yeniden tasarımda)
 * 4. Renk  5. Fotoğraf gerçekçiliği  6. Maske / kapsam / yerinde düzenleme.
 *
 * "Yapay zeka görünümü"nün başlıca nedenleri ve karşılıkları:
 * - dişlerin büyümesi → boyut mevcut dişlere göre tarif edilir; korumacı
 *   tasarımlarda (Doğal, Beyazlatma, Ortodonti, Minimal) dişler aynı kalır,
 *   ideal oranlar yalnız yeniden tasarımda istenir
 * - saf beyaz, tekdüze, parlayan diş → ton aralığı, saydam kenar, gölgeli köşe
 * - fotoğrafa "yapıştırılmış" görünüm → aynı kamera, ışık yönü, gren, netlik
 */
object SmilePrompt {
    /** Cümle önceliği: sınır aşılırsa önce en düşük öncelikliler çıkarılır. */
    private enum class Priority(val rawValue: Int) { helpful(1), important(2), essential(3) }

    /** DSD oranları — yalnız dişler yeniden tasarlanırken. */
    const val proportions = "Use real dental proportions: the two central incisors are the most dominant teeth, about 75–80% as wide as they are long; the lateral incisors are about two-thirds as wide as the centrals and slightly shorter; the canines look narrower in this front view and have gently pointed tips; the biting edges form a gentle convex curve that follows the lower lip; the small V-shaped notches between the tooth edges grow slightly from the centrals toward the canines; the gum line is scalloped, with the central and canine gum peaks at a similar height and the laterals slightly lower; only small dark corridors remain at the mouth corners."

    /** Fotoğrafla bütünleşme — en önemli gerçekçilik cümlesi. */
    const val photoMatch = "Photographic realism matters most. The teeth must look captured by the same camera at the same moment as the lips around them: the same focus and sharpness, exposure, white balance, noise and compression; only the tooth shade itself changes as described. Follow the light already in the photo: soft highlights on the enamel only where this light falls, a gentle shadow from the upper lip across the top of the upper teeth, teeth gradually darker toward the mouth corners and the back of the mouth, and the dark inside of the mouth still visible between and behind the teeth where the mouth is open."

    /** Mine dokusu ve dişler arası doğal farklılık. */
    const val enamel = "Real enamel: fine surface texture with faint horizontal lines, slight translucency along the biting edges, a little warmer and more saturated near the gums; neighbouring teeth differ slightly in tone, outline and angle, with tiny natural differences between the left and right side. Lower teeth, where visible, sit slightly deeper in shadow and look a little less bright than the upper teeth."

    /** Kaçınılacak görünümler (kısa tutulur; modeller olumlu tarifi daha iyi izliyor). */
    const val avoid = "Nothing may look artificial: no denture look, no plastic or porcelain-tile teeth, no glow, no oversized or extra teeth, no airbrushed smoothness, no pasted-in look."

    /**
     * Yeniden tasarımın tek cümlelik özeti, ör. "Give the patient a new full, even
     * Hollywood smile: porcelain veneers on the upper front teeth, squarish, …".
     */
    fun headline(d: SmileDesign): String {
        val traits = mutableListOf<String>()
        d.form.adjective?.let { traits.add(it) }
        val size = listOfNotNull(d.length.lengthAdjective, d.width.widthAdjective)
        if (size.isNotEmpty()) traits.add(size.joinToString(" and ") + " than now")
        if (d.procedures.contains(SmileProcedure.alignment)) traits.add("evenly aligned")
        d.shade.shortName?.let { traits.add("in $it") }
        val work = if (d.procedures.contains(SmileProcedure.veneers))
            "porcelain veneers on the upper front teeth"
        else
            "reshaped upper front teeth, as with composite bonding"
        var s = "Give the patient a new " + (d.character.adjective?.let { "$it " } ?: "") + "smile: " + work
        if (traits.isNotEmpty()) s += ", " + traits.joinToString(", ")
        if (d.procedures.contains(SmileProcedure.implants)) s += ", with every missing or broken tooth restored"
        if (d.procedures.contains(SmileProcedure.gumContour)) s += ", and an even gum line with little gum showing"
        return "$s."
    }

    /**
     * `maxLength`: hedef modelin prompt sınırı. Cümleler öncelikleriyle eklenir;
     * sınır aşılırsa en düşük öncelikliler (sondan başlayarak) çıkarılır.
     */
    fun build(
        design: SmileDesign,
        maxLength: Int = 800,
        maskedContext: Boolean = true,
        masked: Boolean = false,
        scope: MaskScope = MaskScope.all,
        maskedOnly: Boolean = false,
        frontal: Boolean = true,
    ): String {
        val p = design.procedures
        val editsGums = p.contains(SmileProcedure.gumContour)
        val redesign = design.isRedesign
        val moves = p.contains(SmileProcedure.alignment) || p.contains(SmileProcedure.gapClosure)
        val parts = mutableListOf<Pair<String, Priority>>()
        fun add(text: String, priority: Priority) { parts.add(text to priority) }

        // 1) Görev ve korunacaklar
        val target = if (editsGums) "the teeth and the gum line" else "the teeth"
        // Yeniden tasarımda istenen değişim ilk cümlede: model ilk ve net talimatı izliyor;
        // "renkleri koru" ise diş rengini de kapsamasın diye dudak/cilt/arka planla sınırlı.
        val opening = if (maskedOnly)
            "Using the provided image, which shows only a patient's mouth opening on a black background, "
        else
            "Using the provided photo, a close-up of a patient's smile, "
        val keep = if (maskedOnly) "" else "; keep the lips, skin, facial hair and background exactly as they are, preserving the original lighting and composition"
        if (redesign) {
            add(opening + headline(design).lowercasedFirst() + " Change only $target$keep.", Priority.essential)
        } else {
            add(opening + "change only $target$keep.", Priority.essential)
        }
        // Korumacı tasarımda "idealize etme"; yeniden tasarımda ise değişim belirgin olmalı —
        // aksi halde gerçekçilik cümleleri modeli frenliyor ve Hollywood bile neredeyse aynı çıkıyordu.
        if (redesign) {
            add("This is a cosmetic makeover preview the dentist shows the patient: the new smile must be clearly different from the current teeth at first glance, while still looking like real teeth in an ordinary, untouched photo of the same person.", Priority.essential)
        } else {
            add("This is a treatment preview the dentist shows the patient, so it must look like an ordinary, untouched photo of the same person taken right after treatment: convincing and believable, never idealized.", Priority.important)
        }

        // 2) Tedavi
        if (!redesign) {
            add("Work with the patient's own teeth: each tooth keeps its shape, size, length and position unless a fix below changes it.", Priority.essential)
        }
        val procedures = SmileProcedure.allCases.filter { p.contains(it) }
        if (procedures.isEmpty()) {
            add("Make a subtle, natural improvement.", Priority.essential)
        }
        for (procedure in procedures) {
            add(procedure.instruction(redesign), Priority.essential)
        }

        // 3) Tasarım (yalnız yeniden şekillendirmede) ve dizilim
        if (redesign) {
            val t = mutableListOf(design.form.instruction)
            design.length.lengthInstruction?.let { t.add(it) }
            design.width.widthInstruction?.let { t.add(it) }
            design.character.instruction?.let { t.add(it) }
            add("Tooth design: " + t.joinToString("; ") + ".", Priority.essential)
            // Form/boyut istenip lamina seçilmediyse model yeniden şekillendirmeyi atlıyor → açıkça iste
            if (!p.contains(SmileProcedure.veneers)) {
                add("Visibly reshape the upper front teeth to this design, as with veneers or composite bonding.", Priority.essential)
            }
            // Boyut istenmediyse model dişleri büyütüp ağzı doldurmaya çalışıyor
            if (design.length == ToothScale.zero && design.width == ToothScale.zero) {
                add("Keep the overall tooth size in proportion to the current teeth and this face; do not enlarge the teeth to fill the mouth.", Priority.essential)
            }
            add(proportions, Priority.important)
        } else if (p.contains(SmileProcedure.alignment)) {
            add("Keep each tooth's own size and shape while arranging them in a smooth arch whose biting edges follow the curve of the lower lip.", Priority.important)
        }
        if (redesign || moves) {
            // Karşıdan çekimde simetri; yandan çekimde perspektif korunur ("yamuk" diş olmasın)
            add(
                if (frontal)
                    "The photo is taken from the front: keep the dental midline vertical and centered under the nose, with the left and right sides balanced in size and height."
                else
                    "The head is slightly turned: keep this perspective, so teeth on the far side look a little narrower, and keep the arch level with the lips.",
                Priority.important,
            )
        }

        // 4) Renk — beyazlatma seçilip renk "Mevcut" bırakılırsa çelişmesin
        if (design.shade == ToothShade.keep) {
            add("Tooth shade: " + (if (p.contains(SmileProcedure.whitening)) "a few shades lighter than now, still natural" else design.shade.instruction) + ".", Priority.essential)
        } else {
            // Hedef renk yazılınca model mevcut rengi "yeterince yakın" sayıp bırakabiliyor → açıkça iste
            add("Tooth shade: " + design.shade.instruction + ". If the teeth are darker or more yellow than this now, lighten them clearly until they reach it.", Priority.essential)
        }

        // 5) Fotoğraf gerçekçiliği
        add(photoMatch, Priority.essential)
        add(enamel, Priority.important)
        add(avoid, Priority.helpful)

        // 6) Maske, çene kapsamı, yerinde düzenleme
        if (masked) {
            add("Change only the area marked by the mask, the teeth inside the lip opening; the new teeth must fit naturally inside the existing lips.", Priority.essential)
        }
        when (scope) {
            MaskScope.upper -> add("Change only the upper teeth; keep the lower teeth exactly as they are.", Priority.essential)
            MaskScope.lower -> add("Change only the lower teeth; keep the upper teeth exactly as they are.", Priority.essential)
            MaskScope.all -> Unit
        }
        if (maskedOnly) {
            // KVKK: modele yalnızca ağız açıklığı gider, geri kalanı siyah
            add("Everything black is hidden on purpose: keep it pure black and never draw lips, skin or anything else there. Fill the visible opening with the same outline, position and size; do not move or rescale anything.", Priority.essential)
        } else {
            // Geometri kilidi: model ağzı kapatıp/yüzü yeniden çizerse cihazdaki birleştirme tutmaz
            add("Edit in place: same person, same head pose, same framing and scale. Keep the exact lip contour, the mouth corners and how wide the mouth is open; the new teeth fill the existing opening and never cover or reshape the lips. Lips, skin and facial hair stay unchanged" + (if (maskedContext) "; blurred areas stay blurred and unchanged." else "."), Priority.essential)
        }

        fun joined(): String = parts.joinToString(" ") { it.first }
        var prompt = joined()
        while (prompt.length > maxLength) {
            val lowest = parts.minOfOrNull { it.second.rawValue } ?: break
            if (lowest >= Priority.essential.rawValue) break
            val i = parts.indexOfLast { it.second.rawValue == lowest }
            if (i < 0) break
            parts.removeAt(i)
            prompt = joined()
        }
        return prompt.take(maxLength)
    }

    private fun String.lowercasedFirst(): String = take(1).lowercase() + drop(1)
}
