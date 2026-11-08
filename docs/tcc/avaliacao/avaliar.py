"""
Avaliação do OmniReview: qualidade das tags, do motor de recomendação transmídia e desempenho.

Entradas
  conjunto_referencia.csv          gabarito anotado manualmente (58 obras), dividido em desenvolvimento/teste
  resultado_classificacao_v1.jsonl classificador v1 (somente LLM, prompt original) - HarnessClassificacao v1
  resultado_classificacao.jsonl    classificador v2 (LLM com gêneros oficiais + combinação) - HarnessClassificacao v2
  API do back-end (OMNIREVIEW_API, padrão http://localhost:8080/api) e Neo4j (http://localhost:7474)

Saída
  resultados.json, profile_consulta_recomendacao.txt

Uso: python avaliar.py [--somente-tags]
"""
import base64
import csv
import json
import math
import os
import random
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

AQUI = Path(__file__).parent
API = os.environ.get("OMNIREVIEW_API", "http://localhost:8080/api")
NEO4J = "http://localhost:7474/db/neo4j/tx/commit"


def _senha_neo4j():
    """Senha do Neo4j: variável NEO4J_PASSWORD ou backend-java/config/segredos.properties (fora do Git)."""
    if os.environ.get("NEO4J_PASSWORD"):
        return os.environ["NEO4J_PASSWORD"]
    segredos = AQUI.parents[2] / "backend-java" / "config" / "segredos.properties"
    if segredos.exists():
        for linha in segredos.read_text(encoding="utf-8").splitlines():
            if linha.startswith("spring.neo4j.authentication.password="):
                return linha.split("=", 1)[1].strip()
    raise SystemExit("Defina NEO4J_PASSWORD ou crie backend-java/config/segredos.properties")


NEO4J_AUTH = ("neo4j", _senha_neo4j())
INVALIDAS = {"deu erro", "sem classificação", ""}
K = 10
MAX_TAGS = 4
LIMIAR_RELEVANCIA = 2  # duas ou mais tags em comum no gabarito => obra relevante
SOMENTE_TAGS = "--somente-tags" in sys.argv
random.seed(42)


# ======================================================================= utilitários
TOKEN = None   # sessão da conta temporária de avaliação (a API exige login)


def get_json(url):
    cabecalhos = {"Authorization": f"Bearer {TOKEN}"} if TOKEN and url.startswith(API) else {}
    with urllib.request.urlopen(urllib.request.Request(url, headers=cabecalhos), timeout=120) as r:
        return json.load(r)


def _auth(metodo, rota, corpo, token=None):
    cab = {"Content-Type": "application/json"}
    if token:
        cab["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(API + rota, data=json.dumps(corpo).encode(), headers=cab, method=metodo)
    with urllib.request.urlopen(req, timeout=60) as r:
        texto = r.read().decode()
        return json.loads(texto) if texto else None


def abrir_sessao_de_avaliacao():
    """Cria uma conta temporária para a avaliação e agenda a exclusão dela ao final."""
    import atexit
    import secrets
    global TOKEN
    usuario, senha = "avaliacao_" + secrets.token_hex(4), "Aval-" + secrets.token_urlsafe(12)
    TOKEN = _auth("POST", "/auth/cadastrar", {"usuario": usuario, "senha": senha})["token"]
    atexit.register(lambda: _auth("DELETE", "/auth/conta", {"senha": senha}, TOKEN))


def cypher(query, params=None):
    corpo = json.dumps({"statements": [{"statement": query, "parameters": params or {}}]}).encode()
    req = urllib.request.Request(NEO4J, data=corpo, headers={
        "Content-Type": "application/json",
        "Authorization": "Basic " + base64.b64encode(":".join(NEO4J_AUTH).encode()).decode()})
    with urllib.request.urlopen(req, timeout=60) as r:
        res = json.load(r)
    if res.get("errors"):
        raise RuntimeError(res["errors"])
    return res["results"][0]


def hits_do_plano(no):
    return (no.get("DbHits", no.get("dbHits", 0)) or 0) + sum(hits_do_plano(c) for c in no.get("children", []))


def tags(texto):
    return [t.strip() for t in str(texto or "").split(",") if t.strip().lower() not in INVALIDAS]


def pct(valores, p):
    v = sorted(valores)
    if not v:
        return None
    i = (len(v) - 1) * p / 100
    lo, hi = int(i), min(int(i) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (i - lo)


def media(v):
    v = [x for x in v if x is not None]
    return sum(v) / len(v) if v else None


def jaccard(a, b):
    a, b = set(a), set(b)
    return len(a & b) / len(a | b) if a | b else 1.0


def r4(x):
    return None if x is None else round(x, 4)


def unicos(seq):
    vistos, out = set(), []
    for x in seq:
        if x not in vistos:
            vistos.add(x)
            out.append(x)
    return out


def ler_jsonl(nome):
    caminho = AQUI / nome
    if not caminho.exists():
        return {}
    return {d["titulo"]: d for d in (json.loads(l) for l in open(caminho, encoding="utf-8") if l.strip())}


# ======================================================================= dados
linhas_ref = list(csv.DictReader(open(AQUI / "conjunto_referencia.csv", encoding="utf-8"), delimiter=";"))
ref, contagem_tipo = {}, Counter()
for r in linhas_ref:
    # divisão estratificada por mídia, na ordem do arquivo: posições pares -> desenvolvimento, ímpares -> teste
    particao = "dev" if contagem_tipo[r["tipo"]] % 2 == 0 else "teste"
    contagem_tipo[r["tipo"]] += 1
    ref[r["titulo"]] = {"tipo": r["tipo"], "gold": r["tags_gabarito"].split("|"), "particao": particao}
DEV = [t for t, v in ref.items() if v["particao"] == "dev"]
TESTE = [t for t, v in ref.items() if v["particao"] == "teste"]

v1 = ler_jsonl("resultado_classificacao_v1.jsonl")
v2 = ler_jsonl("resultado_classificacao.jsonl")
if not SOMENTE_TAGS:
    abrir_sessao_de_avaliacao()
catalogo = [] if SOMENTE_TAGS else get_json(f"{API}/obras")
por_titulo = {o["titulo"]: o for o in catalogo}

resultados = {
    "gerado_em": time.strftime("%Y-%m-%d %H:%M"),
    "api": API,
    "catalogo": dict(Counter(o["tipo"] for o in catalogo), total=len(catalogo)),
    "conjunto_referencia": dict(Counter(v["tipo"] for v in ref.values()), total=len(ref),
                                desenvolvimento=len(DEV), teste=len(TESTE)),
}


# ======================================================================= 1. qualidade das tags
def avaliar_tags(predicoes, titulos):
    tp = fp = fn = 0
    por_item, por_tipo = [], defaultdict(list)
    falsos_pos, falsos_neg = Counter(), Counter()
    for titulo in titulos:
        if titulo not in predicoes:
            continue
        item = ref[titulo]
        pred, gold = set(predicoes[titulo]), set(item["gold"])
        acerto = pred & gold
        tp += len(acerto); fp += len(pred - gold); fn += len(gold - pred)
        falsos_pos.update(pred - gold); falsos_neg.update(gold - pred)
        p = len(acerto) / len(pred) if pred else 0.0
        r = len(acerto) / len(gold)
        f = 2 * p * r / (p + r) if p + r else 0.0
        primeira = predicoes[titulo][0] if predicoes[titulo] else None
        linha = {"titulo": titulo, "tipo": item["tipo"], "particao": item["particao"], "p": p, "r": r, "f1": f,
                 "jaccard": jaccard(pred, gold), "acerto_1a_tag": primeira in gold, "algum_acerto": bool(acerto),
                 "previsto": predicoes[titulo], "gabarito": item["gold"]}
        por_item.append(linha)
        por_tipo[item["tipo"]].append(linha)

    def resumo(ls):
        return {"n": len(ls),
                "precisao_macro": r4(media([l["p"] for l in ls])), "revocacao_macro": r4(media([l["r"] for l in ls])),
                "f1_macro": r4(media([l["f1"] for l in ls])), "jaccard_medio": r4(media([l["jaccard"] for l in ls])),
                "acerto_primeira_tag": r4(media([float(l["acerto_1a_tag"]) for l in ls])),
                "itens_com_algum_acerto": r4(media([float(l["algum_acerto"]) for l in ls]))}

    pm = tp / (tp + fp) if tp + fp else 0
    rm = tp / (tp + fn) if tp + fn else 0
    out = resumo(por_item)
    out.update({"precisao_micro": r4(pm), "revocacao_micro": r4(rm), "f1_micro": r4(2 * pm * rm / (pm + rm) if pm + rm else 0),
                "por_tipo": {t: resumo(l) for t, l in por_tipo.items()},
                "falsos_positivos_mais_comuns": falsos_pos.most_common(8),
                "falsos_negativos_mais_comuns": falsos_neg.most_common(8),
                "itens": por_item})
    return out


def estrategias(llm, generos):
    """Formas de combinar as tags do LLM com os gêneros oficiais (escolha feita no conjunto de desenvolvimento)."""
    conf = [t for t in llm if t in generos]
    return {
        "llm_puro": llm[:MAX_TAGS],
        "generos_puros": generos[:MAX_TAGS],
        "confirmadas_llm_generos": unicos(conf + llm + generos)[:MAX_TAGS],   # implementada em IaService.combinar
        "generos2_mais_llm": unicos(generos[:2] + llm + generos)[:MAX_TAGS],
        "llm_completa_generos": unicos(llm + generos)[:MAX_TAGS],
    }


fontes = {}
if v1:
    fontes["v1_llm_original"] = {t: tags(h["execucoes"][0]["tags"]) for t, h in v1.items()}
if v2:
    por_estrategia = defaultdict(dict)
    for t, h in v2.items():
        for nome, pred in estrategias(tags(h["execucoes"][0]["llm"]), tags(h["generos_api"])).items():
            por_estrategia[nome][t] = pred
    fontes.update({f"v2_{k}": v for k, v in por_estrategia.items()})
if catalogo:
    fontes["producao_banco"] = {t: tags(por_titulo[t].get("dnaCriativo")) for t in ref if t in por_titulo}

resultados["tags"] = {nome: {"desenvolvimento": avaliar_tags(pred, DEV), "teste": avaliar_tags(pred, TESTE),
                             "todos": avaliar_tags(pred, list(ref))}
                      for nome, pred in fontes.items()}
candidatas = [k for k in resultados["tags"] if k.startswith("v2_") and k != "v2_generos_puros"]
if candidatas:
    escolhida = max(candidatas, key=lambda k: resultados["tags"][k]["desenvolvimento"]["f1_micro"])
    resultados["selecao_estrategia"] = {
        "criterio": "maior F1 micro no conjunto de desenvolvimento",
        "escolhida": escolhida,
        "f1_dev": {k: resultados["tags"][k]["desenvolvimento"]["f1_micro"] for k in candidatas},
    }


def estabilidade(harness, campo="tags"):
    runs = [h for h in harness.values() if len(h["execucoes"]) >= 2]
    if not runs:
        return None
    # Latência: só a 1ª execução de cada obra (a 2ª reaproveita o cache de prompt do Ollama e seria otimista);
    # a 1ª obra é a partida a frio (carregamento do modelo) e é reportada à parte.
    lat = [h["execucoes"][0]["ms"] for h in harness.values()]
    quente = lat[1:]
    return {
        "consistencia_identica": r4(media([float(set(tags(h["execucoes"][0][campo])) == set(tags(h["execucoes"][1][campo]))) for h in runs])),
        "consistencia_jaccard": r4(media([jaccard(tags(h["execucoes"][0][campo]), tags(h["execucoes"][1][campo])) for h in runs])),
        "latencia_ms": {"partida_a_frio": round(lat[0], 1), "n": len(quente), "p50": round(pct(quente, 50), 1),
                        "p95": round(pct(quente, 95), 1), "max": round(max(quente), 1), "media": round(media(quente), 1)},
        "latencia_busca_api_ms": {tp: {"p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1), "max": round(max(v), 1)}
                                  for tp, v in {t: [h["ms_busca_api"] for h in harness.values() if h["tipo"] == t]
                                                for t in ("Filme", "Serie", "Jogo")}.items() if v},
        "taxa_sinopse_referencia": r4(media([float(h["tem_sinopse"]) for h in harness.values()])),
        "jogos_com_dados": r4(media([float(h["tem_sinopse"] or bool(h["generos_api"])) for h in harness.values() if h["tipo"] == "Jogo"])),
    }


resultados["ia"] = {"v1": estabilidade(v1), "v2": estabilidade(v2)}
ESCOLHIDA = resultados.get("selecao_estrategia", {}).get("escolhida", "producao_banco")

# Latência em rodada dedicada (sem outras cargas no servidor de IA): 1 execução por obra, 15 obras
dedicada = ler_jsonl("resultado_latencia.jsonl")
if dedicada:
    lat_d = [h["execucoes"][0]["ms"] for h in dedicada.values()]
    quentes = lat_d[1:]
    resultados["ia"]["latencia_dedicada_ms"] = {
        "n": len(quentes), "partida_a_frio": round(lat_d[0], 1), "p50": round(pct(quentes, 50), 1),
        "p95": round(pct(quentes, 95), 1), "max": round(max(quentes), 1),
        "acima_de_25s": sum(1 for x in quentes if x > 25000)}


def _info_ollama():
    """Modelo carregado no Ollama e quanto dele está na GPU (size_vram = 0 => execução só na CPU)."""
    segredos = AQUI.parents[2] / "backend-java" / "config" / "segredos.properties"
    url = next((l.split("=", 1)[1].strip() for l in segredos.read_text(encoding="utf-8").splitlines()
                if l.startswith("omnireview.ollama.url=")), None) if segredos.exists() else None
    try:
        modelos = get_json(url.rstrip("/") + "/api/ps")["models"]
        return [{"modelo": m["name"], "tamanho_bytes": m.get("size"), "na_gpu_bytes": m.get("size_vram")} for m in modelos]
    except Exception as e:
        return {"erro": repr(e)}


resultados["ia"]["servidor_ollama"] = _info_ollama()
if SOMENTE_TAGS:
    json.dump(resultados, open(AQUI / "resultados_tags.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    for nome, t in resultados["tags"].items():
        print(f"{nome:34s} dev F1={t['desenvolvimento']['f1_micro']:.3f}  teste F1={t['teste']['f1_micro']:.3f}  todos F1={t['todos']['f1_micro']:.3f}")
    print(resultados.get("selecao_estrategia"))
    print({k: (v or {}).get("consistencia_jaccard") for k, v in resultados["ia"].items()})
    sys.exit(0)

# Integridade do catálogo
taxonomia = set(json.load(open(AQUI / "taxonomia.json", encoding="utf-8")))
brutas = [[t.strip() for t in str(o.get("dnaCriativo") or "").split(",") if t.strip()] for o in catalogo]
resultados["integridade_catalogo"] = {
    "obras": len(catalogo),
    "tags_todas_na_taxonomia": r4(media([float(all(t in taxonomia for t in b) and len(b) > 0) for b in brutas])),
    "entre_1_e_4_tags": r4(media([float(1 <= len(b) <= 4) for b in brutas])),
    "obras_deu_erro": sum(1 for o in catalogo if (o.get("dnaCriativo") or "").strip() == "Deu erro"),
    "obras_sem_classificacao": sum(1 for o in catalogo if (o.get("dnaCriativo") or "").strip() in ("", "Sem classificação")),
    "com_sinopse": r4(media([float(bool(o.get("sinopse")) and not o["sinopse"].startswith("Sem sinopse")) for o in catalogo])),
    "com_imagem": r4(media([float(bool(o.get("imagem"))) for o in catalogo])),
}
resultados["tendencias"] = {m: len(get_json(f"{API}/tendencias/{m}")) for m in ("filmes", "series", "jogos")}
contagem = Counter(t for o in catalogo for t in tags(o.get("dnaCriativo")))
resultados["tags_catalogo"] = {
    "tags_distintas_usadas": len(contagem),
    "tamanho_taxonomia": len(taxonomia),
    "media_tags_por_obra": r4(media([len(tags(o.get("dnaCriativo"))) for o in catalogo])),
    "top15": contagem.most_common(15),
    "obras_com_ficcao_cientifica": r4(sum("Ficção Científica" in tags(o.get("dnaCriativo")) for o in catalogo) / len(catalogo)),
}
if v1:
    cont_v1 = Counter(t for h in v1.values() for t in tags(h["execucoes"][0]["tags"]))
    resultados["tags_catalogo"]["ficcao_cientifica_referencia_v1"] = r4(cont_v1["Ficção Científica"] / len(v1))
resultados["tags_catalogo"]["ficcao_cientifica_referencia_atual"] = r4(
    sum("Ficção Científica" in fontes[ESCOLHIDA].get(t, []) for t in ref) / len(ref))
resultados["tags_catalogo"]["obras_referencia_no_acervo"] = sum(1 for t in ref if t in por_titulo)
resultados["tags_catalogo"]["ficcao_cientifica_gabarito"] = r4(sum("Ficção Científica" in v["gold"] for v in ref.values()) / len(ref))


# ======================================================================= 2. motor de recomendação
def motor(consulta, excluir, base, metodo="idf", limite=60, saida=20):
    """Réplica do /api/recomendar.
    metodo="idf": versão atual (nós Tag, casamento exato, score = soma de 1 + ln(N/df)).
    metodo="contagem": versão anterior (nº de tags em comum por CONTAINS no texto)."""
    if metodo == "idf":
        n = len(base)
        df = Counter(t for o in base for t in set(o["tags"]))
        peso = {t: 1.0 + math.log(n / df[t]) for t in set(consulta) if df[t]}
        cand = []
        for o in base:
            if o["titulo"] in excluir:
                continue
            comuns = [t for t in set(consulta) if t in o["tags"]]
            if comuns:
                cand.append((-sum(peso[t] for t in comuns), -len(comuns), o["titulo"], o))
    else:
        cand = []
        for o in base:
            if o["titulo"] in excluir or not o["tags"]:
                continue
            dna = ", ".join(o["tags"]).lower()
            comuns = sum(1 for t in consulta if t.lower() in dna)
            if comuns:
                cand.append((-comuns, 0, o["titulo"], o))
    cand.sort(key=lambda x: (x[0], x[1], x[2]))
    filas = defaultdict(list)
    for *_, o in cand[:limite]:
        filas[o["tipo"].lower()].append(o)
    out = []
    while len(out) < saida and any(filas.values()):
        for f in filas.values():
            if f and len(out) < saida:
                out.append(f.pop(0))
    return out


def relevancia(seed, titulo):
    if titulo not in ref:
        return None
    n = len(set(ref[seed]["gold"]) & set(ref[titulo]["gold"]))
    return n if n >= LIMIAR_RELEVANCIA else 0


def metricas_lista(seed, lista_titulos):
    """Lista condensada (SAKAI, 2007): itens sem julgamento são removidos antes do corte K."""
    julgados = [t for t in lista_titulos if t in ref and t != seed]
    rels = [relevancia(seed, t) for t in julgados]
    relevantes = [t for t in ref if t != seed and relevancia(seed, t)]
    if not relevantes:
        return None
    top = rels[:K]
    acertos = sum(1 for r in top if r)
    dcg = sum(r / math.log2(i + 2) for i, r in enumerate(top))
    ideal = sorted((relevancia(seed, t) for t in relevantes), reverse=True)[:K]
    idcg = sum(r / math.log2(i + 2) for i, r in enumerate(ideal))
    return {"p@5": sum(1 for r in rels[:5] if r) / max(1, min(5, len(rels))),
            "p@10": acertos / max(1, min(K, len(rels))),
            "r@10": acertos / len(relevantes), "ndcg@10": dcg / idcg if idcg else 0.0,
            "mrr": next((1 / (i + 1) for i, r in enumerate(rels) if r), 0.0),
            "hit@10": float(acertos > 0), "julgados_retornados": len(julgados)}


def agrega(lista):
    lista = [m for m in lista if m]
    return {k: r4(media([m[k] for m in lista])) for k in lista[0]} | {"seeds_avaliadas": len(lista)}


def base_ref(fonte):
    return [{"titulo": t, "tipo": v["tipo"], "tags": fonte.get(t, [])} for t, v in ref.items()]


def cenario(fonte, metodo, sementes):
    base = base_ref(fonte)
    return agrega([metricas_lista(s, [o["titulo"] for o in motor(fonte.get(s, []), {s}, base, metodo)]) for s in sementes])


gold = {t: v["gold"] for t, v in ref.items()}
fonte_atual = fontes[ESCOLHIDA]
cen_fontes = {"oraculo_tags_gabarito": gold, "producao_atual": fonte_atual}
if "v1_llm_original" in fontes:
    cen_fontes["v1_llm_original"] = fontes["v1_llm_original"]
if "v2_generos_puros" in fontes:
    cen_fontes["generos_api"] = fontes["v2_generos_puros"]

cen = {}
for nome_fonte, fonte in cen_fontes.items():
    for metodo in ("idf", "contagem"):
        cen[f"{nome_fonte}|{metodo}"] = {"todos": cenario(fonte, metodo, list(ref)), "teste": cenario(fonte, metodo, TESTE)}
aleatorio_todos, aleatorio_teste = [], []
for _ in range(200):
    for s in ref:
        outros = [t for t in ref if t != s]
        random.shuffle(outros)
        m = metricas_lista(s, outros)
        aleatorio_todos.append(m)
        if s in TESTE:
            aleatorio_teste.append(m)
cen["aleatorio"] = {"todos": agrega(aleatorio_todos), "teste": agrega(aleatorio_teste)}

# Produção: API real, sementes = obras de referência, consulta = tags gravadas da semente
base_prod = [{"titulo": o["titulo"], "tipo": o["tipo"], "tags": tags(o.get("dnaCriativo"))} for o in catalogo]
prod, lat_rec, transmidia, tipos_distintos, com_jogo, nao_vazias, recomendados, equivalencia = [], [], [], [], [], [], set(), []
exemplos = {}


def api_recomendar(consulta, excluir):
    q = urllib.parse.urlencode([("tags", t) for t in consulta] + [("excluir", e) for e in excluir])
    t0 = time.perf_counter()
    res = get_json(f"{API}/recomendar?{q}")
    return res, (time.perf_counter() - t0) * 1000


EXEMPLOS = ("A Viagem de Chihiro", "Call of Duty: Modern Warfare 4", "Dexter: Ressurreição", "Resident Evil")
for s in [o["titulo"] for o in catalogo]:
    consulta = tags(por_titulo[s].get("dnaCriativo"))
    if not consulta:
        continue
    res, ms = api_recomendar(consulta, [s])
    lat_rec.append(ms)
    titulos = [o["titulo"] for o in res]
    if s in ref:
        prod.append(metricas_lista(s, titulos))
    top = res[:K]
    recomendados.update(titulos)
    transmidia.append(media([float(o["tipo"] != por_titulo[s]["tipo"]) for o in top]) if top else 0.0)
    tipos_distintos.append(len({o["tipo"] for o in top}))
    com_jogo.append(float(any(o["tipo"] == "Jogo" for o in top)))
    nao_vazias.append(float(len(res) > 0))
    equivalencia.append(float([o["titulo"] for o in motor(consulta, {s}, base_prod, "idf")] == titulos))
    if s in EXEMPLOS:
        exemplos[s] = {"consulta": consulta, "top10": [(o["titulo"], o["tipo"], o["dnaCriativo"]) for o in top]}

resultados["recomendacao"] = {
    "producao_api": agrega(prod) if any(prod) else None,
    "sementes_producao": len(transmidia),
    "replica_identica_a_api": r4(media(equivalencia)),
    "alem_da_acuracia": {
        "cobertura_catalogo": r4(len(recomendados) / len(catalogo)),
        "taxa_transmidia_top10": r4(media(transmidia)),
        "media_tipos_distintos_top10": r4(media(tipos_distintos)),
        "seeds_com_jogo_no_top10": r4(media(com_jogo)),
        "seeds_com_ao_menos_uma_recomendacao": r4(media(nao_vazias)),
    },
    "exemplos": exemplos,
    "cenarios_referencia": cen,
}

# ======================================================================= 3. desempenho e consultas
lat_obras = []
for _ in range(30):
    t0 = time.perf_counter(); get_json(f"{API}/obras"); lat_obras.append((time.perf_counter() - t0) * 1000)
for _ in range(150):
    _, ms = api_recomendar(random.sample(list(contagem), random.randint(2, 5)), [])
    lat_rec.append(ms)
resultados["desempenho"] = {
    "recomendar_ms": {"n": len(lat_rec), "p50": round(pct(lat_rec, 50), 1), "p95": round(pct(lat_rec, 95), 1),
                      "p99": round(pct(lat_rec, 99), 1), "max": round(max(lat_rec), 1)},
    "listar_obras_ms": {"n": len(lat_obras), "p50": round(pct(lat_obras, 50), 1), "p95": round(pct(lat_obras, 95), 1)},
}

CONSULTA_REC = ("MATCH (todas:Obra) WITH count(todas) AS n "
                "MATCH (t:Tag) WHERE t.nome IN $tags "
                "MATCH (t)<-[:TEM_TAG]-(o) "
                "WITH n, t, collect(o) AS obras "
                "WITH t, obras, 1.0 + log(toFloat(n) / size(obras)) AS peso "
                "UNWIND obras AS o "
                "WITH o, sum(peso) AS score, count(t) AS comuns "
                "WHERE NOT o.titulo IN $excluir "
                "RETURN o.titulo AS titulo, o.tipo AS tipo, comuns, round(score, 2) AS score "
                "ORDER BY score DESC, comuns DESC, titulo LIMIT 60")
CONSULTA_ANTIGA = ("MATCH (o:Obra) WHERE o.dnaCriativo IS NOT NULL AND NOT o.titulo IN $excluir "
                   "WITH o, [t IN $tags WHERE toLower(o.dnaCriativo) CONTAINS toLower(t)] AS comuns "
                   "WHERE size(comuns) > 0 RETURN o.titulo AS titulo ORDER BY size(comuns) DESC, titulo LIMIT 60")
# Operadores que leem todos os nós do rótulo (varredura completa, inclusive pelo índice)
VARREDURAS = ("NodeByLabelScan", "AllNodesScan", "NodeIndexScan", "NodeUniqueIndexScan")
params_demo = {"tags": ["Animação", "Aventura", "Fantasia"], "excluir": ["A Viagem de Chihiro"]}
try:
    perfil = cypher("PROFILE " + CONSULTA_REC, params_demo)
    raiz = perfil["plan"]["root"]
    (AQUI / "profile_consulta_recomendacao.txt").write_text(raiz.get("string-representation", ""), encoding="utf-8")
    antigo = cypher("PROFILE " + CONSULTA_ANTIGA, params_demo)["plan"]["root"]
    plano_novo, plano_antigo = raiz.get("string-representation", ""), antigo.get("string-representation", "")
    resultados["desempenho"]["profile_cypher"] = {
        "db_hits": hits_do_plano(raiz), "linhas": len(perfil["data"]), "db_hits_consulta_anterior": hits_do_plano(antigo),
        "usa_indice": "NodeUniqueIndexSeek" in plano_novo or "NodeIndexSeek" in plano_novo,
        "varre_todas_as_obras": any(op in plano_novo for op in VARREDURAS),
        "anterior_varre_todas_as_obras": any(op in plano_antigo for op in VARREDURAS),
        "obras_no_acervo": len(catalogo)}
except Exception as e:
    resultados["desempenho"]["profile_cypher"] = {"erro": repr(e)}

resultados["grafo"] = {
    "tags": cypher("MATCH (t:Tag) RETURN count(t)")["data"][0]["row"][0],
    "relacoes_tem_tag": cypher("MATCH ()-[r:TEM_TAG]->() RETURN count(r)")["data"][0]["row"][0],
    "restricoes": [r["row"][0] for r in cypher("SHOW CONSTRAINTS YIELD name RETURN name ORDER BY name")["data"]],
}
resultados["demonstracao_cypher"] = {
    "consulta_recomendacao": CONSULTA_REC,
    "parametros_demo": params_demo,
    "recomendar_demo": [r["row"] for r in cypher(CONSULTA_REC, params_demo)["data"][:10]],
    "tags_mais_frequentes": [r["row"] for r in cypher(
        "MATCH (t:Tag)<-[:TEM_TAG]-(o:Obra) RETURN t.nome AS tag, count(o) AS obras ORDER BY obras DESC, tag LIMIT 10")["data"]],
    "pontes_transmidia": [r["row"] for r in cypher(
        "MATCH (j:Obra {tipo: 'Jogo'})-[:TEM_TAG]->(t:Tag)<-[:TEM_TAG]-(o:Obra) WHERE o.tipo <> 'Jogo' "
        "WITH j, o, collect(t.nome) AS comuns WHERE size(comuns) >= 3 "
        "RETURN j.titulo AS jogo, o.titulo AS obra, o.tipo AS tipo, comuns "
        "ORDER BY size(comuns) DESC, jogo, obra LIMIT 10")["data"]],
}

json.dump(resultados, open(AQUI / "resultados.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
for nome, t in resultados["tags"].items():
    print(f"{nome:34s} teste F1={t['teste']['f1_micro']:.3f}  todos F1={t['todos']['f1_micro']:.3f}")
for nome, c in cen.items():
    print(f"{nome:40s} nDCG@10 todos={c['todos']['ndcg@10']:.3f} teste={c['teste']['ndcg@10']:.3f}")
print(resultados["recomendacao"]["producao_api"], resultados["recomendacao"]["replica_identica_a_api"],
      resultados["recomendacao"]["alem_da_acuracia"])
print(resultados["desempenho"], resultados["grafo"])
