package com.omnireview.backend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Proteções em todas as respostas:
 *  - cabeçalhos de segurança: CSP (só scripts/estilos do próprio site e fontes do Google; imagens https), proibição
 *    de abrir o site dentro de iframes de outros sites (clickjacking), nosniff, Referrer-Policy e Permissions-Policy;
 *  - respostas da API sem cache (dados pessoais não ficam guardados em proxies nem no disco do navegador);
 *  - limite de tamanho do corpo das requisições ({@link #MAX_CORPO}): um envio gigante não derruba o servidor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SegurancaFiltro extends OncePerRequestFilter {

    /** A maior coisa que o site envia é a capa de fundo do perfil (~1,5 MB em base64). */
    static final long MAX_CORPO = 3L * 1024 * 1024;

    static final String CSP = String.join("; ",
            "default-src 'self'",
            // A interface usa scripts e atributos onclick inline; todo dado é escapado antes de ir para o HTML
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' data: blob: https:",   // blob: é a foto escolhida no editor de perfil, antes de recortar
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain cadeia)
            throws ServletException, IOException {
        resp.setHeader("Content-Security-Policy", CSP);
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        resp.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        resp.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (req.getRequestURI().startsWith("/api/")) resp.setHeader("Cache-Control", "no-store");

        long tamanho = req.getContentLengthLong();
        if (tamanho > MAX_CORPO) {
            AutenticacaoInterceptor.responderErro(resp, 413, "Envio grande demais.");
            return;
        }
        // Sem Content-Length (envio em partes), conta os bytes enquanto lê
        cadeia.doFilter(tamanho < 0 && temCorpo(req) ? new CorpoLimitado(req) : req, resp);
    }

    private static boolean temCorpo(HttpServletRequest req) {
        String m = req.getMethod();
        return "POST".equalsIgnoreCase(m) || "PUT".equalsIgnoreCase(m) || "PATCH".equalsIgnoreCase(m) || "DELETE".equalsIgnoreCase(m);
    }

    /** Requisição cujo corpo para de ser lido (com erro) ao passar de {@link #MAX_CORPO}. */
    static final class CorpoLimitado extends HttpServletRequestWrapper {
        private ServletInputStream entrada;

        CorpoLimitado(HttpServletRequest req) {
            super(req);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (entrada == null) {
                ServletInputStream original = super.getInputStream();
                entrada = new ServletInputStream() {
                    private long lidos;

                    private void contar(long n) throws IOException {
                        if (n > 0 && (lidos += n) > MAX_CORPO) throw new IOException("Envio grande demais.");
                    }

                    @Override public int read() throws IOException {
                        int b = original.read();
                        if (b >= 0) contar(1);
                        return b;
                    }
                    @Override public int read(byte[] buf, int off, int len) throws IOException {
                        int n = original.read(buf, off, len);
                        contar(n);
                        return n;
                    }
                    @Override public boolean isFinished() { return original.isFinished(); }
                    @Override public boolean isReady() { return original.isReady(); }
                    @Override public void setReadListener(ReadListener l) { original.setReadListener(l); }
                };
            }
            return entrada;
        }
    }
}
