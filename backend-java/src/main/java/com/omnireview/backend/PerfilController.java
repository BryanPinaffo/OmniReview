package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Perfil, obras concluídas e seguidores. Todas as rotas exigem login (o usuário vem da sessão). */
@RestController
@RequestMapping("/api/perfil")
public class PerfilController {

    public record Titulo(String titulo) {}

    private final PerfilService perfis;

    public PerfilController(PerfilService perfis) {
        this.perfis = perfis;
    }

    /** Foto e títulos concluídos do usuário logado (carregado junto com o catálogo). */
    @GetMapping("/eu/resumo")
    public Map<String, Object> resumo(HttpServletRequest req) {
        return perfis.resumoDoUsuario(eu(req));
    }

    /** Atualiza foto, capa de fundo e/ou bio do usuário logado. */
    @PostMapping("/eu")
    public ResponseEntity<Object> atualizar(@RequestBody(required = false) Map<String, Object> campos, HttpServletRequest req) {
        return responder(perfis.atualizar(eu(req), campos));
    }

    @PostMapping("/concluidas")
    public ResponseEntity<Object> concluir(@RequestBody(required = false) Titulo t, HttpServletRequest req) {
        if (t == null) return erro(400, "Informe a obra.");
        return responder(perfis.concluir(eu(req), t.titulo()));
    }

    @DeleteMapping("/concluidas")
    public ResponseEntity<Object> desfazer(@RequestParam String titulo, HttpServletRequest req) {
        return responder(perfis.desfazerConclusao(eu(req), titulo));
    }

    /** Busca de pessoas: GET /api/perfil?q=termo (sem segmento fixo, para não colidir com nomes de usuário). */
    @GetMapping
    public List<Map<String, Object>> buscar(@RequestParam(defaultValue = "") String q, HttpServletRequest req) {
        return perfis.buscar(q, eu(req));
    }

    @GetMapping("/{usuario}")
    public ResponseEntity<Object> perfil(@PathVariable String usuario, HttpServletRequest req) {
        return responder(perfis.perfil(usuario, eu(req)));
    }

    @GetMapping("/{usuario}/seguidores")
    public ResponseEntity<Object> seguidores(@PathVariable String usuario, HttpServletRequest req) {
        return responder(perfis.conexoes(usuario, eu(req), true));
    }

    @GetMapping("/{usuario}/seguindo")
    public ResponseEntity<Object> seguindo(@PathVariable String usuario, HttpServletRequest req) {
        return responder(perfis.conexoes(usuario, eu(req), false));
    }

    @PostMapping("/{usuario}/seguir")
    public ResponseEntity<Object> seguir(@PathVariable String usuario, HttpServletRequest req) {
        return responder(perfis.seguir(eu(req), usuario));
    }

    @DeleteMapping("/{usuario}/seguir")
    public ResponseEntity<Object> deixarDeSeguir(@PathVariable String usuario, HttpServletRequest req) {
        return responder(perfis.deixarDeSeguir(eu(req), usuario));
    }

    private static String eu(HttpServletRequest req) {
        return (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<Object> responder(PerfilService.Resultado r) {
        return r.ok() ? ResponseEntity.status(r.status()).body(r.corpo()) : erro(r.status(), r.erro());
    }

    private static ResponseEntity<Object> erro(int status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("erro", mensagem));
    }
}
