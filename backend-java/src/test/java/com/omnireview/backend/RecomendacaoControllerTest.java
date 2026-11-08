package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
