package com.omnireview.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AutenticacaoService auth;

    public WebConfig(AutenticacaoService auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AutenticacaoInterceptor(auth))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/cadastrar", "/api/auth/entrar");
        // Rotas só do administrador (roda depois do interceptor acima, que identifica a sessão)
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns(AdminInterceptor.ROTAS_ADMIN);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Permite o protótipo React (Vite) e outras origens locais chamarem a API com o token
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
