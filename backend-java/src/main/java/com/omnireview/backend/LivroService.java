package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Livros pela API pública do Open Library (não precisa de chave).
 * Com lang=pt a busca devolve a edição em português, quando existe (título e capa da edição brasileira).
 * A sinopse vem da "obra" do Open Library, muitas vezes em inglês: o {@link IaService} traduz para o português.
 */
@Service
public class LivroService {

    private static final String BASE = "https://openlibrary.org";
    private static final String CAMPOS = "key,title,author_name,cover_i,first_publish_year,number_of_pages_median,subject,"
            + "ratings_average,ratings_count,editions,editions.title,editions.language,editions.cover_i,editions.key";
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
            {"history", "Histórico"}, {"biography", "Biografia"}, {"autobiography", "Biografia"}, {"memoir", "Biografia"},
            {"war", "Guerra"}, {"adventure", "Aventura"}, {"humor", "Comédia"}, {"vampire", "Vampiros"}, {"zombie", "Zumbi"},
            {"coming of age", "Coming of Age"}, {"psycholog", "Psicológico"}, {"politic", "Político"}, {"mytholog", "Mitologia"},
            {"spy", "Espionagem"}, {"espionage", "Espionagem"}, {"time travel", "Viagem no Tempo"}, {"survival", "Sobrevivência"},
            {"short stories", "Antologia"}, {"juvenile fiction", "Família"}, {"young adult", "Young Adult"},
            {"self-help", "Autoajuda"}, {"self-actualization", "Autoajuda"}, {"success", "Autoajuda"}, {"habit", "Autoajuda"},
            {"business", "Negócios"}, {"finance", "Negócios"}, {"economics", "Negócios"}, {"philosophy", "Filosofia"},
            {"poetry", "Poesia"}, {"classic", "Clássico"}, {"brazilian literature", "Literatura Brasileira"},
            {"literatura brasileira", "Literatura Brasileira"}, {"drama", "Drama"}
        };
        for (String[] p : pares) ASSUNTOS.put(p[0], p[1]);
    }

    private final RestTemplate carteiro;
    private final IaService ia;

    public LivroService(IaService ia) {
        this.ia = ia;
        this.carteiro = ClienteHttp.criar(6_000, 15_000);
        // O Open Library pede um User-Agent que identifique a aplicação
        this.carteiro.getInterceptors().add((pedido, corpo, execucao) -> {
            pedido.getHeaders().set("User-Agent", "OmniReview/1.0 (projeto academico)");
            return execucao.execute(pedido, corpo);
        });
    }

    /**
     * Retorna {titulo, sinopse, generos, capa, capa} ou null se nada for encontrado.
     * A sinopse vem como está (às vezes em inglês): quem for salvar o livro chama {@link #emPortugues(String[])}.
     */
    public String[] buscarLivroNaInternet(String nome) {
        Map<String, Object> doc = buscarDoc(nome);
        if (doc == null) return null;
        String capa = capa(doc);
        return new String[]{tituloEmPortugues(doc), descricao(doc), generosParaTaxonomia(doc.get("subject")), capa, capa};
    }

    /** Traduz a sinopse dos dados de um livro para o português (com o Ollama), se estiver em inglês. */
    public String[] emPortugues(String[] dados) {
        if (dados == null || dados.length < 2 || dados[1] == null) return dados;
        String[] copia = dados.clone();
        copia[1] = ia.traduzirParaPortugues(dados[1]);
        return copia;
    }

    /** Ficha do livro: autores, ano, páginas, tempo de leitura estimado e onde ler/comprar. Null se não encontrado. */
    public Map<String, Object> ficha(String titulo) {
        Map<String, Object> doc = buscarDoc(titulo);
        if (doc == null) return null;
        Map<String, Object> f = new LinkedHashMap<>();
        if (doc.get("author_name") instanceof List<?> a && !a.isEmpty())
            f.put("autores", String.join(", ", a.stream().limit(3).map(String::valueOf).toList()));
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
        return f;
    }

    /** Livros em alta na semana (Open Library), com capa e autor; no máximo {@link #MAX_TENDENCIAS}. */
    @SuppressWarnings("unchecked")
    public List<String> buscarTendencias() {
        Set<String> titulos = new LinkedHashSet<>();
        try {
            Map<String, Object> r = carteiro.getForObject(java.net.URI.create(BASE + "/trending/weekly.json?limit=60"), Map.class);
            if (r != null && r.get("works") instanceof List<?> obras) {
                for (Object o : obras) {
                    if (!(o instanceof Map<?, ?> w) || !(w.get("title") instanceof String t)) continue;
                    boolean temCapa = w.get("cover_i") != null;
                    boolean temAutor = w.get("author_name") instanceof List<?> a && !a.isEmpty();
                    if (temCapa && temAutor && t.length() <= 80 && titulos.size() < MAX_TENDENCIAS) titulos.add(t);
                }
            }
        } catch (Exception e) {
            System.out.println("Open Library: falha ao buscar tendências: " + e.getMessage());
        }
        return new ArrayList<>(titulos);
    }

    // ---------------- auxiliares ----------------

    /** Entre os 5 primeiros resultados: o que tem o título igual (original ou em português); senão, o primeiro com capa. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buscarDoc(String nome) {
        if (nome == null || nome.isBlank()) return null;
        try {
            Map<String, Object> r = carteiro.getForObject(ClienteHttp.uri(BASE + "/search.json",
                    Map.of("q", nome, "lang", "pt", "limit", 5, "fields", CAMPOS)), Map.class);
            if (r == null || !(r.get("docs") instanceof List<?> docs) || docs.isEmpty()) return null;
            String alvo = Provedores.normalizar(nome);
            List<Map<String, Object>> lista = (List<Map<String, Object>>) docs;
            return lista.stream()
                    .filter(d -> alvo.equals(Provedores.normalizar((String) d.get("title"))) || alvo.equals(Provedores.normalizar(tituloEmPortugues(d))))
                    .findFirst()
                    .orElseGet(() -> lista.stream().filter(d -> d.get("cover_i") != null).findFirst().orElse(lista.get(0)));
        } catch (Exception e) {
            System.out.println("Open Library: falha ao buscar '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    private static Map<?, ?> edicaoPt(Map<String, Object> doc) {
        if (doc.get("editions") instanceof Map<?, ?> ed && ed.get("docs") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> e
                && e.get("language") instanceof List<?> idiomas && idiomas.contains("por")) return e;
        return null;
    }

    static String tituloEmPortugues(Map<String, Object> doc) {
        Map<?, ?> e = edicaoPt(doc);
        return e != null && e.get("title") instanceof String t && !t.isBlank() ? t.strip() : (String) doc.get("title");
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

    private static Map<String, Object> loja(String nome, String modalidade, String link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", nome);
        m.put("modalidade", modalidade);
        m.put("link", link);
        return m;
    }
}
