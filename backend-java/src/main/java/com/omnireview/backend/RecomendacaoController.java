package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;
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
    private final JogosService jogosService;
    private final IaService iaService;
    private final HistoricoService historico;
    private final LivroService livroService;

    public RecomendacaoController(ObraRepository repository, TmdbService tmdbService, JogosService jogosService, IaService iaService,
                                  HistoricoService historico, LivroService livroService) {
        this.historico = historico;
        this.livroService = livroService;
        this.repository = repository;
        this.tmdbService = tmdbService;
        this.jogosService = jogosService;
        this.iaService = iaService;
    }

    // ------------------------------------------------------------------ cadastro

    @PostMapping("/adicionar/filme")
    public ResponseEntity<Obra> adicionarFilme(@RequestParam String nome, HttpServletRequest req) {
        return adicionar(nome, "Filme", usuario(req));
    }

    @PostMapping("/adicionar/serie")
    public ResponseEntity<Obra> adicionarSerie(@RequestParam String nome, HttpServletRequest req) {
        return adicionar(nome, "Serie", usuario(req));
    }

    @PostMapping("/adicionar/jogo")
    public ResponseEntity<Obra> adicionarJogo(@RequestParam String nome, HttpServletRequest req) {
        return adicionar(nome, "Jogo", usuario(req));
    }

    @PostMapping("/adicionar/livro")
    public ResponseEntity<Obra> adicionarLivro(@RequestParam String nome, HttpServletRequest req) {
        return adicionar(nome, "Livro", usuario(req));
    }

    /**
     * A obra deste item da web no catálogo, adicionando-a se ainda não estiver (usado pelo Top 15 automático).
     * Procura pelo identificador da fonte; obras antigas (sem fonte) com o mesmo título e mídia contam como a mesma
     * e passam a guardar a fonte. Com ref, busca exatamente aquele item; sem ref, pelo nome.
     */
    public Optional<Obra> garantirNoCatalogo(String ref, String nome, String tipo, String usuario, String origem) {
        if (ref != null) {
            Optional<Obra> ja = repository.findFirstByFonte(ref);
            if (ja.isPresent()) return ja;
        }
        String[] dados = ref != null ? dadosPorRef(ref) : buscarDados(nome, tipo);
        if (dados == null || campo(dados, 0) == null) return Optional.empty();
        String fonte = campo(dados, 5);
        // Pela fonte do item ou pela outra (o jogo do Top 15 da Steam pode já ter vindo do IGDB pela busca)
        Optional<Obra> ja = obraDaFonte(dados);
        if (ja.isPresent()) {
            List<String> outras = ja.get().getOutrasFontes();
            if (ref != null && !ref.equals(ja.get().getFonte()) && (outras == null || !outras.contains(ref))) {
                ja.get().adicionarOutraFonte(ref);   // da próxima vez, acha direto pelo id da Steam
                repository.save(ja.get());
            }
            return ja;
        }
        // Livro com o mesmo título (em português ou o original) é o mesmo livro, mesmo vindo de outra fonte
        // (Google Books x Open Library); filmes e séries com o mesmo nome podem ser obras diferentes (remakes)
        boolean livro = "Livro".equals(tipo);
        Optional<Obra> antiga = mesmaObraNoAcervo(dados[0], tipo)
                .or(() -> livro && nome != null ? mesmaObraNoAcervo(nome, tipo) : Optional.empty())
                .filter(o -> semFonteConfiavel(o) || livro);
        if (antiga.isPresent()) {
            if (fonte != null && semFonteConfiavel(antiga.get())) {
                antiga.get().trocarFonte(fonte);
                antiga.get().adicionarOutraFonte(campo(dados, 7));
                if (ref != null) antiga.get().adicionarOutraFonte(ref);
                repository.save(antiga.get());
            }
            return antiga;
        }
        return Optional.of(classificarESalvar(dados, tipo, usuario, origem, true));
    }

    /**
     * Importa até {@code limite} livros em alta (Open Library) que ainda não estão no acervo. Usado na primeira
     * inicialização com livros (ver {@link LivrosIniciais}); devolve quantos livros entraram.
     */
    public int importarLivrosEmAlta(String usuario, String origem, int limite) {
        int novos = 0;
        for (String[] item : livroService.emAltaComRef(Math.max(limite, LivroService.MAX_TENDENCIAS) * 2)) {
            if (novos >= limite) break;
            try {
                long antes = repository.count();
                garantirNoCatalogo(item[0], item[1], "Livro", usuario, origem);
                if (repository.count() > antes) novos++;
            } catch (Exception e) {
                System.out.println("Livros em alta: falha em '" + item[1] + "': " + e.getMessage());
            }
        }
        return novos;
    }

    private ResponseEntity<Obra> adicionar(String nome, String tipo, String usuario) {
        if (nome == null || nome.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String[] dados = buscarDados(nome.trim(), tipo);
        if (dados == null || campo(dados, 0) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(classificarESalvar(dados, tipo, usuario, "pela Administração"));
    }

    private Obra classificarESalvar(String[] dadosDaWeb, String tipo, String usuario, String origem) {
        return classificarESalvar(dadosDaWeb, tipo, usuario, origem, false);
    }

    /**
     * @param escolhaExplicita a pessoa escolheu este item exato (pelo id da fonte): se ele ainda não está no acervo,
     *                         entra como obra nova, com título próprio se o nome já estiver em uso ("Duna (1984)").
     */
    private Obra classificarESalvar(String[] dadosDaWeb, String tipo, String usuario, String origem, boolean escolhaExplicita) {
        String[] dados = comSinopseEmPortugues(dadosDaWeb);
        String fonte = campo(dados, 5);
        Optional<Obra> mesmaFonte = obraDaFonte(dados);
        if (mesmaFonte.isPresent()) dados[0] = mesmaFonte.get().getTitulo();
        else if (escolhaExplicita) dados[0] = tituloUnico(dados[0], tipo, campo(dados, 6));
        else dados[0] = tituloNoAcervo(dados[0], tipo);   // "Duna" livro e "Duna" filme não podem ocupar o mesmo título
        Optional<Obra> existente = mesmaFonte.isPresent() ? mesmaFonte : repository.findById(dados[0]);
        Obra obra = existente.orElseGet(() -> new Obra(dados[0], tipo, null));
        boolean nova = existente.isEmpty();
        if (nova) {
            obra.setAdicionadaPor(usuario);
            obra.setAdicionadaEm(java.time.Instant.now().toString());
        }
        obra.setTipo(tipo);
        if (fonte != null) obra.trocarFonte(fonte);   // a fonte anterior (ex.: "steam:ID") continua reconhecendo a obra
        obra.adicionarOutraFonte(campo(dados, 7));
        obra.setDnaCriativo(iaService.extrairDnaCriativo(dados[0], campo(dados, 1), tipo, campo(dados, 2)));
        aplicarDetalhes(obra, dados);
        Obra salva = salvar(obra);
        historico.registrar(usuario, "obra", (nova ? "adicionou " : "atualizou ") + HistoricoService.nomeDoTipo(tipo),
                salva.getTitulo(), salva.getTitulo(), origem);
        return salva;
    }

    /** Resposta da busca da tela inicial: a obra e se ela acabou de entrar no acervo. */
    /**
     * Resposta da busca da tela inicial: a obra e se ela acabou de entrar no acervo; ou, quando o nome existe com a
     * mesma força em mais de uma mídia (ex.: "Duna" filme e livro), só as opções, para a pessoa escolher (nada é salvo).
     */
    public record ResultadoBusca(Obra obra, boolean adicionada, List<Map<String, Object>> opcoes) {
        static ResultadoBusca obra(Obra obra, boolean adicionada) { return new ResultadoBusca(obra, adicionada, null); }
        static ResultadoBusca escolher(List<Map<String, Object>> opcoes) { return new ResultadoBusca(null, false, opcoes); }
    }

    private static final Map<String, String> TIPOS = Map.of("filme", "Filme", "serie", "Serie", "jogo", "Jogo", "livro", "Livro");

    /**
     * "O que você quer assistir hoje?": devolve a obra se já estiver no acervo; senão, busca na web
     * (filmes e séries no TMDB, jogos no IGDB/Steam, livros no Google Books/Open Library, em paralelo), escolhe o título mais parecido com o
     * que foi digitado, gera o DNA com a IA e salva. Com tipo (filme|serie|jogo|livro), busca só naquela mídia.
     * Sem tipo, se mais de uma mídia tiver um título igualmente parecido, devolve as opções para a pessoa escolher.
     * Com ignorarAcervo=true, não para no acervo (para procurar o mesmo nome em outra mídia).
     */
    @PostMapping("/buscar-ou-adicionar")
    public ResponseEntity<ResultadoBusca> buscarOuAdicionar(@RequestParam String nome, @RequestParam(required = false) String tipo,
                                                            @RequestParam(defaultValue = "false") boolean ignorarAcervo,
                                                            @RequestParam(required = false) String ref,
                                                            HttpServletRequest req) {
        // A pessoa confirmou um item exato da lista de opções: salva ele (ou abre, se já estiver no acervo)
        if (ref != null && !ref.isBlank()) {
            String tipoRef = tipoDaRef(ref);
            if (tipoRef == null) return ResponseEntity.badRequest().build();
            Optional<Obra> jaTem = repository.findFirstByFonte(ref);
            if (jaTem.isPresent()) return ResponseEntity.ok(ResultadoBusca.obra(jaTem.get(), false));
            String[] dados = dadosPorRef(ref);
            if (dados == null || campo(dados, 0) == null) return ResponseEntity.notFound().build();
            // O mesmo jogo pode já estar no catálogo por outra fonte (ex.: veio do Top 15 da Steam)
            Optional<Obra> pelaOutraFonte = obraDaFonte(dados);
            if (pelaOutraFonte.isPresent()) return ResponseEntity.ok(ResultadoBusca.obra(pelaOutraFonte.get(), false));
            return ResponseEntity.ok(ResultadoBusca.obra(classificarESalvar(dados, tipoRef, usuario(req),
                    "pela busca “O que você quer ver hoje?”, confirmada pela pessoa", true), true));
        }
        String consulta = nome == null ? "" : nome.trim();
        if (consulta.isEmpty() || consulta.length() > 120) {
            return ResponseEntity.badRequest().build();
        }
        List<String> tipos;
        if (tipo == null || tipo.isBlank()) {
            tipos = List.of("Filme", "Serie", "Jogo", "Livro");
        } else if (TIPOS.containsKey(tipo.toLowerCase())) {
            tipos = List.of(TIPOS.get(tipo.toLowerCase()));
        } else {
            return ResponseEntity.badRequest().build();
        }

        // 1) Já está no acervo? (com o mesmo nome em mais de uma mídia, pergunta qual)
        String chave = chaveDeBusca(consulta);
        List<Obra> locais = ignorarAcervo ? List.of() : repository.findAll().stream()
                .filter(o -> tipos.contains(o.getTipo()) && chaveDeBusca(semSufixoDeMidia(o.getTitulo())).equals(chave))
                .toList();
        if (locais.size() == 1) {
            return ResponseEntity.ok(ResultadoBusca.obra(locais.get(0), false));
        }
        if (locais.size() > 1) {
            return ResponseEntity.ok(ResultadoBusca.escolher(locais.stream()
                    .map(o -> opcao(o.getTipo(), o.getTitulo(), o.getImagem() != null ? o.getImagem() : o.getCapa(), o.getSinopse(), true))
                    .toList()));
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
        // Mais de uma mídia com título igualmente parecido: a pessoa escolhe (nada é salvo agora)
        List<Integer> empatados = empatados(consulta, titulos);
        if (empatados.size() > 1) {
            return ResponseEntity.ok(ResultadoBusca.escolher(empatados.stream().map(i -> {
                String[] d = buscas.get(i).join();
                return opcao(tipos.get(i), d[0], campo(d, 3) != null ? campo(d, 3) : campo(d, 4), campo(d, 1),
                        mesmaObraNoAcervo(d[0], tipos.get(i)).isPresent());
            }).toList()));
        }
        String[] dados = buscas.get(escolhido).join();
        String tipoEscolhido = tipos.get(escolhido);

        // 3) O título encontrado na web pode já estar no acervo (ex.: digitou "duna parte 2")
        Optional<Obra> existente = mesmaObraNoAcervo(dados[0], tipoEscolhido);
        if (existente.isPresent()) {
            return ResponseEntity.ok(ResultadoBusca.obra(existente.get(), false));
        }
        return ResponseEntity.ok(ResultadoBusca.obra(classificarESalvar(dados, tipoEscolhido, usuario(req),
                "pela busca “O que você quer assistir hoje?” (digitou “" + consulta + "”)"), true));
    }

    static final int OPCOES_POR_MIDIA_ESPECIFICA = 6;
    static final int OPCOES_POR_MIDIA_QUALQUER = 3;
    static final int MAX_OPCOES = 8;

    /**
     * Opções para a pessoa confirmar antes de adicionar (nada é salvo aqui). Com tipo, até 6 resultados daquela mídia;
     * sem tipo, até 3 de cada mídia. O título igual ao digitado vem primeiro; o resto, na ordem de cada fonte (que já
     * pesa a parecença do nome e a popularidade), intercalando as mídias. Cada opção:
     * {ref, tipo, titulo, ano, imagem, sinopse|detalhe, jaNoAcervo, tituloNoAcervo}.
     */
    @GetMapping("/buscar-opcoes")
    public ResponseEntity<Map<String, Object>> buscarOpcoes(@RequestParam String nome, @RequestParam(required = false) String tipo) {
        String consulta = nome == null ? "" : nome.trim();
        if (consulta.isEmpty() || consulta.length() > 120) return ResponseEntity.badRequest().build();
        List<String> tipos;
        if (tipo == null || tipo.isBlank()) tipos = List.of("Filme", "Serie", "Jogo", "Livro");
        else if (TIPOS.containsKey(tipo.toLowerCase(Locale.ROOT))) tipos = List.of(TIPOS.get(tipo.toLowerCase(Locale.ROOT)));
        else return ResponseEntity.badRequest().build();
        int porMidia = tipos.size() == 1 ? OPCOES_POR_MIDIA_ESPECIFICA : OPCOES_POR_MIDIA_QUALQUER;

        List<CompletableFuture<List<Map<String, Object>>>> buscas = tipos.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> candidatosDe(t, consulta, porMidia)).exceptionally(e -> List.of()))
                .toList();
        List<Map<String, Object>> opcoes = new ArrayList<>();
        for (int i = 0; i < tipos.size(); i++) {
            String t = tipos.get(i);
            Set<String> titulosVistos = new HashSet<>();
            int ordem = 0;
            for (Map<String, Object> c : buscas.get(i).join()) {
                Map<String, Object> o = new LinkedHashMap<>(c);
                String titulo = (String) c.get("titulo");
                o.put("tipo", t.toLowerCase(Locale.ROOT));
                // Já no acervo? Pelo id da fonte (ou pelo da Steam, no jogo do IGDB); nas obras antigas (sem fonte),
                // só o 1º resultado com aquele título
                Optional<Obra> noAcervo = repository.findFirstByFonte((String) c.get("ref"));
                if (noAcervo.isEmpty() && o.remove("refSteam") instanceof String refSteam) noAcervo = repository.findFirstByFonte(refSteam);
                o.remove("refSteam");
                if (noAcervo.isEmpty() && titulosVistos.add(chaveDeBusca(titulo))) {
                    noAcervo = mesmaObraNoAcervo(titulo, t).filter(RecomendacaoController::semFonteConfiavel);
                }
                o.put("jaNoAcervo", noAcervo.isPresent());
                noAcervo.ifPresent(obra -> o.put("tituloNoAcervo", obra.getTitulo()));
                if (o.get("sinopse") instanceof String s && s.length() > 180) {
                    int corte = s.lastIndexOf(' ', 180);
                    o.put("sinopse", s.substring(0, corte > 80 ? corte : 180) + "…");
                }
                // A ordem de cada fonte já pesa nome e popularidade; aqui só o título igual ao digitado sobe para o topo
                // (antes, "começa com" passava na frente: "zelda" mostrava o Zelda II antes do Breath of the Wild)
                o.put("_pontos", (semelhanca(consulta, titulo) == 3 ? 1000 : 0) - ordem++ * 10 - i);
                opcoes.add(o);
            }
        }
        opcoes.sort(Comparator.comparingInt((Map<String, Object> o) -> (int) o.get("_pontos")).reversed());
        List<Map<String, Object>> saida = opcoes.stream().limit(MAX_OPCOES).toList();
        saida.forEach(o -> o.remove("_pontos"));
        return ResponseEntity.ok(Map.of("opcoes", saida));
    }

    private List<Map<String, Object>> candidatosDe(String tipo, String nome, int maximo) {
        return switch (tipo) {
            case "Filme" -> tmdbService.candidatos("movie", nome, maximo);
            case "Serie" -> tmdbService.candidatos("tv", nome, maximo);
            case "Jogo" -> jogosService.candidatos(nome, maximo);
            case "Livro" -> livroService.candidatos(nome, maximo);
            default -> List.of();
        };
    }

    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile(
            "tmdb:(movie|tv):([0-9]{1,10})|igdb:([0-9]{1,10})|steam:([0-9]{1,10})|ol:(/works/OL[0-9]{1,12}W)|gb:([A-Za-z0-9_-]{8,20})");

    /** Mídia de um identificador de fonte, ou null se o formato for inválido (nada fora deste formato é aceito). */
    static String tipoDaRef(String ref) {
        java.util.regex.Matcher m = ref == null ? null : REF.matcher(ref);
        if (m == null || !m.matches()) return null;
        if (m.group(1) != null) return m.group(1).equals("tv") ? "Serie" : "Filme";
        if (m.group(3) != null || m.group(4) != null) return "Jogo";
        return "Livro";
    }

    private String[] dadosPorRef(String ref) {
        java.util.regex.Matcher m = REF.matcher(ref);
        if (!m.matches()) return null;
        if (m.group(1) != null) return tmdbService.porId(m.group(1), Long.parseLong(m.group(2)));
        if (m.group(3) != null) return jogosService.porIgdb(Long.parseLong(m.group(3)));
        if (m.group(4) != null) return jogosService.porSteam(m.group(4));
        if (m.group(6) != null) return livroService.porVolumeGoogle(m.group(6));
        return livroService.porChave(m.group(5));
    }

    /**
     * Título livre no acervo para uma obra nova escolhida pela pessoa. O nome puro, se ninguém usa; senão, com a
     * mídia (outra mídia já tem esse nome) e/ou o ano (a mesma mídia já tem: "Duna (1984)").
     */
    private String tituloUnico(String titulo, String tipo, String ano) {
        if (!repository.existsById(titulo)) return titulo;
        String midia = NOME_MIDIA.getOrDefault(tipo, tipo);
        boolean outraMidia = repository.findById(titulo).map(o -> !tipo.equals(o.getTipo())).orElse(false);
        List<String> tentativas = new ArrayList<>();
        if (outraMidia) tentativas.add(titulo + " (" + midia + ")");
        if (ano != null) tentativas.add(titulo + " (" + ano + ")");
        if (!outraMidia) tentativas.add(titulo + " (" + midia + ")");
        if (ano != null) tentativas.add(titulo + " (" + midia + ", " + ano + ")");
        for (String t : tentativas) if (!repository.existsById(t)) return t;
        for (int n = 2; ; n++) if (!repository.existsById(titulo + " (" + n + ")")) return titulo + " (" + n + ")";
    }

    /**
     * Índices das mídias empatadas no melhor resultado, quando o empate é forte (título igual ou um começando
     * com o outro). Ex.: "Duna" acha o filme "Duna" e o livro "Duna" (ambos iguais): pergunta. Já "Duna" com filme
     * "Duna" (igual) e jogo "Dune: Awakening" (diferente) não pergunta.
     */
    static List<Integer> empatados(String consulta, List<String> titulos) {
        int melhor = 0;
        for (String t : titulos) if (t != null) melhor = Math.max(melhor, semelhanca(consulta, t));
        if (melhor < 2) return List.of();
        List<Integer> saida = new ArrayList<>();
        for (int i = 0; i < titulos.size(); i++) {
            if (titulos.get(i) != null && semelhanca(consulta, titulos.get(i)) == melhor) saida.add(i);
        }
        return saida;
    }

    private static Map<String, Object> opcao(String tipo, String titulo, String imagem, String sinopse, boolean jaNoAcervo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo == null ? "" : tipo.toLowerCase(Locale.ROOT));
        m.put("titulo", titulo);
        m.put("imagem", imagem);
        String s = sinopse == null || sinopse.startsWith("Sem sinopse") ? null : sinopse.replaceAll("\\s+", " ").strip();
        m.put("sinopse", s == null ? null : s.length() <= 180 ? s : s.substring(0, s.lastIndexOf(' ', 180) > 80 ? s.lastIndexOf(' ', 180) : 180) + "…");
        m.put("jaNoAcervo", jaNoAcervo);
        return m;
    }

    private static final Map<String, String> NOME_MIDIA = Map.of("Filme", "Filme", "Serie", "Série", "Jogo", "Jogo", "Livro", "Livro");
    private static final java.util.regex.Pattern SUFIXO_MIDIA = java.util.regex.Pattern.compile(
            "\\s+\\((?:Filme|Série|Jogo|Livro|(?:Filme|Série|Jogo|Livro), (?:19|20)[0-9]{2}|(?:19|20)[0-9]{2})\\)$");

    /** "Duna (Filme)" -> "Duna": o sufixo só existe para separar obras de mídias diferentes com o mesmo nome. */
    static String semSufixoDeMidia(String titulo) {
        return titulo == null ? null : SUFIXO_MIDIA.matcher(titulo).replaceFirst("");
    }

    /** A obra deste título nesta mídia, esteja ela com o título puro ou com o sufixo da mídia. */
    private Optional<Obra> mesmaObraNoAcervo(String titulo, String tipo) {
        Optional<Obra> puro = repository.findById(titulo);
        if (puro.isPresent() && tipo.equals(puro.get().getTipo())) return puro;
        return repository.findById(titulo + " (" + NOME_MIDIA.getOrDefault(tipo, tipo) + ")")
                .filter(o -> tipo.equals(o.getTipo()));
    }

    /**
     * A obra do catálogo que veio destes dados: pela fonte (índice 5) ou pela outra fonte (índice 7; ex.: o jogo do
     * IGDB que já entrou pelo id da Steam, no Top 15).
     */
    private Optional<Obra> obraDaFonte(String[] dados) {
        String fonte = campo(dados, 5), outra = campo(dados, 7);
        Optional<Obra> pelaFonte = fonte == null ? Optional.empty() : repository.findFirstByFonte(fonte);
        return pelaFonte.isPresent() || outra == null ? pelaFonte : repository.findFirstByFonte(outra);
    }

    /**
     * Obra sem fonte, ou com fonte de uma API que saiu do ar (RAWG, trocada pelo IGDB): só dá para reconhecê-la pelo
     * título. Com fonte válida, o mesmo título pode ser outra obra (remake, outro livro com o mesmo nome).
     */
    static boolean semFonteConfiavel(Obra o) {
        return o.getFonte() == null || o.getFonte().startsWith("rawg:");
    }

    /** Título com que a obra fica no acervo: o próprio, ou "Título (Mídia)" se outra mídia já usa esse título. */
    private String tituloNoAcervo(String titulo, String tipo) {
        Optional<Obra> mesma = mesmaObraNoAcervo(titulo, tipo);
        if (mesma.isPresent()) return mesma.get().getTitulo();
        return repository.existsById(titulo) ? titulo + " (" + NOME_MIDIA.getOrDefault(tipo, tipo) + ")" : titulo;
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
    public List<Obra> reprocessarDna(@RequestParam(defaultValue = "false") boolean todas, HttpServletRequest req) {
        List<Obra> atualizadas = new ArrayList<>();
        for (Obra obra : repository.findAll()) {
            String dna = obra.getDnaCriativo();
            boolean falhou = dna == null || dna.isBlank() || TAGS_INVALIDAS.contains(dna.trim().toLowerCase());
            if (!todas && !falhou) {
                continue;
            }
            String[] dados = dadosParaSalvar(obra.getTitulo(), obra.getTipo());
            obra.setDnaCriativo(iaService.extrairDnaCriativo(obra.getTitulo(), campo(dados, 1), obra.getTipo(), campo(dados, 2)));
            aplicarDetalhes(obra, dados);
            atualizadas.add(salvar(obra));
            System.out.println("DNA refeito: " + obra.getTitulo() + " -> " + obra.getDnaCriativo());
        }
        historico.registrar(usuario(req), "obra", "refez as tags com a IA de", atualizadas.size() + (atualizadas.size() == 1 ? " obra" : " obras"),
                null, todas ? "todo o catálogo" : "só as que tinham falhado");
        return atualizadas;
    }

    /** Preenche sinopse e imagens que estiverem faltando, sem chamar a IA. */
    /** Traduções por chamada da manutenção (cada uma leva alguns segundos na IA); o resto fica para a próxima. */
    static final int MAX_TRADUCOES_POR_VEZ = 15;

    @PostMapping("/completar-detalhes")
    public List<Obra> completarDetalhes(HttpServletRequest req) {
        List<Obra> atualizadas = new ArrayList<>();
        int traducoes = 0;
        for (Obra obra : repository.findAll()) {
            if (temSinopse(obra.getSinopse()) && obra.getImagem() != null) {
                // Já completa: só traduz a sinopse, se tiver ficado em inglês (obras de antes da tradução automática)
                if (traducoes < MAX_TRADUCOES_POR_VEZ && IaService.pareceIngles(obra.getSinopse())) {
                    traducoes++;
                    String traduzida = iaService.traduzirParaPortugues(obra.getSinopse());
                    if (!traduzida.equals(obra.getSinopse())) {
                        obra.setSinopse(traduzida);
                        atualizadas.add(repository.save(obra));
                    }
                }
                continue;
            }
            String[] dados = dadosParaSalvar(obra.getTitulo(), obra.getTipo());
            if (dados != null) {
                aplicarDetalhes(obra, dados);
                atualizadas.add(repository.save(obra));
            }
        }
        if (!atualizadas.isEmpty()) {
            historico.registrar(usuario(req), "obra", "completou ou traduziu sinopses e capas de",
                    atualizadas.size() + (atualizadas.size() == 1 ? " obra" : " obras"), null, null);
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
            @RequestParam(required = false) String titulo,
            @RequestParam(required = false) String tipo,
            @RequestParam(defaultValue = "false") boolean paraMim,
            HttpServletRequest req) {

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

        // Com tipo (filme|serie|jogo|livro), só aquela mídia; sem tipo, todas intercaladas
        String tipoObra = tipo == null || tipo.isBlank() ? null : TIPOS.get(tipo.toLowerCase(Locale.ROOT));
        // paraMim=true ("Para você"): sem o que a pessoa já favoritou ou já viu, jogou ou leu
        String quem = paraMim ? usuario(req) : null;
        return intercalarPorTipo(repository.recomendarPorTags(consulta, excluidos, tipoObra, quem), MAX_RECOMENDACOES);
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

    @GetMapping("/tendencias/livros")
    public List<String> tendenciasLivros() {
        return livroService.buscarTendencias();
    }

    @GetMapping("/tendencias/jogos")
    public List<String> tendenciasJogos() {
        return jogosService.buscarTendencias();
    }

    // ------------------------------------------------------------------ auxiliares

    /** Busca a obra na API da mídia: {titulo, sinopse, generos, imagem, capa} ou null. */
    private String[] buscarDados(String nome, String tipo) {
        return switch (tipo == null ? "" : tipo.toLowerCase()) {
            case "filme" -> tmdbService.buscarFilmeNaInternet(nome);
            case "serie" -> tmdbService.buscarSerieNaInternet(nome);
            case "jogo" -> jogosService.buscarJogoNaInternet(nome);
            case "livro" -> livroService.buscarLivroNaInternet(nome);
            default -> null;
        };
    }

    private static String usuario(HttpServletRequest req) {
        return req == null ? null : (String) req.getAttribute(AutenticacaoInterceptor.ATRIBUTO_USUARIO);
    }

    /** Como {@link #buscarDados}, mas com a sinopse já em português (para gravar no catálogo). */
    private String[] dadosParaSalvar(String nome, String tipo) {
        return comSinopseEmPortugues(buscarDados(nome, tipo));
    }

    /**
     * Cópia dos dados com a sinopse traduzida pelo Ollama quando vem em inglês: livros do Open Library e jogos do IGDB
     * (o resumo é sempre em inglês) ou da Steam sem descrição em português (ex.: Geometry Dash). Se a IA falhar, fica a original.
     */
    private String[] comSinopseEmPortugues(String[] dados) {
        if (dados == null) return null;
        String[] copia = dados.clone();
        if (copia.length > 1 && copia[1] != null) copia[1] = iaService.traduzirParaPortugues(copia[1]);
        return copia;
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
