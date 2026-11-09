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
        // descrição de jogo da Steam sem versão em português (agora também é traduzida)
        assertTrue(IaService.pareceIngles("Jump and fly your way through danger in this rhythm-based action platformer! Prepare for a near "
                + "impossible challenge in the world of Geometry Dash. Push your skills to the limit as you jump, fly and flip your way "
                + "through dangerous passages and spiky obstacles."));
        assertFalse(IaService.pareceIngles("Cyberpunk 2077 é um RPG de ação e aventura em mundo aberto que se passa em Night City, "
                + "uma megalópole obcecada por poder, glamour e modificações corporais."));
        // sinopses de uma frase só
        assertTrue(IaService.pareceIngles("Jump and fly your way through danger in this rhythm-based action platformer!"));
        assertTrue(IaService.pareceIngles("Times change. Technology changes. And so THE RULES must be updated. From work to style, "
                + "these are the things successful men need to know."));
        // português com nome em inglês continua português
        assertFalse(IaService.pareceIngles("A experiência Call of Duty é compatível com Modern Warfare 4, Black Ops 7 e Warzone."));
        assertFalse(IaService.pareceIngles("Em The Last of Us, Joel e Ellie atravessam um país destruído por uma pandemia."));
        assertFalse(IaService.pareceIngles("Deadlock é um jogo multijogador em fase inicial de desenvolvimento."));
    }

    @Test
    void tituloTodoEmMaiusculasViraTituloNormal() {
        assertEquals("O Alquimista", LivroService.arrumarCaixa("O ALQUIMISTA"));
        assertEquals("Em Chamas - Os Jogos da Fome - Livro II", LivroService.arrumarCaixa("EM CHAMAS - OS JOGOS DA FOME - LIVRO II"));
        assertEquals("A Revolução Civil", LivroService.arrumarCaixa("A REVOLUÇÃO CIVIL"));
        assertEquals("Hábitos Atômicos", LivroService.arrumarCaixa("Hábitos Atômicos"));   // já normal: não mexe
        assertEquals("1984", LivroService.arrumarCaixa("1984"));
        assertEquals("IT", LivroService.arrumarCaixa("IT"));                             // curto demais para saber
        assertEquals("嫌われる勇気", LivroService.arrumarCaixa("嫌われる勇気"));
    }

    @Test
    void topDeLivrosSoAceitaObraComEdicaoEmPortuguesEVariasEdicoes() {
        Map<String, Object> bom = Map.of("key", "/works/OL17930368W", "title", "Atomic Habits", "cover_i", 1,
                "author_name", List.of("James Clear"), "language", List.of("eng", "por"), "edition_count", 42);
        assertTrue(LivroService.serveParaEmAlta(bom));

        Map<String, Object> semTraducao = new java.util.HashMap<>(bom);
        semTraducao.put("language", List.of("eng"));
        assertFalse(LivroService.serveParaEmAlta(semTraducao));

        Map<String, Object> edicaoAvulsa = new java.util.HashMap<>(bom);   // ex.: "Esquire's The New Rules for Men"
        edicaoAvulsa.put("edition_count", 1);
        assertFalse(LivroService.serveParaEmAlta(edicaoAvulsa));

        Map<String, Object> semCapa = new java.util.HashMap<>(bom);
        semCapa.remove("cover_i");
        assertFalse(LivroService.serveParaEmAlta(semCapa));
    }
}
