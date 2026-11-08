package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gestão de usuários pelo administrador (o acesso é barrado pelo {@link AdminInterceptor}). */
@RestController
@RequestMapping("/api/admin/usuarios")
public class AdminController {

    private final AutenticacaoService auth;
    private final AvaliacaoService avaliacoes;
    private final Neo4jClient neo4j;

    public AdminController(AutenticacaoService auth, AvaliacaoService avaliacoes, Neo4jClient neo4j) {
        this.auth = auth;
        this.avaliacoes = avaliacoes;
        this.neo4j = neo4j;
    }

    /** Todos os usuários com papel, data de criação e atividade (notas e comentários). */
    @GetMapping
    public List<Map<String, Object>> listar() {
        Collection<Map<String, Object>> linhas = neo4j.query(
                "MATCH (u:Usuario) " +
                "OPTIONAL MATCH (u)-[a:AVALIOU]->() WITH u, count(a) AS notas " +
                "OPTIONAL MATCH (c:Comentario {autor: u.usuario}) " +
                "WITH u, notas, count(c) AS comentarios, coalesce(u.papel, 'USUARIO') AS papel " +
                "RETURN u.usuario AS usuario, papel, u.criadoEm AS criadoEm, u.criadoPor AS criadoPor, notas, comentarios " +
                "ORDER BY CASE papel WHEN 'ADMIN' THEN 0 ELSE 1 END, usuario").fetch().all();
        return linhas.stream().map(l -> {
            Map<String, Object> u = new LinkedHashMap<>(l);
            u.put("notas", ((Number) l.get("notas")).intValue());
            u.put("comentarios", ((Number) l.get("comentarios")).intValue());
            return u;
        }).toList();
    }

    /** Cria um usuário comum (o administrador não cria outros administradores pelo painel). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> criar(@RequestBody(required = false) AutenticacaoController.Credenciais c,
                                                     HttpServletRequest req) {
        if (c == null) return ResponseEntity.badRequest().body(Map.of("erro", "Informe usuário e senha."));
        String admin = (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
        AutenticacaoService.Resultado r = auth.criarUsuarioComum(c.usuario(), c.senha(), admin);
        if (!r.ok()) return ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
        return ResponseEntity.status(201).body(Map.of("usuario", r.usuario(), "papel", r.papel()));
    }

    /** Redefine a senha de um usuário comum (o administrador passa a nova senha para a pessoa). */
    @PostMapping("/{usuario}/senha")
    public ResponseEntity<Map<String, Object>> redefinirSenha(@PathVariable String usuario,
                                                              @RequestBody(required = false) Map<String, String> corpo) {
        AutenticacaoService.Resultado r = auth.redefinirSenhaDeComum(usuario, corpo == null ? null : corpo.get("novaSenha"));
        if (!r.ok()) return ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
        return ResponseEntity.noContent().build();
    }

    /** Exclui um usuário comum, com as notas e comentários dele. */
    @DeleteMapping("/{usuario}")
    public ResponseEntity<Map<String, Object>> excluir(@PathVariable String usuario) {
        AutenticacaoService.Resultado r = auth.excluirUsuarioComum(usuario);
        if (!r.ok()) return ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
        avaliacoes.removerTudoDe(r.usuario());
        return ResponseEntity.noContent().build();
    }
}
