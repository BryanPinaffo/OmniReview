package com.omnireview.backend;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SegurancaTest {

    private static MockHttpServletRequest req(String remoto, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remoto);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void ipRealVemDoProxyDaRedeInternaEUsaOUltimoValor() {
        // pelo Tailscale Funnel: conexão vem do Docker (172.x); o IP real é o último do X-Forwarded-For
        assertEquals("203.0.113.9", ClienteIp.de(req("172.18.0.1", "203.0.113.9")));
        // quem tenta enganar põe um IP falso no começo: o proxy acrescenta o verdadeiro no fim
        assertEquals("203.0.113.9", ClienteIp.de(req("172.18.0.1", "1.2.3.4, 203.0.113.9")));
        // conexão direta da internet: o cabeçalho não é confiável e é ignorado
        assertEquals("198.51.100.7", ClienteIp.de(req("198.51.100.7", "1.2.3.4")));
        // lixo no cabeçalho é ignorado
        assertEquals("172.18.0.1", ClienteIp.de(req("172.18.0.1", "<script>")));
        assertTrue(ClienteIp.enderecoInterno("100.68.96.53"));   // Tailscale
        assertFalse(ClienteIp.enderecoInterno("172.32.0.1"));
    }

    @Test
    void limitadorBloqueiaAcimaDoMaximoELiberaNaProximaJanela() {
        Clock[] relogio = {Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)};
        LimitadorDeTaxa l = new LimitadorDeTaxa(new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return relogio[0].instant(); }
        });
        for (int i = 0; i < 5; i++) assertEquals(0, l.usar("cadastrar|ip", 5, Duration.ofHours(1)));
        long espera = l.usar("cadastrar|ip", 5, Duration.ofHours(1));
        assertTrue(espera > 3000 && espera <= 3600, "espera em segundos: " + espera);
        assertEquals(0, l.usar("cadastrar|outro-ip", 5, Duration.ofHours(1)), "outro endereço não é afetado");
        relogio[0] = Clock.offset(relogio[0], Duration.ofMinutes(61));
        assertEquals(0, l.usar("cadastrar|ip", 5, Duration.ofHours(1)));
    }

    @Test
    void senhasComunsSaoRecusadas() {
        assertTrue(AutenticacaoService.senhaFraca("Senha123"));
        assertTrue(AutenticacaoService.senhaFraca("12345678"));
        assertTrue(AutenticacaoService.senhaFraca("aaaaaaaa"));
        assertTrue(AutenticacaoService.senhaFraca("abababab"));
        assertFalse(AutenticacaoService.senhaFraca("Pipoca-com-guarana-7"));
    }

    @Test
    void cspProibeIframesEObjetosEPermiteSoAsOrigensUsadas() {
        assertTrue(SegurancaFiltro.CSP.contains("frame-ancestors 'none'"));
        assertTrue(SegurancaFiltro.CSP.contains("object-src 'none'"));
        assertTrue(SegurancaFiltro.CSP.contains("connect-src 'self'"));
        assertFalse(SegurancaFiltro.CSP.contains("unsafe-eval"));
    }
}
