package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Rotas de autenticação. Cadastrar e entrar são públicas; as demais exigem o token. */
@RestController
@RequestMapping("/api/auth")
public class AutenticacaoController {

    /** Corpo das requisições de cadastro, login e exclusão de conta. */
    public record Credenciais(String usuario, String senha) {}

    /** Corpo da redefinição de senha. */
    public record TrocaDeSenha(String senhaAtual, String novaSenha) {}

    private final AutenticacaoService auth;
    private final AvaliacaoService avaliacoes;
    private final HistoricoService historico;

    public AutenticacaoController(AutenticacaoService auth, AvaliacaoService avaliacoes, HistoricoService historico) {
        this.auth = auth;
        this.avaliacoes = avaliacoes;
        this.historico = historico;
    }

    @PostMapping("/cadastrar")
    public ResponseEntity<Map<String, Object>> cadastrar(@RequestBody(required = false) Credenciais c) {
        AutenticacaoService.Resultado r = c == null ? AutenticacaoService.Resultado.falha(400, "Informe usuário e senha.")
                : auth.cadastrar(c.usuario(), c.senha());
        if (r.ok()) historico.registrar(r.usuario(), "conta", "criou a própria conta", r.usuario(), null, "pela tela de cadastro");
        return responder(r, 201);
    }

    @PostMapping("/entrar")
    public ResponseEntity<Map<String, Object>> entrar(@RequestBody(required = false) Credenciais c, HttpServletRequest req) {
        return responder(c == null ? AutenticacaoService.Resultado.falha(400, "Informe usuário e senha.")
                : auth.entrar(c.usuario(), c.senha(), ClienteIp.de(req)), 200,
                c != null && AutenticacaoService.senhaFraca(c.senha()));   // avisa quem ainda usa senha fácil de adivinhar
    }

    @GetMapping("/eu")
    public Map<String, Object> eu(HttpServletRequest req) {
        return Map.of("usuario", req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO),
                "papel", req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_PAPEL));
    }

    @PostMapping("/sair")
    public ResponseEntity<Void> sair(HttpServletRequest req) {
        auth.sair(AutenticacaoInterceptor.token(req));
        return ResponseEntity.noContent().build();
    }

    /** Redefine a senha do usuário logado (qualquer papel, inclusive administrador). */
    @PostMapping("/senha")
    public ResponseEntity<Map<String, Object>> alterarSenha(HttpServletRequest req, @RequestBody(required = false) TrocaDeSenha t) {
        if (t == null) return ResponseEntity.badRequest().body(Map.of("erro", "Informe a senha atual e a nova."));
        String usuario = (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
        AutenticacaoService.Resultado r = auth.alterarSenha(usuario, t.senhaAtual(), t.novaSenha(), AutenticacaoInterceptor.token(req));
        if (r.ok()) historico.registrar(usuario, "conta", "alterou a própria senha", usuario);
        return r.ok() ? ResponseEntity.noContent().build() : ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
    }

    @DeleteMapping("/conta")
    public ResponseEntity<Map<String, Object>> excluirConta(HttpServletRequest req, @RequestBody(required = false) Credenciais c) {
        String usuario = (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
        AutenticacaoService.Resultado r = auth.excluirConta(usuario, c == null ? null : c.senha());
        if (!r.ok()) return responder(r, 200);
        avaliacoes.removerTudoDe(usuario);   // notas e comentários saem junto com a conta
        historico.registrar(usuario, "conta", "excluiu a própria conta", usuario);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Map<String, Object>> responder(AutenticacaoService.Resultado r, int statusSucesso) {
        return responder(r, statusSucesso, false);
    }

    private static ResponseEntity<Map<String, Object>> responder(AutenticacaoService.Resultado r, int statusSucesso, boolean senhaFraca) {
        if (!r.ok()) {
            return ResponseEntity.status(r.status()).body(Map.of("erro", r.erro()));
        }
        return ResponseEntity.status(statusSucesso).body(Map.of("token", r.token(), "usuario", r.usuario(), "papel", r.papel(),
                "senhaFraca", senhaFraca));
    }
}
