package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LivroServiceTest {

    @Test
    void limpaDescricaoDoOpenLibrary() {
        String bruto = "**Dom Casmurro** é um romance de [Machado de Assis](https://pt.wikipedia.org/x) sobre ciúme ([source][1]).\n"
                + "----------\nContains: algo\n[1]: https://exemplo.com";
        assertEquals("Dom Casmurro é um romance de Machado de Assis sobre ciúme .", LivroService.limparDescricao(bruto));
        assertNull(LivroService.limparDescricao("curta"));
        assertNull(LivroService.limparDescricao(null));
    }

    @Test
    void assuntosViramTagsDaTaxonomia() {
        String tags = LivroService.generosParaTaxonomia(List.of("Fantasy fiction", "Magic", "Wizards", "Juvenile fiction", "Self-help techniques"));
        assertEquals("Fantasia, Família, Autoajuda", tags);
        for (String t : tags.split(", ")) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(t), t);
    }

    @Test
    void emListaLongaDeAssuntosIgnoraOQueApareceUmaVezSo() {
        List<String> assuntos = new java.util.ArrayList<>(List.of("Fiction", "Schools", "Vampires", "Magic", "Wizards", "Magic in fiction",
                "Fantasy fiction", "Juvenile fiction", "Wizards, juvenile fiction"));
        for (int i = 0; i < 30; i++) assuntos.add("Assunto qualquer " + i);
        String tags = LivroService.generosParaTaxonomia(assuntos);
        assertTrue(tags.startsWith("Fantasia"), tags);
        assertFalse(tags.contains("Vampiros"), tags);
        assertTrue(tags.contains("Família"), tags);
    }

    private static Map<String, Object> doc(String titulo, String tituloPt, int edicoes) {
        return Map.of("key", "/works/" + titulo.hashCode(), "title", titulo, "edition_count", edicoes,
                "editions", Map.of("docs", List.of(Map.of("title", tituloPt, "language", List.of("por")))));
    }

    @Test
    void ordenaPelaObraOriginalENaoPeloTituloSolto() {
        // casos reais do Open Library: a edição avulsa de 2016 vinha antes da obra de Jane Austen
        var orgulho = LivroService.ordenarPorRelevancia("Orgulho e Preconceito", new java.util.ArrayList<>(List.of(
                doc("Orgulho e Preconceito", "Orgulho e Preconceito", 1),
                doc("Pride and Prejudice", "Orgulho e Preconceito", 4042),
                doc("Amor Louco Amor", "Amor Louco Amor", 1))));
        assertEquals("Pride and Prejudice", orgulho.get(0).get("title"));
        assertEquals(2, orgulho.size(), "o que não tem nada a ver com o título sai");
        // ordenar só por edições trazia Shakespeare ("Sogno d'una notte") para "Duna"
        var duna = LivroService.ordenarPorRelevancia("Duna", new java.util.ArrayList<>(List.of(
                doc("A Midsummer Night's Dream", "Sogno d'una notte d'estate", 1506),
                doc("Dune", "Duna", 155))));
        assertEquals("Dune", duna.get(0).get("title"));
        assertEquals(1, duna.size());
    }

    @Test
    void usaOTituloDaEdicaoEmPortugues() {
        Map<String, Object> doc = Map.of("title", "Atomic Habits",
                "editions", Map.of("docs", List.of(Map.of("title", "Hábitos Atômicos", "language", List.of("por")))));
        assertEquals("Hábitos Atômicos", LivroService.tituloEmPortugues(doc));
        Map<String, Object> semPt = Map.of("title", "Atomic Habits",
                "editions", Map.of("docs", List.of(Map.of("title", "Hábitos atómicos", "language", List.of("spa")))));
        assertEquals("Atomic Habits", LivroService.tituloEmPortugues(semPt));
    }

    @Test
    void tagsDeLivroSoValemParaLivros() {
        List<String> livro = IaService.taxonomiaPara("Livro");
        assertTrue(livro.contains("Autoajuda"));
        assertTrue(livro.contains("Fantasia"));
        assertFalse(livro.contains("FPS"));
        assertFalse(livro.contains("Anime"));
        assertFalse(livro.contains("Sitcom"));
        for (String tipo : List.of("Filme", "Serie", "Jogo")) assertFalse(IaService.taxonomiaPara(tipo).contains("Autoajuda"), tipo);
        for (String tag : IaService.TAGS_SO_LIVROS) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(tag), tag);
    }

    @Test
    void detectaSinopseEmIngles() {
        assertTrue(IaService.pareceIngles("The boy discovers he is a wizard and is invited to study at a school of magic with his friends."));
        assertFalse(IaService.pareceIngles("O menino descobre que é um bruxo e é convidado para estudar em uma escola de magia com seus amigos."));
    }
}
