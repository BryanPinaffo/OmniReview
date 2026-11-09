package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Na primeira vez que o OmniReview sobe sem nenhum livro no acervo, importa os livros em alta
 * (até {@link LivroService#MAX_TENDENCIAS}) em segundo plano, com sinopse em português e tags da IA.
 * Roda depois do {@link InicializacaoGrafo} (que espera o Neo4j). Desligue com omnireview.livros.importar-iniciais=false.
 */
@Component
@Order(3)
public class LivrosIniciais implements ApplicationRunner {

    private final Neo4jClient neo4j;
    private final RecomendacaoController recomendacoes;
    private final boolean ligado;

    public LivrosIniciais(Neo4jClient neo4j, RecomendacaoController recomendacoes,
                          @Value("${omnireview.livros.importar-iniciais:true}") boolean ligado) {
        this.neo4j = neo4j;
        this.recomendacoes = recomendacoes;
        this.ligado = ligado;
    }

    static final String MARCA = "MERGE (c:Configuracao {chave: 'livrosIniciais'}) SET c.concluidaEm = $agora";

    @Override
    public void run(ApplicationArguments args) {
        if (!ligado) return;
        long faltam;
        try {
            // Já terminou uma vez? (a marca só é gravada no fim: se o servidor cair no meio, continua na próxima vez)
            boolean feita = neo4j.query("MATCH (c:Configuracao {chave: 'livrosIniciais'}) RETURN count(c) > 0 AS f")
                    .fetchAs(Boolean.class).one().orElse(false);
            if (feita) return;
            long livros = neo4j.query("MATCH (o:Obra {tipo: 'Livro'}) RETURN count(o) AS n").fetchAs(Long.class).one().orElse(0L);
            faltam = LivroService.MAX_TENDENCIAS - livros;
        } catch (Exception e) {
            System.out.println("Livros iniciais: banco indisponível (" + e.getMessage() + ")");
            return;
        }
        if (faltam <= 0) { marcarConcluida(); return; }
        final int limite = (int) faltam;
        Thread t = new Thread(() -> {
            System.out.println("Livros iniciais: importando até " + limite + " livro(s) em alta em segundo plano...");
            int n = recomendacoes.importarLivrosEmAlta("sistema", "importação automática dos livros em alta (Open Library)", limite);
            System.out.println("Livros iniciais: " + n + " livro(s) adicionado(s).");
            if (n > 0) marcarConcluida();   // sem nada novo (ex.: sem internet), tenta de novo na próxima inicialização
        }, "livros-iniciais");
        t.setDaemon(true);
        t.start();
    }

    private void marcarConcluida() {
        try {
            neo4j.query(MARCA).bindAll(java.util.Map.of("agora", java.time.Instant.now().toString())).run();
        } catch (Exception e) {
            System.out.println("Livros iniciais: não foi possível gravar a conclusão (" + e.getMessage() + ")");
        }
    }
}
