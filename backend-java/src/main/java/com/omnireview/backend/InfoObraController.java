package com.omnireview.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ficha da obra exibida na janela de detalhes: onde assistir/jogar, duração do filme, temporadas da série
 * e tempo médio de jogo. Consultada na hora (TMDB / RAWG / Steam) e guardada em memória por algumas horas,
 * porque os catálogos dos serviços mudam com frequência e não vale gravar isso no banco.
 */
@RestController
@RequestMapping("/api/obras")
public class InfoObraController {

    static final Duration VALIDADE = Duration.ofHours(12);
    static final Duration VALIDADE_FALHA = Duration.ofMinutes(15);

    private record Entrada(Map<String, Object> ficha, Instant expiraEm) {}

    private final ObraRepository repository;
    private final TmdbService tmdb;
    private final RawgService rawg;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    public InfoObraController(ObraRepository repository, TmdbService tmdb, RawgService rawg) {
        this.repository = repository;
        this.tmdb = tmdb;
        this.rawg = rawg;
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(@RequestParam String titulo) {
        Obra obra = titulo == null ? null : repository.findById(titulo).orElse(null);
        if (obra == null) return ResponseEntity.status(404).body(Map.of("erro", "Obra não encontrada."));

        String tipo = tipoDe(obra);
        String chave = tipo + "|" + obra.getTitulo();
        Entrada e = cache.get(chave);
        if (e == null || Instant.now().isAfter(e.expiraEm())) {
            Map<String, Object> ficha = montar(tipo, obra.getTitulo());
            boolean achou = Boolean.TRUE.equals(ficha.remove("encontrada"));
            e = new Entrada(ficha, Instant.now().plus(achou ? VALIDADE : VALIDADE_FALHA));
            cache.put(chave, e);
        }
        return ResponseEntity.ok(e.ficha());
    }

    private Map<String, Object> montar(String tipo, String titulo) {
        Map<String, Object> ficha = new LinkedHashMap<>();
        ficha.put("titulo", titulo);
        ficha.put("tipo", tipo);
        Map<String, Object> dados = switch (tipo) {
            case "jogo" -> rawg.fichaJogo(titulo);
            case "serie" -> tmdb.ficha("tv", titulo);
            default -> tmdb.ficha("movie", titulo);
        };
        if (dados != null) ficha.putAll(dados);
        // Steam é a reserva quando a RAWG cai: guarda por pouco tempo para o tempo médio aparecer quando ela voltar
        ficha.put("encontrada", dados != null && !"Steam".equals(dados.get("fonte")));
        if (tipo.equals("jogo")) {
            // A RAWG mede o tempo médio jogado; o tempo para zerar fica no HowLongToBeat
            ficha.put("linkTempoParaZerar", "https://howlongtobeat.com/?q=" + URLEncoder.encode(titulo, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return ficha;
    }

    static String tipoDe(Obra obra) {
        String t = Provedores.normalizar(obra.getTipo());
        if (t.startsWith("jogo") || t.startsWith("game")) return "jogo";
        if (t.startsWith("serie")) return "serie";
        return "filme";
    }
}
