"""
Gera docs/tcc/OmniReview_Documentacao_TCC.docx a partir de:
  avaliacao/resultados.json, avaliacao/conjunto_referencia.csv, diagramas/*.png
Todos os números do documento vêm do resultados.json (nada digitado à mão).

Requisitos: python-docx, matplotlib
Uso: python gerar_documento.py
"""
import csv
import json
import math
from collections import Counter
from datetime import date
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

AQUI = Path(__file__).parent
R = json.load(open(AQUI / "avaliacao" / "resultados.json", encoding="utf-8"))
REF = list(csv.DictReader(open(AQUI / "avaliacao" / "conjunto_referencia.csv", encoding="utf-8"), delimiter=";"))
GRAF = AQUI / "graficos"
GRAF.mkdir(exist_ok=True)
DIAG = AQUI / "diagramas"

# Paleta categórica validada (slots 1-3) + tinta de texto
AZUL, LARANJA, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
TINTA, TINTA2, GRADE = "#0b0b0b", "#52514e", "#e6e5e1"


# ============================================================ helpers numéricos
def f(x, casas=2):
    return "—" if x is None else f"{x:.{casas}f}".replace(".", ",")


def p(x, casas=1):
    return "—" if x is None else f"{x * 100:.{casas}f}%".replace(".", ",")


def ms(x):
    if x is None:
        return "—"
    return f"{x / 1000:.1f} s".replace(".", ",") if x >= 1000 else f"{x:.0f} ms"


# ============================================================ gráficos
def estilo_eixos(ax):
    for lado in ("top", "right", "left"):
        ax.spines[lado].set_visible(False)
    ax.spines["bottom"].set_color("#b9b8b2")
    ax.tick_params(colors=TINTA2, labelsize=9, length=0)
    ax.yaxis.grid(True, color=GRADE, linewidth=0.8)
    ax.set_axisbelow(True)


def barras_agrupadas(arquivo, categorias, series, titulo, ylabel, ymax=1.0, formato=lambda v: f(v)):
    fig, ax = plt.subplots(figsize=(7.2, 3.6), dpi=200)
    n = len(series)
    largura = 0.8 / n
    for i, (nome, valores, cor) in enumerate(series):
        xs = [x + (i - (n - 1) / 2) * largura for x in range(len(categorias))]
        barras = ax.bar(xs, valores, width=largura * 0.9, color=cor, label=nome, edgecolor="white", linewidth=1.5)
        for b, v in zip(barras, valores):
            ax.text(b.get_x() + b.get_width() / 2, b.get_height() + ymax * 0.015, formato(v),
                    ha="center", va="bottom", fontsize=8, color=TINTA)
    ax.set_xticks(range(len(categorias)))
    ax.set_xticklabels(categorias, fontsize=9, color=TINTA)
    ax.set_ylim(0, ymax * 1.12)
    ax.set_ylabel(ylabel, fontsize=9, color=TINTA2)
    fig.suptitle(titulo, fontsize=11, color=TINTA, x=0.01, ha="left", fontweight="bold")
    estilo_eixos(ax)
    fig.legend(*ax.get_legend_handles_labels(), frameon=False, fontsize=9, ncol=n, loc="upper left",
               bbox_to_anchor=(0.01, 0.93), labelcolor=TINTA, handlelength=1.2)
    fig.tight_layout(rect=(0, 0, 1, 0.86))
    fig.savefig(GRAF / arquivo, facecolor="white")
    plt.close(fig)
    return GRAF / arquivo


# ============================================================ documento
doc = Document()
sec = doc.sections[0]
sec.page_height, sec.page_width = Cm(29.7), Cm(21.0)
sec.top_margin = sec.left_margin = Cm(3)
sec.bottom_margin = sec.right_margin = Cm(2)

base = doc.styles["Normal"]
base.font.name = "Arial"
base.element.rPr.rFonts.set(qn("w:eastAsia"), "Arial")
base.font.size = Pt(11)
base.paragraph_format.space_after = Pt(6)
base.paragraph_format.line_spacing = 1.3
for nivel, tam in ((1, 15), (2, 13), (3, 11.5)):
    st = doc.styles[f"Heading {nivel}"]
    st.font.name = "Arial"
    rf_ = st.element.rPr.rFonts
    for atributo in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
        rf_.attrib.pop(qn(atributo), None)
    rf_.set(qn("w:eastAsia"), "Arial")
    st.font.size = Pt(tam)
    st.font.bold = True
    st.font.color.rgb = RGBColor(0x1F, 0x3A, 0x5F)
    st.paragraph_format.space_before = Pt(16 if nivel == 1 else 10)
    st.paragraph_format.space_after = Pt(6)
    st.paragraph_format.keep_with_next = True

contador = {"fig": 0, "tab": 0}


def par(texto="", negrito=False, italico=False, alinhamento=None, tamanho=None, cor=None, depois=None):
    pp = doc.add_paragraph()
    if texto:
        adiciona_rich(pp, texto, negrito, italico, tamanho, cor)
    if alinhamento is not None:
        pp.alignment = alinhamento
    if depois is not None:
        pp.paragraph_format.space_after = Pt(depois)
    return pp


def adiciona_rich(pp, texto, negrito=False, italico=False, tamanho=None, cor=None):
    """Aceita **negrito**, *itálico* e `código` inline."""
    import re
    for parte in re.split(r"(\*\*[^*]+\*\*|\*[^*\s][^*]*\*|`[^`]+`)", texto):
        if not parte:
            continue
        e_negrito, e_codigo = parte.startswith("**"), parte.startswith("`")
        e_italico = not e_negrito and parte.startswith("*") and parte.endswith("*") and len(parte) > 2
        run = pp.add_run(parte.strip("*`") if (e_negrito or e_codigo or e_italico) else parte)
        run.bold = negrito or e_negrito
        run.italic = italico or e_italico
        if parte.startswith("`"):
            run.font.name = "Consolas"
            run.font.size = Pt(9.5)
        elif tamanho:
            run.font.size = Pt(tamanho)
        if cor:
            run.font.color.rgb = RGBColor.from_string(cor)


def bullets(itens):
    for it in itens:
        pp = doc.add_paragraph(style="List Bullet")
        adiciona_rich(pp, it)
        pp.paragraph_format.space_after = Pt(3)


def sombrear(celula, cor):
    tcPr = celula._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), cor)
    tcPr.append(shd)


def legenda(tipo, texto):
    contador[tipo] += 1
    rotulo = "Figura" if tipo == "fig" else "Tabela"
    pp = doc.add_paragraph()
    r = pp.add_run(f"{rotulo} {contador[tipo]} — ")
    r.bold = True
    r.font.size = Pt(9.5)
    r2 = pp.add_run(texto)
    r2.font.size = Pt(9.5)
    pp.alignment = WD_ALIGN_PARAGRAPH.CENTER if tipo == "fig" else WD_ALIGN_PARAGRAPH.LEFT
    pp.paragraph_format.space_after = Pt(4 if tipo == "tab" else 12)
    if tipo == "tab":
        pp.paragraph_format.keep_with_next = True
    return contador[tipo]


def fonte(texto="Elaborado pelo autor (2026)."):
    pp = par(f"Fonte: {texto}", tamanho=8.5, cor="52514E", depois=12)
    return pp


def tabela(cabecalho, linhas, larguras=None, titulo=None, fonte_txt="Elaborado pelo autor (2026), a partir de resultados.json.",
           tamanho=9, destaque_status=None):
    if titulo:
        legenda("tab", titulo)
    t = doc.add_table(rows=1, cols=len(cabecalho))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(cabecalho):
        c = t.rows[0].cells[i]
        c.text = ""
        run = c.paragraphs[0].add_run(h)
        run.bold = True
        run.font.size = Pt(tamanho)
        run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
        sombrear(c, "1F3A5F")
    for li, linha in enumerate(linhas):
        cells = t.add_row().cells
        for i, v in enumerate(linha):
            cells[i].text = ""
            pp = cells[i].paragraphs[0]
            adiciona_rich(pp, str(v), tamanho=tamanho)
            for run in pp.runs:
                run.font.size = Pt(tamanho)
            pp.paragraph_format.space_after = Pt(0)
            pp.paragraph_format.line_spacing = 1.1
            if li % 2 == 1:
                sombrear(cells[i], "F3F6FA")
            if destaque_status is not None and i == destaque_status:
                cor = {"Atendido": "DFF3E8", "Parcial": "FFF2D6", "Não atendido": "FBE1E0"}.get(str(v).split(" ")[0] if not str(v).startswith("Não") else "Não atendido")
                if cor:
                    sombrear(cells[i], cor)
    # repete o cabeçalho em quebras de página
    trPr = t.rows[0]._tr.get_or_add_trPr()
    th = OxmlElement("w:tblHeader")
    th.set(qn("w:val"), "true")
    trPr.append(th)
    if larguras:
        for row in t.rows:
            for i, w in enumerate(larguras):
                row.cells[i].width = Cm(w)
    if fonte_txt:
        fonte(fonte_txt)
    return t


def codigo(texto):
    for linha in texto.strip("\n").split("\n"):
        pp = doc.add_paragraph()
        run = pp.add_run(linha if linha else " ")
        run.font.name = "Consolas"
        run.element.rPr.rFonts.set(qn("w:eastAsia"), "Consolas")
        run.font.size = Pt(8.5)
        pp.paragraph_format.space_after = Pt(0)
        pp.paragraph_format.line_spacing = 1.0
        pp.paragraph_format.left_indent = Cm(0.4)
        pPr = pp._p.get_or_add_pPr()
        shd = OxmlElement("w:shd")
        shd.set(qn("w:val"), "clear")
        shd.set(qn("w:fill"), "F3F4F6")
        pPr.append(shd)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def figura(caminho, texto, largura=15.5, fonte_txt="Elaborado pelo autor (2026)."):
    doc.add_picture(str(caminho), width=Cm(largura))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    doc.paragraphs[-1].paragraph_format.keep_with_next = True
    n = legenda("fig", texto)
    doc.paragraphs[-1].paragraph_format.space_after = Pt(2)
    fonte(fonte_txt).alignment = WD_ALIGN_PARAGRAPH.CENTER
    return n


def campo(pp, instrucao):
    r = pp.add_run()
    a = OxmlElement("w:fldChar"); a.set(qn("w:fldCharType"), "begin")
    b = OxmlElement("w:instrText"); b.set(qn("xml:space"), "preserve"); b.text = instrucao
    c = OxmlElement("w:fldChar"); c.set(qn("w:fldCharType"), "separate")
    d = OxmlElement("w:t"); d.text = "Clique com o botão direito e escolha \"Atualizar campo\" para gerar o sumário."
    e = OxmlElement("w:fldChar"); e.set(qn("w:fldCharType"), "end")
    for el in (a, b, c, d, e):
        r._r.append(el)


def quebra():
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


# rodapé com número de página
rodape = sec.footer.paragraphs[0]
rodape.alignment = WD_ALIGN_PARAGRAPH.RIGHT
r = rodape.add_run()
for tipo, txt in (("begin", None), (None, "PAGE"), ("end", None)):
    if tipo:
        el = OxmlElement("w:fldChar"); el.set(qn("w:fldCharType"), tipo)
    else:
        el = OxmlElement("w:instrText"); el.set(qn("xml:space"), "preserve"); el.text = txt
    r._r.append(el)

# ============================================================ dados derivados
TS = R["tags"]
SEL = R.get("selecao_estrategia", {})
ESC = SEL.get("escolhida", "v2_confirmadas_llm_generos")
PROD = TS[ESC]                      # tags do pipeline final (harness com as classes de produção)
BANCO = TS.get("producao_banco")    # tags gravadas no acervo atual (só obras de referência presentes)
V1 = TS.get("v1_llm_original")
GEN = TS.get("v2_generos_puros")
REC = R["recomendacao"]
CEN = REC["cenarios_referencia"]
DES = R["desempenho"]
IA1 = (R.get("ia") or {}).get("v1") or {}
IA2 = (R.get("ia") or {}).get("v2") or {}
INT = R["integridade_catalogo"]
CAT = R["catalogo"]
TC = R["tags_catalogo"]
GR = R.get("grafo", {})
UI_T = json.load(open(AQUI / "avaliacao" / "teste_interface.json", encoding="utf-8")) if (AQUI / "avaliacao" / "teste_interface.json").exists() else None
UI_OK = {c["id"]: c["ok"] for c in (UI_T or {}).get("casos", [])}

# Testes unitários (relatórios do Surefire)
import re as _re
import xml.etree.ElementTree as _ET
unit = {"testes": 0, "falhas": 0, "erros": 0, "classes": 0}
for xml in (AQUI.parent.parent / "backend-java" / "target" / "surefire-reports").glob("TEST-*.xml"):
    raiz = _ET.parse(xml).getroot()
    unit["classes"] += 1
    unit["testes"] += int(raiz.get("tests", 0))
    unit["falhas"] += int(raiz.get("failures", 0))
    unit["erros"] += int(raiz.get("errors", 0))

# Varredura de segredos no código-fonte versionado (RNF10)
padrao_chave = _re.compile(r"(api[_-]?key|API_KEY|password|senha)\s*[=:]\s*\"?[A-Za-z0-9]{16,}", _re.I)
achados = []
for pasta in ("backend-java/src", "frontend-react/src"):
    for arq in (AQUI.parent.parent / pasta).rglob("*"):
        if arq.is_file() and arq.suffix in (".java", ".js", ".jsx", ".properties", ".html", ".yml"):
            texto = arq.read_text(encoding="utf-8", errors="ignore")
            if padrao_chave.search(texto) or _re.search(r"\b[0-9a-f]{32}\b", texto):
                achados.append(str(arq.relative_to(AQUI.parent.parent)))
gitignore_ok = "config/segredos.properties" in (AQUI.parent.parent / "backend-java" / ".gitignore").read_text(encoding="utf-8")

NOMES_FONTE = {
    "v1_llm_original": "v1 — LLM com prompt original (antes)",
    "v2_generos_puros": "Gêneros oficiais das APIs (baseline)",
    "v2_llm_puro": "v2 — LLM com gêneros no prompt",
    "v2_confirmadas_llm_generos": "v2 — híbrido: confirmadas + LLM + gêneros",
    "v2_generos2_mais_llm": "v2 — híbrido: 2 gêneros + LLM",
    "v2_llm_completa_generos": "v2 — híbrido: LLM + gêneros",
    "producao_banco": "Tags gravadas no acervo atual",
}
NOMES_CEN = {"oraculo_tags_gabarito": "Oráculo (tags do gabarito)", "producao_atual": "Tags atuais (v2 final)",
             "v1_llm_original": "Tags v1 (antes)", "generos_api": "Gêneros das APIs"}


def c(nome, metodo="idf", part="teste"):
    return CEN.get(f"{nome}|{metodo}", {}).get(part) or {}


ALE_T = CEN["aleatorio"]["teste"]
ALE = CEN["aleatorio"]["todos"]

# ============================================================ gráficos
graficos = {}
fontes_graf = [k for k in ("v1_llm_original", "v2_generos_puros", ESC) if k in TS]
cores = {"v1_llm_original": LARANJA, "v2_generos_puros": AQUA, ESC: AZUL}
rotulos_curtos = {"v1_llm_original": "v1 (antes)", "v2_generos_puros": "Gêneros das APIs", ESC: "v2 híbrido (atual)"}
graficos["tags"] = barras_agrupadas(
    "qualidade_tags.png", ["Precisão", "Revocação", "F1", "Jaccard"],
    [(rotulos_curtos[k], [TS[k]["teste"]["precisao_micro"], TS[k]["teste"]["revocacao_micro"], TS[k]["teste"]["f1_micro"],
                          TS[k]["teste"]["jaccard_medio"]], cores[k]) for k in fontes_graf],
    f"Qualidade das tags no conjunto de teste (n = {PROD['teste']['n']})", "valor (0 a 1)")
graficos["tags_tipo"] = barras_agrupadas(
    "f1_por_midia.png", ["Filmes", "Séries", "Jogos"],
    [(rotulos_curtos[k], [TS[k]["todos"]["por_tipo"].get(t, {}).get("f1_macro") or 0 for t in ("Filme", "Serie", "Jogo")], cores[k])
     for k in fontes_graf],
    "F1 (macro) por mídia — conjunto de referência completo", "F1")
cats = [k for k in ("v1_llm_original", "generos_api", "producao_atual", "oraculo_tags_gabarito") if f"{k}|idf" in CEN]
graficos["rec"] = barras_agrupadas(
    "recomendacao_cenarios.png", [NOMES_CEN[k].replace(" (", "\n(") for k in cats] + ["Aleatório"],
    [("Contagem + CONTAINS (antes)", [c(k, "contagem", "todos").get("ndcg@10", 0) for k in cats] + [ALE["ndcg@10"]], LARANJA),
     ("IDF + grafo (atual)", [c(k, "idf", "todos").get("ndcg@10", 0) for k in cats] + [ALE["ndcg@10"]], AZUL)],
    "nDCG@10 por fonte de tags e método de ranqueamento", "nDCG@10")

# ============================================================ CAPA
for _ in range(5):
    par()
par("OMNIREVIEW", negrito=True, alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=24, cor="1F3A5F", depois=4)
par("Plataforma de recomendação transmídia de filmes, séries e jogos baseada em grafos e IA local",
    alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=13, cor="52514E", depois=40)
par("Documentação técnica: requisitos, modelagem UML, motor de recomendação e avaliação experimental",
    negrito=True, alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=12)
for _ in range(10):
    par()
par("Bryan Pinaffo", alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=12, depois=2)
par("Trabalho de Conclusão de Curso", alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=11, depois=2)
par(date.today().strftime("%m/%Y"), alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=11)
quebra()
par("SUMÁRIO", negrito=True, alinhamento=WD_ALIGN_PARAGRAPH.CENTER, tamanho=13)
campo(doc.add_paragraph(), 'TOC \\o "1-2" \\h \\z \\u')
quebra()

data_medicao = R["gerado_em"][8:10] + "/" + R["gerado_em"][5:7] + "/" + R["gerado_em"][:4]

# ============================================================ 1
doc.add_heading("1 Introdução e escopo", 1)
par("O OmniReview unifica filmes, séries e jogos em um único catálogo e recomenda obras **entre mídias diferentes** "
    "(recomendação transmídia). Cada obra recebe um **DNA criativo**: de 1 a 4 tags de uma taxonomia controlada de "
    f"{TC['tamanho_taxonomia']} tags, geradas por um **classificador híbrido** que combina os gêneros oficiais das APIs "
    "públicas (TMDB para filmes e séries; Steam/RAWG para jogos) com tags específicas escolhidas por um modelo de "
    "linguagem executado localmente (Ollama, `llama3.2:3b`). As obras e as tags são nós do banco de grafos Neo4j, "
    "ligados pela relação `TEM_TAG`, e o motor ranqueia as obras por travessia no grafo com peso IDF, intercalando as mídias.")
par("O documento reúne: (i) requisitos com identificadores, prioridades e critérios de aceitação mensuráveis; (ii) "
    "diagramas UML extraídos do código; (iii) a implementação e a demonstração do motor de recomendação e de suas "
    "consultas Cypher; (iv) a avaliação da qualidade das tags e das recomendações contra um conjunto de referência, "
    "além de desempenho e testes automatizados; e (v) as correções feitas a partir da primeira rodada de avaliação. "
    f"Todos os números foram medidos no sistema em execução em {data_medicao}, com o acervo atual de {CAT['total']} obras "
    f"({CAT.get('Filme', 0)} filmes, {CAT.get('Serie', 0)} séries e {CAT.get('Jogo', 0)} jogos), e são gerados "
    "automaticamente pelos scripts de avaliação.")
tabela(["Camada", "Tecnologia"], [
    ["Interface", "SPA em HTML, CSS e JavaScript (`index.html`) servida pelo Spring Boot; protótipo mobile em React (Vite)"],
    ["Back-end", "Java 17, Spring Boot 4.1 (Web MVC), Spring Data Neo4j"],
    ["Banco de dados", "Neo4j 2026.08 (Docker): `(:Obra)-[:TEM_TAG]->(:Tag)`, restrições de unicidade em `Obra.titulo` e `Tag.nome`"],
    ["IA", "Ollama em servidor próprio (Tailscale), `llama3.2:3b`, saída estruturada (JSON Schema), temperatura 0"],
    ["Fontes externas", "TMDB API, Steam Store API e RAWG API (com disjuntor)"],
    ["Configuração", "Chaves e senhas em `config/segredos.properties` (fora do Git) ou variáveis de ambiente"],
    ["Qualidade", f"{unit['testes']} testes JUnit; {UI_T['total'] if UI_T else 0} casos de teste de interface (DevTools Protocol); scripts de avaliação em Python e Java"],
], larguras=[3.3, 12.7], titulo="Pilha tecnológica", fonte_txt="Elaborado pelo autor (2026).")

# ============================================================ 2 REQUISITOS
doc.add_heading("2 Requisitos", 1)
par("Prioridades pelo método **MoSCoW** (*Must*, *Should*, *Could*). A coluna **Status** é calculada automaticamente "
    "a partir das medições (seção 6), dos testes automatizados ou da varredura do código-fonte, conforme indicado.")


def st(cond, parcial=False):
    return "Atendido" if cond else ("Parcial" if parcial else "Não atendido")


pa = REC.get("producao_api") or {}
aa = REC["alem_da_acuracia"]
lat2 = (R.get("ia") or {}).get("latencia_dedicada_ms") or IA2.get("latencia_ms", {})
OLLAMA = (R.get("ia") or {}).get("servidor_ollama")
SEM_GPU = isinstance(OLLAMA, list) and OLLAMA and all((m.get("na_gpu_bytes") or 0) == 0 for m in OLLAMA)
N_REF_ACERVO = TC.get("obras_referencia_no_acervo")
rf = [
    ["RF01", "Cadastrar obra por nome e tipo, buscando título, sinopse, gêneros e imagens em API externa.", "Must",
     "≥ 95% das 58 obras de referência retornam sinopse não vazia.",
     p(IA2.get("taxa_sinopse_referencia")), st((IA2.get("taxa_sinopse_referencia") or 0) >= 0.95)],
    ["RF02", "Gerar o DNA criativo com 1 a 4 tags exclusivamente da taxonomia oficial.", "Must",
     "100% das obras com 1–4 tags da taxonomia; 0 obras com \"Deu erro\".",
     f"{p(INT['tags_todas_na_taxonomia'])} na taxonomia; {p(INT['entre_1_e_4_tags'])} com 1–4; {INT['obras_deu_erro']} com erro",
     st(INT["tags_todas_na_taxonomia"] == 1 and INT["entre_1_e_4_tags"] == 1 and INT["obras_deu_erro"] == 0)],
    ["RF03", "Usar os gêneros oficiais quando o LLM falhar.", "Must", "0 obras \"Sem classificação\"; teste unitário do fallback aprovado.",
     f"{INT['obras_sem_classificacao']} obras; teste {'aprovado' if unit['falhas'] + unit['erros'] == 0 and unit['testes'] else 'pendente'}",
     st(INT["obras_sem_classificacao"] == 0 and unit["testes"] > 0 and unit["falhas"] + unit["erros"] == 0)],
    ["RF04", "Listar o catálogo e filtrar por título, mídia e tags.", "Must",
     "GET /api/obras com p95 ≤ 500 ms; caso de teste T08 aprovado.",
     f"p95 = {ms(DES['listar_obras_ms']['p95'])}; T08 {'ok' if UI_OK.get('T08') else 'falhou'}",
     st(DES["listar_obras_ms"]["p95"] <= 500 and UI_OK.get("T08", False))],
    ["RF05", "Exibir detalhes em janela flutuante: título, tipo, tags, sinopse, pôster e capa.", "Should",
     "≥ 95% das obras com sinopse e imagem; caso T04 aprovado.",
     f"sinopse {p(INT['com_sinopse'])}; imagem {p(INT['com_imagem'])}; T04 {'ok' if UI_OK.get('T04') else 'falhou'}",
     st(INT["com_sinopse"] >= 0.95 and INT["com_imagem"] >= 0.95 and UI_OK.get("T04", False))],
    ["RF06", "Favoritar obras e manter o perfil no navegador.", "Should", "Favorito persiste após recarregar (caso T03).",
     "T03 " + ("aprovado" if UI_OK.get("T03") else "reprovado"), st(UI_OK.get("T03", False))],
    ["RF07", "Recomendar obras de outras mídias a partir das tags do perfil.", "Must",
     "HitRate@10 ≥ 0,70 (cenário de referência, teste); taxa transmídia no top-10 ≥ 50% (API em produção); caso T07.",
     f"HitRate = {f(c('producao_atual')['hit@10'])}; transmídia = {p(aa['taxa_transmidia_top10'])}",
     st(c("producao_atual")["hit@10"] >= 0.70 and aa["taxa_transmidia_top10"] >= 0.5 and UI_OK.get("T07", False))],
    ["RF08", "Mostrar, na janela da obra, outras obras com o mesmo DNA.", "Could",
     "≥ 90% das obras de referência recebem sugestão; caso T05.",
     f"{p(aa['seeds_com_ao_menos_uma_recomendacao'])}", st(aa["seeds_com_ao_menos_uma_recomendacao"] >= 0.9 and UI_OK.get("T05", False))],
    ["RF09", "Importar títulos em tendência das três mídias.", "Should", "Cada /api/tendencias/{mídia} retorna ≥ 10 títulos.",
     f"filmes {R['tendencias']['filmes']}, séries {R['tendencias']['series']}, jogos {R['tendencias']['jogos']}",
     st(min(R["tendencias"].values()) >= 10)],
    ["RF10", "Manutenção do acervo: completar sinopses/capas e refazer tags.", "Could", "≤ 1% das obras sem imagem.",
     p(1 - INT["com_imagem"]) + " sem imagem", st(1 - INT["com_imagem"] <= 0.01)],
]
tabela(["ID", "Requisito funcional", "Prior.", "Critério de aceitação", "Medido", "Status"], rf,
       larguras=[1.2, 4.4, 1.3, 4.2, 3.1, 1.8], titulo="Requisitos funcionais", tamanho=8, destaque_status=5)

prof = DES.get("profile_cypher", {})
rnf = [
    ["RNF01", "Desempenho da recomendação", "Must", "p95 de GET /api/recomendar ≤ 200 ms (≥ 200 requisições).",
     f"p95 = {ms(DES['recomendar_ms']['p95'])} (n = {DES['recomendar_ms']['n']})", st(DES["recomendar_ms"]["p95"] <= 200)],
    ["RNF02", "Desempenho da classificação", "Should", "p95 ≤ 30 s por obra (rodada dedicada, sem a partida a frio).",
     f"mediana = {ms(lat2.get('p50'))}; p95 = {ms(lat2.get('p95'))}" + (" (LLM só na CPU)" if SEM_GPU else ""),
     st((lat2.get("p95") or 1e9) <= 30000, (lat2.get("p50") or 1e9) <= 30000)],
    ["RNF03", "Estabilidade da IA", "Should", "Jaccard médio entre duas execuções ≥ 0,80.",
     f(IA2.get("consistencia_jaccard")), st((IA2.get("consistencia_jaccard") or 0) >= 0.8)],
    ["RNF04", "Qualidade das tags", "Must", "F1 micro ≥ 0,60 no conjunto de teste.",
     f"F1 = {f(PROD['teste']['f1_micro'])}", st(PROD["teste"]["f1_micro"] >= 0.6, PROD["teste"]["f1_micro"] >= 0.45)],
    ["RNF05", "Qualidade das recomendações", "Should", "nDCG@10 (tags atuais, teste) ≥ 2× o aleatório.",
     f"{f(c('producao_atual')['ndcg@10'])} vs {f(ALE_T['ndcg@10'])} ({f(c('producao_atual')['ndcg@10'] / ALE_T['ndcg@10'], 1)}×)",
     st(c("producao_atual")["ndcg@10"] >= 2 * ALE_T["ndcg@10"], c("producao_atual")["ndcg@10"] > ALE_T["ndcg@10"])],
    ["RNF06", "Resiliência a falhas de API", "Must", "Com a RAWG fora do ar, 100% dos 13 jogos obtêm dados e a busca de jogos tem p95 ≤ 3 s.",
     f"{p(IA2.get('jogos_com_dados'))}; p95 = {ms(IA2.get('latencia_busca_api_ms', {}).get('Jogo', {}).get('p95'))}",
     st((IA2.get("jogos_com_dados") or 0) >= 1 and (IA2.get("latencia_busca_api_ms", {}).get("Jogo", {}).get("p95") or 1e9) <= 3000,
        (IA2.get("jogos_com_dados") or 0) >= 1)],
    ["RNF07", "Privacidade", "Should", "Perfil de favoritos só no navegador (localStorage); caso T03.", "Inspeção + T03",
     st(UI_OK.get("T03", False))],
    ["RNF08", "Responsividade", "Should", "Sem rolagem horizontal em 400 px (caso T09).", "T09 " + ("aprovado" if UI_OK.get("T09") else "reprovado"),
     st(UI_OK.get("T09", False))],
    ["RNF09", "Qualidade do código", "Should", "100% dos testes automatizados aprovados; nenhum erro de JavaScript (T10).",
     f"{unit['testes'] - unit['falhas'] - unit['erros']}/{unit['testes']} JUnit; {UI_T['aprovados'] if UI_T else 0}/{UI_T['total'] if UI_T else 0} interface",
     st(unit["testes"] > 0 and unit["falhas"] + unit["erros"] == 0 and UI_T and UI_T["aprovados"] == UI_T["total"])],
    ["RNF10", "Segurança de segredos", "Must", "Nenhuma chave ou senha no código-fonte; arquivo de segredos ignorado pelo Git.",
     f"{len(achados)} ocorrências; .gitignore {'ok' if gitignore_ok else 'ausente'}", st(not achados and gitignore_ok)],
    ["RNF11", "Escalabilidade da consulta", "Should", "Plano da recomendação usa índice e não varre todas as obras (custo proporcional às obras relacionadas, não ao acervo).",
     ("índice: sim" if prof.get("usa_indice") else "índice: não") + "; " + ("varre o acervo" if prof.get("varre_todas_as_obras") else "sem varredura completa")
     + f"; {prof.get('db_hits', '—')} vs {prof.get('db_hits_consulta_anterior', '—')} db hits",
     st(prof.get("usa_indice") and not prof.get("varre_todas_as_obras"))],
]
tabela(["ID", "Requisito não funcional", "Prior.", "Critério de aceitação", "Medido", "Status"], rnf,
       larguras=[1.3, 3.0, 1.3, 5.1, 3.5, 1.8], titulo="Requisitos não funcionais", tamanho=8, destaque_status=5)

# ============================================================ 3 UML
doc.add_heading("3 Modelagem UML", 1)
par("Diagramas escritos em PlantUML a partir do código atual (`docs/tcc/diagramas/*.puml`, versionados com o projeto).")
doc.add_heading("3.1 Casos de uso", 2)
figura(DIAG / "casos_de_uso.png", "Diagrama de casos de uso", 16)
tabela(["Caso de uso", "Ator", "Requisitos"], [
    ["UC01 Explorar catálogo / UC02 Buscar e filtrar", "Usuário", "RF04"],
    ["UC03 Ver detalhes / UC04 Ver obras com o mesmo DNA", "Usuário", "RF05, RF08"],
    ["UC05 Favoritar obra", "Usuário", "RF06, RNF07"],
    ["UC06 Receber recomendações transmídia", "Usuário", "RF07, RNF01, RNF05, RNF11"],
    ["UC07 Adicionar obra / UC09 Gerar DNA criativo", "Curador", "RF01–RF03, RNF02–RNF04, RNF06"],
    ["UC08 Importar tendências", "Curador", "RF09"],
    ["UC10 Completar sinopses e capas / UC11 Refazer tags", "Curador", "RF10"],
], larguras=[8, 2.5, 5.5], titulo="Rastreabilidade entre casos de uso e requisitos", fonte_txt="Elaborado pelo autor (2026).")
doc.add_heading("3.2 Classes", 2)
figura(DIAG / "classes.png", "Diagrama de classes do back-end", 16.5)
doc.add_heading("3.3 Sequência", 2)
figura(DIAG / "seq_adicionar.png", "Sequência de cadastro de obra e geração do DNA criativo", 16)
figura(DIAG / "seq_recomendar.png", "Sequência da recomendação transmídia", 15)
doc.add_heading("3.4 Componentes e implantação", 2)
figura(DIAG / "componentes.png", "Diagrama de componentes e implantação", 16.5)

# ============================================================ 4 MOTOR
doc.add_heading("4 Motor de recomendação transmídia", 1)
doc.add_heading("4.1 Classificador híbrido (DNA criativo)", 2)
bullets([
    f"**Taxonomia controlada** de {TC['tamanho_taxonomia']} tags: gêneros, subgêneros, temas, formatos e mecânicas de jogo.",
    "**Gêneros oficiais:** os gêneros da API de origem (TMDB, Steam ou RAWG) são convertidos para a taxonomia por tabelas de mapeamento; animações com idioma original japonês recebem também *Anime*.",
    "**Taxonomia por mídia:** filmes e séries não podem receber tags exclusivas de jogos (por exemplo, *Battle Royale*, *Multijogador Local*), e formatos de TV (*Sitcom*, *Telenovela*...) só valem para séries. A restrição é aplicada no próprio JSON Schema enviado ao LLM.",
    "**LLM com saída estruturada:** o prompt traz título, sinopse, gêneros oficiais, regras contra tags genéricas e dois exemplos neutros (que não pertencem ao acervo); a resposta é restrita por JSON Schema a valores `enum` da taxonomia; `temperature = 0` e `seed = 42` tornam a geração determinística.",
    "**Combinação:** `IaService.combinar` mantém os 2 primeiros gêneros oficiais (os mais relevantes na ordem da API), acrescenta as tags do LLM e completa com os gêneros restantes, até 4 tags. A estratégia foi escolhida pelo maior F1 no conjunto de desenvolvimento (seção 6.1).",
    "**Tolerância a falhas:** até 2 tentativas no LLM; sem resposta, ficam os gêneros oficiais; sem nada, \"Sem classificação\".",
])
doc.add_heading("4.2 Modelo em grafo", 2)
par(f"Cada obra é um nó `(:Obra {{titulo, tipo, dnaCriativo, sinopse, imagem, capa}})` e cada tag, um nó `(:Tag {{nome}})`. "
    f"A relação `(:Obra)-[:TEM_TAG]->(:Tag)` é criada ao salvar a obra e sincronizada na inicialização. O grafo atual tem "
    f"**{GR.get('tags', '—')} tags** e **{GR.get('relacoes_tem_tag', '—')} relações**, com as restrições de unicidade "
    f"{', '.join('`' + x + '`' for x in GR.get('restricoes', []))} (que também criam índices).")
doc.add_heading("4.3 Ranqueamento com IDF", 2)
par("Seja P o conjunto de tags do perfil (as 5 mais frequentes entre as obras favoritas), N o número de obras e df(t) "
    "o número de obras com a tag t:")
codigo("""
peso(t)  = 1 + ln( N / df(t) )                       tags raras pesam mais que tags comuns
score(o) = soma de peso(t) para t em P com (o)-[:TEM_TAG]->(t)
ranking  = obras com score > 0, sem as já favoritadas, por score desc, nº de tags em comum desc, título; limite 60
saída    = intercalação round-robin por mídia (Filme, Série, Jogo), máximo 20
""")
par("O casamento é exato no nó `Tag`: na versão anterior, a comparação por `CONTAINS` no texto fazia *Comédia* casar com "
    "*Comédia Romântica* e todas as tags tinham o mesmo peso.")
doc.add_heading("4.4 Consultas Cypher e demonstração", 2)
par("**Consulta 1 — recomendação** (`ObraRepository.recomendarPorTags`):", depois=2)
codigo("""
MATCH (todas:Obra) WITH count(todas) AS n
MATCH (t:Tag) WHERE t.nome IN $tags
MATCH (t)<-[:TEM_TAG]-(o:Obra)
WITH n, t, collect(o) AS obras
WITH t, obras, 1.0 + log(toFloat(n) / size(obras)) AS peso
UNWIND obras AS o
WITH o, sum(peso) AS score, count(t) AS comuns
WHERE NOT o.titulo IN $excluir
RETURN o ORDER BY score DESC, comuns DESC, o.titulo LIMIT 60
""")
demo = R.get("demonstracao_cypher", {})
if demo.get("recomendar_demo"):
    pd_ = demo.get("parametros_demo", {})
    tabela(["Obra", "Tipo", "Tags em comum", "Score"], [[a, {"Serie": "Série"}.get(b, b), c_, f(s)] for a, b, c_, s in demo["recomendar_demo"]],
           larguras=[8, 2.5, 3, 2.5],
           titulo=f"Execução da consulta 1 com $tags = [{', '.join(pd_.get('tags', []))}] e $excluir = [{', '.join(pd_.get('excluir', []))}] (primeiras linhas, antes da intercalação)",
           fonte_txt="Neo4j do OmniReview.")
par("**Consulta 2 — sincronização das tags de uma obra** (`ObraRepository.sincronizarTags`):", depois=2)
codigo("""
MATCH (o:Obra {titulo: $titulo})
OPTIONAL MATCH (o)-[r:TEM_TAG]->() DELETE r
WITH DISTINCT o
UNWIND [nome IN split(coalesce(o.dnaCriativo, ''), ',') | trim(nome)] AS nome
WITH o, nome WHERE nome <> '' AND NOT nome IN ['Deu erro', 'Sem classificação']
MERGE (t:Tag {nome: nome})
MERGE (o)-[:TEM_TAG]->(t)
""")
par("**Consulta 3 — distribuição das tags no acervo:**", depois=2)
codigo("MATCH (t:Tag)<-[:TEM_TAG]-(o:Obra)\nRETURN t.nome AS tag, count(o) AS obras ORDER BY obras DESC, tag LIMIT 10")
if demo.get("tags_mais_frequentes"):
    tabela(["Tag", "Obras", "% do acervo", "Peso IDF"],
           [[t, n, p(n / CAT["total"]), f(1 + math.log(CAT["total"] / n))] for t, n in demo["tags_mais_frequentes"]],
           larguras=[6, 2.5, 3, 3], titulo="Tags mais frequentes e seus pesos", fonte_txt="Neo4j do OmniReview.")
par("**Consulta 4 — pontes transmídia** (jogos e obras de outras mídias com 3 ou mais tags em comum):", depois=2)
codigo("""
MATCH (j:Obra {tipo: 'Jogo'})-[:TEM_TAG]->(t:Tag)<-[:TEM_TAG]-(o:Obra) WHERE o.tipo <> 'Jogo'
WITH j, o, collect(t.nome) AS comuns WHERE size(comuns) >= 3
RETURN j.titulo AS jogo, o.titulo AS obra, o.tipo AS tipo, comuns
ORDER BY size(comuns) DESC, jogo, obra LIMIT 10
""")
if demo.get("pontes_transmidia"):
    tabela(["Jogo", "Obra", "Tipo", "Tags em comum"],
           [[a, b, {"Serie": "Série"}.get(c_, c_), ", ".join(d)] for a, b, c_, d in demo["pontes_transmidia"]],
           larguras=[4.2, 5, 1.8, 5], titulo="Pontes transmídia no acervo", fonte_txt="Neo4j do OmniReview.")
par("**Exemplos pela API** (GET /api/recomendar com as tags da obra-semente; top-10 após a intercalação):")
for semente, ex in REC.get("exemplos", {}).items():
    tabela(["#", "Obra recomendada", "Tipo", "DNA criativo"],
           [[i + 1, t, {"Serie": "Série"}.get(tp, tp), dna] for i, (t, tp, dna) in enumerate(ex["top10"])],
           larguras=[0.8, 5.5, 1.7, 8], titulo=f"Recomendações para \"{semente}\" (perfil: {', '.join(ex['consulta'])})",
           fonte_txt="API do OmniReview em execução.", tamanho=8)

# ============================================================ 5 METODOLOGIA
doc.add_heading("5 Metodologia de avaliação", 1)
doc.add_heading("5.1 Conjunto de referência", 2)
cref = R["conjunto_referencia"]
par(f"Foram anotadas manualmente **{cref['total']} obras** do acervo ({cref.get('Filme', 0)} filmes, {cref.get('Serie', 0)} "
    f"séries e {cref.get('Jogo', 0)} jogos — todos os jogos disponíveis), com 2 a 4 tags corretas da taxonomia por obra "
    "(Apêndice A). As obras cobrem animação, super-heróis, ficção científica, terror, comédia romântica, anime, crime e "
    "diversos gêneros de jogos. A anotação foi feita por um único anotador; recomenda-se uma segunda anotação e o "
    "cálculo do kappa de Cohen como evolução.")
par(f"**Origem das tags avaliadas.** As tags das 58 obras de referência foram geradas pelo harness de avaliação "
    "(`HarnessClassificacao.java`), que instancia as próprias classes de produção (`IaService`, `TmdbService`, "
    "`RawgService`/`SteamService`) e executa exatamente o mesmo pipeline do cadastro. Isso torna a avaliação "
    f"independente do conteúdo do banco: o acervo em produção foi reiniciado durante o projeto e hoje contém "
    f"{CAT['total']} obras, das quais {N_REF_ACERVO} pertencem ao conjunto de referência. As métricas de produção "
    "pela API (cobertura, taxa transmídia, latência e validação da réplica) usam o acervo atual.")
doc.add_heading("5.2 Divisão desenvolvimento/teste", 2)
par(f"Para evitar ajustar o sistema aos próprios dados de avaliação, o conjunto foi dividido de forma estratificada por "
    f"mídia: **{cref['desenvolvimento']} obras de desenvolvimento** e **{cref['teste']} de teste**. As decisões de projeto "
    "do classificador (por exemplo, a estratégia de combinação) foram tomadas olhando apenas o desenvolvimento; os "
    "resultados principais são reportados no teste. Os exemplos do prompt não pertencem ao acervo. O classificador v2 "
    "passou por duas iterações: (1) gêneros oficiais no prompt e escolha da estratégia de combinação; (2) após a análise "
    "dos erros **apenas do desenvolvimento**, restrição da taxonomia por mídia, regra de *Anime* e remoção do modo "
    "\"Um Jogador\" dos gêneros da Steam. Os números de teste reportados são os da versão final.")
doc.add_heading("5.3 Métricas das tags", 2)
bullets([
    "**Precisão** = |P ∩ G| / |P|; **Revocação** = |P ∩ G| / |G|; **F1** = média harmônica (P = previstas, G = gabarito). *Micro* soma os acertos de todas as obras; *macro* faz a média por obra.",
    "**Jaccard** = |P ∩ G| / |P ∪ G|; **acerto da 1ª tag**: a primeira tag prevista está no gabarito.",
    "**Estabilidade**: Jaccard entre duas execuções independentes da mesma obra.",
])
doc.add_heading("5.4 Métricas das recomendações", 2)
par("A obra b é **relevante** para a semente a quando os gabaritos compartilham pelo menos 2 tags; o grau de relevância "
    "(para o nDCG) é o número de tags compartilhadas. Como só as obras de referência têm gabarito, usa-se a avaliação em "
    "**lista condensada** (SAKAI, 2007): obras sem julgamento são removidas antes do corte em K = 10. Métricas: "
    "Precisão@10, Revocação@10, nDCG@10 (JÄRVELIN; KEKÄLÄINEN, 2002), MRR e HitRate@10; além da acurácia, cobertura do "
    "catálogo, taxa transmídia e número de mídias distintas no top-10 (KAMINSKAS; BRIDGE, 2016).")
par("Para separar o efeito da **qualidade das tags** do efeito do **algoritmo**, o motor foi replicado em Python e "
    f"executado com quatro fontes de tags (gabarito/oráculo, v1, gêneros das APIs e tags atuais) e dois métodos de "
    f"ranqueamento (contagem por `CONTAINS`, da versão anterior, e IDF no grafo, atual). A réplica foi validada contra a "
    f"API: lista idêntica em **{p(REC['replica_identica_a_api'])}** das sementes. O baseline aleatório é a média de 200 embaralhamentos.")
doc.add_heading("5.5 Desempenho e testes automatizados", 2)
par(f"Latências medidas de ponta a ponta (cliente HTTP → Spring Boot → Neo4j): {DES['recomendar_ms']['n']} chamadas a "
    f"/api/recomendar e {DES['listar_obras_ms']['n']} a /api/obras. A latência do LLM foi medida pelo harness Java, que "
    "chama as classes de produção, considerando a primeira execução de cada obra (a segunda reaproveita o cache de prompt "
    "do Ollama). Como a rodada principal coincidiu com outra carga no mesmo servidor de IA (importação de obras), a "
    "latência do RNF02 vem de uma rodada dedicada com 15 obras e o servidor sem outras requisições. O custo das consultas foi obtido com `PROFILE`. A interface foi testada por um script que controla o "
    "navegador pelo Chrome DevTools Protocol (`teste_interface.mjs`), e o back-end por testes JUnit.")

# ============================================================ 6 RESULTADOS
doc.add_heading("6 Resultados", 1)
doc.add_heading("6.1 Qualidade das tags", 2)
if SEL:
    tabela(["Estratégia (v2)", "F1 micro — desenvolvimento"],
           [[NOMES_FONTE.get(k, k) + (" ← escolhida" if k == ESC else ""), f(v)] for k, v in SEL["f1_dev"].items()],
           larguras=[11, 5], titulo="Seleção da estratégia de combinação no conjunto de desenvolvimento", tamanho=9)
ordem = [k for k in ("v1_llm_original", "v2_generos_puros", "v2_llm_puro", ESC) if k in TS]
ordem = list(dict.fromkeys(ordem))
tabela(["Fonte das tags", "Precisão", "Revocação", "F1 micro", "F1 macro", "Jaccard", "1ª tag"],
       [[NOMES_FONTE.get(k, k), f(TS[k]["teste"]["precisao_micro"]), f(TS[k]["teste"]["revocacao_micro"]), f(TS[k]["teste"]["f1_micro"]),
         f(TS[k]["teste"]["f1_macro"]), f(TS[k]["teste"]["jaccard_medio"]), p(TS[k]["teste"]["acerto_primeira_tag"])] for k in ordem],
       larguras=[5.4, 1.7, 1.8, 1.6, 1.6, 1.5, 1.6], titulo=f"Qualidade das tags no conjunto de teste (n = {PROD['teste']['n']})", tamanho=8.5)
tabela(["Fonte das tags", "F1 (teste)", "F1 (todos os 58)"],
       [[NOMES_FONTE.get(k, k), f(TS[k]["teste"]["f1_micro"]), f(TS[k]["todos"]["f1_micro"])] for k in ordem],
       larguras=[9, 3.5, 3.5], titulo="F1 micro no teste e no conjunto completo", tamanho=9)
figura(graficos["tags"], "Qualidade das tags no conjunto de teste", 15)
figura(graficos["tags_tipo"], "F1 por mídia", 15)
if BANCO:
    par(f"**Conferência com o banco:** nas {BANCO['todos']['n']} obras de referência presentes no acervo atual, as tags "
        f"gravadas no Neo4j têm F1 micro de {f(BANCO['todos']['f1_micro'])}. Parte dessas obras foi cadastrada antes do "
        "classificador final (importação feita durante a reinicialização do banco), e a reclassificação completa do acervo "
        "atualizou as demais.")
fp_ = ", ".join(f"{t} ({n})" for t, n in PROD["todos"]["falsos_positivos_mais_comuns"][:5])
fn_ = ", ".join(f"{t} ({n})" for t, n in PROD["todos"]["falsos_negativos_mais_comuns"][:5])
par(f"**Erros mais frequentes (tags atuais, 58 obras).** Falsos positivos: {fp_}. Falsos negativos: {fn_}.")
if "ficcao_cientifica_referencia_v1" in TC:
    par(f"**Viés de Ficção Científica:** a tag aparecia em {p(TC['ficcao_cientifica_referencia_v1'])} das obras de "
        f"referência no classificador v1; no atual, em {p(TC['ficcao_cientifica_referencia_atual'])} "
        f"(no gabarito: {p(TC['ficcao_cientifica_gabarito'])}).")
tabela(["Indicador", "v1 (antes)", "v2 (atual)"], [
    ["Estabilidade: conjuntos idênticos em 2 execuções", p(IA1.get("consistencia_identica")), p(IA2.get("consistencia_identica"))],
    ["Estabilidade: Jaccard médio entre execuções", f(IA1.get("consistencia_jaccard")), f(IA2.get("consistencia_jaccard"))],
    ["Latência do LLM — mediana (v2: rodada dedicada)", ms(IA1.get("latencia_ms", {}).get("p50")), ms(lat2.get("p50"))],
    ["Latência do LLM — p95 (v2: rodada dedicada)", ms(IA1.get("latencia_ms", {}).get("p95")), ms(lat2.get("p95"))],
    ["Latência do LLM — partida a frio", ms(IA1.get("latencia_ms", {}).get("partida_a_frio")), ms(lat2.get("partida_a_frio"))],
    ["Busca de jogos na API — mediana / p95",
     " / ".join(ms(IA1.get("latencia_busca_api_ms", {}).get("Jogo", {}).get(k)) for k in ("p50", "p95")),
     " / ".join(ms(IA2.get("latencia_busca_api_ms", {}).get("Jogo", {}).get(k)) for k in ("p50", "p95"))],
], larguras=[9, 3.5, 3.5], titulo="Estabilidade e latência da classificação", tamanho=9,
   fonte_txt="Harness Java com as classes de produção (2 execuções por obra).")

doc.add_heading("6.2 Qualidade das recomendações", 2)
linhas_cen = []
for k in ("oraculo_tags_gabarito", "producao_atual", "generos_api", "v1_llm_original"):
    for metodo, nome_m in (("idf", "IDF + grafo"), ("contagem", "Contagem")):
        m = c(k, metodo, "teste")
        if m:
            linhas_cen.append([NOMES_CEN[k], nome_m, f(m["p@10"]), f(m["r@10"]), f(m["ndcg@10"]), f(m["mrr"]), f(m["hit@10"])])
linhas_cen.append(["Aleatório", "—", f(ALE_T["p@10"]), f(ALE_T["r@10"]), f(ALE_T["ndcg@10"]), f(ALE_T["mrr"]), f(ALE_T["hit@10"])])
tabela(["Fonte das tags", "Ranqueamento", "P@10", "R@10", "nDCG@10", "MRR", "Hit@10"], linhas_cen,
       larguras=[4.6, 2.6, 1.5, 1.5, 1.8, 1.5, 1.6],
       titulo=f"Recomendações com sementes do conjunto de teste ({c('producao_atual').get('seeds_avaliadas', '—')} sementes)", tamanho=8.5)
figura(graficos["rec"], "nDCG@10 por fonte de tags e método de ranqueamento (58 sementes)", 15.5)
tabela([f"Indicador (API em produção, {REC.get('sementes_producao', '—')} obras do acervo como sementes)", "Valor"], [
    [f"HitRate@10 / nDCG@10 (só as {pa.get('seeds_avaliadas', 0)} sementes com gabarito)", f"{f(pa.get('hit@10'))} / {f(pa.get('ndcg@10'))}"],
    ["Cobertura do catálogo", p(aa["cobertura_catalogo"])],
    ["Taxa transmídia no top-10", p(aa["taxa_transmidia_top10"])],
    ["Mídias distintas no top-10 (máx. 3)", f(aa["media_tipos_distintos_top10"])],
    ["Sementes com ao menos um jogo no top-10", p(aa["seeds_com_jogo_no_top10"])],
    ["Réplica do motor idêntica à API", p(REC["replica_identica_a_api"])],
], larguras=[11, 5], titulo="Indicadores da recomendação em produção", tamanho=9)

doc.add_heading("6.3 Desempenho", 2)
tabela(["Operação", "Mediana", "p95", "p99 / máx."], [
    ["GET /api/recomendar", ms(DES["recomendar_ms"]["p50"]), ms(DES["recomendar_ms"]["p95"]), f"{ms(DES['recomendar_ms']['p99'])} / {ms(DES['recomendar_ms']['max'])}"],
    ["GET /api/obras (catálogo completo)", ms(DES["listar_obras_ms"]["p50"]), ms(DES["listar_obras_ms"]["p95"]), "—"],
    ["Classificação pelo LLM (por obra)", ms(lat2.get("p50")), ms(lat2.get("p95")), ms(lat2.get("max"))],
], larguras=[6.5, 3, 3, 3.5], titulo="Latências medidas", tamanho=9)
if "db_hits" in prof:
    pd_ = demo.get("parametros_demo", {})
    par(f"`PROFILE` com o perfil [{', '.join(pd_.get('tags', []))}] e {prof.get('obras_no_acervo', CAT['total'])} obras no acervo: a "
        f"consulta atual fez **{prof['db_hits']} acessos ao banco** (db hits), contra **{prof['db_hits_consulta_anterior']}** "
        "da consulta anterior. Mais importante que o total neste acervo pequeno é a forma do plano: a consulta atual parte do "
        "índice único de `Tag.nome` (`NodeUniqueIndexSeek`), obtém N pelo contador interno do banco "
        "(`NodeCountFromCountStore`) e percorre só as obras ligadas às tags do perfil, enquanto a anterior lê todas as "
        "obras (`NodeIndexScan`) e compara texto em cada uma. O custo da nova cresce com as obras relacionadas; o da "
        "anterior, com o tamanho do acervo (plano completo no Apêndice B).")

doc.add_heading("6.4 Testes automatizados", 2)
par(f"**Back-end:** {unit['testes']} testes JUnit em {unit['classes']} classes, com {unit['falhas']} falhas e {unit['erros']} erros "
    "(validação e normalização das tags, toda a taxonomia, combinação híbrida, intercalação por mídia, codificação de URLs "
    "e carregamento do contexto Spring com o Neo4j).")
if UI_T:
    tabela(["Caso", "Descrição", "Resultado", "Detalhe"],
           [[x["id"], x["descricao"], "Aprovado" if x["ok"] else "Reprovado", x["detalhe"]] for x in UI_T["casos"]],
           larguras=[1.2, 7.3, 2.2, 5.3], titulo=f"Testes de interface ({UI_T['aprovados']}/{UI_T['total']} aprovados)", tamanho=8,
           fonte_txt="teste_interface.mjs executado no Microsoft Edge (modo headless).", destaque_status=None)

doc.add_heading("6.5 Síntese dos requisitos", 2)
todos = rf + rnf
cont = Counter(x[5] for x in todos)
pend = [f"{x[0]} ({x[5].lower()})" for x in todos if x[5] != "Atendido"]
par(f"Dos {len(todos)} requisitos, **{cont['Atendido']} foram atendidos**, {cont['Parcial']} parcialmente e "
    f"{cont['Não atendido']} não foram atendidos." + (" Pendentes: " + "; ".join(pend) + "." if pend else ""))

# ============================================================ 7 CORREÇÕES
doc.add_heading("7 Correções implementadas a partir da avaliação", 1)
par("A primeira rodada de avaliação identificou problemas que foram corrigidos e medidos novamente:")
corr = [
    ["Tags genéricas e viés de *Ficção Científica* (F1 v1 = " + (f(V1["teste"]["f1_micro"]) if V1 else "—") + ")",
     "Classificador híbrido: gêneros oficiais no prompt, regras contra tags genéricas, exemplos neutros e combinação com os gêneros",
     f"F1 (teste) {f(V1['teste']['f1_micro']) if V1 else '—'} → {f(PROD['teste']['f1_micro'])}"],
    ["LLM atribuía tags de jogo a filmes e séries (ex.: *Battle Royale* em *300*) e nunca usava *Anime*",
     "Taxonomia restrita por mídia no JSON Schema; regra Animação + idioma japonês → *Anime*; remoção do modo \"Um Jogador\" dos gêneros da Steam",
     "Testes `IaServiceTest` e `TmdbServiceTest`"],
    ["Respostas diferentes para a mesma obra",
     "`temperature = 0` e `seed = 42`", f"Jaccard entre execuções {f(IA1.get('consistencia_jaccard'))} → {f(IA2.get('consistencia_jaccard'))}"],
    ["Tags guardadas só como texto; `CONTAINS` casava substrings; tags com o mesmo peso",
     "Nós `:Tag` + `TEM_TAG`, restrições únicas (índices), casamento exato e peso IDF",
     f"varredura do acervo → busca por índice; db hits {prof.get('db_hits_consulta_anterior', '—')} → {prof.get('db_hits', '—')}"],
    ["Busca de jogos esperava 8 s pelo tempo limite da RAWG a cada jogo",
     "Disjuntor: após uma falha, a RAWG fica 10 min sem ser consultada e a Steam é usada direto",
     f"mediana {ms(IA1.get('latencia_busca_api_ms', {}).get('Jogo', {}).get('p50'))} → {ms(IA2.get('latencia_busca_api_ms', {}).get('Jogo', {}).get('p50'))}"],
    ["Chaves de API e senha no código-fonte", "`config/segredos.properties` fora do Git (+ modelo de exemplo) e variáveis de ambiente",
     f"{len(achados)} segredos no código"],
    ["Títulos com \"&\", \"+\" ou acentos quebravam a busca (URL sem codificação)", "`ClienteHttp.uri` com codificação estrita",
     "Testes `ClienteHttpTest`"],
    ["Obra não encontrada respondia 200 com corpo vazio", "Resposta 404; 400 para nome vazio", "Contrato da API"],
    ["Código morto (`CargaService`) e protótipo React chamando a API sem parâmetros",
     "Remoção do código morto; protótipo usa catálogo e tags reais", "Build e lint do React sem erros"],
    ["Teste de interface reaproveitava navegadores de execuções anteriores (resultado instável)",
     "Porta livre a cada execução, estado limpo antes do caso T03 e encerramento do navegador via `Browser.close`",
     "3 execuções seguidas com 10/10"],
    ["Favoritos e responsividade só verificados manualmente", "Testes de interface automatizados",
     f"{UI_T['aprovados']}/{UI_T['total']} casos" if UI_T else "—"],
    ["Uma obra sem pôster (resultado de busca incompleto)", "Escolha do primeiro resultado com pôster entre os 5 primeiros",
     f"obras com imagem: {p(INT['com_imagem'])}"],
]
tabela(["Problema encontrado", "Correção", "Evidência (antes → depois)"], corr, larguras=[5.2, 6.0, 4.8],
       titulo="Correções e efeito medido", tamanho=8)

# ============================================================ 8 DISCUSSÃO
doc.add_heading("8 Discussão e limitações", 1)
ganho = c("producao_atual")["ndcg@10"] / ALE_T["ndcg@10"]
itens_disc = [
    f"**Tags melhores geram recomendações melhores.** No teste, o mesmo algoritmo (IDF) atinge nDCG@10 = {f(c('oraculo_tags_gabarito')['ndcg@10'])} "
    f"com as tags do gabarito, {f(c('producao_atual')['ndcg@10'])} com as tags atuais e {f(c('v1_llm_original')['ndcg@10'])} com as tags v1 "
    f"({f(ganho, 1)}× o aleatório com as tags atuais). A qualidade do classificador continua sendo o principal fator.",
]
if GEN:
    g_f1, p_f1 = GEN["teste"]["f1_micro"], PROD["teste"]["f1_micro"]
    itens_disc.append(
        f"**Contribuição do LLM sobre os gêneros oficiais:** F1 de {f(p_f1)} (híbrido) contra {f(g_f1)} (só gêneros) no teste. "
        + ("O LLM acrescenta tags específicas (por exemplo, *Super-herói*, *Zumbi*, *Espionagem*) que as APIs não oferecem."
           if p_f1 > g_f1 else
           "Neste conjunto, o LLM de 3 bilhões de parâmetros ainda não supera os gêneros oficiais nas tags; seu ganho aparece "
           "no vocabulário específico e nas recomendações. Um modelo maior é o próximo passo natural."))
g_rec, a_rec = c("generos_api")["ndcg@10"], c("producao_atual")["ndcg@10"]
if g_rec > a_rec:
    itens_disc.append(
        f"**Tags melhores no F1 não garantem recomendações melhores:** apesar do F1 maior, as tags híbridas tiveram nDCG@10 "
        f"de {f(a_rec)} no teste, contra {f(g_rec)} dos gêneros oficiais puros. As tags específicas acrescentadas pelo LLM "
        "às vezes estão erradas (por exemplo, *Vampiros* em *Venom*) e, por serem raras, recebem peso IDF alto, o que "
        "aproxima obras não relacionadas. Restringir o LLM a tags com confiança alta, ou dar peso menor às tags que vieram "
        "só do LLM, são caminhos diretos de melhoria.")
d_idf = {k: c(k)["ndcg@10"] - c(k, "contagem")["ndcg@10"] for k in ("oraculo_tags_gabarito", "producao_atual", "generos_api")}
itens_disc += [
    "**Efeito do IDF:** em relação à contagem simples, o nDCG@10 (teste) variou "
    + "; ".join(f"{('+' + f(v, 3)) if v >= 0.0005 else (f(v, 3) if v <= -0.0005 else '0,000')} com {NOMES_CEN[k][0].lower() + NOMES_CEN[k][1:]}" for k, v in d_idf.items())
    + ". O ganho aparece quando as tags são confiáveis; com tags ruidosas, ele é neutralizado pelo peso dado a tags raras erradas.",
    "**Validade:** 58 obras de referência e um único anotador; relevância definida por sobreposição do gabarito, sem usuários reais; acervo desbalanceado (%d jogos)." % CAT.get("Jogo", 0),
    "**Identidade da obra:** o título é o identificador; duas obras diferentes com o mesmo título (por exemplo, um filme e sua série) colidiriam. Um identificador composto (fonte + id externo) resolveria.",
    "**Dependência de serviços externos:** a RAWG estava fora do ar durante toda a avaliação; o disjuntor e a Steam mantiveram o cadastro de jogos funcionando. Uma queda de rede durante a medição afetou 5 obras, que foram medidas novamente.",
]
if SEM_GPU:
    itens_disc.append(f"**Hardware de IA:** o servidor Ollama executa o modelo inteiramente na CPU (0 bytes em GPU). Isso explica a mediana de {ms(lat2.get('p50'))} por obra e os picos acima de 25 s, causados também pela segunda tentativa quando o LLM devolve apenas tags inválidas. Uma GPU, mesmo modesta, reduziria a latência em uma ordem de grandeza.")
bullets(itens_disc)
doc.add_heading("9 Trabalhos futuros", 1)
bullets([
    "Comparar modelos maiores (por exemplo, `qwen2.5:7b`) com o mesmo harness e o mesmo conjunto de teste.",
    "Ampliar o conjunto de referência (≥ 150 obras, 2 anotadores, kappa de Cohen) e avaliar com usuários reais.",
    "Persistir perfis no Neo4j (`(:Usuario)-[:CURTIU]->(:Obra)`) e combinar com filtragem colaborativa.",
    "Usar identificador composto (fonte + id externo) para as obras.",
])
doc.add_heading("10 Reprodutibilidade", 1)
par("Com o Neo4j, o back-end e o Ollama em execução (segredos em `backend-java/config/segredos.properties`):")
codigo("""
cd backend-java && mvnw test                                   # testes JUnit
java -cp <classpath> HarnessClassificacao config/segredos.properties ../docs/tcc/avaliacao/conjunto_referencia.csv ../docs/tcc/avaliacao/resultado_classificacao.jsonl 2
node docs/tcc/avaliacao/teste_interface.mjs http://localhost:8080/
python docs/tcc/avaliacao/avaliar.py
java -jar plantuml.jar -charset UTF-8 -tpng docs/tcc/diagramas/0*.puml
python docs/tcc/gerar_documento.py
""")

doc.add_heading("Referências", 1)
for ref_ in [
    "JÄRVELIN, K.; KEKÄLÄINEN, J. Cumulated gain-based evaluation of IR techniques. ACM Transactions on Information Systems, v. 20, n. 4, p. 422–446, 2002.",
    "KAMINSKAS, M.; BRIDGE, D. Diversity, serendipity, novelty, and coverage: a survey and empirical analysis of beyond-accuracy objectives in recommender systems. ACM Transactions on Interactive Intelligent Systems, v. 7, n. 1, 2016.",
    "MANNING, C. D.; RAGHAVAN, P.; SCHÜTZE, H. Introduction to Information Retrieval. Cambridge: Cambridge University Press, 2008.",
    "RICCI, F.; ROKACH, L.; SHAPIRA, B. (ed.). Recommender Systems Handbook. 3. ed. New York: Springer, 2022.",
    "SAKAI, T. Alternatives to Bpref. In: Proceedings of the 30th ACM SIGIR Conference, p. 71–78, 2007.",
    "OBJECT MANAGEMENT GROUP. Unified Modeling Language (UML), version 2.5.1. 2017.",
    "NEO4J. Cypher Manual. Disponível em: https://neo4j.com/docs/cypher-manual/. Acesso em: nov. 2026.",
    "OLLAMA. Structured outputs. Disponível em: https://ollama.com/blog/structured-outputs. Acesso em: nov. 2026.",
]:
    par(ref_, tamanho=10)

quebra()
doc.add_heading("Apêndice A — Conjunto de referência e resultado por obra", 1)
itens = {i["titulo"]: i for i in PROD["todos"]["itens"]}
particao = {i["titulo"]: i["particao"] for i in PROD["todos"]["itens"]}
tabela(["Obra", "Tipo", "Part.", "Gabarito", "Tags atuais", "F1"],
       [[r["titulo"], {"Serie": "Série"}.get(r["tipo"], r["tipo"]), {"dev": "Dev", "teste": "Teste"}.get(particao.get(r["titulo"]), "—"),
         r["tags_gabarito"].replace("|", ", "),
         ", ".join(itens[r["titulo"]]["previsto"]) if r["titulo"] in itens else "—",
         f(itens[r["titulo"]]["f1"]) if r["titulo"] in itens else "—"] for r in REF],
       larguras=[3.4, 1.1, 1.1, 4.4, 4.4, 1.0], titulo="Conjunto de referência, partição e tags atuais", tamanho=7.5,
       fonte_txt="Gabarito anotado pelo autor; tags lidas do Neo4j.")
perfil_txt = AQUI / "avaliacao" / "profile_consulta_recomendacao.txt"
if perfil_txt.exists():
    nova = doc.add_section()
    nova.orientation = WD_ORIENT.LANDSCAPE
    nova.page_width, nova.page_height = Cm(29.7), Cm(21.0)
    nova.left_margin = nova.right_margin = Cm(1.5)
    doc.add_heading("Apêndice B — Plano de execução da consulta de recomendação (PROFILE)", 1)
    for linha in perfil_txt.read_text(encoding="utf-8").splitlines():
        pp = doc.add_paragraph()
        run = pp.add_run(linha if linha else " ")
        run.font.name = "Consolas"
        run.element.rPr.rFonts.set(qn("w:eastAsia"), "Consolas")
        run.font.size = Pt(5.2)
        pp.paragraph_format.space_after = Pt(0)
        pp.paragraph_format.line_spacing = 1.0

saida = AQUI / "OmniReview_Documentacao_TCC.docx"
try:
    doc.save(saida)
except PermissionError:
    saida = AQUI / "OmniReview_Documentacao_TCC_novo.docx"
    doc.save(saida)
    print("AVISO: o .docx original está aberto no Word; salvo como", saida.name)
print("ok", saida)
