package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProvedoresTest {

    private static Map<String, Object> p(int id, String nome) {
        return Map.of("provider_id", id, "provider_name", nome, "logo_path", "/x.png");
    }

    @Test
    void juntaModalidadesTiraVariantesELinkaParaABuscaDoServico() {
        Map<String, Object> br = Map.of(
                "link", "https://www.themoviedb.org/movie/1/watch?locale=BR",
                "flatrate", List.of(p(8, "Netflix"), p(1796, "Netflix Standard with Ads"), p(1899, "HBO Max"),
                        p(1825, "HBO Max Amazon Channel"), p(1889, "Universal+ Amazon Channel")),
                "rent", List.of(p(2, "Apple TV Store")),
                "buy", List.of(p(2, "Apple TV Store")));

        List<Map<String, Object>> lista = Provedores.deTmdb(br, "Duna: Parte 2");
        List<Object> nomes = lista.stream().map(m -> m.get("nome")).toList();

        assertEquals(List.of("Netflix", "HBO Max", "Universal+ Amazon Channel", "Apple TV Store"), nomes);
        assertEquals("https://www.netflix.com/search?q=Duna%3A+Parte+2", lista.get(0).get("link"));
        assertEquals("https://www.themoviedb.org/movie/1/watch?locale=BR", lista.get(1).get("link")); // sem busca conhecida
        assertTrue(((String) lista.get(2).get("link")).startsWith("https://www.primevideo.com/"));
        assertEquals("Aluguel ou compra", lista.get(3).get("modalidade"));
        assertEquals("Assinatura", lista.get(0).get("modalidade"));
        assertEquals("https://image.tmdb.org/t/p/w92/x.png", lista.get(0).get("logo"));
    }

    @Test
    void semDadosDoBrasilDevolveListaVazia() {
        assertTrue(Provedores.deTmdb(null, "x").isEmpty());
        assertTrue(Provedores.deTmdb(Map.of("link", "https://x"), "x").isEmpty());
    }
}
