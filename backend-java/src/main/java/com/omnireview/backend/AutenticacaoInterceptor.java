package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Exige "Authorization: Bearer <token>" em /api/** (exceto cadastrar e entrar).
 * Sem sessão válida, responde 401 e a interface redireciona para a tela de login.
 */
public class AutenticacaoInterceptor implements HandlerInterceptor {

    static final String ATRIBUTO_USUARIO = "omnireview.usuario";
    static final String ATRIBUTO_PAPEL = "omnireview.papel";

    private final AutenticacaoService auth;

    public AutenticacaoInterceptor(AutenticacaoService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;   // pré-requisição de CORS
        }
        Optional<AutenticacaoService.Sessao> sessao = auth.sessao(token(req));
        if (sessao.isPresent()) {
            req.setAttribute(ATRIBUTO_USUARIO, sessao.get().usuario());
            req.setAttribute(ATRIBUTO_PAPEL, sessao.get().papel());
            return true;
        }
        responderErro(resp, HttpServletResponse.SC_UNAUTHORIZED, "Sessão inválida ou expirada. Entre novamente.");
        return false;
    }

    /** Resposta de erro em JSON ({"erro": ...}); as mensagens são fixas, sem dados do usuário. */
    static void responderErro(HttpServletResponse resp, int status, String mensagem) throws java.io.IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
    }

    static String token(HttpServletRequest req) {
        String cabecalho = req.getHeader("Authorization");
        return cabecalho != null && cabecalho.regionMatches(true, 0, "Bearer ", 0, 7) ? cabecalho.substring(7).trim() : null;
    }
}
