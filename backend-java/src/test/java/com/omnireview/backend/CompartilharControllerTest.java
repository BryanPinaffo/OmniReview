package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompartilharControllerTest {

    @Test
    void paginaTemPreviaEscapadaERedirecionaParaAObra() {
        Obra obra = new Obra("Tom & \"Jerry\" <script>", "Filme", "Comédia");
        obra.setSinopse("Gato e rato.");
        obra.setImagem("https://image.tmdb.org/t/p/w500/x.jpg");
        String html = CompartilharController.pagina(obra, obra.getTitulo(), "/?obra=Tom%20%26");

        assertTrue(html.contains("<meta property=\"og:title\" content=\"Tom &amp; &quot;Jerry&quot; &lt;script&gt; (Filme) | OmniReview\">"));
        assertTrue(html.contains("<meta property=\"og:image\" content=\"https://image.tmdb.org/t/p/w500/x.jpg\">"));
        assertTrue(html.contains("og:description\" content=\"Gato e rato.\""));
        assertTrue(html.contains("location.replace(\"/?obra=Tom%20%26\")"));
        assertFalse(html.contains("<script>\""), "título não pode abrir tag");
    }

    @Test
    void obraInexistenteNaoTemImagemEJsNaoEscapaDaString() {
        String html = CompartilharController.pagina(null, "x", "/?obra=a\"</script><b>");
        assertFalse(html.contains("og:image"));
        assertTrue(html.contains("location.replace(\"/?obra=a\\u0022\\u003c/script\\u003e\\u003cb\\u003e\")"));
    }

    @Test
    void resumeSinopseLonga() {
        String longa = "palavra ".repeat(80);
        String r = CompartilharController.resumir(longa);
        assertTrue(r.length() <= CompartilharController.MAX_DESCRICAO + 1 && r.endsWith("…"));
    }
}
