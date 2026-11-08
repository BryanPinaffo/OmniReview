package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Busca filmes e séries no TMDB (título, sinopse, gêneros e imagens). */
@Service
public class TmdbService {

    private static final String BASE = "https://api.themoviedb.org/3";
    private static final int MAX_TENDENCIAS = 15;

    // IDs de gênero do TMDB (filmes e séries) -> tags da taxonomia do IaService
    private static final Map<Integer, String> GENEROS_TMDB = Map.ofEntries(
        Map.entry(28, "Ação"), Map.entry(12, "Aventura"), Map.entry(16, "Animação"),
        Map.entry(35, "Comédia"), Map.entry(80, "Crime"), Map.entry(99, "Documentário"),
        Map.entry(18, "Drama"), Map.entry(10751, "Família"), Map.entry(14, "Fantasia"),
        Map.entry(36, "Histórico"), Map.entry(27, "Terror"), Map.entry(10402, "Musical"),
        Map.entry(9648, "Mistério"), Map.entry(10749, "Romance"), Map.entry(878, "Ficção Científica"),
        Map.entry(53, "Suspense"), Map.entry(10752, "Guerra"), Map.entry(37, "Faroeste"),
        Map.entry(10759, "Ação, Aventura"), Map.entry(10762, "Família"), Map.entry(10764, "Reality Show"),
        Map.entry(10765, "Ficção Científica, Fantasia"), Map.entry(10766, "Telenovela"),
        Map.entry(10767, "Talk Show"), Map.entry(10768, "Guerra, Político")
    );

    private final String apiKey;
    private final RestTemplate carteiro = ClienteHttp.criar(5_000, 15_000);

    public TmdbService(@Value("${omnireview.tmdb.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("AVISO: omnireview.tmdb.api-key não configurada (veja config/segredos.exemplo.properties).");
        }
    }

    /** Retorna {titulo, sinopse, generos, poster, capa} ou null se nada for encontrado. */
    public String[] buscarFilmeNaInternet(String nome) {
        return buscar("movie", "title", nome);
    }

    /** Retorna {titulo, sinopse, generos, poster, capa} ou null se nada for encontrado. */
    public String[] buscarSerieNaInternet(String nome) {
        return buscar("tv", "name", nome);
    }

    public List<String> buscarTendenciasFilmes() {
        return tendencias("movie", "title");
    }

    public List<String> buscarTendenciasSeries() {
        return tendencias("tv", "name");
    }

    @SuppressWarnings("unchecked")
    private String[] buscar(String tipoTmdb, String campoTitulo, String nome) {
        try {
            Map<String, Object> resposta = carteiro.getForObject(
                    ClienteHttp.uri(BASE + "/search/" + tipoTmdb, Map.of("query", nome, "api_key", apiKey, "language", "pt-BR")),
                    Map.class);
            List<Map<String, Object>> resultados = resposta == null ? null : (List<Map<String, Object>>) resposta.get("results");
            if (resultados == null || resultados.isEmpty()) {
                return null;
            }
            Map<String, Object> obra = melhorResultado(resultados);
            String sinopse = sinopseOuIngles((String) obra.get("overview"), tipoTmdb, obra.get("id"));
            return new String[]{
                    (String) obra.get(campoTitulo),
                    sinopse,
                    generosParaTaxonomia(obra.get("genre_ids"), obra.get("original_language")),
                    imagem(obra.get("poster_path"), "w500"),
                    imagem(obra.get("backdrop_path"), "w1280")};
        } catch (Exception e) {
            System.out.println("TMDB: falha ao buscar '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> tendencias(String tipoTmdb, String campoTitulo) {
        Set<String> titulos = new LinkedHashSet<>();
        for (int pagina = 1; pagina <= 3 && titulos.size() < MAX_TENDENCIAS; pagina++) {
            try {
                Map<String, Object> resposta = carteiro.getForObject(ClienteHttp.uri(BASE + "/trending/" + tipoTmdb + "/week",
                        Map.of("api_key", apiKey, "language", "pt-BR", "page", pagina)), Map.class);
                List<Map<String, Object>> resultados = resposta == null ? null : (List<Map<String, Object>>) resposta.get("results");
                if (resultados == null) break;
                for (Map<String, Object> item : resultados) {
                    if (item.get(campoTitulo) instanceof String t && !t.isBlank() && titulos.size() < MAX_TENDENCIAS) {
                        titulos.add(t);
                    }
                }
            } catch (Exception e) {
                System.out.println("TMDB: falha ao buscar tendências: " + e.getMessage());
                break;
            }
        }
        return new ArrayList<>(titulos);
    }

    // Muitas obras não têm sinopse em pt-BR no TMDB: nesse caso usa a versão em inglês
    @SuppressWarnings("unchecked")
    private String sinopseOuIngles(String sinopsePt, String tipoTmdb, Object id) {
        if (sinopsePt != null && !sinopsePt.isBlank()) return sinopsePt;
        if (id == null) return null;
        try {
            Map<String, Object> detalhes = carteiro.getForObject(
                    ClienteHttp.uri(BASE + "/" + tipoTmdb + "/" + id, Map.of("api_key", apiKey, "language", "en-US")), Map.class);
            String overview = detalhes == null ? null : (String) detalhes.get("overview");
            return overview != null && !overview.isBlank() ? overview : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Entre os 5 primeiros resultados, prefere o primeiro que tem pôster (evita itens incompletos)
    private Map<String, Object> melhorResultado(List<Map<String, Object>> resultados) {
        return resultados.stream().limit(5)
                .filter(r -> r.get("poster_path") instanceof String c && !c.isBlank())
                .findFirst()
                .orElse(resultados.get(0));
    }

    private String imagem(Object caminho, String tamanho) {
        return caminho instanceof String c && !c.isBlank() ? "https://image.tmdb.org/t/p/" + tamanho + c : null;
    }

    /** Converte os gêneros do TMDB para a taxonomia; animação de idioma original japonês recebe também "Anime". */
    static String generosParaTaxonomia(Object genreIds, Object idiomaOriginal) {
        Set<String> tags = new LinkedHashSet<>();
        if (genreIds instanceof List<?> ids) {
            for (Object id : ids) {
                if (id instanceof Number n && GENEROS_TMDB.containsKey(n.intValue())) {
                    for (String t : GENEROS_TMDB.get(n.intValue()).split(",\\s*")) {
                        tags.add(t);
                        if (t.equals("Animação") && "ja".equals(idiomaOriginal)) tags.add("Anime");
                    }
                }
            }
        }
        return String.join(", ", tags);
    }
}
