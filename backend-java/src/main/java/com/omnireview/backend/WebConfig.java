package com.omnireview.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AutenticacaoService auth;
    private final LimitadorDeTaxa limitador;

    public WebConfig(AutenticacaoService auth, LimitadorDeTaxa limitador) {
        this.auth = auth;
        this.limitador = limitador;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1) Limites de uso (antes de tudo, inclusive login e cadastro)
        registry.addInterceptor(new LimiteInterceptor(limitador, auth))
                .addPathPatterns("/api/**");
        // 2) Sessão obrigatória
        registry.addInterceptor(new AutenticacaoInterceptor(auth))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/cadastrar", "/api/auth/entrar");
        // 3) Rotas só do administrador (roda depois do interceptor acima, que identifica a sessão)
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns(AdminInterceptor.ROTAS_ADMIN);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Atenção: NÃO restringir a lista de origens. Em produção o site passa pelo Tailscale Funnel: o navegador manda
        // "Origin: https://...ts.net", mas o servidor recebe a conexão como http://172.17.0.1:18090 e acharia que é
        // outro site, recusando o login com 403 (já aconteceu). Liberar não abre brecha porque a API não usa cookies:
        // a sessão é um token Bearer que só o próprio site conhece (fica no localStorage da origem dele), então outro
        // site não tem como agir em nome de ninguém. Sem allowCredentials, o navegador também não envia cookies.
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
