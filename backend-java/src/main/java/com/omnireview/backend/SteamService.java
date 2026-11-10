package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Fonte de jogos pela API pública da loja Steam (não precisa de chave): os mais jogados agora (Top 15), a sinopse
 * em português dos jogos de PC e a reserva quando o IGDB está fora do ar ou não conhece o jogo.
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
            return porAppId(String.valueOf(itens.get(0).get("id")));
        } catch (Exception e) {
            System.out.println("Steam: falha ao buscar o jogo '" + nome + "': " + e.getMessage());
            return null;
        }
    }

    /** Até {@code maximo} jogos da busca da loja: {ref ("steam:ID"), titulo, imagem, detalhe}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> candidatos(String nome, int maximo) {
        try {
            String urlBusca = BASE + "/storesearch/?term=" + URLEncoder.encode(nome, StandardCharsets.UTF_8) + "&" + IDIOMA;
            Map<String, Object> busca = carteiro.getForObject(java.net.URI.create(urlBusca), Map.class);
            List<Map<String, Object>> itens = busca == null ? null : (List<Map<String, Object>>) busca.get("items");
            List<Map<String, Object>> lista = new ArrayList<>();
            if (itens == null) return lista;
            for (Map<String, Object> item : itens) {
                if (lista.size() >= maximo) break;
                if (item.get("id") == null || !(item.get("name") instanceof String n) || n.isBlank() || pareceExtra(n)) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("ref", "steam:" + item.get("id"));
                c.put("titulo", limparTitulo(n));
                c.put("imagem", "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + item.get("id") + "/library_600x900.jpg");
                c.put("detalhe", "Loja Steam");
                lista.add(c);
            }
            return lista;
        } catch (Exception e) {
            System.out.println("Steam: falha ao listar '" + nome + "': " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Jogos mais jogados agora na Steam (até {@code maximo}): {ref ("steam:ID"), título}. A lista da Steam mistura programas
     * (ex.: Wallpaper Engine); cada item é conferido na loja e só entram jogos.
     */
    @SuppressWarnings("unchecked")
    public List<String[]> maisJogados(int maximo) {
        List<String[]> lista = new ArrayList<>();
        try {
            Map<String, Object> r = carteiro.getForObject(java.net.URI.create(
                    "https://api.steampowered.com/ISteamChartsService/GetMostPlayedGames/v1/"), Map.class);
            if (!(r != null && r.get("response") instanceof Map<?, ?> resp && resp.get("ranks") instanceof List<?> ranks)) return lista;
            for (Object o : ranks) {
                if (lista.size() >= maximo) break;
                if (!(o instanceof Map<?, ?> rank) || !(rank.get("appid") instanceof Number id)) continue;
                Map<String, Object> app = detalhesDoApp(String.valueOf(id.longValue()));
                if (app == null || !"game".equals(app.get("type")) || ehProgramaNaoJogo(app.get("genres"))) continue;
                lista.add(new String[]{"steam:" + id.longValue(), limparTitulo((String) app.get("name"))});
            }
        } catch (Exception e) {
            System.out.println("Steam: falha ao buscar os mais jogados: " + e.getMessage());
        }
        return lista;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detalhesDoApp(String id) {
        try {
            Map<String, Object> resposta = carteiro.getForObject(java.net.URI.create(BASE + "/appdetails?appids=" + id + "&" + IDIOMA), Map.class);
            Map<String, Object> app = resposta == null ? null : (Map<String, Object>) resposta.get(id);
            return app != null && Boolean.TRUE.equals(app.get("success")) ? (Map<String, Object>) app.get("data") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Gêneros de software da Steam (ids 51 a 60: utilitários, edição de vídeo/foto/áudio, design...). */
    static boolean ehProgramaNaoJogo(Object genres) {
        if (!(genres instanceof List<?> lista)) return false;
        for (Object g : lista) {
            if (g instanceof Map<?, ?> m && m.get("id") != null) {
                try {
                    int id = Integer.parseInt(String.valueOf(m.get("id")));
                    if (id >= 51 && id <= 60) return true;
                } catch (NumberFormatException ignorado) { /* id não numérico: não é de software */ }
            }
        }
        return false;
    }

    /** DLC, trilha sonora, pacote ou item cosmético (a busca da loja mistura tudo com os jogos). */
    static boolean pareceExtra(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        return n.contains(" - ") || n.contains(" – ") || n.matches(".*\\b(dlc|soundtrack|trilha sonora|ost|season pass|passe de temporada|pack|pacote|bundle|expansion|expansão|artbook|demo)\\b.*");
    }

    /** Jogo pelo id da Steam: {titulo, sinopse, generos, poster, capa, ref, ano}; null se não existir. */
    @SuppressWarnings("unchecked")
    public String[] porAppId(String id) {
        if (id == null || !id.matches("[0-9]{1,10}")) return null;
        try {
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
            String ano = null;
            if (dados.get("release_date") instanceof Map<?, ?> rd && rd.get("date") instanceof String data) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)[0-9]{2}").matcher(data);
                if (m.find()) ano = m.group();
            }
            return new String[]{titulo, sinopse, String.join(", ", tags), poster, capa, "steam:" + id, ano};
        } catch (Exception e) {
            System.out.println("Steam: falha ao abrir o jogo " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Descrição curta do jogo na loja, se a Steam tiver em português (muitos jogos só têm em inglês); senão null.
     * Usada no lugar do resumo do IGDB, que é sempre em inglês.
     */
    public String sinopseEmPortugues(String appid) {
        if (appid == null || !appid.matches("[0-9]{1,10}")) return null;
        Map<String, Object> app = detalhesDoApp(appid);
        if (app == null) return null;
        String sinopse = limparHtml((String) app.get("short_description"));
        return sinopse.length() >= 40 && !IaService.pareceIngles(sinopse) ? sinopse : null;
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
