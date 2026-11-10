package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IaServiceTest {

    @Test
    void tituloDaObraNaoPassaPelaTraducao() {
        // caso real: o modelo traduzia "Breath of the Wild" para "Alívio do Fogo"
        var trocas = new java.util.LinkedHashMap<String, String>();
        String texto = "The Legend of Zelda: Breath of the Wild is the first 3D open-world game in the Zelda series. "
                + "Unlike previous games, Breath of the Wild lets you climb anything.";
        String protegido = IaService.protegerTitulo(texto, "The Legend of Zelda: Breath of the Wild", trocas);
        assertEquals("OBRA1X is the first 3D open-world game in the Zelda series. Unlike previous games, OBRA2X lets you climb anything.", protegido);
        // o modelo às vezes come o X final, ou já devolve o título no lugar do marcador
        String volta = IaService.restaurarTitulo("OBRA1X é o primeiro jogo de mundo aberto em 3D. Diferente dos anteriores, OBRA2 deixa escalar tudo.", trocas);
        assertEquals("The Legend of Zelda: Breath of the Wild é o primeiro jogo de mundo aberto em 3D. Diferente dos anteriores, "
                + "Breath of the Wild deixa escalar tudo.", volta);
        // sem título, ou título que não aparece no texto: nada muda
        var nenhuma = new java.util.LinkedHashMap<String, String>();
        assertEquals(texto, IaService.protegerTitulo(texto, null, nenhuma));
        assertEquals(texto, IaService.protegerTitulo(texto, "Hades", nenhuma));
        assertTrue(nenhuma.isEmpty());
        // o subtítulo só vira marcador se ainda aparecer sozinho no texto (aqui ele só aparece dentro do título)
        var curto = new java.util.LinkedHashMap<String, String>();
        IaService.protegerTitulo("Duna: Parte 2 continues. Part of the story...", "Duna: Parte 2", curto);
        assertEquals(java.util.Set.of("OBRA1X"), curto.keySet());
    }

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
