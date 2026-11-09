package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Livros pelo Google Books: o maior catálogo de edições brasileiras, com sinopse em português, capa, editora,
 * páginas e categorias. Precisa de uma chave gratuita (omnireview.googlebooks.api-key / OMNIREVIEW_GOOGLEBOOKS_API_KEY);
 * sem ela, ou se a API falhar, o {@link LivroService} usa o Open Library. Depois de uma falha (ex.: cota estourada),
 * fica {@link #PAUSA_APOS_FALHA} sem tentar, como a RAWG nos jogos.
 */
@Service
public class GoogleLivrosService {

    private static final String BASE = "https://www.googleapis.com/books/v1";
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(10);
    static final java.util.regex.Pattern ID_VOLUME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,20}");

    private final String apiKey;
    private final RestTemplate carteiro = ClienteHttp.criar(5_000, 12_000);
    private volatile Instant indisponivelAte = Instant.MIN;

    public GoogleLivrosService(@Value("${omnireview.googlebooks.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        if (this.apiKey.isEmpty()) {
            System.out.println("AVISO: omnireview.googlebooks.api-key não configurada; livros vêm só do Open Library.");
        }
    }

    public boolean disponivel() {
        return !apiKey.isEmpty() && Instant.now().isAfter(indisponivelAte);
    }

    /**
     * Até {@code maximo} livros: {ref ("gb:ID"), titulo, ano, imagem, detalhe (autor · editora), sinopse}.
     * Edições repetidas do mesmo livro (mesmo título e autor) aparecem uma vez só.
     * Devolve null se o Google não estiver disponível (para o chamador usar o Open Library).
     */
    public List<Map<String, Object>> candidatos(String nome, int maximo) {
        List<Map<String, Object>> itens = pesquisar(nome, Math.min(40, maximo * 3));
        if (itens == null) return null;
        List<Map<String, Object>> lista = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (Map<String, Object> item : itens) {
            Map<?, ?> v = info(item);
            if (v == null || !(v.get("title") instanceof String titulo) || titulo.isBlank() || !(item.get("id") instanceof String id)) continue;
            String autor = primeiroAutor(v);
            if (!vistos.add(Provedores.normalizar(titulo) + "|" + Provedores.normalizar(autor))) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ref", "gb:" + id);
            c.put("titulo", titulo.strip());
            String ano = ano(v);
            if (ano != null) c.put("ano", ano);
            c.put("imagem", capa(v));
            String editora = v.get("publisher") instanceof String p && !p.isBlank() ? p : null;
            String detalhe = String.join(" · ", new ArrayList<>(Arrays.asList(autor, editora)).stream().filter(Objects::nonNull).toList());
            if (!detalhe.isBlank()) c.put("detalhe", detalhe);
            String sinopse = limparHtml((String) v.get("description"));
            if (sinopse != null) c.put("sinopse", sinopse);
            lista.add(c);
        }
        // Título igual ao digitado primeiro (a ordem do Google é mantida entre os empatados)
        lista.sort(Comparator.comparingInt((Map<String, Object> c) -> RecomendacaoController.semelhanca(nome, (String) c.get("titulo"))).reversed());
        return lista.size() > maximo ? new ArrayList<>(lista.subList(0, maximo)) : lista;
    }

    /** O livro mais provável para o nome: título igual, com capa e sinopse de preferência. Null se nada/indisponível. */
    public String[] buscar(String nome) {
        List<Map<String, Object>> itens = pesquisar(nome, 10);
        if (itens == null || itens.isEmpty()) return null;
        String alvo = Provedores.normalizar(nome);
        Comparator<Map<String, Object>> qualidade = Comparator
                .comparing((Map<String, Object> i) -> info(i) != null && alvo.equals(Provedores.normalizar((String) info(i).get("title"))))
                .thenComparing(i -> info(i) != null && info(i).get("description") != null)
                .thenComparing(i -> info(i) != null && info(i).get("imageLinks") != null);
        Map<String, Object> melhor = itens.stream().filter(i -> info(i) != null).max(qualidade).orElse(null);
        return melhor == null ? null : dadosDoVolume(melhor);
    }

    /** Livro pelo id do volume no Google Books: {titulo, sinopse, generos, poster, capa, ref, ano}; null se falhar. */
    @SuppressWarnings("unchecked")
    public String[] porId(String id) {
        Map<String, Object> volume = volume(id);
        return volume == null ? null : dadosDoVolume(volume);
    }

    /** Ficha para a janela da obra: autores, editora, ano, páginas, tempo de leitura, nota e onde ler/comprar. */
    public Map<String, Object> ficha(String id, String titulo) {
        Map<String, Object> volume = volume(id);
        Map<?, ?> v = volume == null ? null : info(volume);
        if (v == null) return null;
        Map<String, Object> f = new LinkedHashMap<>();
        if (v.get("authors") instanceof List<?> a && !a.isEmpty())
            f.put("autores", String.join(", ", a.stream().limit(3).map(String::valueOf).toList()));
        if (v.get("publisher") instanceof String p && !p.isBlank()) f.put("editora", p);
        String ano = ano(v);
        if (ano != null) f.put("ano", Integer.parseInt(ano));
        if (v.get("pageCount") instanceof Number n && n.intValue() > 0) {
            f.put("paginas", n.intValue());
            f.put("tempoLeituraHoras", Math.max(1, Math.round(n.intValue() / LivroService.PAGINAS_POR_HORA)));
        }
        if (v.get("averageRating") instanceof Number n && v.get("ratingsCount") instanceof Number c && c.intValue() >= 5)
            f.put("notaGoogle", n.doubleValue());
        String linkGoogle = v.get("infoLink") instanceof String l && l.startsWith("http") ? l.replaceFirst("^http:", "https:") : null;
        if (linkGoogle != null) f.put("linkFicha", linkGoogle);
        String busca = URLEncoder.encode(titulo + (f.get("autores") instanceof String a ? " " + a.split(",")[0] : ""), StandardCharsets.UTF_8);
        List<Map<String, Object>> onde = new ArrayList<>();
        if (linkGoogle != null) onde.add(LivroService.loja("Google Livros", "Prévia e e-book", linkGoogle));
        onde.add(LivroService.loja("Amazon", "Comprar (impresso ou Kindle)", "https://www.amazon.com.br/s?i=stripbooks&k=" + busca));
        onde.add(LivroService.loja("Estante Virtual", "Usados e seminovos", "https://www.estantevirtual.com.br/busca?q=" + URLEncoder.encode(titulo, StandardCharsets.UTF_8)));
        f.put("ondeLer", onde);
        f.put("fonteDados", "Google Books");
        return f;
    }

    // ---------------- auxiliares ----------------

    /** Itens da busca (primeiro só edições em português; se não houver, de qualquer idioma). Null se indisponível. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pesquisar(String nome, int maximo) {
        if (!disponivel() || nome == null || nome.isBlank()) return null;
        try {
            for (boolean soPortugues : new boolean[]{true, false}) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("q", nome);
                p.put("printType", "books");
                p.put("maxResults", Math.max(1, Math.min(40, maximo)));
                if (soPortugues) p.put("langRestrict", "pt");
                p.put("key", apiKey);
                Map<String, Object> r = carteiro.getForObject(ClienteHttp.uri(BASE + "/volumes", p), Map.class);
                List<Map<String, Object>> itens = r == null ? null : (List<Map<String, Object>>) r.get("items");
                if (itens != null && !itens.isEmpty()) return itens;
            }
            return List.of();
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> volume(String id) {
        if (!disponivel() || id == null || !ID_VOLUME.matcher(id).matches()) return null;
        try {
            return carteiro.getForObject(ClienteHttp.uri(BASE + "/volumes/" + id, Map.of("key", apiKey)), Map.class);
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    private void registrarFalha(Exception e) {
        indisponivelAte = Instant.now().plus(PAUSA_APOS_FALHA);
        System.out.println("Google Books indisponível (" + e.getMessage() + "); usando o Open Library pelos próximos "
                + PAUSA_APOS_FALHA.toMinutes() + " min.");
    }

    static String[] dadosDoVolume(Map<String, Object> item) {
        Map<?, ?> v = info(item);
        if (v == null || !(v.get("title") instanceof String titulo)) return null;
        List<String> partes = new ArrayList<>();
        if (v.get("categories") instanceof List<?> cats)
            for (Object c : cats) partes.addAll(Arrays.asList(String.valueOf(c).split("\\s*/\\s*")));
        String capa = capa(v);
        return new String[]{titulo.strip(), limparHtml((String) v.get("description")), LivroService.generosParaTaxonomia(partes),
                capa, capa, "gb:" + item.get("id"), ano(v)};
    }

    private static Map<?, ?> info(Map<String, Object> item) {
        return item != null && item.get("volumeInfo") instanceof Map<?, ?> v ? v : null;
    }

    private static String primeiroAutor(Map<?, ?> v) {
        return v.get("authors") instanceof List<?> a && !a.isEmpty() ? String.valueOf(a.get(0)) : null;
    }

    static String ano(Map<?, ?> v) {
        return v.get("publishedDate") instanceof String d && d.matches("[0-9]{4}.*") ? d.substring(0, 4) : null;
    }

    /** Capa em tamanho bom (300x446): a miniatura padrão do Google tem só 128px de largura. */
    static String capa(Map<?, ?> v) {
        if (!(v.get("imageLinks") instanceof Map<?, ?> links)) return null;
        Object url = links.get("thumbnail") != null ? links.get("thumbnail") : links.get("smallThumbnail");
        if (!(url instanceof String u) || !u.startsWith("http")) return null;
        return u.replaceFirst("^http:", "https:").replace("&edge=curl", "") + "&fife=w480-h720";
    }

    /** A descrição do Google às vezes vem com HTML (<p>, <b>, <br>): vira texto simples. */
    static String limparHtml(String s) {
        if (s == null) return null;
        String t = s.replaceAll("(?i)<br\\s*/?>|</p>", " ").replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
        return t.length() < 30 ? null : t;
    }
}
