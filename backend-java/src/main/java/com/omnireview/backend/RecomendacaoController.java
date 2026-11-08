package com.omnireview.backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class RecomendacaoController {

    static final int MAX_RECOMENDACOES = 20;
    private static final Set<String> TAGS_INVALIDAS = Set.of("deu erro", IaService.SEM_CLASSIFICACAO.toLowerCase());

    private final ObraRepository repository;
    private final TmdbService tmdbService;
    private final RawgService rawgService;
    private final IaService iaService;

    public RecomendacaoController(ObraRepository repository, TmdbService tmdbService, RawgService rawgService, IaService iaService) {
        this.repository = repository;
        this.tmdbService = tmdbService;
        this.rawgService = rawgService;
        this.iaService = iaService;
    }

    // ------------------------------------------------------------------ cadastro

    @PostMapping("/adicionar/filme")
    public ResponseEntity<Obra> adicionarFilme(@RequestParam String nome) {
        return adicionar(nome, "Filme");
    }

    @PostMapping("/adicionar/serie")
    public ResponseEntity<Obra> adicionarSerie(@RequestParam String nome) {
        return adicionar(nome, "Serie");
    }

    @PostMapping("/adicionar/jogo")
    public ResponseEntity<Obra> adicionarJogo(@RequestParam String nome) {
        return adicionar(nome, "Jogo");
    }

    private ResponseEntity<Obra> adicionar(String nome, String tipo) {
        if (nome == null || nome.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String[] dados = buscarDados(nome.trim(), tipo);
        if (dados == null || campo(dados, 0) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(classificarESalvar(dados, tipo));
    }

    private Obra classificarESalvar(String[] dados, String tipo) {
        Obra obra = repository.findById(dados[0]).orElseGet(() -> new Obra(dados[0], tipo, null));
        obra.setTipo(tipo);
        obra.setDnaCriativo(iaService.extrairDnaCriativo(dados[0], campo(dados, 1), tipo, campo(dados, 2)));
        aplicarDetalhes(obra, dados);
        return salvar(obra);
    }

    /** Resposta da busca da tela inicial: a obra e se ela acabou de entrar no acervo. */
    public record ResultadoBusca(Obra obra, boolean adicionada) {}

    private static final Map<String, String> TIPOS = Map.of("filme", "Filme", "serie", "Serie", "jogo", "Jogo");

    /**
     * "O que você quer assistir hoje?": devolve a obra se já estiver no acervo; senão, busca na web
     * (filmes e séries no TMDB, jogos na RAWG/Steam, em paralelo), escolhe o título mais parecido com o
     * que foi digitado, gera o DNA com a IA e salva. Com tipo (filme|serie|jogo), busca só naquela mídia.
     */
    @PostMapping("/buscar-ou-adicionar")
    public ResponseEntity<ResultadoBusca> buscarOuAdicionar(@RequestParam String nome, @RequestParam(required = false) String tipo) {
        String consulta = nome == null ? "" : nome.trim();
        if (consulta.isEmpty() || consulta.length() > 120) {
            return ResponseEntity.badRequest().build();
        }
        List<String> tipos;
        if (tipo == null || tipo.isBlank()) {
            tipos = List.of("Filme", "Serie", "Jogo");
        } else if (TIPOS.containsKey(tipo.toLowerCase())) {
            tipos = List.of(TIPOS.get(tipo.toLowerCase()));
        } else {
            return ResponseEntity.badRequest().build();
        }

        // 1) Já está no acervo?
        String chave = chaveDeBusca(consulta);
        Optional<Obra> local = repository.findAll().stream()
                .filter(o -> tipos.contains(o.getTipo()) && chaveDeBusca(o.getTitulo()).equals(chave))
                .findFirst();
        if (local.isPresent()) {
            return ResponseEntity.ok(new ResultadoBusca(local.get(), false));
        }

        // 2) Busca na web, nas mídias pedidas, em paralelo
        List<CompletableFuture<String[]>> buscas = tipos.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> buscarDados(consulta, t)).exceptionally(e -> null))
                .toList();
        List<String> titulos = new ArrayList<>();
        for (CompletableFuture<String[]> b : buscas) {
            titulos.add(campo(b.join(), 0));
        }
        int escolhido = escolherMaisParecido(consulta, titulos);
        if (escolhido < 0) {
            return ResponseEntity.notFound().build();
        }
        String[] dados = buscas.get(escolhido).join();
        String tipoEscolhido = tipos.get(escolhido);

        // 3) O título encontrado na web pode já estar no acervo (ex.: digitou "duna parte 2")
        Optional<Obra> existente = repository.findById(dados[0]);
        if (existente.isPresent()) {
            return ResponseEntity.ok(new ResultadoBusca(existente.get(), false));
        }
        return ResponseEntity.ok(new ResultadoBusca(classificarESalvar(dados, tipoEscolhido), true));
    }

    /** Título em forma comparável: sem acentos, minúsculo, só letras e números. */
    static String chaveDeBusca(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** 3 = igual, 2 = um começa com o outro, 1 = um contém o outro, 0 = diferente. */
    static int semelhanca(String consulta, String titulo) {
        String a = chaveDeBusca(consulta), b = chaveDeBusca(titulo);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 3;
        if (a.startsWith(b) || b.startsWith(a)) return 2;
        if (a.contains(b) || b.contains(a)) return 1;
        return 0;
    }

    /**
     * Índice do título mais parecido com a consulta (empate: o primeiro, na ordem filme, série, jogo);
     * -1 se nenhuma busca encontrou nada.
     */
    static int escolherMaisParecido(String consulta, List<String> titulos) {
        int melhor = -1, pontos = -1;
        for (int i = 0; i < titulos.size(); i++) {
            if (titulos.get(i) == null) continue;
            int p = semelhanca(consulta, titulos.get(i));
            if (p > pontos) { melhor = i; pontos = p; }
        }
        return melhor;
    }

    // ------------------------------------------------------------------ manutenção

    /**
     * Refaz o DNA criativo. Por padrão, só das obras que falharam ("Deu erro" ou "Sem classificação");
     * com todas=true, de todo o acervo (usado ao trocar o classificador).
     */
    @PostMapping("/reprocessar-dna")
    public List<Obra> reprocessarDna(@RequestParam(defaultValue = "false") boolean todas) {
        List<Obra> atualizadas = new ArrayList<>();
        for (Obra obra : repository.findAll()) {
            String dna = obra.getDnaCriativo();
            boolean falhou = dna == null || dna.isBlank() || TAGS_INVALIDAS.contains(dna.trim().toLowerCase());
            if (!todas && !falhou) {
                continue;
            }
            String[] dados = buscarDados(obra.getTitulo(), obra.getTipo());
            obra.setDnaCriativo(iaService.extrairDnaCriativo(obra.getTitulo(), campo(dados, 1), obra.getTipo(), campo(dados, 2)));
            aplicarDetalhes(obra, dados);
            atualizadas.add(salvar(obra));
            System.out.println("DNA refeito: " + obra.getTitulo() + " -> " + obra.getDnaCriativo());
        }
        return atualizadas;
    }

    /** Preenche sinopse e imagens que estiverem faltando, sem chamar a IA. */
    @PostMapping("/completar-detalhes")
    public List<Obra> completarDetalhes() {
        List<Obra> atualizadas = new ArrayList<>();
        for (Obra obra : repository.findAll()) {
            if (temSinopse(obra.getSinopse()) && obra.getImagem() != null) {
                continue;
            }
            String[] dados = buscarDados(obra.getTitulo(), obra.getTipo());
            if (dados != null) {
                aplicarDetalhes(obra, dados);
                atualizadas.add(repository.save(obra));
            }
        }
        return atualizadas;
    }

    // ------------------------------------------------------------------ consulta

    @GetMapping("/obras")
    public List<Obra> listarTodasObras() {
        return repository.findAll();
    }

    /**
     * Recomenda obras de qualquer mídia que compartilhem tags com o perfil.
     * Ex.: /api/recomendar?tags=Ação&tags=RPG&excluir=The Witcher 3
     * O ranking (IDF) vem do Neo4j; aqui o resultado é intercalado por mídia.
     */
    @GetMapping("/recomendar")
    public List<Obra> buscarRecomendacoes(
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) List<String> excluir,
            @RequestParam(required = false) String dna,
            @RequestParam(required = false) String titulo) {

        List<String> consulta = new ArrayList<>();
        if (tags != null) consulta.addAll(tags);
        if (dna != null) consulta.addAll(Arrays.asList(dna.split(",")));
        consulta = consulta.stream()
                .map(t -> t == null ? "" : t.trim())
                .filter(t -> !t.isEmpty() && !TAGS_INVALIDAS.contains(t.toLowerCase()))
                .distinct()
                .toList();
        if (consulta.isEmpty()) {
            return List.of();
        }
        List<String> excluidos = new ArrayList<>();
        if (excluir != null) excluidos.addAll(excluir);
        if (titulo != null) excluidos.add(titulo);

        return intercalarPorTipo(repository.recomendarPorTags(consulta, excluidos), MAX_RECOMENDACOES);
    }

    /** Agrupa por tipo mantendo a ordem do ranking e intercala (round-robin) até o limite. */
    static List<Obra> intercalarPorTipo(List<Obra> ranking, int limite) {
        Map<String, Deque<Obra>> porTipo = new LinkedHashMap<>();
        for (Obra o : ranking) {
            String tipo = o.getTipo() == null ? "" : o.getTipo().toLowerCase();
            porTipo.computeIfAbsent(tipo, k -> new ArrayDeque<>()).add(o);
        }
        List<Obra> saida = new ArrayList<>();
        while (saida.size() < limite && porTipo.values().stream().anyMatch(q -> !q.isEmpty())) {
            for (Deque<Obra> fila : porTipo.values()) {
                if (!fila.isEmpty() && saida.size() < limite) saida.add(fila.poll());
            }
        }
        return saida;
    }

    @GetMapping("/tendencias/filmes")
    public List<String> tendenciasFilmes() {
        return tmdbService.buscarTendenciasFilmes();
    }

    @GetMapping("/tendencias/series")
    public List<String> tendenciasSeries() {
        return tmdbService.buscarTendenciasSeries();
    }

    @GetMapping("/tendencias/jogos")
    public List<String> tendenciasJogos() {
        return rawgService.buscarTendencias();
    }

    // ------------------------------------------------------------------ auxiliares

    /** Busca a obra na API da mídia: {titulo, sinopse, generos, imagem, capa} ou null. */
    private String[] buscarDados(String nome, String tipo) {
        return switch (tipo == null ? "" : tipo.toLowerCase()) {
            case "filme" -> tmdbService.buscarFilmeNaInternet(nome);
            case "serie" -> tmdbService.buscarSerieNaInternet(nome);
            case "jogo" -> rawgService.buscarJogoNaInternet(nome);
            default -> null;
        };
    }

    private Obra salvar(Obra obra) {
        Obra salva = repository.save(obra);
        repository.sincronizarTags(salva.getTitulo());
        return salva;
    }

    private static String campo(String[] dados, int i) {
        return dados != null && dados.length > i ? dados[i] : null;
    }

    private static boolean temSinopse(String s) {
        return s != null && !s.isBlank() && !s.startsWith("Sem sinopse");
    }

    private static void aplicarDetalhes(Obra obra, String[] dados) {
        String sinopse = campo(dados, 1);
        if (temSinopse(sinopse) || !temSinopse(obra.getSinopse()) && sinopse != null) obra.setSinopse(sinopse);
        if (campo(dados, 3) != null) obra.setImagem(campo(dados, 3));
        if (campo(dados, 4) != null) obra.setCapa(campo(dados, 4));
    }
}
