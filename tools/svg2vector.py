#!/usr/bin/env python3
"""Web ikon setini (tools/ikon-kaynak/dfy, dfycift — Hugeicons + çift tonlu SVG) Android VectorDrawable'a çevirir.

Neden: SVG'leri çalışma anında Coil ile çözmek her ikonda eşzamansız yükleme + ilk karede boşluk demekti (listelerde
takılma). VectorDrawable derlemede doğrulanır, eşzamanlı çizilir ve önbelleğe alınır.

Desteklenen: svg/g/path/circle/ellipse; fill, stroke, stroke-width, stroke-linecap, stroke-linejoin, stroke-miterlimit,
opacity (g'den path'e çarpılarak iner — VectorDrawable grubunda alfa yok), fill-rule. Yol verisi SVG dilbilgisine göre
ayrıştırılıp açık ayraçlarla yeniden yazılır (sıkıştırılmış yay bayrakları "a1 1 0 011 1" eski Android ayrıştırıcısında bozulur).

Kullanım: python3 tools/svg2vector.py   (repo kökünden)
Çıktı: app/src/main/res/drawable/{dfy,dfycift}_<ad>.xml + app/src/main/java/com/dentifay/app/theme/DfyIconRes.kt
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(KOK, "tools/ikon-kaynak")
DRAWABLE = os.path.join(KOK, "app/src/main/res/drawable")
KT = os.path.join(KOK, "app/src/main/java/com/dentifay/app/theme/DfyIconRes.kt")

SAYI = re.compile(r"[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?")
KOMUT_PARAM = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def yol_normalle(d: str) -> str:
    """SVG yol verisini ayrıştırıp her sayıyı açık ayraçla yazar (yay bayrakları tek karakter okunur)."""
    i, n = 0, len(d)
    cikti = []
    komut = None

    def bosluk_atla(j):
        while j < n and d[j] in " \t\r\n,":
            j += 1
        return j

    while True:
        i = bosluk_atla(i)
        if i >= n:
            break
        ch = d[i]
        if ch.isalpha():
            komut = ch
            i += 1
            if komut in "Zz":
                cikti.append(komut)
                continue
        elif komut is None:
            raise ValueError(f"yol komutla başlamıyor: {d[:30]}")
        # bu komutun bir parametre kümesi
        sayac = KOMUT_PARAM[komut.upper()]
        degerler = []
        for k in range(sayac):
            i = bosluk_atla(i)
            if komut.upper() == "A" and k in (3, 4):
                if i < n and d[i] in "01":
                    degerler.append(d[i])
                    i += 1
                    continue
                raise ValueError(f"yay bayrağı beklenirdi: {d[i:i+10]}")
            m = SAYI.match(d, i)
            if not m:
                raise ValueError(f"sayı beklenirdi ({komut}): {d[i:i+15]!r}")
            degerler.append(m.group(0))
            i = m.end()
        cikti.append(komut + " " + " ".join(degerler))
        # örtük tekrar: M'den sonra gelen çiftler L sayılır
        if komut == "M":
            komut = "L"
        elif komut == "m":
            komut = "l"
    return " ".join(cikti)


def renk(deger):
    if deger is None or deger == "none":
        return None
    if deger in ("currentColor", "black"):
        return "#FF000000"
    if deger.startswith("#"):
        h = deger[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        return "#FF" + h.upper()
    if deger == "white":
        return "#FFFFFFFF"
    raise ValueError(f"renk: {deger}")


def daire_yolu(cx, cy, rx, ry):
    return (f"M {cx - rx} {cy} A {rx} {ry} 0 1 0 {cx + rx} {cy} A {rx} {ry} 0 1 0 {cx - rx} {cy} Z")


MIRAS = ["fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "fill-rule", "clip-rule"]


def yolu_yaz(el, stil, opaklik):
    tag = el.tag.split("}")[-1]
    if tag == "path":
        d = yol_normalle(el.get("d"))
    elif tag == "circle":
        r = float(el.get("r"))
        d = daire_yolu(float(el.get("cx", 0)), float(el.get("cy", 0)), r, r)
    elif tag == "ellipse":
        d = daire_yolu(float(el.get("cx", 0)), float(el.get("cy", 0)), float(el.get("rx")), float(el.get("ry")))
    else:
        raise ValueError(f"desteklenmeyen öğe: {tag}")
    s = dict(stil)
    for k in MIRAS:
        if el.get(k) is not None:
            s[k] = el.get(k)
    op = opaklik * float(el.get("opacity", "1"))
    dolgu = renk(s.get("fill", "#000000"))   # SVG varsayılan dolgusu siyah
    cizgi = renk(s.get("stroke"))
    a = [f'        android:pathData="{d}"']
    if dolgu:
        a.append(f'        android:fillColor="{dolgu}"')
        if op < 1:
            a.append(f'        android:fillAlpha="{op:.3f}"')
        if s.get("fill-rule") == "evenodd":
            a.append('        android:fillType="evenOdd"')
    if cizgi:
        a.append(f'        android:strokeColor="{cizgi}"')
        a.append(f'        android:strokeWidth="{float(s.get("stroke-width", "1"))}"')
        if s.get("stroke-linecap") in ("round", "square"):
            a.append(f'        android:strokeLineCap="{s["stroke-linecap"]}"')
        if s.get("stroke-linejoin") in ("round", "bevel"):
            a.append(f'        android:strokeLineJoin="{s["stroke-linejoin"]}"')
        if s.get("stroke-miterlimit"):
            a.append(f'        android:strokeMiterLimit="{s["stroke-miterlimit"]}"')
        if op < 1:
            a.append(f'        android:strokeAlpha="{op:.3f}"')
    return "    <path\n" + "\n".join(a) + " />"


def gez(el, stil, opaklik, yollar):
    for c in el:
        tag = c.tag.split("}")[-1]
        if tag == "g":
            s = dict(stil)
            for k in MIRAS:
                if c.get(k) is not None:
                    s[k] = c.get(k)
            gez(c, s, opaklik * float(c.get("opacity", "1")), yollar)
        elif tag in ("path", "circle", "ellipse"):
            yollar.append(yolu_yaz(c, stil, opaklik))
        elif tag in ("title", "desc"):
            continue
        else:
            raise ValueError(f"desteklenmeyen öğe: {tag}")


def cevir(svg_yolu):
    kok = ET.parse(svg_yolu).getroot()
    vb = kok.get("viewBox")
    if vb:
        _, _, vw, vh = [float(x) for x in vb.replace(",", " ").split()]
    else:
        vw, vh = float(kok.get("width", 24)), float(kok.get("height", 24))
    stil = {k: kok.get(k) for k in MIRAS if kok.get(k) is not None}
    yollar = []
    gez(kok, stil, float(kok.get("opacity", "1")), yollar)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- ÜRETİLEN DOSYA — tools/svg2vector.py (kaynak: tools/ikon-kaynak/" + os.path.relpath(svg_yolu, ASSETS) + ") -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        f'    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">\n'
        + "\n".join(yollar)
        + "\n</vector>\n"
    )


def kaynak_adi(on, ad):
    return f"{on}_" + re.sub(r"[^a-z0-9]", "_", ad.lower())


def main():
    os.makedirs(DRAWABLE, exist_ok=True)
    for f in os.listdir(DRAWABLE):
        if f.startswith(("dfy_", "dfycift_")) and f.endswith(".xml"):
            os.remove(os.path.join(DRAWABLE, f))
    girdiler = {}
    for klasor, on in (("dfy", "dfy"), ("dfycift", "dfycift")):
        for f in sorted(os.listdir(os.path.join(ASSETS, klasor))):
            if not f.endswith(".svg"):
                continue
            ad = f[:-4]
            res = kaynak_adi(on, ad)
            with open(os.path.join(DRAWABLE, res + ".xml"), "w") as o:
                o.write(cevir(os.path.join(ASSETS, klasor, f)))
            girdiler.setdefault(klasor, []).append((ad, res))
    satirlar = [
        "package com.dentifay.app.theme",
        "",
        "import com.dentifay.app.R",
        "",
        "// ÜRETİLEN DOSYA — tools/svg2vector.py. Web ikon adı → VectorDrawable kaynağı.",
        "object DfyIconRes {",
    ]
    for klasor, deg in (("dfy", "tek"), ("dfycift", "cift")):
        satirlar.append(f"    val {deg}: Map<String, Int> = mapOf(")
        satirlar += [f'        "{ad}" to R.drawable.{res},' for ad, res in girdiler[klasor]]
        satirlar.append("    )")
    satirlar.append("}")
    with open(KT, "w") as o:
        o.write("\n".join(satirlar) + "\n")
    print(f"{sum(len(v) for v in girdiler.values())} ikon çevrildi")


if __name__ == "__main__":
    sys.exit(main())
