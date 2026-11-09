package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gera o "DNA criativo" (1 a 4 tags da taxonomia oficial) de uma obra.
 *
 * Classificador híbrido:
 *  1) o LLM local (Ollama) recebe título, sinopse e os gêneros oficiais da API e escolhe tags da taxonomia,
 *     com saída estruturada (JSON Schema com enum) e geração determinística (temperature 0, seed fixa);
 *  2) as tags do LLM são combinadas com os gêneros oficiais ({@link #combinar});
 *  3) se o LLM falhar, usam-se só os gêneros oficiais; sem nada, "Sem classificação".
 */
@Service
public class IaService {

    public static final String SEM_CLASSIFICACAO = "Sem classificação";
    static final int MAX_TAGS = 4;
    private static final int TENTATIVAS = 2;

    static final List<String> TAXONOMIA_OFICIAL = Arrays.asList(
        "Ação", "Aventura", "Comédia", "Drama", "Ficção Científica", "Fantasia", "Terror", "Suspense", "Mistério", "Romance", "Animação",
         "Documentário", "Crime", "Policial", "Família", "Musical", "Guerra", "Histórico", "Biografia", "Faroeste", "Sobrenatural", "Pós-Apocalíptico",
          "Cyberpunk", "Steampunk", "Distopia", "Espionagem", "Super-herói", "Slasher", "Gore", "Zumbi", "Monstros", "Vampiros", "Alienígenas",
        "Viagem no Tempo", "Multiverso", "Space Opera", "Fantasia Sombria", "Fantasia Medieval", "Mitologia", "Fatia de Vida", "Coming of Age",
        "Psicológico", "Noir", "Neo-Noir", "Político", "Militar", "Sobrevivência", "Desastre", "Esporte", "Artes Marciais", "Paródia", "Satírico",
        "Comédia Romântica", "Comédia de Erros", "Comédia Pastelão", "Humor Negro", "Comédia Dramática", "Melodrama", "Found Footage", "Cult", "Indie",
        "Antologia", "Live-Action", "Stop Motion", "Anime", "Minissérie", "Sitcom", "Procedural", "Telenovela", "Reality Show", "Talk Show", "RPG",
        "JRPG", "ARPG", "CRPG", "MMORPG", "MMO", "Shooter", "FPS", "TPS", "RTS", "TBS", "Estratégia", "MOBA", "Battle Royale", "Roguelike", "Roguelite",
        "Metroidvania", "Soulslike", "Plataforma", "Plataforma 2D", "Plataforma 3D", "Quebra-cabeça", "Puzzle", "Hack and Slash", "Beat 'em Up", "Luta",
        "Corrida", "Simulação", "Simulador Espacial", "Simulador de Voo", "Simulador de Vida", "Construção de Cidades", "Gerenciamento", "Tower Defense",
        "Deckbuilder", "Cartas Colecionáveis", "Auto Battler", "Survival Horror", "Point and Click", "Visual Novel","Mundo Aberto", "Sandbox", "Linear",
        "Walking Simulator", "Furtividade", "Crafting", "Base Building", "Loot Shooter", "Dungeon Crawler", "Gacha", "Ritmo", "Música", "Party Game", "Trivia",
        "Bullet Hell", "Twin-Stick Shooter", "Shoot 'em Up", "Top-Down", "Isométrico", "VR", "AR", "Cooperativo", "Co-op Local", "Cooperativo Online",
        "Multijogador", "Multijogador Local", "Multijogador Online", "Um Jogador", "PvP", "PvE", "Competitivo", "Casual", "Hardcore", "Perma-Death",
         "Narrativa Rica", "Escolhas Importam", "Finais Múltiplos", "Protagonista Feminina", "Protagonista Silencioso", "Customização de Personagem",
        "Pixel Art", "Retro", "Gráficos Estilizados", "Hiper-realista", "Mundo Subaquático", "Cthulhu", "Detetive", "Investigação", "Tribunal", "Prisional",
        "Conspiração", "Psicopatas", "Serial Killer", "Viagem Espacial", "Mecha", "Isekai", "Gótico", "Lovecraftiano", "Heist", "Assalto", "Máfia", "Gângster",
         "Conquista Espacial", "Exploração", "Física Realista", "Loot", "Economia Aberta", "Moddable",
        "Não Ficção", "Autoajuda", "Negócios", "Filosofia", "Poesia", "Clássico", "Literatura Brasileira", "Young Adult"
    );

    // Tags que só fazem sentido para jogos (mecânicas, modos, estilo visual de jogo)
    static final Set<String> TAGS_SO_JOGOS = Set.of(
        "RPG", "JRPG", "ARPG", "CRPG", "MMORPG", "MMO", "Shooter", "FPS", "TPS", "RTS", "TBS", "Estratégia", "MOBA",
        "Battle Royale", "Roguelike", "Roguelite", "Metroidvania", "Soulslike", "Plataforma", "Plataforma 2D", "Plataforma 3D",
        "Quebra-cabeça", "Puzzle", "Hack and Slash", "Beat 'em Up", "Luta", "Simulação", "Simulador Espacial", "Simulador de Voo",
        "Simulador de Vida", "Construção de Cidades", "Gerenciamento", "Tower Defense", "Deckbuilder", "Cartas Colecionáveis",
        "Auto Battler", "Survival Horror", "Point and Click", "Visual Novel", "Mundo Aberto", "Sandbox", "Linear",
        "Walking Simulator", "Furtividade", "Crafting", "Base Building", "Loot Shooter", "Dungeon Crawler", "Gacha", "Ritmo",
        "Party Game", "Trivia", "Bullet Hell", "Twin-Stick Shooter", "Shoot 'em Up", "Top-Down", "Isométrico", "VR", "AR",
        "Cooperativo", "Co-op Local", "Cooperativo Online", "Multijogador", "Multijogador Local", "Multijogador Online",
        "Um Jogador", "PvP", "PvE", "Competitivo", "Casual", "Hardcore", "Perma-Death", "Escolhas Importam", "Finais Múltiplos",
        "Protagonista Silencioso", "Customização de Personagem", "Pixel Art", "Gráficos Estilizados", "Hiper-realista",
        "Física Realista", "Loot", "Economia Aberta", "Moddable");

    // Formatos de televisão (não se aplicam a filmes nem a jogos)
    static final Set<String> TAGS_SO_SERIES = Set.of("Sitcom", "Telenovela", "Talk Show", "Reality Show", "Minissérie", "Procedural");

    // Categorias de livraria (só para livros)
    static final Set<String> TAGS_SO_LIVROS = Set.of("Não Ficção", "Autoajuda", "Negócios", "Filosofia", "Poesia", "Clássico",
            "Literatura Brasileira", "Young Adult");

    // Formatos audiovisuais (não se aplicam a livros)
    static final Set<String> TAGS_SO_AUDIOVISUAL = Set.of("Animação", "Documentário", "Musical", "Found Footage", "Live-Action",
            "Stop Motion", "Anime");

    /** Tags da taxonomia permitidas para a mídia (o LLM não pode, por exemplo, dar "Battle Royale" a um filme). */
    public static List<String> taxonomiaPara(String tipo) {
        String t = tipo == null ? "" : IaService.normalizar(tipo);
        return TAXONOMIA_OFICIAL.stream().filter(tag -> switch (t) {
            case "jogo" -> !TAGS_SO_SERIES.contains(tag) && !TAGS_SO_LIVROS.contains(tag);
            case "serie" -> !TAGS_SO_JOGOS.contains(tag) && !TAGS_SO_LIVROS.contains(tag);
            case "filme" -> !TAGS_SO_JOGOS.contains(tag) && !TAGS_SO_SERIES.contains(tag) && !TAGS_SO_LIVROS.contains(tag);
            case "livro" -> !TAGS_SO_JOGOS.contains(tag) && !TAGS_SO_SERIES.contains(tag) && !TAGS_SO_AUDIOVISUAL.contains(tag);
            default -> true;
        }).toList();
    }

    // "tag normalizada" (sem acento, minúscula, sem pontuação) -> tag oficial
    private static final Map<String, String> TAGS_NORMALIZADAS = new LinkedHashMap<>();
    static {
        for (String tag : TAXONOMIA_OFICIAL) {
            TAGS_NORMALIZADAS.put(normalizar(tag), tag);
        }
    }

    private final String urlGerar;
    private final String modelo;
    private final RestTemplate carteiro = ClienteHttp.criar(5_000, 120_000);

    public IaService(@Value("${omnireview.ollama.url:http://localhost:11434}") String urlOllama,
                     @Value("${omnireview.ollama.modelo:llama3.2:3b}") String modelo) {
        this.urlGerar = urlOllama.replaceAll("/+$", "") + "/api/generate";
        this.modelo = modelo;
    }

    public String extrairDnaCriativo(String titulo, String sinopse, String tipo) {
        return extrairDnaCriativo(titulo, sinopse, tipo, null);
    }

    /** Pipeline completo usado em produção: LLM + combinação com os gêneros oficiais + alternativas. */
    public String extrairDnaCriativo(String titulo, String sinopse, String tipo, String generosOficiais) {
        List<String> generos = extrairTagsValidas(generosOficiais);
        List<String> doLlm = classificarComLlm(titulo, sinopse, tipo, generos);
        List<String> finais = combinar(doLlm, generos);
        return finais.isEmpty() ? SEM_CLASSIFICACAO : String.join(", ", finais);
    }

    /** Apenas a etapa do LLM (até 2 tentativas). Lista vazia se o LLM falhar. Público para a avaliação. */
    public List<String> classificarComLlm(String titulo, String sinopse, String tipo, List<String> generos) {
        boolean temSinopse = sinopse != null && !sinopse.isBlank() && !sinopse.startsWith("Sem sinopse");
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            try {
                String texto = chamarOllama(titulo, temSinopse ? sinopse : null, tipo, generos);
                List<String> permitidas = taxonomiaPara(tipo);
                List<String> tags = extrairTagsValidas(texto).stream().filter(permitidas::contains).toList();
                if (!tags.isEmpty()) {
                    return tags;
                }
            } catch (Exception e) {
                System.out.println("Erro ao chamar o Ollama (" + titulo + "): " + e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Combina os gêneros oficiais com as tags do LLM: os 2 primeiros gêneros oficiais (os mais relevantes,
     * na ordem da API), depois as tags do LLM e, por fim, os gêneros restantes, até {@link #MAX_TAGS}.
     * A estratégia foi escolhida pelo maior F1 na metade de desenvolvimento do conjunto de referência (ver docs/tcc).
     */
    public static List<String> combinar(List<String> doLlm, List<String> generos) {
        LinkedHashSet<String> saida = new LinkedHashSet<>(generos.subList(0, Math.min(2, generos.size())));
        saida.addAll(doLlm);
        saida.addAll(generos);
        return new ArrayList<>(saida).subList(0, Math.min(MAX_TAGS, saida.size()));
    }

    private String chamarOllama(String titulo, String sinopse, String tipo, List<String> generos) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String prompt =
                "Você é um catalogador de filmes, séries, jogos e livros. Escolha de 2 a 4 tags da LISTA PERMITIDA " +
                "que melhor descrevem a obra. Responda somente com o JSON pedido.\n\n" +
                "REGRAS:\n" +
                "1. Use os GÊNEROS OFICIAIS como ponto de partida e mantenha os que realmente se aplicam.\n" +
                "2. Prefira tags específicas (subgênero, tema, formato ou mecânica de jogo) quando a sinopse as " +
                "sustentar, em vez de repetir só gêneros amplos.\n" +
                "3. Use Ficção Científica apenas se houver tecnologia futurista, espaço ou ciência especulativa; " +
                "use Fantasia apenas se houver magia ou seres míticos.\n" +
                "4. Para jogos, inclua o gênero de jogabilidade.\n\n" +
                "LISTA PERMITIDA: " + String.join(", ", taxonomiaPara(tipo)) + "\n\n" +
                "EXEMPLO (filme) - Sinopse: Um detetive investiga uma série de assassinatos na Londres vitoriana. " +
                "Gêneros oficiais: Crime, Mistério\n" +
                "{\"tags\": [\"Mistério\", \"Detetive\", \"Crime\", \"Histórico\"]}\n\n" +
                "EXEMPLO (jogo) - Sinopse: Cultive sua fazenda, crie animais e faça amizade com os moradores da vila. " +
                "Gêneros oficiais: Simulação, Indie, RPG\n" +
                "{\"tags\": [\"Simulador de Vida\", \"Simulação\", \"Casual\", \"Pixel Art\"]}\n\n" +
                "OBRA:\n" +
                "Tipo: " + (tipo == null ? "desconhecido" : tipo) + "\n" +
                "Título: " + titulo + "\n" +
                "Gêneros oficiais: " + (generos.isEmpty() ? "(não informados)" : String.join(", ", generos)) + "\n" +
                "Sinopse: " + (sinopse == null ? "(sem sinopse; use o que você sabe sobre o título)" : limitar(sinopse, 1500)) + "\n";

        // Saída estruturada: o Ollama só consegue gerar tags que existem na taxonomia.
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tags", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string", "enum", taxonomiaPara(tipo)),
                                "minItems", 1,
                                "maxItems", MAX_TAGS)),
                "required", List.of("tags"));

        Map<String, Object> opcoes = new HashMap<>();
        opcoes.put("temperature", 0);   // determinístico: a mesma obra gera as mesmas tags
        opcoes.put("seed", 42);
        opcoes.put("num_predict", 120);
        opcoes.put("num_ctx", 4096);    // o padrão (2048) truncava o prompt com a lista inteira

        Map<String, Object> corpo = new HashMap<>();
        corpo.put("model", modelo);
        corpo.put("prompt", prompt);
        corpo.put("stream", false);
        corpo.put("format", schema);
        corpo.put("options", opcoes);

        @SuppressWarnings("unchecked")
        Map<String, Object> resposta = carteiro.postForObject(urlGerar, new HttpEntity<>(corpo, headers), Map.class);
        if (resposta == null || !(resposta.get("response") instanceof String texto)) {
            throw new IllegalStateException("Resposta do Ollama sem campo 'response'");
        }
        return texto;
    }

    /**
     * Traduz uma sinopse para o português do Brasil com o Ollama, se ela parecer estar em inglês.
     * Em qualquer falha devolve o texto original (a sinopse em inglês é melhor do que nenhuma).
     */
    public String traduzirParaPortugues(String texto) {
        if (texto == null || texto.isBlank() || !pareceIngles(texto)) return texto;
        try {
            Map<String, Object> opcoes = new HashMap<>();
            opcoes.put("temperature", 0);
            opcoes.put("seed", 42);
            opcoes.put("num_predict", 700);
            Map<String, Object> corpo = new HashMap<>();
            corpo.put("model", modelo);
            corpo.put("prompt", "Traduza a sinopse abaixo para o português do Brasil, de forma natural e fiel. " +
                    "Mantenha nomes próprios. Responda somente com a tradução, sem comentários.\n\n" + limitar(texto, 1800));
            corpo.put("stream", false);
            corpo.put("options", opcoes);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> resposta = carteiro.postForObject(urlGerar, new HttpEntity<>(corpo, headers), Map.class);
            String traducao = resposta == null ? null : (String) resposta.get("response");
            if (traducao == null || traducao.isBlank() || traducao.length() < texto.length() / 3) return texto;
            return traducao.strip().replaceAll("^(?i)(tradução|sinopse)\\s*:\\s*", "");
        } catch (Exception e) {
            System.out.println("Ollama: não foi possível traduzir a sinopse (" + e.getMessage() + ")");
            return texto;
        }
    }

    /** Heurística simples: muitas palavras comuns do inglês e poucas do português. */
    static boolean pareceIngles(String texto) {
        String t = " " + texto.toLowerCase(Locale.ROOT).replaceAll("[^a-zà-ú ]", " ") + " ";
        int en = 0, pt = 0;
        for (String w : new String[]{" the ", " and ", " of ", " is ", " with ", " his ", " her ", " to ", " in ", " a "}) if (t.contains(w)) en++;
        for (String w : new String[]{" o ", " e ", " de ", " que ", " um ", " uma ", " com ", " não ", " para ", " seu "}) if (t.contains(w)) pt++;
        return en >= 4 && en > pt;
    }

    /**
     * Procura tags oficiais num texto (JSON ou lista separada por vírgula), ignorando
     * acentos, maiúsculas e pontuação. Mantém a ordem e remove duplicadas.
     */
    public static List<String> extrairTagsValidas(String texto) {
        List<String> encontradas = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return encontradas;
        }
        List<String> candidatos = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(texto);
        while (m.find()) {
            candidatos.add(m.group(1));
        }
        if (candidatos.isEmpty()) {
            candidatos.addAll(Arrays.asList(texto.split("[,;\\n]")));
        }
        for (String candidato : candidatos) {
            String oficial = TAGS_NORMALIZADAS.get(normalizar(candidato));
            if (oficial != null && !encontradas.contains(oficial)) {
                encontradas.add(oficial);
                if (encontradas.size() == MAX_TAGS) {
                    break;
                }
            }
        }
        return encontradas;
    }

    static String normalizar(String s) {
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String limitar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
