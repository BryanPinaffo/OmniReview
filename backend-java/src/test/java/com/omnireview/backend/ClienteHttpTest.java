package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClienteHttpTest {

    @Test
    void codificaCaracteresEspeciaisDoTitulo() {
        URI uri = ClienteHttp.uri("https://api.exemplo.com/search", Map.of("query", "Deadpool & Wolverine"));
        assertEquals("query=Deadpool%20%26%20Wolverine", uri.getRawQuery());
    }

    @Test
    void codificaAcentosESinalDeMais() {
        URI uri = ClienteHttp.uri("https://api.exemplo.com/search", Map.of("query", "Ressurreição C++"));
        assertTrue(uri.getRawQuery().contains("Ressurrei%C3%A7%C3%A3o%20C%2B%2B"), uri.getRawQuery());
    }
}
