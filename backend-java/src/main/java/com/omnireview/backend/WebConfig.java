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
        // O site é servido pelo próprio back-end (mesma origem) e não precisa de CORS. Só o protótipo React rodando
        // no computador de quem desenvolve (Vite, localhost) pode chamar a API de outra origem.
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
