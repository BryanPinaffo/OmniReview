package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Bloqueia (403) as rotas de administração para quem não é administrador. */
public class AdminInterceptor implements HandlerInterceptor {

    /** Importar tendências, manutenção do acervo, cadastro manual de obras e gestão de usuários. */
    static final String[] ROTAS_ADMIN = {
            "/api/admin/**", "/api/tendencias/**", "/api/adicionar/**", "/api/completar-detalhes", "/api/reprocessar-dna"
    };

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        if (Usuario.ADMIN.equals(req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_PAPEL))) {
            return true;
        }
        AutenticacaoInterceptor.responderErro(resp, HttpServletResponse.SC_FORBIDDEN, "Apenas o administrador pode fazer isso.");
        return false;
    }
}
