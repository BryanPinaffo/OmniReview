package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Limites de uso da API contra robôs e abuso (resposta 429 com Retry-After):
 *  - qualquer rota: 600 requisições por minuto por endereço;
 *  - login: 30 tentativas a cada 15 min por endereço (além do bloqueio por usuário do {@link AutenticacaoService});
 *  - cadastro: 5 contas por hora por endereço;
 *  - listar opções e adicionar obra da web (APIs externas e a IA): 40 por hora por usuário comum e 80 por endereço;
 *  - comentários: 20 a cada 10 min por usuário; listas e participantes: 60 a cada 10 min por usuário.
 * O administrador não tem limite por usuário (só o geral por endereço).
 */
public class LimiteInterceptor implements HandlerInterceptor {

    private final LimitadorDeTaxa limitador;
    private final AutenticacaoService auth;

    public LimiteInterceptor(LimitadorDeTaxa limitador, AutenticacaoService auth) {
        this.limitador = limitador;
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String metodo = req.getMethod();
        if ("OPTIONS".equalsIgnoreCase(metodo)) return true;
        String ip = ClienteIp.de(req);
        // A própria máquina (testes automáticos, manutenção): em produção nada chega como loopback,
        // pois o Tailscale Funnel entra pela rede do Docker e o IP real vem no X-Forwarded-For
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) return true;
        String rota = req.getRequestURI();
        // Roda antes da autenticação (para conter também enxurradas sem login): descobre o usuário pelo token
        var sessao = auth.sessao(AutenticacaoInterceptor.token(req));
        String usuario = sessao.map(AutenticacaoService.Sessao::usuario).orElse(null);
        boolean admin = sessao.map(AutenticacaoService.Sessao::admin).orElse(false);

        long espera = limitador.usar("geral|" + ip, 600, Duration.ofMinutes(1));
        // Listar opções e confirmar a escolhida chamam APIs externas (e a IA, ao salvar): contam no mesmo limite
        if (espera == 0 && (rota.equals("/api/buscar-opcoes") || ("POST".equalsIgnoreCase(metodo) && rota.equals("/api/buscar-ou-adicionar")))) {
            espera = limitador.usar("web|" + ip, 80, Duration.ofHours(1));
            if (espera == 0 && usuario != null && !admin) espera = limitador.usar("web|u|" + usuario, 40, Duration.ofHours(1));
        } else if (espera == 0 && "POST".equalsIgnoreCase(metodo)) {
            if (rota.equals("/api/auth/entrar")) {
                espera = limitador.usar("entrar|" + ip, 30, Duration.ofMinutes(15));
            } else if (rota.equals("/api/auth/cadastrar")) {
                espera = limitador.usar("cadastrar|" + ip, 5, Duration.ofHours(1));
            } else if (rota.equals("/api/avaliacoes/comentarios") && usuario != null && !admin) {
                espera = limitador.usar("comentar|" + usuario, 20, Duration.ofMinutes(10));
            } else if (rota.startsWith("/api/listas") && usuario != null && !admin) {
                espera = limitador.usar("listas|" + usuario, 60, Duration.ofMinutes(10));
            }
        }
        if (espera == 0) return true;
        resp.setHeader("Retry-After", String.valueOf(espera));
        long minutos = Math.max(1, (espera + 59) / 60);
        AutenticacaoInterceptor.responderErro(resp, 429, "Muitas solicitações em pouco tempo. Tente de novo em " + minutos + " min.");
        return false;
    }
}
