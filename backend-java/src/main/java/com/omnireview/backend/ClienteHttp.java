package com.omnireview.backend;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/** Utilidades HTTP compartilhadas pelos serviços que consultam APIs externas. */
final class ClienteHttp {

    private ClienteHttp() {}

    static RestTemplate criar(int conexaoMs, int leituraMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(conexaoMs);
        fabrica.setReadTimeout(leituraMs);
        return new RestTemplate(fabrica);
    }

    /**
     * Monta a URI codificando cada parâmetro de forma estrita (espaços, acentos, "&", "+", "#"...),
     * para que títulos como "Deadpool & Wolverine" não quebrem a consulta.
     */
    static URI uri(String base, Map<String, ?> parametros) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base);
        parametros.forEach((nome, valor) -> b.queryParam(nome, "{" + nome + "}"));
        return b.encode().buildAndExpand(parametros).toUri();
    }
}
