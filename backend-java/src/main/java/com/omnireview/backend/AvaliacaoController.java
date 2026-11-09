package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Notas por estrelas e comentários. Todas as rotas exigem login (usuário vem da sessão). */
@RestController
@RequestMapping("/api/avaliacoes")
public class AvaliacaoController {

    public record NovaNota(String titulo, Integer estrelas) {}
    public record NovoComentario(String titulo, String texto, Boolean spoiler) {}

    private final AvaliacaoService avaliacoes;
    private final HistoricoService historico;

    public AvaliacaoController(AvaliacaoService avaliacoes, HistoricoService historico) {
        this.avaliacoes = avaliacoes;
        this.historico = historico;
    }

    /** Média e total de notas de cada obra avaliada. */
    @GetMapping("/resumo")
    public List<Map<String, Object>> resumo() {
        return avaliacoes.resumoGeral();
    }

    /** Média, distribuição, nota do usuário e comentários de uma obra. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> detalhes(@RequestParam String titulo, HttpServletRequest req) {
        return responder(avaliacoes.detalhes(titulo, usuario(req)));
    }

    @PostMapping("/nota")
    public ResponseEntity<Map<String, Object>> avaliar(@RequestBody(required = false) NovaNota n, HttpServletRequest req) {
        if (n == null) return erro(400, "Informe a obra e a nota.");
        AvaliacaoService.Resultado r = avaliacoes.avaliar(n.titulo(), n.estrelas(), usuario(req));
        if (r.ok()) historico.registrar(usuario(req), "avaliacao", "deu " + n.estrelas() + (n.estrelas() == 1 ? " estrela para" : " estrelas para"),
                n.titulo(), n.titulo(), null);
        return responder(r);
    }

    @DeleteMapping("/nota")
    public ResponseEntity<Map<String, Object>> removerNota(@RequestParam String titulo, HttpServletRequest req) {
        AvaliacaoService.Resultado r = avaliacoes.removerNota(titulo, usuario(req));
        if (r.ok()) historico.registrar(usuario(req), "avaliacao", "removeu a nota de", titulo, titulo, null);
        return responder(r);
    }

    @PostMapping("/comentarios")
    public ResponseEntity<Map<String, Object>> comentar(@RequestBody(required = false) NovoComentario c, HttpServletRequest req) {
        if (c == null) return erro(400, "Informe a obra e o comentário.");
        AvaliacaoService.Resultado r = avaliacoes.comentar(c.titulo(), c.texto(), usuario(req), Boolean.TRUE.equals(c.spoiler()));
        if (r.ok()) historico.registrar(usuario(req), "avaliacao", "comentou em", c.titulo(), c.titulo(), "“" + c.texto() + "”");
        return responder(r);
    }

    @DeleteMapping("/comentarios/{id}")
    public ResponseEntity<Map<String, Object>> excluirComentario(@PathVariable String id, HttpServletRequest req) {
        String obra = historico.obraDoComentario(id).orElse(null);
        boolean admin = Usuario.ADMIN.equals(req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_PAPEL));
        AvaliacaoService.Resultado r = avaliacoes.excluirComentario(id, usuario(req), admin);
        if (r.ok()) historico.registrar(usuario(req), "avaliacao", "excluiu um comentário em", obra, obra, admin ? "moderação do administrador" : null);
        return responder(r);
    }

    private static String usuario(HttpServletRequest req) {
        return (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<Map<String, Object>> responder(AvaliacaoService.Resultado r) {
        if (!r.ok()) return erro(r.status(), r.erro());
        if (r.corpo() == null) return ResponseEntity.status(r.status()).build();
        return ResponseEntity.status(r.status()).body(r.corpo());
    }

    private static ResponseEntity<Map<String, Object>> erro(int status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("erro", mensagem));
    }
}
