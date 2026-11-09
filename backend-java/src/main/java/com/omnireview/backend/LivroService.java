package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Livros. Fonte principal: Google Books ({@link GoogleLivrosService}, sinopse em português, edições brasileiras), quando
 * há chave configurada. Reserva: a API pública do Open Library (não precisa de chave), descrita abaixo.
 * Com lang=pt a busca devolve a edição em português, quando existe (título e capa da edição brasileira).
 * A sinopse vem da "obra" do Open Library, muitas vezes em inglês: o {@link IaService} traduz para o português.
 */
@Service
public class LivroService {

    private static final String BASE = "https://openlibrary.org";
    private static final String CAMPOS = "key,title,author_name,cover_i,first_publish_year,number_of_pages_median,subject,"
            + "ratings_average,ratings_count,edition_count,editions,editions.title,editions.language,editions.cover_i,editions.key";
    static final int MAX_TENDENCIAS = 15;
    static final double PAGINAS_POR_HORA = 40;   // ritmo médio de leitura, para estimar o tempo de leitura

    // Assunto do Open Library (trecho, em minúsculas) -> tag da taxonomia
    private static final Map<String, String> ASSUNTOS = new LinkedHashMap<>();
    static {
        String[][] pares = {
            {"science fiction", "Ficção Científica"}, {"ficção científica", "Ficção Científica"}, {"dystopia", "Distopia"},
            {"fantasy", "Fantasia"}, {"fantasia", "Fantasia"}, {"magic", "Fantasia"}, {"horror", "Terror"}, {"terror", "Terror"},
            {"thriller", "Suspense"}, {"suspense", "Suspense"}, {"mystery", "Mistério"}, {"detective", "Detetive"},
            {"crime", "Crime"}, {"romance", "Romance"}, {"love stories", "Romance"}, {"historical fiction", "Histórico"},
            {"history", "Histórico"}, {"historical", "Histórico"}, {"biography", "Biografia"}, {"autobiography", "Biografia"}, {"memoir", "Biografia"},
            {"war", "Guerra"}, {"adventure", "Aventura"}, {"humor", "Comédia"}, {"vampire", "Vampiros"}, {"zombie", "Zumbi"},
            {"coming of age", "Coming of Age"}, {"psycholog", "Psicológico"}, {"politic", "Político"}, {"mytholog", "Mitologia"},
            {"spy", "Espionagem"}, {"espionage", "Espionagem"}, {"time travel", "Viagem no Tempo"}, {"survival", "Sobrevivência"},
            {"short stories", "Antologia"}, {"juvenile fiction", "Família"}, {"young adult", "Young Adult"},
            {"self-help", "Autoajuda"}, {"self-actualization", "Autoajuda"}, {"success", "Autoajuda"}, {"habit", "Autoajuda"},
            {"business", "Negócios"}, {"finance", "Negócios"}, {"economics", "Negócios"}, {"philosophy", "Filosofia"},
            {"poetry", "Poesia"}, {"classic", "Clássico"}, {"drama", "Drama"}
        };
        for (String[] p : pares) ASSUNTOS.put(p[0], p[1]);
    }

    private final RestTemplate carteiro;
    private final GoogleLivrosService google;

    public LivroService(GoogleLivrosService google) {
        this.google = google;
        this.carteiro = ClienteHttp.criar(6_000, 15_000);
        // O Open Library pede um User-Agent que identifique a aplicação
        this.carteiro.getInterceptors().add((pedido, corpo, execucao) -> {
            pedido.getHeaders().set("User-Agent", "OmniReview/1.0 (projeto academico)");
            return execucao.execute(pedido, corpo);
        });
    }

    /**
     * Retorna {titulo, sinopse, generos, capa, capa} ou null se nada for encontrado.
     * A sinopse vem como está (às vezes em inglês): quem salva a obra traduz (RecomendacaoController).
     */
    public String[] buscarLivroNaInternet(String nome) {
        String[] doGoogle = google.buscar(nome);
        if (doGoogle != null) return doGoogle;
        Map<String, Object> doc = buscarDoc(nome);
        return doc == null ? null : dadosDoDoc(doc);
    }

    /** Livro pelo id do volume no Google Books ("gb:ID"). */
    public String[] porVolumeGoogle(String id) {
        return google.porId(id);
    }

    private String[] dadosDoDoc(Map<String, Object> doc) {
        String capa = capa(doc);
        return new String[]{tituloEmPortugues(doc), descricao(doc), generosParaTaxonomia(doc.get("subject")), capa, capa,
                "ol:" + doc.get("key"), doc.get("first_publish_year") instanceof Number n ? String.valueOf(n.intValue()) : null};
    }

    /** Até {@code maximo} livros da busca: {ref ("ol:/works/OL...W"), titulo, ano, imagem, detalhe (autor)}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> candidatos(String nome, int maximo) {
        if (nome == null || nome.isBlank()) return List.of();
        List<Map<String, Object>> doGoogle = google.candidatos(nome, maximo);
        if (doGoogle != null) return doGoogle;   // null = Google sem chave ou fora do ar
        try {
            List<Map<String, Object>> lista = new ArrayList<>();
            Set<String> vistos = new HashSet<>();
            for (Map<String, Object> d : docsRanqueados(nome)) {
                if (lista.size() >= maximo) break;
                if (!(d.get("key") instanceof String chave) || tituloEmPortugues(d) == null) continue;
                String autor = d.get("author_name") instanceof List<?> a && !a.isEmpty() ? String.valueOf(a.get(0)) : "";
                if (!vistos.add(Provedores.normalizar(tituloEmPortugues(d)) + "|" + Provedores.normalizar(autor))) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("ref", "ol:" + chave);
                c.put("titulo", tituloEmPortugues(d));
                if (d.get("first_publish_year") instanceof Number n) c.put("ano", String.valueOf(n.intValue()));
                String capa = capa(d);
                c.put("imagem", capa == null ? null : capa.replace("-L.jpg", "-M.jpg"));
                if (d.get("author_name") instanceof List<?> a && !a.isEmpty()) c.put("detalhe", String.valueOf(a.get(0)));
                lista.add(c);
            }
            return lista;
        } catch (Exception e) {
            System.out.println("Open Library: falha ao listar '" + nome + "': " + e.getMessage());
            return List.of();
        }
    }

    /** Livro pela chave do Open Library ("/works/OL...W"): {titulo, sinopse, generos, capa, capa, ref, ano}; null se não existir. */
    @SuppressWarnings("unchecked")
    public String[] porChave(String chave) {
        Map<String, Object> doc = docPorChave(chave);
        return doc == null ? null : dadosDoDoc(doc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> docPorChave(String chave) {
        if (chave == null || !chave.matches("/works/OL[0-9]{1,12}W")) return null;
        try {
            Map<String, Object> r = carteiro.getForObject(ClienteHttp.uri(BASE + "/search.json",
                    Map.of("q", "key:" + chave, "lang", "pt", "limit", 1, "fields", CAMPOS)), Map.class);
            if (r == null || !(r.get("docs") instanceof List<?> docs) || docs.isEmpty()) return null;
            return (Map<String, Object>) docs.get(0);
        } catch (Exception e) {
            System.out.println("Open Library: falha ao abrir " + chave + ": " + e.getMessage());
            return null;
        }
    }

    /** Ficha do livro (fonte de onde a obra veio): autores, ano, páginas, tempo de leitura e onde ler/comprar. */
    public Map<String, Object> ficha(Obra obra) {
        String fonte = obra.getFonte();
        String titulo = RecomendacaoController.semSufixoDeMidia(obra.getTitulo());
        if (fonte != null && fonte.startsWith("gb:")) {
            Map<String, Object> f = google.ficha(fonte.substring(3), titulo);
            if (f != null) return f;
        }
        if (fonte != null && fonte.startsWith("ol:")) {
            Map<String, Object> doc = docPorChave(fonte.substring(3));
            if (doc != null) return fichaDoDoc(doc, titulo);
        }
        Map<String, Object> doc = buscarDoc(titulo);
        return doc == null ? null : fichaDoDoc(doc, titulo);
    }

    private Map<String, Object> fichaDoDoc(Map<String, Object> doc, String titulo) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (doc.get("author_name") instanceof List<?> a && !a.isEmpty())
            f.put("autores", String.valueOf(a.get(0)));
        if (doc.get("first_publish_year") instanceof Number n) f.put("ano", n.intValue());
        if (doc.get("number_of_pages_median") instanceof Number n && n.intValue() > 0) {
            f.put("paginas", n.intValue());
            f.put("tempoLeituraHoras", Math.max(1, Math.round(n.intValue() / PAGINAS_POR_HORA)));
        }
        if (doc.get("ratings_average") instanceof Number n && doc.get("ratings_count") instanceof Number c && c.intValue() >= 5)
            f.put("notaOpenLibrary", Math.round(n.doubleValue() * 10) / 10.0);
        String chave = (String) doc.get("key");
        if (chave != null) f.put("linkFicha", BASE + chave);
        String busca = URLEncoder.encode(titulo + (f.get("autores") instanceof String a ? " " + a.split(",")[0] : ""), StandardCharsets.UTF_8);
        String soTitulo = URLEncoder.encode(titulo, StandardCharsets.UTF_8);
        List<Map<String, Object>> onde = new ArrayList<>();
        onde.add(loja("Amazon", "Comprar (impresso ou Kindle)", "https://www.amazon.com.br/s?i=stripbooks&k=" + busca));
        onde.add(loja("Estante Virtual", "Usados e seminovos", "https://www.estantevirtual.com.br/busca?q=" + soTitulo));
        onde.add(loja("Google Livros", "Prévia e e-book", "https://www.google.com/search?tbm=bks&q=" + busca));
        if (chave != null) onde.add(loja("Open Library", "Ler ou emprestar grátis", BASE + chave));
        f.put("ondeLer", onde);
        f.put("fonteDados", "Open Library");
        return f;
    }

    /** Livros em alta na semana (Open Library), com capa e autor; no máximo {@link #MAX_TENDENCIAS}. */
    public List<String> buscarTendencias() {
        return emAlta(MAX_TENDENCIAS);
    }

    /** Títulos (originais) dos livros em alta na semana, na ordem do ranking; até {@code maximo}. */
    public List<String> emAlta(int maximo) {
        return emAltaComRef(maximo).stream().map(item -> item[1]).toList();
    }

    static final int MIN_EDICOES_EM_ALTA = 5;

    /**
     * Livros em alta na semana no Open Library: {ref ("ol:/works/OL...W"), título original}, na ordem do ranking.
     * Pela chave da obra, o livro entra com o título e a capa da edição em português ("Atomic Habits" vira
     * "Hábitos Atômicos"). O ranking semanal mistura edições avulsas e livros sem tradução (ex.: "Esquire's The New
     * Rules for Men"), então só entram obras com edição em português, capa, autor e pelo menos 5 edições.
     */
    @SuppressWarnings("unchecked")
    public List<String[]> emAltaComRef(int maximo) {
        List<String[]> lista = new ArrayList<>();
        Set<String> chaves = new HashSet<>();
        try {
            Map<String, Object> r = carteiro.getForObject(java.net.URI.create(BASE + "/trending/weekly.json?limit=100"), Map.class);
            if (r != null && r.get("works") instanceof List<?> obras) {
                for (Object o : obras) {
                    if (lista.size() >= maximo) break;
                    if (o instanceof Map<?, ?> w && serveParaEmAlta(w) && chaves.add((String) w.get("key"))) {
                        lista.add(new String[]{"ol:" + w.get("key"), ((String) w.get("title")).strip()});
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Open Library: falha ao buscar tendências: " + e.getMessage());
        }
        return lista;
    }

    static boolean serveParaEmAlta(Map<?, ?> w) {
        return w.get("key") instanceof String k && k.matches("/works/OL[0-9]{1,12}W")
                && w.get("title") instanceof String t && !t.isBlank() && t.length() <= 80
                && w.get("cover_i") != null
                && w.get("author_name") instanceof List<?> autores && !autores.isEmpty()
                && w.get("language") instanceof List<?> idiomas && idiomas.contains("por")
                && w.get("edition_count") instanceof Number n && n.intValue() >= MIN_EDICOES_EM_ALTA;
    }

    private static final Set<String> MINUSCULAS = Set.of("a", "o", "as", "os", "e", "de", "da", "do", "das", "dos", "em",
            "no", "na", "nos", "nas", "um", "uma", "para", "por", "com", "ao", "à", "the", "of", "and", "to", "in");

    /** Título todo em maiúsculas ("O ALQUIMISTA", comum em edições cadastradas por livrarias) vira "O Alquimista". */
    static String arrumarCaixa(String titulo) {
        if (titulo == null) return null;
        String letras = titulo.replaceAll("[^\\p{L}]", "");
        Locale pt = Locale.forLanguageTag("pt-BR");
        if (letras.length() < 4 || !letras.equals(letras.toUpperCase(pt)) || letras.equals(letras.toLowerCase(pt))) return titulo;
        String[] palavras = titulo.toLowerCase(pt).split(" ");
        for (int i = 0; i < palavras.length; i++) {
            String p = palavras[i];
            // depois de "-", ":" ou "." começa um subtítulo: "Em Chamas - Os Jogos da Fome"
            boolean comeco = i == 0 || palavras[i - 1].matches("[-–—]|.*[:.]");
            if (p.isEmpty() || (!comeco && MINUSCULAS.contains(p))) continue;
            if (i > 0 && p.matches("(?=[ivx])x{0,3}(ix|iv|v?i{0,3})[.,:;]?")) { palavras[i] = p.toUpperCase(pt); continue; }   // numeral romano: "Livro II"
            int j = 0;
            while (j < p.length() && !Character.isLetter(p.charAt(j))) j++;
            if (j < p.length()) palavras[i] = p.substring(0, j) + Character.toUpperCase(p.charAt(j)) + p.substring(j + 1);
        }
        return String.join(" ", palavras);
    }

    // ---------------- auxiliares ----------------

    /** Entre os 5 primeiros resultados: o que tem o título igual (original ou em português); senão, o primeiro com capa. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buscarDoc(String nome) {
        if (nome == null || nome.isBlank()) return null;
        try {
            List<Map<String, Object>> docs = docsRanqueados(nome);
            return docs.isEmpty() ? null : docs.get(0);
        } catch (Exception e) {
            System.out.println("Open Library: falha ao buscar '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Resultados do Open Library na ordem em que a pessoa espera. A busca padrão do Open Library favorece obras
     * cadastradas com o título em português, que costumam ser edições avulsas ("Orgulho e Preconceito" de 2016),
     * e a ordenação por número de edições sozinha traz lixo em buscas curtas ("Duna" acha "Sogno d'una notte").
     * Então junta as duas buscas e ordena: título que bate com o digitado (no original ou na edição em português)
     * primeiro e, entre eles, a obra com mais edições (a original). Títulos que nada têm a ver só entram se faltar.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> docsRanqueados(String nome) {
        Map<Object, Map<String, Object>> porChave = new LinkedHashMap<>();
        for (Map<String, Object> extra : List.of(Map.<String, Object>of(), Map.<String, Object>of("sort", "editions"))) {
            Map<String, Object> p = new LinkedHashMap<>(Map.of("q", nome, "lang", "pt", "limit", 15, "fields", CAMPOS));
            p.putAll(extra);
            try {
                Map<String, Object> r = carteiro.getForObject(ClienteHttp.uri(BASE + "/search.json", p), Map.class);
                if (r != null && r.get("docs") instanceof List<?> docs) {
                    for (Object o : docs) if (o instanceof Map<?, ?> d && d.get("key") != null) porChave.putIfAbsent(d.get("key"), (Map<String, Object>) d);
                }
            } catch (Exception e) {
                System.out.println("Open Library: falha na busca '" + nome + "' " + extra + ": " + e.getMessage());
            }
        }
        return ordenarPorRelevancia(nome, new ArrayList<>(porChave.values()));
    }

    static List<Map<String, Object>> ordenarPorRelevancia(String nome, List<Map<String, Object>> docs) {
        java.util.function.ToIntFunction<Map<String, Object>> parecido = d -> Math.max(
                RecomendacaoController.semelhanca(nome, (String) d.get("title")),
                RecomendacaoController.semelhanca(nome, tituloEmPortugues(d)));
        java.util.function.ToIntFunction<Map<String, Object>> edicoes = d -> d.get("edition_count") instanceof Number n ? n.intValue() : 0;
        boolean algumParecido = docs.stream().anyMatch(d -> parecido.applyAsInt(d) > 0);
        return docs.stream()
                .filter(d -> !algumParecido || parecido.applyAsInt(d) > 0)
                .sorted(Comparator.comparingInt(parecido).reversed().thenComparing(Comparator.comparingInt(edicoes).reversed()))
                .toList();
    }

    private static Map<?, ?> edicaoPt(Map<String, Object> doc) {
        if (doc.get("editions") instanceof Map<?, ?> ed && ed.get("docs") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> e
                && e.get("language") instanceof List<?> idiomas && idiomas.contains("por")) return e;
        return null;
    }

    static String tituloEmPortugues(Map<String, Object> doc) {
        Map<?, ?> e = edicaoPt(doc);
        return arrumarCaixa(e != null && e.get("title") instanceof String t && !t.isBlank() ? t.strip() : (String) doc.get("title"));
    }

    private static String capa(Map<String, Object> doc) {
        Map<?, ?> e = edicaoPt(doc);
        Object id = e != null && e.get("cover_i") != null ? e.get("cover_i") : doc.get("cover_i");
        return id == null ? null : "https://covers.openlibrary.org/b/id/" + id + "-L.jpg";
    }

    @SuppressWarnings("unchecked")
    private String descricao(Map<String, Object> doc) {
        if (!(doc.get("key") instanceof String chave)) return null;
        try {
            Map<String, Object> w = carteiro.getForObject(java.net.URI.create(BASE + chave + ".json"), Map.class);
            Object d = w == null ? null : w.get("description");
            String texto = d instanceof Map<?, ?> m ? (String) m.get("value") : d instanceof String s ? s : null;
            return limparDescricao(texto);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tira a parte de "fontes"/links que o Open Library costuma pôr no fim da descrição (formato Markdown). */
    static String limparDescricao(String texto) {
        if (texto == null) return null;
        String t = texto.split("\\n\\s*-{3,}")[0]                       // "----------" separa notas e índices
                .replaceAll("(?m)^\\s*\\[\\d+]:\\s*\\S+.*$", "")       // [1]: https://...
                .replaceAll("\\(\\s*\\[[^]]*]\\s*\\[\\d+]\\s*\\)", "")  // ([source][1])
                .replaceAll("\\[([^]]+)]\\((?:https?://)[^)]*\\)", "$1")  // [texto](url) -> texto
                .replaceAll("\\[([^]]+)]\\[\\d+]", "$1")                 // [texto][1] -> texto
                .replaceAll("[*_]{2}([^*_]+)[*_]{2}", "$1")              // **negrito**
                .replaceAll("\\s+", " ").strip();
        return t.length() < 30 ? null : t;
    }

    /**
     * Converte os assuntos do Open Library em tags. Um livro tem dezenas de assuntos (alguns bem marginais),
     * então cada tag ganha pontos pelas vezes que aparece, valendo mais nos primeiros assuntos; ficam as 4 mais fortes.
     */
    static String generosParaTaxonomia(Object assuntos) {
        Map<String, Double> pontos = new LinkedHashMap<>();
        Map<String, Integer> vezes = new HashMap<>();
        boolean listaLonga = false;
        if (assuntos instanceof List<?> lista) {
            listaLonga = lista.size() > 30;
            int i = 0;
            for (Object o : lista.stream().limit(60).toList()) {
                String a = String.valueOf(o).toLowerCase(Locale.ROOT);
                double peso = 1.0 / (1 + i++ / 10.0);   // os 10 primeiros valem 1, os 10 seguintes 0,5...
                Set<String> nesteAssunto = new LinkedHashSet<>();
                for (Map.Entry<String, String> e : ASSUNTOS.entrySet()) {
                    if (a.contains(e.getKey())) nesteAssunto.add(e.getValue());
                }
                nesteAssunto.forEach(tag -> { pontos.merge(tag, peso, Double::sum); vezes.merge(tag, 1, Integer::sum); });
            }
        }
        // Em listas longas (Harry Potter tem 108 assuntos, entre eles "Vampires"), algo citado uma vez só é ruído
        if (listaLonga) pontos.keySet().removeIf(tag -> vezes.getOrDefault(tag, 0) < 2);
        return pontos.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())   // ordenação estável: empate mantém a ordem de chegada
                .limit(IaService.MAX_TAGS).map(Map.Entry::getKey)
                .reduce((x, y) -> x + ", " + y).orElse("");
    }

    static Map<String, Object> loja(String nome, String modalidade, String link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", nome);
        m.put("modalidade", modalidade);
        m.put("link", link);
        return m;
    }
}
