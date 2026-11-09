package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Favoritos do usuário, guardados no banco: (:Usuario)-[:CURTIU {em}]->(:Obra).
 * Antes ficavam só no navegador (localStorage) e sumiam ao trocar de aparelho/navegador ou limpar os dados;
 * POST /importar leva para o banco os favoritos que ainda estiverem salvos no navegador.
 */
@RestController
@RequestMapping("/api/favoritos")
public class FavoritoController {

    static final int MAX_FAVORITOS = 1000;

    public record Titulo(String titulo) {}
    public record Titulos(List<String> titulos) {}

    private final Neo4jClient neo4j;

    public FavoritoController(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /** Títulos favoritados, na ordem em que foram adicionados. */
    @GetMapping
    public List<String> listar(HttpServletRequest req) {
        return new ArrayList<>(neo4j.query(
                        "MATCH (:Usuario {usuario: $eu})-[c:CURTIU]->(o:Obra) RETURN o.titulo AS titulo ORDER BY c.em, o.titulo")
                .bindAll(Map.of("eu", eu(req))).fetchAs(String.class).all());
    }

    @PostMapping
    public ResponseEntity<Object> curtir(@RequestBody(required = false) Titulo t, HttpServletRequest req) {
        if (t == null || t.titulo() == null || t.titulo().isBlank()) return erro(400, "Informe a obra.");
        String eu = eu(req);
        if (contar(eu) >= MAX_FAVORITOS) return erro(400, "Você já tem " + MAX_FAVORITOS + " favoritos.");
        if (adicionar(eu, List.of(t.titulo())) == 0) return erro(404, "Obra não encontrada.");
        return ResponseEntity.ok(listar(req));
    }

    @DeleteMapping
    public List<String> descurtir(@RequestParam String titulo, HttpServletRequest req) {
        neo4j.query("MATCH (:Usuario {usuario: $eu})-[c:CURTIU]->(:Obra {titulo: $titulo}) DELETE c")
                .bindAll(Map.of("eu", eu(req), "titulo", titulo)).run();
        return listar(req);
    }

    /** Junta aos favoritos do banco os que estavam no navegador (títulos que não existem mais são ignorados). */
    @PostMapping("/importar")
    public ResponseEntity<Object> importar(@RequestBody(required = false) Titulos corpo, HttpServletRequest req) {
        if (corpo == null || corpo.titulos() == null) return erro(400, "Informe os títulos.");
        String eu = eu(req);
        List<String> titulos = corpo.titulos().stream().filter(Objects::nonNull).distinct()
                .limit(Math.max(0, MAX_FAVORITOS - contar(eu))).toList();
        if (!titulos.isEmpty()) adicionar(eu, titulos);
        return ResponseEntity.ok(listar(req));
    }

    private long adicionar(String eu, List<String> titulos) {
        return neo4j.query(
                        "MATCH (u:Usuario {usuario: $eu}) UNWIND range(0, size($titulos) - 1) AS i " +
                        "MATCH (o:Obra {titulo: $titulos[i]}) " +
                        "MERGE (u)-[c:CURTIU]->(o) ON CREATE SET c.em = $agora + '#' + right('00000' + toString(i), 5) " +
                        "RETURN count(o) AS n")
                .bindAll(Map.of("eu", eu, "titulos", titulos, "agora", Instant.now().toString()))
                .fetchAs(Long.class).one().orElse(0L);
    }

    private long contar(String eu) {
        return neo4j.query("MATCH (:Usuario {usuario: $eu})-[c:CURTIU]->() RETURN count(c) AS n")
                .bindAll(Map.of("eu", eu)).fetchAs(Long.class).one().orElse(0L);
    }

    private static String eu(HttpServletRequest req) {
        return (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<Object> erro(int status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("erro", mensagem));
    }
}
