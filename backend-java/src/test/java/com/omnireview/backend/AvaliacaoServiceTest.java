package com.omnireview.backend;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvaliacaoServiceTest {

    @Test
    void notaSoAceitaDeUmACincoEstrelas() {
        assertTrue(AvaliacaoService.validarEstrelas(null).isPresent());
        assertTrue(AvaliacaoService.validarEstrelas(0).isPresent());
        assertTrue(AvaliacaoService.validarEstrelas(6).isPresent());
        assertTrue(AvaliacaoService.validarEstrelas(-3).isPresent());
        for (int i = 1; i <= 5; i++) {
            assertTrue(AvaliacaoService.validarEstrelas(i).isEmpty(), "nota " + i);
        }
    }

    @Test
    void comentarioELimpoEValidado() {
        assertEquals("", AvaliacaoService.limparTexto(null));
        assertEquals("Ótimo filme!", AvaliacaoService.limparTexto("  Ótimo filme!  \r\n"));
        assertEquals("a\n\nb", AvaliacaoService.limparTexto("a\n\n\n\n\nb"));
        assertEquals("ab", AvaliacaoService.limparTexto("a\u0000\u0007b"));

        assertTrue(AvaliacaoService.validarComentario("").isPresent(), "vazio");
        assertTrue(AvaliacaoService.validarComentario(AvaliacaoService.limparTexto("   \n  ")).isPresent(), "só espaços");
        assertTrue(AvaliacaoService.validarComentario("x".repeat(1000)).isEmpty(), "1000 caracteres");
        assertTrue(AvaliacaoService.validarComentario("x".repeat(1001)).isPresent(), "1001 caracteres");
        // emojis contam como 1 caractere cada (code points), não 2
        assertTrue(AvaliacaoService.validarComentario("🎮".repeat(1000)).isEmpty(), "1000 emojis");
    }

    @Test
    void mediaArredondadaParaUmaCasa() {
        assertNull(AvaliacaoService.arredondar(null));
        assertEquals(4.3, AvaliacaoService.arredondar(4.333));
        assertEquals(3.7, AvaliacaoService.arredondar(3.66666));
        assertEquals(5.0, AvaliacaoService.arredondar(5.0));
    }

    @Test
    void entradaInvalidaNaoChegaAoBanco() {
        Neo4jClient neo4j = mock(Neo4jClient.class);
        AvaliacaoService service = new AvaliacaoService(neo4j);

        AvaliacaoService.Resultado nota = service.avaliar("Duna", 7, "ana");
        assertFalse(nota.ok());
        assertEquals(400, nota.status());

        AvaliacaoService.Resultado comentario = service.comentar("Duna", "   ", "ana");
        assertFalse(comentario.ok());
        assertEquals(400, comentario.status());

        verifyNoInteractions(neo4j);
    }
}
