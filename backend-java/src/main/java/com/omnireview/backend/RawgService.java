package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Busca jogos na RAWG. Se a RAWG falhar, usa a Steam e deixa de consultar a RAWG
 * por {@link #PAUSA_APOS_FALHA} (disjuntor simples), evitando esperar o timeout a cada jogo.
 */
@Service
public class RawgService {

    private static final String BASE = "https://api.rawg.io/api";
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(10);

    // Gêneros da RAWG (slug) -> tags da taxonomia do IaService
    private static final Map<String, String> GENEROS_RAWG = Map.ofEntries(
        Map.entry("action", "Ação"), Map.entry("adventure", "Aventura"), Map.entry("role-playing-games-rpg", "RPG"),
        Map.entry("shooter", "Shooter"), Map.entry("strategy", "Estratégia"), Map.entry("puzzle", "Puzzle"),
        Map.entry("racing", "Corrida"), Map.entry("sports", "Esporte"), Map.entry("simulation", "Simulação"),
        Map.entry("platformer", "Plataforma"), Map.entry("fighting", "Luta"), Map.entry("casual", "Casual"),
        Map.entry("indie", "Indie"), Map.entry("massively-multiplayer", "MMO"), Map.entry("family", "Família"),
        Map.entry("card", "Cartas Colecionáveis"), Map.entry("arcade", "Retro")
    );

    private final SteamService steamService;
    private final String apiKey;
    private final RestTemplate carteiro = ClienteHttp.criar(4_000, 6_000);
    private volatile Instant rawgIndisponivelAte = Instant.MIN;

    public RawgService(SteamService steamService, @Value("${omnireview.rawg.api-key:}") String apiKey) {
        this.steamService = steamService;
        this.apiKey = apiKey;
    }

    /** Retorna {titulo, sinopse, generos, poster, capa} ou null se nada for encontrado. */
    @SuppressWarnings("unchecked")
    public String[] buscarJogoNaInternet(String nome) {
        if (rawgDisponivel()) {
            try {
                Map<String, Object> busca = carteiro.getForObject(
                        ClienteHttp.uri(BASE + "/games", Map.of("search", nome, "key", apiKey, "page_size", 1)), Map.class);
                List<Map<String, Object>> resultados = busca == null ? null : (List<Map<String, Object>>) busca.get("results");
                if (resultados != null && !resultados.isEmpty()) {
                    Object id = resultados.get(0).get("id");
                    Map<String, Object> detalhes = carteiro.getForObject(
                            ClienteHttp.uri(BASE + "/games/" + id, Map.of("key", apiKey)), Map.class);
                    if (detalhes != null) {
                        String fundo = (String) detalhes.get("background_image");
                        return new String[]{(String) detalhes.get("name"), (String) detalhes.get("description_raw"),
                                generosParaTaxonomia(detalhes.get("genres")), fundo, fundo};
                    }
                }
            } catch (Exception e) {
                registrarFalha(e);
            }
        }
        return steamService.buscarJogo(nome);
    }

    @SuppressWarnings("unchecked")
    public List<String> buscarTendencias() {
        if (rawgDisponivel()) {
            try {
                Map<String, Object> resposta = carteiro.getForObject(
                        ClienteHttp.uri(BASE + "/games", Map.of("key", apiKey, "ordering", "-rating", "page_size", 15)), Map.class);
                List<Map<String, Object>> resultados = resposta == null ? null : (List<Map<String, Object>>) resposta.get("results");
                List<String> titulos = new ArrayList<>();
                if (resultados != null) {
                    for (Map<String, Object> jogo : resultados) titulos.add((String) jogo.get("name"));
                }
                if (!titulos.isEmpty()) return titulos;
            } catch (Exception e) {
                registrarFalha(e);
            }
        }
        return steamService.buscarTendencias(15);
    }

    boolean rawgDisponivel() {
        return Instant.now().isAfter(rawgIndisponivelAte);
    }

    private void registrarFalha(Exception e) {
        rawgIndisponivelAte = Instant.now().plus(PAUSA_APOS_FALHA);
        System.out.println("RAWG indisponível (" + e.getMessage() + "); usando a Steam pelos próximos "
                + PAUSA_APOS_FALHA.toMinutes() + " min.");
    }

    private String generosParaTaxonomia(Object genres) {
        Set<String> tags = new LinkedHashSet<>();
        if (genres instanceof List<?> lista) {
            for (Object g : lista) {
                if (g instanceof Map<?, ?> genero && genero.get("slug") instanceof String slug && GENEROS_RAWG.containsKey(slug)) {
                    tags.add(GENEROS_RAWG.get(slug));
                }
            }
        }
        return String.join(", ", tags);
    }
}
