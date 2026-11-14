"""Erzeugt kleine Beispiel-PDFs mit eigenem Text (für Screenshots und Tests).

Aufruf: python tools/make_sample_books.py shared/samples

Die Texte, Autorinnen und Autoren sind erfunden. Die PDFs nutzen die Standardschriften
Times und Helvetica (nicht eingebettet); für die Zeilenbreiten werden die Maße der
Windows-Schriften Times New Roman und Arial gelesen.
"""
import os
import sys

from PIL import ImageFont

FONTS = r"C:\Windows\Fonts"
metrics = {
    "F1": ImageFont.truetype(os.path.join(FONTS, "times.ttf"), 1000),
    "F2": ImageFont.truetype(os.path.join(FONTS, "timesbd.ttf"), 1000),
    "F3": ImageFont.truetype(os.path.join(FONTS, "arialbd.ttf"), 1000),
    "F4": ImageFont.truetype(os.path.join(FONTS, "arial.ttf"), 1000),
}
BASE = {"F1": "Times-Roman", "F2": "Times-Bold", "F3": "Helvetica-Bold", "F4": "Helvetica"}


class Format:
    """Seitenformat eines Buchs in PDF-Punkten."""

    def __init__(self, w, h, margin_x, top, bottom, body, leading):
        self.W, self.H = w, h
        self.margin_x, self.top, self.bottom = margin_x, top, bottom
        self.body, self.leading = body, leading


# Kleines Taschenbuch und ein A4-Dokument mit langen Zeilen (wie ein Export aus Word).
POCKET = Format(420, 595, 50, 75, 70, 11.5, 16.5)
A4 = Format(595, 842, 72, 90, 80, 11, 15)


def width(text, font, size):
    return metrics[font].getlength(text) / 1000 * size


def esc(text):
    data = text.encode("cp1252")
    return data.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def text_op(x, y, text, font, size):
    return b"BT /%s %.2f Tf %.2f %.2f Td (" % (font.encode(), size, x, y) + esc(text) + b") Tj ET\n"


def centered(f, y, text, font, size):
    return text_op((f.W - width(text, font, size)) / 2, y, text, font, size)


CHAPTERS = [
    ("Erstes Kapitel", "Die Nacht des Sturms", [
        "Seit dreiundvierzig Jahren brannte das Licht auf der Insel Varn, und in all dieser Zeit hatte Ilse Brand keine einzige Nacht verschlafen. Sie kannte jedes Geräusch des Turms: das Knarren der Wendeltreppe, das Summen der Linse, das leise Klirren der Fenster, wenn der Wind aus Nordwest kam.",
        "An diesem Abend aber war etwas anders. Der Himmel über dem Meer hatte die Farbe von nassem Schiefer, und die Möwen waren schon am Nachmittag landeinwärts geflogen. Ilse stellte den Kessel auf den Ofen, wickelte sich in ihren grauen Mantel und stieg die hundertzwölf Stufen hinauf.",
        "Oben angekommen, putzte sie die Scheiben, wie sie es jeden Abend tat. Dann prüfte sie den Docht, füllte das Öl nach und zog das Uhrwerk auf, das die große Linse drehte. Der Lichtstrahl wanderte über das Wasser, langsam und gleichmäßig, wie der Atem eines schlafenden Riesen.",
        "Gegen Mitternacht sah sie es zum ersten Mal: ein schwaches, flackerndes Licht weit draußen, dort, wo eigentlich nur offene See sein durfte. Es leuchtete auf, verschwand und kehrte wieder, dreimal kurz, dreimal lang, dreimal kurz.",
        "Ilse griff nach dem Fernglas. Ihre Hände zitterten nicht, das hatten sie noch nie getan, aber ihr Herz schlug schneller als sonst. Durch die Regenschleier erkannte sie den Umriss eines kleinen Fischerboots, das gegen die Wellen ankämpfte.",
    ]),
    ("Zweites Kapitel", "Das Boot", [
        "Es gab keinen Hafen auf Varn, nur eine schmale Bucht auf der Ostseite, geschützt von zwei Felsnasen, die die Leute von der Insel die Wächter nannten. Wer die Einfahrt nicht kannte, zerschellte an den Klippen, bevor er sie überhaupt sah.",
        "Ilse wusste, was zu tun war. Sie löste die Sperre der Linse und richtete den Strahl so aus, dass er genau zwischen die beiden Wächter fiel. Dann blendete sie das Licht in einem festen Rhythmus ab, so wie es ihr Vater sie gelehrt hatte, als sie noch ein Kind war.",
        "Eine halbe Stunde verging, dann eine ganze. Das Boot verschwand hinter einer Welle und tauchte wieder auf, ein winziger Schatten in einer Welt aus Wasser und Wind. Endlich drehte es bei und hielt auf die Bucht zu.",
        "Als der Morgen graute, legte das Boot an den Felsen der Bucht an. Ein junger Mann sprang an Land, durchnässt und erschöpft, und schaute zum Turm hinauf. Ilse winkte ihm zu. Er hob die Hand und winkte zurück.",
        "Später, als er am Ofen saß und den heißen Tee mit beiden Händen hielt, erzählte er ihr, dass er das Licht schon von weitem gesehen habe. Es sei das Einzige gewesen, woran er sich in dieser Nacht habe festhalten können.",
        "Ilse nickte nur. Sie wusste, dass es so war. Dafür brannte das Licht ja, seit dreiundvierzig Jahren, jede einzelne Nacht.",
    ]),
]


def wrap(f, paragraph, first_indent):
    lines, line, indent = [], "", first_indent
    max_w = f.W - 2 * f.margin_x
    for word in paragraph.split():
        candidate = (line + " " + word).strip()
        if width(candidate, "F1", f.body) + indent > max_w and line:
            lines.append((indent, line, True))
            line, indent = word, 0
        else:
            line = candidate
    lines.append((indent, line, False))
    return lines


def justified(f, y, text, indent, stretch):
    x = f.margin_x
    if not stretch:
        return text_op(x + indent, y, text, "F1", f.body)
    words = text.split()
    gaps = len(words) - 1
    natural = width(text, "F1", f.body)
    extra = (f.W - 2 * f.margin_x - indent - natural) / gaps if gaps else 0
    ops, cursor = b"", x + indent
    for word in words:
        ops += text_op(cursor, y, word, "F1", f.body)
        cursor += width(word + " ", "F1", f.body) + extra
    return ops


def circle(cx, cy, r):
    k = 0.5523 * r
    return (b"%.1f %.1f m " % (cx + r, cy)
            + b"%.1f %.1f %.1f %.1f %.1f %.1f c " % (cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            + b"%.1f %.1f %.1f %.1f %.1f %.1f c " % (cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            + b"%.1f %.1f %.1f %.1f %.1f %.1f c " % (cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            + b"%.1f %.1f %.1f %.1f %.1f %.1f c h f\n" % (cx + k, cy - r, cx + r, cy - k, cx + r, cy))


def cover(f, title, subtitle, author, sky):
    # Gezeichnet für 420 x 595 und auf das Seitenformat skaliert.
    s = f.W / 420
    r, g, b = sky
    ops = b"q %.4f 0 0 %.4f 0 0 cm\n" % (s, f.H / 595)
    ops += b"%.2f %.2f %.2f rg 0 0 420 595 re f\n" % (r, g, b)
    ops += b"0.13 0.20 0.27 rg 0 0 420 150 re f\n"  # Meer
    ops += b"1 0.86 0.45 rg 210 352 m 420 440 l 420 290 l h f\n"  # Lichtstrahl rechts
    ops += b"1 0.86 0.45 rg 210 352 m 0 440 l 0 290 l h f\n"  # Lichtstrahl links
    ops += b"0.93 0.93 0.90 rg 180 150 m 240 150 l 228 330 l 192 330 l h f\n"  # Turm
    ops += b"0.75 0.22 0.20 rg 186 230 m 234 230 l 232 260 l 188 260 l h f\n"  # rotes Band
    ops += b"0.25 0.25 0.28 rg 188 330 m 232 330 l 232 336 l 188 336 l h f\n"
    ops += b"1 0.80 0.30 rg " + circle(210, 352, 16)
    ops += b"0.25 0.25 0.28 rg 192 368 m 228 368 l 210 386 l h f\n"  # Dach
    ops += b"1 1 1 rg\n"
    small = POCKET
    ops += centered(small, 500, title, "F3", 30)
    ops += centered(small, 472, subtitle, "F4", 13)
    ops += centered(small, 60, author, "F4", 12)
    ops += b"Q\n"
    return ops


def build(f, title, subtitle, author, sky):
    pages = [cover(f, title, subtitle, author, sky)]
    title_page = b"0 0 0 rg\n" + centered(f, f.H * 0.66, title, "F2", 24) + centered(f, f.H * 0.66 - 30, subtitle, "F1", 13)
    title_page += centered(f, f.H * 0.66 - 70, author, "F1", 12)
    title_page += centered(f, 120, "Ein Beispieltext zum Ausprobieren von Easy Books.", "F1", 10)
    pages.append(title_page)

    page_no = 3
    for chapter, chapter_title, paragraphs in CHAPTERS:
        ops = b"0 0 0 rg\n" + centered(f, f.H - 140, chapter, "F2", 16) + centered(f, f.H - 162, chapter_title, "F1", 12)
        y = f.H - 210
        for p in paragraphs:
            for indent, line, stretch in wrap(f, p, 14):
                if y < f.bottom + 10:
                    ops += centered(f, 40, "- %d -" % page_no, "F1", 9)
                    pages.append(ops)
                    page_no += 1
                    ops, y = b"0 0 0 rg\n", f.H - f.top
                ops += justified(f, y, line, indent, stretch)
                y -= f.leading
        ops += centered(f, 40, "- %d -" % page_no, "F1", 9)
        pages.append(ops)
        page_no += 1
    return pages


def pdf_text(text):
    """Text für das Info-Dictionary als UTF-16 mit BOM."""
    return b"<FEFF" + text.encode("utf-16-be").hex().upper().encode() + b">"


def write_pdf(path, f, pages, title, author):
    objects = []

    def add(body):
        objects.append(body)
        return len(objects)

    catalog = add(None)
    pages_obj = add(None)
    fonts = {
        name: add(b"<< /Type /Font /Subtype /Type1 /BaseFont /%s /Encoding /WinAnsiEncoding >>" % base.encode())
        for name, base in BASE.items()
    }
    font_dict = b"<< " + b" ".join(b"/%s %d 0 R" % (n.encode(), i) for n, i in fonts.items()) + b" >>"
    kids = []
    for content in pages:
        stream = add(b"<< /Length %d >>\nstream\n" % len(content) + content + b"endstream")
        kids.append(add(
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Resources << /Font %s >> /Contents %d 0 R >>"
            % (pages_obj, f.W, f.H, font_dict, stream)
        ))
    info = add(b"<< /Title " + pdf_text(title) + b" /Author " + pdf_text(author) + b" /Producer (Easy Books sample) >>")
    objects[catalog - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % pages_obj
    objects[pages_obj - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (
        b" ".join(b"%d 0 R" % k for k in kids), len(kids))

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objects) + 1, catalog, info, xref)
    open(path, "wb").write(out)
    print(path, len(pages), "Seiten")


BOOKS = [
    # Ordner ("" = oberste Ebene), Dateiname, Titel, Untertitel, Autor, Himmelfarbe, Format
    ("", "[Holm_Marta]_Der_Leuchtturm (2019).pdf", "Der Leuchtturm", "Eine Geschichte von der Insel Varn",
     "Marta Holm", (0.18, 0.29, 0.38), POCKET),
    ("Meer", "[Eck_Jonas]_Abendrot (2021).pdf", "Abendrot", "Erzählungen vom Meer",
     "Jonas Eck", (0.55, 0.27, 0.22), POCKET),
    ("Winter", "[Sommer_Lea]_Nordlicht (2023).pdf", "Nordlicht", "Ein Wintertagebuch",
     "Lea Sommer", (0.16, 0.36, 0.30), A4),
]

if __name__ == "__main__":
    out = sys.argv[1]
    for folder, file_name, title, subtitle, author, sky, fmt in BOOKS:
        os.makedirs(os.path.join(out, folder), exist_ok=True)
        write_pdf(os.path.join(out, folder, file_name), fmt, build(fmt, title, subtitle, author, sky), title, author)
