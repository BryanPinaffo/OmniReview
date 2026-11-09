package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exclusão de obras pelo administrador (o acesso é barrado pelo {@link AdminInterceptor}, rota /api/admin/**).
 * Excluir uma obra apaga junto: as notas (AVALIOU), os comentários sobre ela, a presença em listas (CONTEM),
 * as marcas de assistido/terminado (CONCLUIU) e as tags que ficarem sem nenhuma obra.
 */
@RestController
@RequestMapping("/api/admin/obras")
public class AdminObraController {

    private static final String CONTAGEM =
            "MATCH (o:Obra {titulo: $titulo}) " +
            "OPTIONAL MATCH (:Usuario)-[a:AVALIOU]->(o) WITH o, count(a) AS notas " +
            "OPTIONAL MATCH (c:Comentario)-[:SOBRE]->(o) WITH o, notas, count(c) AS comentarios " +
            "OPTIONAL MATCH (l:Lista)-[:CONTEM]->(o) WITH o, notas, comentarios, count(l) AS listas " +
            "OPTIONAL MATCH (:Usuario)-[k:CONCLUIU]->(o) " +
            "RETURN o.titulo AS titulo, o.tipo AS tipo, notas, comentarios, listas, count(k) AS concluidas";

    private final Neo4jClient neo4j;
    private final HistoricoService historico;

    public AdminObraController(Neo4jClient neo4j, HistoricoService historico) {
        this.neo4j = neo4j;
        this.historico = historico;
    }

    /** O que será apagado junto com a obra (para a confirmação na interface). */
    @GetMapping("/impacto")
    public ResponseEntity<Map<String, Object>> impacto(@RequestParam String titulo) {
        return contar(titulo)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("erro", "Obra não encontrada.")));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> excluir(@RequestParam String titulo, HttpServletRequest req) {
        Optional<Map<String, Object>> antes = contar(titulo);
        if (antes.isEmpty()) return ResponseEntity.status(404).body(Map.of("erro", "Obra não encontrada."));

        // Comentários e obra numa única instrução (atômica); os demais vínculos saem com o DETACH DELETE
        neo4j.query("MATCH (o:Obra {titulo: $titulo}) " +
                    "OPTIONAL MATCH (c:Comentario)-[:SOBRE]->(o) " +
                    "WITH o, collect(c) AS comentarios " +
                    "FOREACH (c IN comentarios | DETACH DELETE c) " +
                    "DETACH DELETE o")
                .bindAll(Map.of("titulo", titulo)).run();
        neo4j.query("MATCH (t:Tag) WHERE NOT (t)<-[:TEM_TAG]-() DELETE t").run();

        System.out.println("Obra excluída por " + req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO)
                + ": " + titulo + " " + antes.get());
        Map<String, Object> c = antes.get();
        historico.registrar((String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO), "obra",
                "excluiu " + HistoricoService.nomeDoTipo((String) c.get("tipo")), titulo, null,
                "junto com " + c.get("notas") + " nota(s), " + c.get("comentarios") + " comentário(s) e " + c.get("listas") + " presença(s) em listas");
        Map<String, Object> corpo = new LinkedHashMap<>(antes.get());
        corpo.put("excluida", true);
        return ResponseEntity.ok(corpo);
    }

    private Optional<Map<String, Object>> contar(String titulo) {
        return neo4j.query(CONTAGEM).bindAll(Map.of("titulo", titulo == null ? "" : titulo)).fetch().one()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("titulo", l.get("titulo"));
                    m.put("tipo", l.get("tipo"));
                    for (String k : new String[]{"notas", "comentarios", "listas", "concluidas"}) {
                        m.put(k, ((Number) l.get(k)).intValue());
                    }
                    return m;
                });
    }
}
