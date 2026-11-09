package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecomendacaoControllerTest {

    private static Obra obra(String titulo, String tipo) {
        return new Obra(titulo, tipo, "Ação");
    }

    @Test
    void intercalaMidiasMantendoAOrdemDoRanking() {
        List<Obra> ranking = List.of(
                obra("F1", "Filme"), obra("F2", "Filme"), obra("F3", "Filme"),
                obra("S1", "Serie"), obra("J1", "Jogo"), obra("S2", "Serie"));
        List<String> titulos = RecomendacaoController.intercalarPorTipo(ranking, 20).stream().map(o -> o.getTitulo()).toList();
        assertEquals(List.of("F1", "S1", "J1", "F2", "S2", "F3"), titulos);
    }

    @Test
    void respeitaOLimite() {
        List<Obra> ranking = List.of(obra("F1", "Filme"), obra("S1", "Serie"), obra("J1", "Jogo"), obra("F2", "Filme"));
        assertEquals(2, RecomendacaoController.intercalarPorTipo(ranking, 2).size());
    }

    @Test
    void chaveDeBuscaIgnoraAcentosMaiusculasEPontuacao() {
        assertEquals("pokemon a serie", RecomendacaoController.chaveDeBusca("  Pokémon: A Série! "));
        assertEquals("", RecomendacaoController.chaveDeBusca(null));
    }

    @Test
    void perguntaQuandoMaisDeUmaMidiaTemOTituloIgual() {
        // filme, série, jogo, livro
        assertEquals(List.of(0, 3), RecomendacaoController.empatados("duna", Arrays.asList("Duna", "Duna: A Profecia", "Dune: Awakening", "Duna")));
        assertEquals(List.of(1, 2, 3), RecomendacaoController.empatados("the witcher",
                Arrays.asList(null, "The Witcher", "The Witcher", "The Witcher")));
        // títulos que começam com o que foi digitado também empatam (força 2)
        assertEquals(List.of(0, 3), RecomendacaoController.empatados("harry potter",
                Arrays.asList("Harry Potter e a Pedra Filosofal", null, "Hogwarts Legacy", "Harry Potter e a Pedra Filosofal")));
    }

    @Test
    void sufixoDeMidiaSoSeparaTitulosIguais() {
        assertEquals("Duna", RecomendacaoController.semSufixoDeMidia("Duna (Filme)"));
        assertEquals("Duna", RecomendacaoController.semSufixoDeMidia("Duna (Livro)"));
        assertEquals("Duna", RecomendacaoController.semSufixoDeMidia("Duna (1984)"));
        assertEquals("Duna", RecomendacaoController.semSufixoDeMidia("Duna (Filme, 1984)"));
        assertEquals("Blade Runner 2049", RecomendacaoController.semSufixoDeMidia("Blade Runner 2049"));
        assertEquals("O Livro (Filme) da Vida", RecomendacaoController.semSufixoDeMidia("O Livro (Filme) da Vida"));
    }

    @Test
    void buscaDaSteamIgnoraDlcETrilhaSonora() {
        assertTrue(SteamService.pareceExtra("Monster Hunter Wilds - Pingente: Pompom Duna F."));
        assertTrue(SteamService.pareceExtra("Dune: Awakening Soundtrack"));
        assertTrue(SteamService.pareceExtra("Cyberpunk 2077 Season Pass"));
        assertFalse(SteamService.pareceExtra("Dune: Awakening"));
        assertFalse(SteamService.pareceExtra("Hollow Knight"));
    }

    @Test
    void identificadorDaFonteSoAceitaFormatosConhecidos() {
        assertEquals("Filme", RecomendacaoController.tipoDaRef("tmdb:movie:438631"));
        assertEquals("Serie", RecomendacaoController.tipoDaRef("tmdb:tv:100088"));
        assertEquals("Jogo", RecomendacaoController.tipoDaRef("rawg:3498"));
        assertEquals("Jogo", RecomendacaoController.tipoDaRef("steam:1245620"));
        assertEquals("Livro", RecomendacaoController.tipoDaRef("ol:/works/OL893415W"));
        // nada de endereços, caminhos ou parâmetros extras (evita usar o servidor para acessar outros sites)
        for (String ruim : List.of("tmdb:movie:1/../../x", "ol:/works/OL1W?x=1", "http://site", "steam:1;2", "rawg:", "tmdb:person:1", "")) {
            assertEquals(null, RecomendacaoController.tipoDaRef(ruim), ruim);
        }
    }

    @Test
    void naoPerguntaQuandoUmaMidiaEClaramenteMelhor() {
        assertEquals(List.of(0), RecomendacaoController.empatados("duna", Arrays.asList("Duna", "Duna: A Profecia", "Dune: Awakening", null)));
        assertEquals(List.of(), RecomendacaoController.empatados("xyz", Arrays.asList("Abc", null, "Def", "Ghi")));
        // empate fraco (só "contém") não vira pergunta
        assertEquals(List.of(), RecomendacaoController.empatados("anel", Arrays.asList("O Senhor dos Anéis: O Anel", null, null, "O Anel Perdido")));
    }

    @Test
    void escolheOTituloMaisParecidoEntreAsMidias() {
        // filme, série, jogo
        assertEquals(0, RecomendacaoController.escolherMaisParecido("duna", Arrays.asList("Duna", "Duna: A Profecia", "Dune: Awakening")));
        assertEquals(2, RecomendacaoController.escolherMaisParecido("elden ring", Arrays.asList("O Anel", null, "Elden Ring")));
        assertEquals(1, RecomendacaoController.escolherMaisParecido("the last of us", Arrays.asList("Os Últimos", "The Last of Us", "The Last of Us Part I")));
        // nada parecido: fica com a primeira mídia que encontrou algo
        assertEquals(1, RecomendacaoController.escolherMaisParecido("xyz", Arrays.asList(null, "Abc", "Def")));
        assertEquals(-1, RecomendacaoController.escolherMaisParecido("xyz", Arrays.asList(null, null, null)));
    }

    @Test
    void listaVaziaGeraResultadoVazio() {
        assertEquals(List.of(), RecomendacaoController.intercalarPorTipo(List.of(), 20));
    }
}
