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
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(30);   // a RAWG passou dias fora do ar: não fazer cada visitante esperar

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
                    if (detalhes != null) return dadosDoJogo(detalhes, id);
                }
            } catch (Exception e) {
                registrarFalha(e);
            }
        }
        return steamService.buscarJogo(nome);
    }

    private String[] dadosDoJogo(Map<String, Object> detalhes, Object id) {
        String fundo = (String) detalhes.get("background_image");
        String lancamento = (String) detalhes.get("released");
        return new String[]{(String) detalhes.get("name"), (String) detalhes.get("description_raw"),
                generosParaTaxonomia(detalhes.get("genres")), fundo, fundo, "rawg:" + id,
                lancamento != null && lancamento.length() >= 4 ? lancamento.substring(0, 4) : null};
    }

    /** Até {@code maximo} jogos da busca (RAWG; se ela estiver fora do ar, Steam): {ref, titulo, ano, imagem}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> candidatos(String nome, int maximo) {
        if (rawgDisponivel() && apiKey != null && !apiKey.isBlank()) {
            try {
                Map<String, Object> busca = carteiro.getForObject(
                        ClienteHttp.uri(BASE + "/games", Map.of("search", nome, "key", apiKey, "page_size", maximo)), Map.class);
                List<Map<String, Object>> resultados = busca == null ? null : (List<Map<String, Object>>) busca.get("results");
                if (resultados != null) {
                    List<Map<String, Object>> lista = new ArrayList<>();
                    for (Map<String, Object> r : resultados) {
                        if (lista.size() >= maximo) break;
                        if (r.get("id") == null || !(r.get("name") instanceof String n) || n.isBlank()) continue;
                        Map<String, Object> c = new java.util.LinkedHashMap<>();
                        c.put("ref", "rawg:" + r.get("id"));
                        c.put("titulo", n);
                        if (r.get("released") instanceof String d && d.length() >= 4) c.put("ano", d.substring(0, 4));
                        c.put("imagem", r.get("background_image"));
                        lista.add(c);
                    }
                    return lista;
                }
            } catch (Exception e) {
                registrarFalha(e);
            }
        }
        return steamService.candidatos(nome, maximo);
    }

    /** Jogo pelo id da RAWG: {titulo, sinopse, generos, poster, capa, ref, ano}; null se falhar. */
    @SuppressWarnings("unchecked")
    public String[] porId(long id) {
        try {
            Map<String, Object> detalhes = carteiro.getForObject(ClienteHttp.uri(BASE + "/games/" + id, Map.of("key", apiKey)), Map.class);
            return detalhes == null ? null : dadosDoJogo(detalhes, id);
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
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

    /**
     * Ficha de um jogo: tempo médio de jogo (horas, média dos jogadores da RAWG) e lojas onde comprar/jogar.
     * Se a RAWG falhar, devolve ao menos o link da Steam. Retorna null se nada for encontrado.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fichaJogo(String nome) {
        if (rawgDisponivel() && apiKey != null && !apiKey.isBlank()) {
            try {
                Map<String, Object> busca = carteiro.getForObject(
                        ClienteHttp.uri(BASE + "/games", Map.of("search", nome, "key", apiKey, "page_size", 5)), Map.class);
                List<Map<String, Object>> resultados = busca == null ? null : (List<Map<String, Object>>) busca.get("results");
                if (resultados != null && !resultados.isEmpty()) {
                    String alvo = Provedores.normalizar(nome);
                    Map<String, Object> jogo = resultados.stream()
                            .filter(r -> alvo.equals(Provedores.normalizar(String.valueOf(r.get("name")))))
                            .findFirst().orElse(resultados.get(0));
                    Object id = jogo.get("id");
                    Map<String, Object> d = carteiro.getForObject(ClienteHttp.uri(BASE + "/games/" + id, Map.of("key", apiKey)), Map.class);
                    if (d != null) {
                        Map<String, Object> ficha = new java.util.LinkedHashMap<>();
                        if (d.get("playtime") instanceof Number n && n.intValue() > 0) ficha.put("tempoMedioHoras", n.intValue());
                        if (d.get("slug") instanceof String slug) ficha.put("linkFicha", "https://rawg.io/games/" + slug);
                        ficha.put("fonte", "RAWG");
                        ficha.put("ondeJogar", lojas(id, d.get("stores")));
                        return ficha;
                    }
                }
            } catch (Exception e) {
                registrarFalha(e);
            }
        }
        String steam = steamService.linkDaLoja(nome);
        if (steam == null) return null;
        Map<String, Object> ficha = new java.util.LinkedHashMap<>();
        ficha.put("fonte", "Steam");
        ficha.put("ondeJogar", List.of(Map.of("nome", "Steam", "modalidade", "Loja", "link", steam)));
        return ficha;
    }

    // Lojas do jogo: os nomes vêm nos detalhes; os links diretos, em /games/{id}/stores
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lojas(Object idJogo, Object lojasDetalhes) {
        Map<Object, String> links = new java.util.HashMap<>();
        try {
            Map<String, Object> r = carteiro.getForObject(ClienteHttp.uri(BASE + "/games/" + idJogo + "/stores", Map.of("key", apiKey)), Map.class);
            if (r != null && r.get("results") instanceof List<?> itens) {
                for (Object o : itens) {
                    if (o instanceof Map<?, ?> m && m.get("url") instanceof String url && url.startsWith("http")) {
                        links.put(String.valueOf(m.get("store_id")), url);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("RAWG: sem links das lojas (" + e.getMessage() + ")");
        }
        List<Map<String, Object>> lista = new ArrayList<>();
        if (lojasDetalhes instanceof List<?> itens) {
            for (Object o : itens) {
                if (!(o instanceof Map<?, ?> item) || !(item.get("store") instanceof Map<?, ?> loja) || !(loja.get("name") instanceof String nomeLoja)) continue;
                String link = links.get(String.valueOf(loja.get("id")));
                if (link == null && loja.get("domain") instanceof String dominio && !dominio.isBlank()) link = "https://" + dominio;
                if (link == null) continue;
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("nome", nomeLoja);
                m.put("modalidade", "Loja");
                m.put("link", link);
                lista.add(m);
            }
        }
        return lista;
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
