package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IaServiceTest {

    @Test
    void extraiTagsDeRespostaJsonDoLlm() {
        assertEquals(List.of("Ação", "Ficção Científica", "Cyberpunk"),
                IaService.extrairTagsValidas("{\"tags\": [\"Ação\", \"Ficção Científica\", \"Cyberpunk\"]}"));
    }

    @Test
    void ignoraAcentosMaiusculasEPontuacao() {
        assertEquals(List.of("Ficção Científica", "Pós-Apocalíptico", "Beat 'em Up"),
                IaService.extrairTagsValidas("FICCAO CIENTIFICA, pos apocaliptico; beat em up"));
    }

    @Test
    void descartaTagsForaDaTaxonomiaEDuplicadas() {
        assertEquals(List.of("Drama"), IaService.extrairTagsValidas("Drama, Inventada, drama, Outra Coisa"));
    }

    @Test
    void limitaAQuatroTags() {
        assertEquals(4, IaService.extrairTagsValidas("Ação, Aventura, Comédia, Drama, Terror, Romance").size());
    }

    @Test
    void textoVazioOuNuloNaoGeraTags() {
        assertTrue(IaService.extrairTagsValidas(null).isEmpty());
        assertTrue(IaService.extrairTagsValidas("   ").isEmpty());
    }

    @Test
    void combinarComecaPelosDoisPrimeirosGenerosOficiais() {
        List<String> llm = List.of("Super-herói", "Ação", "Comédia");
        List<String> generos = List.of("Ação", "Animação", "Família", "Comédia");
        assertEquals(List.of("Ação", "Animação", "Super-herói", "Comédia"), IaService.combinar(llm, generos));
    }

    @Test
    void filmesNaoRecebemTagsDeJogoNemFormatosDeTv() {
        List<String> filme = IaService.taxonomiaPara("Filme");
        assertTrue(filme.contains("Ação") && filme.contains("Corrida") && filme.contains("Artes Marciais"));
        assertFalse(filme.contains("Battle Royale") || filme.contains("Multijogador Local") || filme.contains("Sitcom"));
        assertTrue(IaService.taxonomiaPara("Serie").contains("Sitcom"));
        assertTrue(IaService.taxonomiaPara("Jogo").contains("FPS"));
        assertFalse(IaService.taxonomiaPara("Jogo").contains("Telenovela"));
    }

    @Test
    void listasExclusivasUsamApenasTagsDaTaxonomia() {
        for (String tag : IaService.TAGS_SO_JOGOS) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(tag), tag);
        for (String tag : IaService.TAGS_SO_SERIES) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(tag), tag);
    }

    @Test
    void combinarUsaGenerosQuandoOLlmFalha() {
        assertEquals(List.of("Ação", "Aventura"), IaService.combinar(List.of(), List.of("Ação", "Aventura")));
        assertTrue(IaService.combinar(List.of(), List.of()).isEmpty());
    }

    @Test
    void todaATaxonomiaEhReconhecidaPelaNormalizacao() {
        for (String tag : IaService.TAXONOMIA_OFICIAL) {
            assertEquals(List.of(tag), IaService.extrairTagsValidas(tag.toUpperCase()), tag);
        }
    }
}
