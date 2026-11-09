package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Top 15 de filmes, séries, jogos e livros (em alta na internet), atualizado sozinho pelo {@link TopService}. */
@RestController
public class TopController {

    private final TopService top;

    public TopController(TopService top) {
        this.top = top;
    }

    @GetMapping("/api/top")
    public Map<String, Object> top() {
        return top.top();
    }

    /** O administrador pede a atualização agora (roda em segundo plano). */
    @PostMapping("/api/admin/top/atualizar")
    public ResponseEntity<Map<String, Object>> atualizar(HttpServletRequest req) {
        String admin = (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
        boolean iniciou = top.atualizarEmSegundoPlano(admin);
        return ResponseEntity.status(202).body(Map.of("iniciou", iniciou, "atualizando", true));
    }
}
