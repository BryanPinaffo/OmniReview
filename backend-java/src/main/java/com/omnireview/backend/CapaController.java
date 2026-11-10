package com.omnireview.backend;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capas do Open Library servidas pelo próprio site (/capas/ol/{id}-{S|M|L}.jpg). Lá, cada capa passa por dois
 * redirecionamentos até o Internet Archive, que a tira de dentro de um .zip: ~2 s por capa, e um carrossel de livros
 * demorava a aparecer. Aqui a capa é buscada uma vez, guardada na memória (até {@link #MAX_CACHE_BYTES}) e entregue na
 * hora, com cache longo no navegador. Só aceita o id numérico da capa: nenhum endereço livre passa por aqui.
 * Fica fora de /api (a tag img não manda o token), como a página de compartilhamento.
 */
@RestController
public class CapaController {

    static final Pattern ARQUIVO = Pattern.compile("([0-9]{1,12})-([SML])\\.jpg");
    static final int MAX_BYTES = 3_000_000;
    static final long MAX_CACHE_BYTES = 48L * 1024 * 1024;
    static final Duration SEM_CAPA_POR = Duration.ofHours(6);
    static final int BUSCAS_SIMULTANEAS = 8;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // covers.openlibrary.org -> archive.org -> ia8xxxx.us.archive.org
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true);   // ordem de uso (LRU)
    private long bytesNoCache = 0;
    private final Map<String, Instant> semCapa = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> buscando = new ConcurrentHashMap<>();
    // Pool próprio: o comum do Java tem poucas threads em servidor pequeno e as capas de um carrossel sairiam uma a uma
    private final ExecutorService baixadores = Executors.newFixedThreadPool(BUSCAS_SIMULTANEAS, r -> {
        Thread t = new Thread(r, "capas-open-library");
        t.setDaemon(true);
        return t;
    });

    @GetMapping("/capas/ol/{arquivo}")
    public ResponseEntity<byte[]> capa(@PathVariable String arquivo) {
        Matcher m = ARQUIVO.matcher(arquivo == null ? "" : arquivo);
        if (!m.matches()) return ResponseEntity.notFound().build();
        String chave = m.group(1) + "-" + m.group(2);

        Instant ate = semCapa.get(chave);
        if (ate != null && Instant.now().isBefore(ate)) return semImagem();
        byte[] imagem = daMemoria(chave);
        if (imagem == null) {
            try {
                // Duas pessoas pedindo a mesma capa ao mesmo tempo: uma busca só
                CompletableFuture<byte[]> busca = buscando.computeIfAbsent(chave,
                        k -> CompletableFuture.supplyAsync(() -> baixar(m.group(1), m.group(2)), baixadores));
                imagem = busca.get(25, TimeUnit.SECONDS);
            } catch (Exception e) {
                return ResponseEntity.status(502).cacheControl(CacheControl.noStore()).build();
            } finally {
                buscando.remove(chave);
            }
            if (imagem == null) return ResponseEntity.status(502).cacheControl(CacheControl.noStore()).build();
            if (imagem.length == 0) {
                semCapa.put(chave, Instant.now().plus(SEM_CAPA_POR));
                return semImagem();
            }
            guardar(chave, imagem);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())   // a capa de um id não muda
                .body(imagem);
    }

    private static ResponseEntity<byte[]> semImagem() {
        return ResponseEntity.status(404).cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).build();
    }

    /** A capa do Open Library; vazio se o livro não tem essa capa; null se a busca falhou (rede, tempo, tamanho). */
    private byte[] baixar(String id, String tamanho) {
        try {
            // default=false: sem capa, o Open Library responde 404 em vez de uma imagem em branco
            HttpRequest pedido = HttpRequest.newBuilder(URI.create("https://covers.openlibrary.org/b/id/" + id + "-" + tamanho + ".jpg?default=false"))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "OmniReview (capas de livros)")
                    .GET().build();
            HttpResponse<InputStream> r = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpo = r.body()) {
                if (r.statusCode() == 404) return new byte[0];
                if (r.statusCode() != 200) return null;
                byte[] dados = corpo.readNBytes(MAX_BYTES + 1);
                if (dados.length > MAX_BYTES || !pareceJpeg(dados)) return null;
                return dados;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.out.println("Capa do Open Library " + id + "-" + tamanho + " indisponível: " + e.getMessage());
            return null;
        }
    }

    static boolean pareceJpeg(byte[] d) {
        return d.length > 100 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    private synchronized byte[] daMemoria(String chave) {
        return cache.get(chave);
    }

    /** Guarda a capa; as usadas há mais tempo saem quando passa de {@link #MAX_CACHE_BYTES}. */
    private synchronized void guardar(String chave, byte[] imagem) {
        byte[] anterior = cache.put(chave, imagem);
        if (anterior != null) bytesNoCache -= anterior.length;
        bytesNoCache += imagem.length;
        var it = cache.entrySet().iterator();
        while (bytesNoCache > MAX_CACHE_BYTES && it.hasNext()) {
            var mais = it.next();
            if (mais.getKey().equals(chave)) continue;
            bytesNoCache -= mais.getValue().length;
            it.remove();
        }
    }
}
