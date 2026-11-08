package com.omnireview.backend;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListaServiceTest {

    @Test
    void nomeELimpoEValidado() {
        assertEquals("", ListaService.limparNome(null));
        assertEquals("Maratona de terror", ListaService.limparNome("  Maratona \n de\tterror  "));
        assertNotNull(ListaService.validarNome(""));
        assertNotNull(ListaService.validarNome(ListaService.limparNome("   ")));
        assertNull(ListaService.validarNome("x".repeat(ListaService.MAX_NOME)));
        assertNotNull(ListaService.validarNome("x".repeat(ListaService.MAX_NOME + 1)));
        assertNull(ListaService.validarNome("🎮".repeat(ListaService.MAX_NOME)), "emojis contam 1 cada");
    }

    @Test
    void nomeInvalidoNaoChegaAoBanco() {
        Neo4jClient neo4j = mock(Neo4jClient.class);
        ListaService listas = new ListaService(neo4j);
        assertEquals(400, listas.criar("ana", "  ").status());
        assertEquals(400, listas.renomear("ana", "id", "x".repeat(41)).status());
        verifyNoInteractions(neo4j);
    }
}
