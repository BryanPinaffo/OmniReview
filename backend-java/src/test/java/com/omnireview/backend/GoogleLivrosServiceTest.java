package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleLivrosServiceTest {

    /** Um volume como a API do Google Books devolve (campos usados). */
    private static Map<String, Object> volume() {
        return Map.of("id", "s1gVAAAAYAAJ", "volumeInfo", Map.of(
                "title", "Orgulho e Preconceito",
                "authors", List.of("Jane Austen"),
                "publisher", "Martin Claret",
                "publishedDate", "2018-03-01",
                "description", "<p>Elizabeth Bennet e o orgulhoso <b>Sr. Darcy</b>&nbsp;se desentendem<br>até perceberem o que sentem.</p>",
                "pageCount", 424,
                "categories", List.of("Fiction / Romance / Historical", "Fiction / Classics"),
                "imageLinks", Map.of("thumbnail", "http://books.google.com/books/content?id=s1gVAAAAYAAJ&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api")));
    }

    @Test
    void montaOsDadosDoLivroAPartirDoVolume() {
        String[] d = GoogleLivrosService.dadosDoVolume(volume());
        assertEquals("Orgulho e Preconceito", d[0]);
        assertEquals("Elizabeth Bennet e o orgulhoso Sr. Darcy se desentendem até perceberem o que sentem.", d[1]);
        assertTrue(d[2].contains("Romance") && d[2].contains("Histórico") && d[2].contains("Clássico"), d[2]);
        assertEquals("https://books.google.com/books/content?id=s1gVAAAAYAAJ&printsec=frontcover&img=1&zoom=1&source=gbs_api&fife=w480-h720", d[3]);
        assertEquals("gb:s1gVAAAAYAAJ", d[5]);
        assertEquals("2018", d[6]);
        assertEquals("Livro", RecomendacaoController.tipoDaRef(d[5]));
    }

    @Test
    void semCapaNemDescricaoNaoQuebra() {
        String[] d = GoogleLivrosService.dadosDoVolume(Map.of("id", "abcdefgh1234", "volumeInfo", Map.of("title", "Sem Nada")));
        assertEquals("Sem Nada", d[0]);
        assertNull(d[1]);
        assertNull(d[3]);
        assertNull(GoogleLivrosService.dadosDoVolume(Map.of("id", "x")));
    }

    @Test
    void semChaveOGoogleFicaDesligadoESemChamadas() {
        GoogleLivrosService g = new GoogleLivrosService("");
        assertFalse(g.disponivel());
        assertNull(g.candidatos("Duna", 5), "null = use o Open Library");
        assertNull(g.buscar("Duna"));
        assertNull(g.porId("s1gVAAAAYAAJ"));
    }

    @Test
    void idDeVolumeSoComCaracteresValidos() {
        assertTrue(GoogleLivrosService.ID_VOLUME.matcher("s1gVAAAAYAAJ").matches());
        assertFalse(GoogleLivrosService.ID_VOLUME.matcher("../../x").matches());
        assertFalse(GoogleLivrosService.ID_VOLUME.matcher("abc?key=1").matches());
    }
}
