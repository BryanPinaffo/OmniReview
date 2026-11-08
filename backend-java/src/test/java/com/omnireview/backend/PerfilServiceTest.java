package com.omnireview.backend;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerfilServiceTest {

    private static final String JPEG = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ==";

    @Test
    void aceitaSoImagensRasterizadasEmBase64() {
        assertNull(PerfilService.validarImagem(JPEG, PerfilService.MAX_FOTO, "foto"));
        assertNull(PerfilService.validarImagem("data:image/png;base64,iVBORw0KGgo=", PerfilService.MAX_FOTO, "foto"));
        assertNull(PerfilService.validarImagem("", PerfilService.MAX_FOTO, "foto"), "vazio remove a imagem");
        assertNull(PerfilService.validarImagem(null, PerfilService.MAX_FOTO, "foto"));
        assertNotNull(PerfilService.validarImagem("data:image/svg+xml;base64,PHN2Zz4=", PerfilService.MAX_FOTO, "foto"), "SVG pode ter script");
        assertNotNull(PerfilService.validarImagem("https://exemplo.com/foto.jpg", PerfilService.MAX_FOTO, "foto"));
        assertNotNull(PerfilService.validarImagem("data:image/jpeg;base64,abc\" onerror=\"x", PerfilService.MAX_FOTO, "foto"));
        assertNotNull(PerfilService.validarImagem("data:image/jpeg;base64," + "A".repeat(PerfilService.MAX_FOTO), PerfilService.MAX_FOTO, "foto"));
    }

    @Test
    void bioELimpa() {
        assertEquals("", PerfilService.limparBio(null));
        assertEquals("Fã de RPG\ne de terror", PerfilService.limparBio("  Fã de RPG\n\n\ne de terror\u0007  "));
    }

    @Test
    void entradaInvalidaNaoChegaAoBanco() {
        Neo4jClient neo4j = mock(Neo4jClient.class);
        PerfilService perfis = new PerfilService(neo4j);
        assertEquals(400, perfis.atualizar("ana", Map.of("foto", "data:image/svg+xml;base64,PHN2Zz4=")).status());
        assertEquals(400, perfis.atualizar("ana", Map.of("bio", "x".repeat(PerfilService.MAX_BIO + 1))).status());
        assertEquals(400, perfis.atualizar("ana", Map.of("foto", 123)).status());
        assertEquals(400, perfis.atualizar("ana", Map.of()).status());
        assertEquals(400, perfis.atualizar("ana", new HashMap<>(Map.of("outroCampo", "x"))).status());
        assertEquals(400, perfis.seguir("ana", "ANA").status(), "não segue a si mesmo");
        verifyNoInteractions(neo4j);
    }
}
