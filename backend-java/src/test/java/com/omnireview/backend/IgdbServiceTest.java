package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IgdbServiceTest {

    @Test
    void converteSegundosEmHorasDasMedidasQueExistem() {
        // formato de game_time_to_beats: segundos para zerar e quantos jogadores informaram
        Map<String, Object> h = IgdbService.horas(Map.of("hastily", 98_000, "normally", 151_200, "completely", 226_800, "count", 412));
        assertEquals(Map.of("historia", 27L, "extras", 42L, "completo", 63L, "respostas", 412), h);
        assertEquals(Map.of("historia", 1L, "respostas", 5), IgdbService.horas(Map.of("hastily", 600, "count", 5)), "menos de 1 h vira 1 h");
        assertTrue(IgdbService.horas(Map.of("count", 3)).isEmpty(), "sem medidas, nada é mostrado");
    }

    @Test
    void descartaTemposAbsurdosInformadosPorPoucosJogadores() {
        // casos reais do acervo
        assertTrue(IgdbService.horas(Map.of("completely", 720_000, "count", 1)).isEmpty(), "ARK: 1 resposta só");
        assertTrue(IgdbService.horas(Map.of("hastily", 360_000, "normally", 32_400, "count", 2)).isEmpty(), "Call of Duty: 2 respostas");
        assertEquals(Map.of("historia", 6L, "extras", 6L, "respostas", 8),
                IgdbService.horas(Map.of("hastily", 21_600, "normally", 21_600, "completely", 5_601_600, "count", 8)), "PEAK: 100% em 1.556 h sai");
        assertTrue(IgdbService.horas(Map.of("hastily", 1_260_000, "count", 7)).isEmpty(), "R.E.P.O.: história em 350 h sai");
        assertEquals(Map.of("historia", 10L, "respostas", 4),
                IgdbService.horas(Map.of("hastily", 36_000, "normally", 18_000, "count", 4)), "extras menor que a história sai");
        // dados bons continuam: Resident Evil Requiem, 36 jogadores
        assertEquals(Map.of("historia", 8L, "extras", 13L, "completo", 30L, "respostas", 36),
                IgdbService.horas(Map.of("hastily", 28_800, "normally", 46_800, "completely", 108_000, "count", 36)));
    }

    @Test
    void escolheOJogoOriginalENaoOModComMesmoNome() {
        // resultados reais do IGDB para "Hollow Knight"
        var jogos = java.util.List.<Map<String, Object>>of(
                Map.of("id", 365702, "name", "Hollow Knight", "game_type", 5),
                Map.of("id", 14593, "name", "Hollow Knight", "game_type", 0, "total_rating_count", 2282),
                Map.of("id", 115289, "name", "Hollow Knight: Silksong", "game_type", 0, "total_rating_count", 528));
        assertEquals(14593, IgdbService.escolherJogo("Hollow Knight", jogos).get("id"));
        assertEquals(115289, IgdbService.escolherJogo("Hollow Knight: Silksong", jogos).get("id"));
    }

    @Test
    void semCredenciaisFicaDesligadoSemChamarNada() {
        IgdbService igdb = new IgdbService("", "");
        assertFalse(igdb.disponivel());
        assertNull(igdb.tempoParaZerar("Hollow Knight"));
    }
}
