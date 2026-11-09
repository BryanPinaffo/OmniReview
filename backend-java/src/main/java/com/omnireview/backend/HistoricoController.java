package com.omnireview.backend;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Histórico de atividades (só o administrador: rota em /api/admin/**, barrada pelo {@link AdminInterceptor}). */
@RestController
@RequestMapping("/api/admin/historico")
public class HistoricoController {

    private final HistoricoService historico;

    public HistoricoController(HistoricoService historico) {
        this.historico = historico;
    }

    /** GET /api/admin/historico?categoria=obra|conta|avaliacao|lista&q=texto&antes=<ms>&limite=50 */
    @GetMapping
    public Map<String, Object> listar(@RequestParam(required = false) String categoria,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) Long antes,
                                      @RequestParam(defaultValue = "50") int limite) {
        return historico.listar(categoria, q, antes, limite);
    }

    /** Quem adicionou mais obras ao acervo. */
    @GetMapping("/autores")
    public List<Map<String, Object>> autores() {
        return historico.autores();
    }
}
