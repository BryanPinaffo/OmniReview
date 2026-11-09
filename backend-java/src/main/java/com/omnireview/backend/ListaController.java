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
    private final HistoricoService historico;

    public ListaController(ListaService listas, HistoricoService historico) {
        this.listas = listas;
        this.historico = historico;
    }

    /** Registra no histórico só quando a ação deu certo. */
    private ListaService.Resultado anotar(ListaService.Resultado r, HttpServletRequest req, String acao, String alvo, String obra, String detalhe) {
        if (r.ok()) historico.registrar(eu(req), "lista", acao, alvo, obra, detalhe);
        return r;
    }

    private String nome(String id) {
        return listas.nomeDaLista(id).orElse("(lista)");
    }

    @GetMapping
    public List<Map<String, Object>> minhas(HttpServletRequest req) {
        return listas.listas(eu(req));
    }

    @PostMapping
    public ResponseEntity<Object> criar(@RequestBody(required = false) Nome n, HttpServletRequest req) {
        ListaService.Resultado r = listas.criar(eu(req), n == null ? null : n.nome());
        return responder(anotar(r, req, "criou a lista", r.ok() ? ListaService.limparNome(n.nome()) : null, null, null));
    }

    /** Renomeia (POST em vez de PUT/PATCH, que não estão liberados no CORS). */
    @PostMapping("/{id}")
    public ResponseEntity<Object> renomear(@PathVariable String id, @RequestBody(required = false) Nome n, HttpServletRequest req) {
        String antes = nome(id);
        ListaService.Resultado r = listas.renomear(eu(req), id, n == null ? null : n.nome());
        return responder(anotar(r, req, "renomeou a lista", antes, null, r.ok() ? "para “" + ListaService.limparNome(n.nome()) + "”" : null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> excluir(@PathVariable String id, HttpServletRequest req) {
        String antes = nome(id);
        return responder(anotar(listas.excluir(eu(req), id), req, "excluiu a lista", antes, null, null));
    }

    @PostMapping("/{id}/obras")
    public ResponseEntity<Object> adicionar(@PathVariable String id, @RequestBody(required = false) Titulo t, HttpServletRequest req) {
        if (t == null) return ResponseEntity.badRequest().body(Map.of("erro", "Informe a obra."));
        return responder(anotar(listas.adicionarObra(eu(req), id, t.titulo()), req, "adicionou à lista", nome(id), t.titulo(), "a obra “" + t.titulo() + "”"));
    }

    @DeleteMapping("/{id}/obras")
    public ResponseEntity<Object> remover(@PathVariable String id, @RequestParam String titulo, HttpServletRequest req) {
        return responder(anotar(listas.removerObra(eu(req), id, titulo), req, "removeu da lista", nome(id), titulo, "a obra “" + titulo + "”"));
    }

    // ---------- Compartilhar (link só para ver) ----------

    @PostMapping("/{id}/link")
    public ResponseEntity<Object> ativarLink(@PathVariable String id, HttpServletRequest req) {
        return responder(anotar(listas.ativarLink(eu(req), id), req, "ativou o link de compartilhamento da lista", nome(id), null, null));
    }

    @DeleteMapping("/{id}/link")
    public ResponseEntity<Object> desativarLink(@PathVariable String id, HttpServletRequest req) {
        return responder(anotar(listas.desativarLink(eu(req), id), req, "desativou o link de compartilhamento da lista", nome(id), null, null));
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
        String quem = m == null || m.usuario() == null ? "" : AutenticacaoService.normalizar(m.usuario().replaceFirst("^@", ""));
        return responder(anotar(listas.adicionarMembro(eu(req), id, m == null ? null : m.usuario()), req,
                "adicionou " + quem + " como participante da lista", nome(id), null, null));
    }

    /** O dono remove alguém; com o próprio nome, o participante sai da lista. */
    @DeleteMapping("/{id}/membros")
    public ResponseEntity<Object> removerMembro(@PathVariable String id, @RequestParam String usuario, HttpServletRequest req) {
        String quem = AutenticacaoService.normalizar(usuario);
        String acao = quem.equals(eu(req)) ? "saiu da lista" : "removeu " + quem + " da lista";
        return responder(anotar(listas.removerMembro(eu(req), id, usuario), req, acao, nome(id), null, null));
    }

    private static String eu(HttpServletRequest req) {
        return (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<Object> responder(ListaService.Resultado r) {
        return r.ok() ? ResponseEntity.status(r.status()).body(r.corpo()) : ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
    }
}
