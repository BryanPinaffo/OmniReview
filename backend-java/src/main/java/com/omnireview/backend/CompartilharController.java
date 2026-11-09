package com.omnireview.backend;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Links de compartilhamento: uma obra (/obra?t=<título>) ou uma lista (/lista?c=<código>).
 * Fica fora de /api (não exige login) e só expõe dados públicos do catálogo (título, tipo, sinopse e pôster),
 * para que WhatsApp, Discord, Telegram etc. montem a prévia (Open Graph). No navegador, a página leva
 * direto para a obra no site (/?obra=...); quem não estiver logado passa pelo login e volta para ela.
 */
@RestController
public class CompartilharController {

    static final int MAX_DESCRICAO = 200;

    private final ObraRepository repository;
    private final ListaService listas;

    public CompartilharController(ObraRepository repository, ListaService listas) {
        this.repository = repository;
        this.listas = listas;
    }

    @GetMapping(value = "/obra", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> obra(@RequestParam(name = "t", defaultValue = "") String titulo) {
        Obra obra = titulo.isBlank() ? null : repository.findById(titulo).orElse(null);
        String destino = "/?obra=" + URLEncoder.encode(titulo, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.status(obra == null ? 404 : 200)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(pagina(obra, titulo, destino));
    }

    /** Link "só para ver" de uma lista: /lista?c=<código>. Sem login mostra só a prévia (nome, dono e alguns títulos). */
    @GetMapping(value = "/lista", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> lista(@RequestParam(name = "c", defaultValue = "") String codigo) {
        String destino = "/?lista=" + URLEncoder.encode(codigo, StandardCharsets.UTF_8).replace("+", "%20");
        var previa = listas.previa(codigo);
        String corpo;
        if (previa.isEmpty()) {
            corpo = html("Lista não encontrada | OmniReview", "O link foi desativado pelo dono ou está incorreto.", null, "a lista", destino);
        } else {
            Map<String, Object> p = previa.get();
            long total = ((Number) p.get("total")).longValue();
            @SuppressWarnings("unchecked") List<String> titulos = (List<String>) p.get("titulos");
            String descricao = total == 0 ? "Lista ainda vazia."
                    : total + (total == 1 ? " obra: " : " obras: ") + String.join(", ", titulos) + (total > titulos.size() ? "…" : "");
            corpo = html(p.get("nome") + " · lista de " + p.get("dono") + " | OmniReview", resumir(descricao),
                    (String) p.get("imagem"), String.valueOf(p.get("nome")), destino);
        }
        return ResponseEntity.status(previa.isEmpty() ? 404 : 200)
                .cacheControl(CacheControl.noCache())
                .body(corpo);
    }

    static String pagina(Obra obra, String titulo, String destino) {
        String nome = obra != null ? obra.getTitulo() : titulo;
        String tipo = obra == null ? "" : switch (InfoObraController.tipoDe(obra)) {
            case "jogo" -> "Jogo";
            case "livro" -> "Livro";
            case "serie" -> "Série";
            default -> "Filme";
        };
        String titPagina = obra == null ? "OmniReview" : nome + " (" + tipo + ") | OmniReview";
        String descricao = obra == null ? "Filmes, séries, jogos e livros com o mesmo DNA criativo."
                : resumir(obra.getSinopse() != null && !obra.getSinopse().startsWith("Sem sinopse")
                        ? obra.getSinopse() : tipo + " no OmniReview: veja onde assistir, avaliações e obras parecidas.");
        String imagem = obra == null ? null : (obra.getImagem() != null ? obra.getImagem() : obra.getCapa());
        return html(titPagina, descricao, imagem, nome, destino);
    }

    /** Página mínima com as tags Open Graph (prévia) que leva o navegador para o destino dentro do site. */
    static String html(String titPagina, String descricao, String imagem, String nome, String destino) {
        boolean imagemOk = imagem != null && imagem.startsWith("https://");
        return """
                <!doctype html>
                <html lang="pt-BR">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%1$s</title>
                <meta name="description" content="%2$s">
                <meta property="og:site_name" content="OmniReview">
                <meta property="og:type" content="website">
                <meta property="og:title" content="%1$s">
                <meta property="og:description" content="%2$s">
                %4$s
                <meta name="twitter:card" content="%5$s">
                <link rel="icon" href="/favicon.svg" type="image/svg+xml">
                <meta name="theme-color" content="#08090d">
                <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#08090d;color:#e8e9ef;font:16px system-ui,sans-serif;text-align:center;padding:16px}a{color:#c9c1ff}</style>
                </head>
                <body>
                <p>Abrindo <b>%6$s</b> no OmniReview… <br><a href="%7$s">Toque aqui se não abrir</a></p>
                <script>location.replace(%8$s);</script>
                </body>
                </html>
                """.formatted(
                html(titPagina), html(descricao), "",
                imagemOk ? "<meta property=\"og:image\" content=\"" + html(imagem) + "\">" : "",
                imagemOk ? "summary_large_image" : "summary",
                html(nome), html(destino), jsString(destino));
    }

    static String resumir(String texto) {
        String t = texto.replaceAll("\\s+", " ").strip();
        if (t.length() <= MAX_DESCRICAO) return t;
        int corte = t.lastIndexOf(' ', MAX_DESCRICAO);
        return t.substring(0, corte > 100 ? corte : MAX_DESCRICAO) + "…";
    }

    static String html(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Literal de string JavaScript seguro dentro de <script> (o destino já vem codificado, mas por garantia). */
    static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128 || "/?=%-_.~".indexOf(c) >= 0) b.append(c);
            else b.append(String.format("\\u%04x", (int) c));
        }
        return b.append('"').toString();
    }
}
