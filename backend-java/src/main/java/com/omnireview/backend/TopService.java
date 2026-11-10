package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top 15 de cada mídia, buscado na internet e mantido atualizado:
 *  - filmes e séries: em alta na semana no TMDB;
 *  - jogos: jogando agora em todas as plataformas (IGDB) e, numa segunda lista que aparece na aba Jogos,
 *    os mais jogados agora na Steam (só PC);
 *  - livros: em alta na semana no Open Library (com o título e a capa da edição em português).
 * O que estiver no Top e ainda não estiver no catálogo é adicionado automaticamente (com o DNA da IA).
 * Cada lista fica no grafo como (:Top {tipo, titulos, atualizadoEm}); roda na inicialização e a cada
 * omnireview.top.intervalo (padrão 6 h). Desligue com omnireview.top.automatico=false.
 */
@Service
public class TopService {

    static final int TAMANHO = 15;
    static final int CANDIDATOS = 30;   // folga: alguns itens podem não ser encontrados ou não ser da mídia
    /** As listas: uma por mídia, mais a dos jogos da Steam ("JogoSteam", cujas obras são do tipo Jogo). */
    static final List<String> LISTAS = List.of("Filme", "Serie", "Jogo", "JogoSteam", "Livro");

    private final RecomendacaoController recomendacoes;
    private final TmdbService tmdb;
    private final JogosService jogos;
    private final LivroService livros;
    private final ObraRepository repository;
    private final Neo4jClient neo4j;
    private final boolean automatico;
    private final AtomicBoolean rodando = new AtomicBoolean(false);
    private volatile String mensagem = "";

    public TopService(RecomendacaoController recomendacoes, TmdbService tmdb, JogosService jogos, LivroService livros,
                      ObraRepository repository, Neo4jClient neo4j,
                      @Value("${omnireview.top.automatico:true}") boolean automatico) {
        this.recomendacoes = recomendacoes;
        this.tmdb = tmdb;
        this.jogos = jogos;
        this.livros = livros;
        this.repository = repository;
        this.neo4j = neo4j;
        this.automatico = automatico;
    }

    /**
     * Até novembro de 2026 a lista "Jogo" era a da Steam. Na primeira inicialização com as duas listas, ela é copiada
     * para "JogoSteam" (a aba Jogos já abre com as duas); a "Jogo" passa a ser o jogando agora do IGDB na próxima rodada.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void separarListaDaSteam() {
        try {
            neo4j.query("MATCH (j:Top {tipo: 'Jogo'}) WHERE NOT EXISTS { MATCH (:Top {tipo: 'JogoSteam'}) } " +
                        "CREATE (:Top {tipo: 'JogoSteam', titulos: j.titulos, atualizadoEm: j.atualizadoEm})").run();
        } catch (Exception e) {
            System.out.println("Top 15: não deu para separar a lista da Steam: " + e.getMessage());
        }
    }

    @Scheduled(initialDelayString = "${omnireview.top.atraso-inicial:PT1M}", fixedDelayString = "${omnireview.top.intervalo:PT6H}")
    public void agendado() {
        if (automatico) atualizar("sistema");
    }

    /** Pede a atualização em segundo plano (administrador). false se já estiver rodando. */
    public boolean atualizarEmSegundoPlano(String usuario) {
        if (rodando.get()) return false;
        Thread t = new Thread(() -> atualizar(usuario), "top15");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean rodando() {
        return rodando.get();
    }

    public void atualizar(String usuario) {
        if (!rodando.compareAndSet(false, true)) return;
        try {
            for (String lista : LISTAS) {
                mensagem = "Atualizando o Top 15 de " + NOMES.get(lista) + "...";
                try {
                    atualizarLista(lista, usuario);
                } catch (Exception e) {
                    System.out.println("Top 15 (" + lista + "): falha: " + e.getMessage());
                }
            }
            mensagem = "";
        } finally {
            rodando.set(false);
        }
    }

    private static final Map<String, String> NOMES = Map.of("Filme", "filmes", "Serie", "séries", "Jogo", "jogos",
            "JogoSteam", "jogos da Steam", "Livro", "livros");

    /** Tipo das obras da lista ("JogoSteam" é uma lista de jogos). */
    static String tipoDaObra(String lista) {
        return "JogoSteam".equals(lista) ? "Jogo" : lista;
    }

    private List<String[]> daInternet(String lista) {
        return switch (lista) {
            case "Filme" -> tmdb.emAlta("movie", CANDIDATOS);
            case "Serie" -> tmdb.emAlta("tv", CANDIDATOS);
            case "Jogo" -> jogos.jogandoAgora(CANDIDATOS);
            case "JogoSteam" -> jogos.maisJogadosNaSteam(CANDIDATOS);
            case "Livro" -> livros.emAltaComRef(CANDIDATOS);
            default -> List.of();
        };
    }

    private void atualizarLista(String lista, String usuario) {
        String tipo = tipoDaObra(lista);
        List<String[]> itens = daInternet(lista);
        if (itens.isEmpty()) return;   // fonte fora do ar: mantém a lista anterior
        List<String> anteriores = titulos(lista);
        List<String> novos = new ArrayList<>();
        int adicionadas = 0;
        for (String[] item : itens) {
            if (novos.size() >= TAMANHO) break;
            try {
                long antes = repository.count();
                Optional<Obra> obra = recomendacoes.garantirNoCatalogo(item[0], item[1], tipo, usuario, "Top 15 automático (em alta na internet)");
                if (obra.isPresent() && tipo.equals(obra.get().getTipo()) && !novos.contains(obra.get().getTitulo())) {
                    novos.add(obra.get().getTitulo());
                    if (repository.count() > antes) adicionadas++;
                    // Enquanto atualiza, o carrossel continua cheio: os novos primeiro, completados pela lista anterior
                    salvar(lista, mesclar(novos, anteriores));
                }
            } catch (Exception e) {
                System.out.println("Top 15 (" + lista + "): não deu para incluir '" + item[1] + "': " + e.getMessage());
            }
        }
        if (!novos.isEmpty()) salvar(lista, novos);
        System.out.println("Top 15 de " + NOMES.get(lista) + " atualizado: " + novos.size() + " obras (" + adicionadas + " novas no catálogo).");
    }

    static List<String> mesclar(List<String> novos, List<String> anteriores) {
        LinkedHashSet<String> m = new LinkedHashSet<>(novos);
        for (String a : anteriores) {
            if (m.size() >= TAMANHO) break;
            m.add(a);
        }
        return new ArrayList<>(m);
    }

    private void salvar(String tipo, List<String> titulos) {
        neo4j.query("MERGE (t:Top {tipo: $tipo}) SET t.titulos = $titulos, t.atualizadoEm = $agora")
                .bindAll(Map.of("tipo", tipo, "titulos", titulos, "agora", Instant.now().toString())).run();
    }

    /** A lista guardada daquela mídia (vazia se ainda não houver). */
    private List<String> titulos(String tipo) {
        return lista(neo4j.query("MATCH (t:Top {tipo: $tipo}) RETURN t.titulos AS titulos").bindAll(Map.of("tipo", tipo))
                .fetch().one().map(m -> m.get("titulos")).orElse(null));
    }

    /** Converte o valor lido do grafo (os títulos já vêm como String pelo fetch()). */
    static List<String> lista(Object valor) {
        if (!(valor instanceof Collection<?> c)) return List.of();
        return c.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    /** Os Top 15 (filme, serie, jogo, jogosteam, livro) com as obras, na ordem do ranking, e quando cada um foi atualizado. */
    public Map<String, Object> top() {
        Map<String, Object> corpo = new LinkedHashMap<>();
        Map<String, Object> quando = new LinkedHashMap<>();
        Map<String, Map<String, Object>> guardados = new HashMap<>();
        neo4j.query("MATCH (t:Top) RETURN t.tipo AS tipo, t.titulos AS titulos, t.atualizadoEm AS em").fetch().all()
                .forEach(m -> guardados.put(String.valueOf(m.get("tipo")), m));
        for (String tipo : LISTAS) {
            Map<String, Object> guardado = guardados.getOrDefault(tipo, Map.of());
            List<String> titulos = lista(guardado.get("titulos"));
            Map<String, Obra> porTitulo = new HashMap<>();
            repository.findAllById(titulos).forEach(o -> porTitulo.put(o.getTitulo(), o));
            corpo.put(tipo.toLowerCase(Locale.ROOT), titulos.stream().map(porTitulo::get).filter(Objects::nonNull).toList());
            quando.put(tipo.toLowerCase(Locale.ROOT), guardado.get("em") == null ? null : String.valueOf(guardado.get("em")));
        }
        corpo.put("atualizadoEm", quando);
        corpo.put("atualizando", rodando.get());
        corpo.put("mensagem", mensagem);
        return corpo;
    }
}
