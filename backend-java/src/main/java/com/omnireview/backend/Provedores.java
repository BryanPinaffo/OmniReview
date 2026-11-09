package com.omnireview.backend;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Monta a lista "onde assistir" a partir dos provedores do TMDB (dados do JustWatch) para o Brasil.
 * O TMDB não fornece o link direto de cada serviço; para os principais, abrimos a busca do próprio
 * serviço já com o título; para os demais, a página do TMDB com todas as opções.
 */
final class Provedores {

    private Provedores() {}

    // Ordem de exibição: primeiro o que já está incluso, depois aluguel/compra
    private static final Map<String, String> MODALIDADES = new LinkedHashMap<>();
    static {
        MODALIDADES.put("flatrate", "Assinatura");
        MODALIDADES.put("free", "Grátis");
        MODALIDADES.put("ads", "Grátis com anúncios");
        MODALIDADES.put("rent", "Aluguel");
        MODALIDADES.put("buy", "Compra");
    }
    static final int MAXIMO = 10;

    // Trecho do nome (normalizado) -> busca do serviço; a ordem importa ("amazon channel" antes de outros nomes)
    private static final Map<String, String> BUSCAS = new LinkedHashMap<>();
    static {
        BUSCAS.put("amazon", "https://www.primevideo.com/search/ref=atv_nb_sr?phrase=");
        BUSCAS.put("prime video", "https://www.primevideo.com/search/ref=atv_nb_sr?phrase=");
        BUSCAS.put("netflix", "https://www.netflix.com/search?q=");
        BUSCAS.put("disney", "https://www.disneyplus.com/pt-br/search?q=");
        BUSCAS.put("apple tv", "https://tv.apple.com/br/search?term=");
        BUSCAS.put("google play", "https://play.google.com/store/search?c=movies&q=");
        BUSCAS.put("globoplay", "https://globoplay.globo.com/busca/?q=");
        BUSCAS.put("crunchyroll", "https://www.crunchyroll.com/pt-br/search?q=");
        BUSCAS.put("youtube", "https://www.youtube.com/results?search_query=");
        BUSCAS.put("mubi", "https://mubi.com/pt/search/films?query=");
    }

    /** Lista de {nome, logo, modalidade, link} sem repetir serviço; vazia se não houver dados para o Brasil. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> deTmdb(Map<String, Object> brasil, String titulo) {
        if (brasil == null) return List.of();
        String paginaTmdb = brasil.get("link") instanceof String l ? l : null;

        // provider_id -> dados, juntando as modalidades em que o mesmo serviço aparece
        Map<Object, Map<String, Object>> porId = new LinkedHashMap<>();
        Map<Object, Set<String>> modalidades = new HashMap<>();
        MODALIDADES.forEach((chave, rotulo) -> {
            if (!(brasil.get(chave) instanceof List<?> itens)) return;
            for (Object o : itens) {
                if (!(o instanceof Map<?, ?> p) || !(p.get("provider_name") instanceof String nome)) continue;
                Object id = p.get("provider_id") != null ? p.get("provider_id") : nome;
                porId.computeIfAbsent(id, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nome", nome);
                    if (p.get("logo_path") instanceof String logo && !logo.isBlank()) m.put("logo", "https://image.tmdb.org/t/p/w92" + logo);
                    m.put("link", linkPara(nome, titulo, paginaTmdb));
                    return m;
                });
                modalidades.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(rotulo);
            }
        });

        Set<String> nomes = new HashSet<>();
        porId.values().forEach(m -> nomes.add(normalizar((String) m.get("nome"))));
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Map.Entry<Object, Map<String, Object>> e : porId.entrySet()) {
            String nome = (String) e.getValue().get("nome");
            // "HBO Max Amazon Channel" e "Netflix Standard with Ads" são redundantes quando o próprio serviço já está na lista
            String base = semVariante(nome);
            if (!base.equals(normalizar(nome)) && nomes.contains(base)) continue;
            Set<String> mods = modalidades.get(e.getKey());
            e.getValue().put("modalidade", mods.equals(Set.of("Aluguel", "Compra")) ? "Aluguel ou compra" : String.join(", ", mods));
            lista.add(e.getValue());
            if (lista.size() >= MAXIMO) break;
        }
        return lista;
    }

    static String semVariante(String nome) {
        return normalizar(nome)
                .replaceAll("\\s+(amazon|apple tv)\\s+channel$", "")
                .replaceAll("\\s+(standard|basic|basico)?\\s*(with|com)\\s+(ads|anuncios)$", "");
    }

    static String linkPara(String nomeServico, String titulo, String reserva) {
        String n = normalizar(nomeServico);
        for (Map.Entry<String, String> b : BUSCAS.entrySet()) {
            if (n.contains(b.getKey())) return b.getValue() + URLEncoder.encode(titulo, StandardCharsets.UTF_8);
        }
        return reserva;
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+]+", " ").trim();
    }
}
