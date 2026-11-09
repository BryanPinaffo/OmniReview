package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * "Minhas listas". O usuário vem da sessão. Cada lista tem um dono e pode ter participantes (lista em conjunto):
 * todos adicionam e removem obras; só o dono renomeia, exclui, compartilha e escolhe quem participa.
 */
@RestController
@RequestMapping("/api/listas")
public class ListaController {

    public record Nome(String nome) {}
    public record Titulo(String titulo) {}

    private final ListaService listas;

    public ListaController(ListaService listas) {
        this.listas = listas;
    }

    @GetMapping
    public List<Map<String, Object>> minhas(HttpServletRequest req) {
        return listas.listas(eu(req));
    }

    @PostMapping
    public ResponseEntity<Object> criar(@RequestBody(required = false) Nome n, HttpServletRequest req) {
        return responder(listas.criar(eu(req), n == null ? null : n.nome()));
    }

    /** Renomeia (POST em vez de PUT/PATCH, que não estão liberados no CORS). */
    @PostMapping("/{id}")
    public ResponseEntity<Object> renomear(@PathVariable String id, @RequestBody(required = false) Nome n, HttpServletRequest req) {
        return responder(listas.renomear(eu(req), id, n == null ? null : n.nome()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> excluir(@PathVariable String id, HttpServletRequest req) {
        return responder(listas.excluir(eu(req), id));
    }

    @PostMapping("/{id}/obras")
    public ResponseEntity<Object> adicionar(@PathVariable String id, @RequestBody(required = false) Titulo t, HttpServletRequest req) {
        if (t == null) return ResponseEntity.badRequest().body(Map.of("erro", "Informe a obra."));
        return responder(listas.adicionarObra(eu(req), id, t.titulo()));
    }

    @DeleteMapping("/{id}/obras")
    public ResponseEntity<Object> remover(@PathVariable String id, @RequestParam String titulo, HttpServletRequest req) {
        return responder(listas.removerObra(eu(req), id, titulo));
    }

    // ---------- Compartilhar (link só para ver) ----------

    @PostMapping("/{id}/link")
    public ResponseEntity<Object> ativarLink(@PathVariable String id, HttpServletRequest req) {
        return responder(listas.ativarLink(eu(req), id));
    }

    @DeleteMapping("/{id}/link")
    public ResponseEntity<Object> desativarLink(@PathVariable String id, HttpServletRequest req) {
        return responder(listas.desativarLink(eu(req), id));
    }

    /** Lista aberta pelo link compartilhado (qualquer usuário logado com o código). */
    @GetMapping("/compartilhada/{token}")
    public ResponseEntity<Object> compartilhada(@PathVariable String token, HttpServletRequest req) {
        return responder(listas.compartilhada(eu(req), token));
    }

    // ---------- Lista em conjunto (participantes podem adicionar e remover obras) ----------

    public record Membro(String usuario) {}

    @PostMapping("/{id}/membros")
    public ResponseEntity<Object> adicionarMembro(@PathVariable String id, @RequestBody(required = false) Membro m, HttpServletRequest req) {
        return responder(listas.adicionarMembro(eu(req), id, m == null ? null : m.usuario()));
    }

    /** O dono remove alguém; com o próprio nome, o participante sai da lista. */
    @DeleteMapping("/{id}/membros")
    public ResponseEntity<Object> removerMembro(@PathVariable String id, @RequestParam String usuario, HttpServletRequest req) {
        return responder(listas.removerMembro(eu(req), id, usuario));
    }

    private static String eu(HttpServletRequest req) {
        return (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<Object> responder(ListaService.Resultado r) {
        return r.ok() ? ResponseEntity.status(r.status()).body(r.corpo()) : ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
    }
}
