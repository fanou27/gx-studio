# -*- coding: utf-8 -*-
import re
from odf.opendocument import OpenDocumentText
from odf.style import (Style, TextProperties, ParagraphProperties, TableColumnProperties,
                       TableCellProperties, GraphicProperties, PageLayout, PageLayoutProperties,
                       MasterPage, ListLevelProperties, TableProperties)
from odf.text import H, P, Span, List, ListItem, ListStyle, ListLevelStyleBullet
from odf.table import Table, TableColumn, TableRow, TableCell
from odf.draw import Frame, Image as DImage
from PIL import Image as PImage

SRC = '/home/claude/studio/'
doc = OpenDocumentText()
# ---- page
pl = PageLayout(name="pm1")
pl.addElement(PageLayoutProperties(pagewidth="21cm", pageheight="29.7cm", margintop="1.8cm",
              marginbottom="1.8cm", marginleft="2cm", marginright="2cm"))
doc.automaticstyles.addElement(pl)
doc.masterstyles.addElement(MasterPage(name="Standard", pagelayoutname=pl))
# ---- styles
def pstyle(name, size="11pt", bold=False, color=None, family="Liberation Sans", mt="0cm", mb="0.18cm",
           align=None, bg=None, font=None, keep=False, ml=None, parent=None):
    st = Style(name=name, family="paragraph")
    tp = dict(fontsize=size, fontname=font or family, fontfamily=font or family)
    if bold: tp['fontweight'] = "bold"
    if color: tp['color'] = color
    st.addElement(TextProperties(**tp))
    pp = dict(margintop=mt, marginbottom=mb)
    if align: pp['textalign'] = align
    if bg: pp['backgroundcolor'] = bg
    if keep: pp['keepwithnext'] = "always"
    if ml: pp['marginleft'] = ml
    st.addElement(ParagraphProperties(**pp))
    doc.styles.addElement(st)
    return st
S_TITLE = pstyle("Titre", "26pt", True, "#1d3557", align="center", mt="3cm", mb="0.3cm")
S_SUB = pstyle("SousTitre", "14pt", False, "#457b9d", align="center", mb="0.2cm")
S_H1 = pstyle("T1", "17pt", True, "#1d3557", mt="0.5cm", mb="0.25cm", keep=True)
S_H2 = pstyle("T2", "13pt", True, "#e76f51", mt="0.35cm", mb="0.15cm", keep=True)
S_P = pstyle("Corps", "11pt", align="justify")
S_CODE = pstyle("Code", "8.5pt", font="Liberation Mono", bg="#f1f3f5", mb="0cm", mt="0cm", ml="0.3cm")
S_CAP = pstyle("Legende", "9.5pt", False, "#555555", align="center", mb="0.3cm")
S_CELL = pstyle("Cellule", "9.5pt", mb="0.05cm")
S_CELLB = pstyle("CelluleTitre", "9.5pt", True, "#ffffff", mb="0.05cm")
S_CENTER = pstyle("Centre", "11pt", align="center")
for f in ("Liberation Sans", "Liberation Mono"):
    from odf.style import FontFace
    doc.fontfacedecls.addElement(FontFace(name=f, fontfamily=f))
bold = Style(name="Gras", family="text"); bold.addElement(TextProperties(fontweight="bold")); doc.styles.addElement(bold)
mono = Style(name="Mono", family="text"); mono.addElement(TextProperties(fontname="Liberation Mono", fontfamily="Liberation Mono", fontsize="9.5pt", color="#9d0208")); doc.styles.addElement(mono)
cellh = Style(name="CellH", family="table-cell"); cellh.addElement(TableCellProperties(backgroundcolor="#1d3557", border="0.5pt solid #999999", padding="0.08cm")); doc.automaticstyles.addElement(cellh)
cell = Style(name="CellN", family="table-cell"); cell.addElement(TableCellProperties(border="0.5pt solid #999999", padding="0.08cm")); doc.automaticstyles.addElement(cell)
cellz = Style(name="CellZ", family="table-cell"); cellz.addElement(TableCellProperties(backgroundcolor="#f4f7fb", border="0.5pt solid #999999", padding="0.08cm")); doc.automaticstyles.addElement(cellz)
frs = Style(name="fr", family="graphic"); frs.addElement(GraphicProperties(anchortype="as-char", verticalpos="top")); doc.automaticstyles.addElement(frs)
bl = ListStyle(name="Puces")
blv = ListLevelStyleBullet(level=1, bulletchar="•")
blv.addElement(ListLevelProperties(spacebefore="0.4cm", minlabelwidth="0.45cm"))
bl.addElement(blv); doc.automaticstyles.addElement(bl)

def rich(p, s):
    # **gras** et `code`
    for part in re.split(r'(\*\*[^*]+\*\*|`[^`]+`)', s):
        if not part: continue
        if part.startswith('**'): p.addElement(Span(stylename=bold, text=part[2:-2]))
        elif part.startswith('`'): p.addElement(Span(stylename=mono, text=part[1:-1]))
        else: p.addText(part)
    return p
def h1(t): doc.text.addElement(H(outlinelevel=1, stylename=S_H1, text=t))
def h2(t): doc.text.addElement(H(outlinelevel=2, stylename=S_H2, text=t))
def para(t): doc.text.addElement(rich(P(stylename=S_P), t))
def bullets(items):
    l = List(stylename=bl)
    for it in items:
        li = ListItem(); li.addElement(rich(P(stylename=S_P), it)); l.addElement(li)
    doc.text.addElement(l)
def code(t):
    for line in t.strip('\n').split('\n'):
        p = P(stylename=S_CODE)
        # conserver les espaces
        from odf.text import S as Sp
        line = line.replace('\t', '        ')
        for part in re.split(r'( {2,}|^ )', line):
            if not part: continue
            if part.strip() == '': p.addElement(Sp(c=len(part)))
            else: p.addText(part)
        doc.text.addElement(p)
    doc.text.addElement(P(stylename=S_P, text=""))
_img = [0]
def image(path, width_cm, caption=None):
    w, h = PImage.open(path).size
    hcm = width_cm * h / w
    _img[0] += 1
    href = doc.addPicture(path)
    p = P(stylename=S_CENTER)
    fr = Frame(stylename=frs, width="%.2fcm" % width_cm, height="%.2fcm" % hcm, anchortype="as-char", name="img%d" % _img[0])
    fr.addElement(DImage(href=href)); p.addElement(fr)
    doc.text.addElement(p)
    if caption: doc.text.addElement(P(stylename=S_CAP, text=caption))
_tbl = [0]
def table(rows, widths):
    _tbl[0] += 1
    ts = Style(name="Tab%d" % _tbl[0], family="table"); ts.addElement(TableProperties(width="%.1fcm" % sum(widths), align="center")); doc.automaticstyles.addElement(ts)
    t = Table(name="Tableau%d" % _tbl[0], stylename=ts)
    for i, w in enumerate(widths):
        cs = Style(name="Tab%dC%d" % (_tbl[0], i), family="table-column"); cs.addElement(TableColumnProperties(columnwidth="%.1fcm" % w)); doc.automaticstyles.addElement(cs)
        t.addElement(TableColumn(stylename=cs))
    for r, row in enumerate(rows):
        tr = TableRow()
        for c in row:
            tc = TableCell(stylename=cellh if r == 0 else (cellz if r % 2 == 0 else cell))
            tc.addElement(rich(P(stylename=S_CELLB if r == 0 else S_CELL), c))
            tr.addElement(tc)
        t.addElement(tr)
    doc.text.addElement(t)
    doc.text.addElement(P(stylename=S_P, text=""))
def excerpt(fname, start, end_label=None, maxlines=60):
    """extrait du source entre la ligne commencant par 'start' et 'end_label'"""
    lines = open(SRC + fname, encoding='utf-8', errors='replace').read().split('\n')
    i = next(k for k, l in enumerate(lines) if l.startswith(start))
    out = []
    for l in lines[i:]:
        if end_label and out and l.startswith(end_label): break
        out.append(l)
        if len(out) >= maxlines: break
    code('\n'.join(out))
IMG = '/home/claude/studio/doc/img/'
exec(open('/home/claude/studio/doc/content.py', encoding='utf-8').read())
doc.save('/home/claude/studio/doc/GX_Studio_documentation.odt')
print('saved')
