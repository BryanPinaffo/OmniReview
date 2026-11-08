package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TmdbServiceTest {

    @Test
    void animacaoJaponesaRecebeAnime() {
        assertEquals("Animação, Anime, Aventura", TmdbService.generosParaTaxonomia(List.of(16, 12), "ja"));
    }

    @Test
    void animacaoDeOutroIdiomaNaoRecebeAnime() {
        assertEquals("Animação, Família", TmdbService.generosParaTaxonomia(List.of(16, 10751), "en"));
    }

    @Test
    void generoCompostoDeSerieViraDuasTagsSemRepetir() {
        assertEquals("Ação, Aventura, Ficção Científica, Fantasia", TmdbService.generosParaTaxonomia(List.of(10759, 12, 10765), "en"));
    }
}
