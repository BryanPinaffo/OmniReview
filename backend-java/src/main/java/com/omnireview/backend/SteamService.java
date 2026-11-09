package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Fonte de jogos pela API pública da loja Steam (não precisa de chave).
 * Usada quando a RAWG está fora do ar.
 */
@Service
public class SteamService {

    private static final String BASE = "https://store.steampowered.com/api";
    private static final String IDIOMA = "l=brazilian&cc=BR";

    // Gêneros/categorias da Steam (normalizados, sem acento) -> tags da taxonomia do IaService
    private static final Map<String, String> GENEROS_STEAM = Map.ofEntries(
        Map.entry("acao", "Ação"), Map.entry("action", "Ação"),
        Map.entry("aventura", "Aventura"), Map.entry("adventure", "Aventura"),
        Map.entry("rpg", "RPG"),
        Map.entry("estrategia", "Estratégia"), Map.entry("strategy", "Estratégia"),
        Map.entry("simulacao", "Simulação"), Map.entry("simulation", "Simulação"),
        Map.entry("casual", "Casual"), Map.entry("indie", "Indie"),
        Map.entry("esportes", "Esporte"), Map.entry("sports", "Esporte"),
        Map.entry("corrida", "Corrida"), Map.entry("racing", "Corrida"),
        Map.entry("multijogador massivo", "MMO"), Map.entry("massively multiplayer", "MMO"),
        Map.entry("multijogador", "Multijogador"), Map.entry("multi player", "Multijogador"),
        Map.entry("cooperativo", "Cooperativo"), Map.entry("co op", "Cooperativo"),
        Map.entry("jxj", "PvP"), Map.entry("pvp", "PvP")
    );

    private final RestTemplate carteiro = ClienteHttp.criar(8_000, 15_000);

    /** Retorna {titulo, sinopse, generos, poster, capa} ou null. */
    @SuppressWarnings("unchecked")
    public String[] buscarJogo(String nome) {
        try {
            String urlBusca = BASE + "/storesearch/?term=" + URLEncoder.encode(nome, StandardCharsets.UTF_8) + "&" + IDIOMA;
            Map<String, Object> busca = carteiro.getForObject(java.net.URI.create(urlBusca), Map.class);
            List<Map<String, Object>> itens = busca == null ? null : (List<Map<String, Object>>) busca.get("items");
            if (itens == null || itens.isEmpty()) {
                return null;
            }
            Object id = itens.get(0).get("id");

            Map<String, Object> resposta = carteiro.getForObject(
                    java.net.URI.create(BASE + "/appdetails?appids=" + id + "&" + IDIOMA), Map.class);
            Map<String, Object> app = resposta == null ? null : (Map<String, Object>) resposta.get(String.valueOf(id));
            if (app == null || !Boolean.TRUE.equals(app.get("success"))) {
                return null;
            }
            Map<String, Object> dados = (Map<String, Object>) app.get("data");

            String titulo = limparTitulo((String) dados.get("name"));
            String sinopse = limparHtml((String) dados.get("short_description"));
            if (sinopse.isBlank()) {
                sinopse = limitar(limparHtml((String) dados.get("about_the_game")), 1500);
            }

            Set<String> tags = new LinkedHashSet<>();
            adicionarTags(tags, dados.get("genres"));
            adicionarTags(tags, dados.get("categories"));

            String capa = (String) dados.get("header_image");
            String poster = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + id + "/library_600x900.jpg";
            return new String[]{titulo, sinopse, String.join(", ", tags), poster, capa};
        } catch (Exception e) {
            System.out.println("Steam: falha ao buscar o jogo '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    /** Link da página do jogo na loja Steam, ou null se não encontrado. */
    @SuppressWarnings("unchecked")
    public String linkDaLoja(String nome) {
        try {
            String urlBusca = BASE + "/storesearch/?term=" + URLEncoder.encode(nome, StandardCharsets.UTF_8) + "&" + IDIOMA;
            Map<String, Object> busca = carteiro.getForObject(java.net.URI.create(urlBusca), Map.class);
            List<Map<String, Object>> itens = busca == null ? null : (List<Map<String, Object>>) busca.get("items");
            if (itens == null || itens.isEmpty() || itens.get(0).get("id") == null) return null;
            return "https://store.steampowered.com/app/" + itens.get(0).get("id");
        } catch (Exception e) {
            System.out.println("Steam: falha ao buscar a loja de '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    /** Mais vendidos + promoções da Steam (só jogos, sem pacotes/DLCs). */
    @SuppressWarnings("unchecked")
    public List<String> buscarTendencias(int limite) {
        Set<String> titulos = new LinkedHashSet<>();
        try {
            Map<String, Object> resposta = carteiro.getForObject(
                    java.net.URI.create(BASE + "/featuredcategories?" + IDIOMA), Map.class);
            for (String secao : List.of("top_sellers", "specials")) {
                Map<String, Object> bloco = (Map<String, Object>) resposta.get(secao);
                if (bloco == null || !(bloco.get("items") instanceof List<?> itens)) {
                    continue;
                }
                for (Object o : itens) {
                    Map<String, Object> item = (Map<String, Object>) o;
                    String nome = limparTitulo((String) item.get("name"));
                    boolean ehApp = item.get("type") instanceof Number t && t.intValue() == 0;
                    boolean ehDlc = nome.contains(" - ") || nome.toLowerCase().contains("temporada")
                            || nome.toLowerCase().contains("soundtrack");
                    if (ehApp && !ehDlc && !nome.isBlank() && titulos.size() < limite) {
                        titulos.add(nome);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Steam: falha ao buscar tendências: " + e.getMessage());
        }
        return new ArrayList<>(titulos);
    }

    private void adicionarTags(Set<String> tags, Object lista) {
        if (!(lista instanceof List<?> itens)) {
            return;
        }
        for (Object o : itens) {
            if (o instanceof Map<?, ?> m && m.get("description") instanceof String desc) {
                String tag = GENEROS_STEAM.get(normalizar(desc));
                if (tag != null) {
                    tags.add(tag);
                }
            }
        }
    }

    private static String limparTitulo(String s) {
        return s == null ? "" : s.replaceAll("[®™©]", "").replaceAll("\\s+", " ").trim();
    }

    private static String limparHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String normalizar(String s) {
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String limitar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
