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
 * Ficha da obra exibida na janela de detalhes: onde assistir/jogar/ler, duração do filme, temporadas da série,
 * plataformas e tempo para zerar do jogo, páginas do livro. Consultada na hora (TMDB / IGDB / Steam / livros) e
 * guardada em memória por algumas horas, porque os catálogos dos serviços mudam com frequência e não vale gravar no banco.
 */
@RestController
@RequestMapping("/api/obras")
public class InfoObraController {

    static final Duration VALIDADE = Duration.ofHours(12);
    static final Duration VALIDADE_FALHA = Duration.ofMinutes(15);

    private record Entrada(Map<String, Object> ficha, Instant expiraEm) {}

    private final ObraRepository repository;
    private final TmdbService tmdb;
    private final JogosService jogos;
    private final LivroService livros;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    public InfoObraController(ObraRepository repository, TmdbService tmdb, JogosService jogos, LivroService livros) {
        this.livros = livros;
        this.repository = repository;
        this.tmdb = tmdb;
        this.jogos = jogos;
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(@RequestParam String titulo) {
        Obra obra = titulo == null ? null : repository.findById(titulo).orElse(null);
        if (obra == null) return ResponseEntity.status(404).body(Map.of("erro", "Obra não encontrada."));

        String tipo = tipoDe(obra);
        String chave = tipo + "|" + obra.getTitulo();
        Entrada e = cache.get(chave);
        if (e == null || Instant.now().isAfter(e.expiraEm())) {
            Map<String, Object> ficha = montar(tipo, obra);
            boolean achou = Boolean.TRUE.equals(ficha.remove("encontrada"));
            e = new Entrada(ficha, Instant.now().plus(achou ? VALIDADE : VALIDADE_FALHA));
            cache.put(chave, e);
        }
        return ResponseEntity.ok(e.ficha());
    }

    private Map<String, Object> montar(String tipo, Obra obra) {
        String titulo = obra.getTitulo();
        Map<String, Object> ficha = new LinkedHashMap<>();
        ficha.put("titulo", titulo);
        ficha.put("tipo", tipo);
        Map<String, Object> dados = switch (tipo) {
            case "jogo" -> jogos.ficha(obra);   // plataformas, lojas e tempo para zerar (IGDB; a Steam é a reserva)
            case "livro" -> livros.ficha(obra);
            case "serie" -> tmdb.ficha("tv", titulo);
            default -> tmdb.ficha("movie", titulo);
        };
        if (dados != null) ficha.putAll(dados);
        // A Steam é a reserva quando o IGDB falha, e o tempo para zerar pode falhar sozinho: nesses casos a ficha fica
        // pouco tempo no cache, para os dados completos aparecerem logo que o IGDB voltar
        boolean falhaMomentanea = Boolean.TRUE.equals(ficha.remove("tempoIndisponivel"));
        ficha.put("encontrada", dados != null && !"Steam".equals(dados.get("fonte")) && !falhaMomentanea);
        if (tipo.equals("jogo")) {
            // O HowLongToBeat fica como link para quem quiser comparar o tempo para zerar
            String nome = RecomendacaoController.semSufixoDeMidia(titulo);
            ficha.put("linkTempoParaZerar", "https://howlongtobeat.com/?q=" + URLEncoder.encode(nome, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return ficha;
    }

    static String tipoDe(Obra obra) {
        String t = Provedores.normalizar(obra.getTipo());
        if (t.startsWith("jogo") || t.startsWith("game")) return "jogo";
        if (t.startsWith("serie")) return "serie";
        if (t.startsWith("livro") || t.startsWith("book")) return "livro";
        return "filme";
    }
}
