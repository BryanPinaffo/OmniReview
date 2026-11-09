package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TopServiceTest {

    @Test
    void enquantoAtualizaOsNovosVemPrimeiroEAListaAnteriorCompletaAte15() {
        List<String> anteriores = new ArrayList<>();
        for (int i = 1; i <= 15; i++) anteriores.add("Antiga " + i);

        List<String> mescla = TopService.mesclar(List.of("Nova A", "Antiga 3", "Nova B"), anteriores);

        assertEquals(TopService.TAMANHO, mescla.size());
        assertEquals(List.of("Nova A", "Antiga 3", "Nova B", "Antiga 1", "Antiga 2", "Antiga 4"), mescla.subList(0, 6));
        assertEquals(1, mescla.stream().filter("Antiga 3"::equals).count());   // sem repetir
    }

    @Test
    void semListaAnteriorFicaSoComOsNovos() {
        assertEquals(List.of("A", "B"), TopService.mesclar(List.of("A", "B"), List.of()));
    }

    @Test
    void listaLidaDoGrafoViraTitulos() {
        assertEquals(List.of("Duna", "Arcane"), TopService.lista(List.of("Duna", "Arcane")));
        assertEquals(List.of(), TopService.lista(null));
        assertEquals(List.of(), TopService.lista("não é lista"));
    }

    @Test
    void programasDaSteamNaoContamComoJogos() {
        // 57 = Utilitários, 53 = Design e ilustração (gêneros de software da Steam)
        assertTrue(SteamService.ehProgramaNaoJogo(List.of(Map.of("id", "57", "description", "Utilities"))));
        assertTrue(SteamService.ehProgramaNaoJogo(List.of(Map.of("id", "1", "description", "Action"), Map.of("id", 53))));
        assertFalse(SteamService.ehProgramaNaoJogo(List.of(Map.of("id", "1", "description", "Action"), Map.of("id", "37"))));
        assertFalse(SteamService.ehProgramaNaoJogo(null));
        assertFalse(SteamService.ehProgramaNaoJogo(List.of(Map.of("id", "x"))));
    }
}
