package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IGDB (base de jogos da Twitch): jogos de todas as plataformas (PC, PlayStation, Xbox, Nintendo e celular), com capa,
 * artes, gêneros, plataformas, lojas e o tempo para zerar informado pelos jogadores. É a fonte principal de jogos
 * (substituiu a RAWG, que saiu do ar); a Steam fica de reserva (ver {@link JogosService}).
 * Precisa de um aplicativo gratuito da Twitch (omnireview.igdb.client-id / client-secret, ou OMNIREVIEW_IGDB_CLIENT_ID /
 * OMNIREVIEW_IGDB_CLIENT_SECRET). O token de acesso é pedido à Twitch e renovado sozinho antes de vencer. Depois de uma
 * falha, fica {@link #PAUSA_APOS_FALHA} sem tentar (os jogos vêm da Steam nesse meio-tempo).
 */
@Service
public class IgdbService {

    private static final String API = "https://api.igdb.com/v4/";
    private static final String TOKEN = "https://id.twitch.tv/oauth2/token";
    private static final String IMAGENS = "https://images.igdb.com/igdb/image/upload/";
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(10);
    static final long INTERVALO_MS = 260;   // o IGDB aceita até 4 consultas por segundo

    /** Só jogos "de verdade": sem edições (version_parent), DLCs, pacotes, mods, temporadas nem tema adulto (42). */
    static final String FILTRO = "version_parent = null & game_type = (0,4,8,9,10,11) & themes != (42)";
    static final String CAMPOS_BUSCA = "fields name,first_release_date,total_rating_count,hypes,game_type,cover.image_id,"
            + "platforms.abbreviation,platforms.name,external_games.uid,external_games.external_game_source;";
    static final String CAMPOS_JOGO = "fields name,summary,first_release_date,total_rating_count,hypes,game_type,"
            + "genres.slug,themes.slug,game_modes.slug,player_perspectives.slug,platforms.abbreviation,platforms.name,"
            + "cover.image_id,artworks.image_id,screenshots.image_id,websites.url,websites.type,"
            + "external_games.uid,external_games.external_game_source,external_games.url;";

    static final int FONTE_STEAM = 1;   // external_game_sources: 1 = Steam

    private final String clientId;
    private final String clientSecret;
    private final RestTemplate carteiro = ClienteHttp.criar(5_000, 10_000);
    private volatile String token;
    private volatile Instant tokenVenceEm = Instant.MIN;
    private volatile Instant indisponivelAte = Instant.MIN;
    private long proximaConsulta = 0;

    public IgdbService(@Value("${omnireview.igdb.client-id:}") String clientId,
                       @Value("${omnireview.igdb.client-secret:}") String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        if (this.clientId.isEmpty() || this.clientSecret.isEmpty()) {
            System.out.println("AVISO: credenciais do IGDB não configuradas; os jogos vêm só da Steam (sem consoles nem tempo para zerar).");
        }
    }

    public boolean disponivel() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && Instant.now().isAfter(indisponivelAte);
    }

    // ------------------------------------------------------------------ catálogo

    /**
     * Jogos com esse nome, os que mais combinam primeiro (ver {@link #ordenar}). Junta duas consultas: a busca por
     * relevância do IGDB (acha "gta" -> "Grand Theft Auto V") e "nome contém o texto" pelos mais avaliados (a busca
     * sozinha devolve "Cyberpunk", jogo obscuro, e não o "Cyberpunk 2077"). Null se o IGDB estiver indisponível.
     */
    public List<Map<String, Object>> pesquisar(String nome, int limite) {
        if (!disponivel() || nome == null || nome.isBlank()) return null;
        String n = textoSeguro(nome);
        if (n.isEmpty()) return List.of();
        try {
            List<Map<String, Object>> porRelevancia = consultar("games", "search \"" + n + "\"; " + CAMPOS_BUSCA + " where " + FILTRO + "; limit 30;");
            List<Map<String, Object>> porNome = consultar("games", CAMPOS_BUSCA + " where name ~ *\"" + n + "\"* & " + FILTRO
                    + "; sort total_rating_count desc; limit 20;");
            if (porRelevancia == null || porNome == null) return null;
            Map<Object, Map<String, Object>> unicos = new LinkedHashMap<>();
            for (Map<String, Object> j : porRelevancia) unicos.putIfAbsent(j.get("id"), j);
            for (Map<String, Object> j : porNome) unicos.putIfAbsent(j.get("id"), j);
            return ordenar(nome, new ArrayList<>(unicos.values())).stream().limit(Math.max(1, limite)).toList();
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    /** O jogo completo pelo id do IGDB: Map vazio se não existir; null se o IGDB estiver indisponível. */
    public Map<String, Object> jogo(long id) {
        if (!disponivel()) return null;
        try {
            List<Map<String, Object>> r = consultar("games", CAMPOS_JOGO + " where id = " + id + "; limit 1;");
            if (r == null) return null;
            return r.isEmpty() ? Map.of() : r.get(0);
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    /** Id no IGDB do jogo da Steam com esse appid: -1 se o IGDB não o conhece; null se estiver indisponível. */
    public Long idPelaSteam(String appid) {
        if (!disponivel() || appid == null || !appid.matches("[0-9]{1,10}")) return null;
        try {
            List<Map<String, Object>> r = consultar("external_games",
                    "fields game; where uid = \"" + appid + "\" & external_game_source = " + FONTE_STEAM + "; limit 1;");
            if (r == null) return null;
            return !r.isEmpty() && r.get(0).get("game") instanceof Number n ? n.longValue() : -1L;
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    static final int PLATAFORMA_NAVEGADOR = 82;

    /**
     * Jogando agora: os jogos que mais gente marcou como "jogando" no IGDB (todas as plataformas), na ordem do ranking:
     * {ref ("igdb:ID"), nome}. Sem DLCs nem edições ("Minecraft: Java Edition" sai quando "Minecraft" também está) e sem
     * os jogos só de navegador feitos para lives da Twitch. Null se o IGDB estiver indisponível.
     */
    public List<String[]> jogandoAgora(int limite) {
        if (!disponivel()) return null;
        try {
            // popularity_type 3 = "Playing" (quantas pessoas marcaram que estão jogando)
            List<Map<String, Object>> pop = consultar("popularity_primitives",
                    "fields game_id,value; where popularity_type = 3; sort value desc; limit " + Math.min(100, limite * 2 + 10) + ";");
            if (pop == null) return null;
            List<Long> ids = pop.stream().map(p -> p.get("game_id")).filter(Number.class::isInstance)
                    .map(n -> ((Number) n).longValue()).distinct().toList();
            if (ids.isEmpty()) return List.of();
            List<Map<String, Object>> jogos = consultar("games", "fields name,platforms; where id = ("
                    + String.join(",", ids.stream().map(String::valueOf).toList()) + ") & " + FILTRO + "; limit 100;");
            if (jogos == null) return null;
            Map<Long, Map<String, Object>> porId = new HashMap<>();
            for (Map<String, Object> j : jogos) if (j.get("id") instanceof Number id) porId.put(id.longValue(), j);
            return ranking(ids.stream().map(porId::get).filter(Objects::nonNull).toList(), limite);
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    /** Os jogos do "jogando agora" que entram na lista (ver {@link #jogandoAgora}), na ordem recebida. */
    static List<String[]> ranking(List<Map<String, Object>> jogos, int limite) {
        Set<String> nomes = new HashSet<>();
        for (Map<String, Object> j : jogos) if (nome(j) != null) nomes.add(nome(j));
        List<String[]> lista = new ArrayList<>();
        for (Map<String, Object> j : jogos) {
            if (lista.size() >= limite) break;
            String nome = nome(j);
            if (nome == null || nome.isBlank() || edicaoDeOutro(nome, nomes) || soNoNavegador(j.get("platforms"))) continue;
            lista.add(new String[]{"igdb:" + ((Number) j.get("id")).longValue(), nome});
        }
        return lista;
    }

    /** "Minecraft: Java Edition" quando "Minecraft" também está na lista. */
    static boolean edicaoDeOutro(String nome, Set<String> nomes) {
        int doisPontos = nome.indexOf(':');
        if (doisPontos <= 0 || !nome.toLowerCase(Locale.ROOT).matches(".*\\bedi(tion|ção)$")) return false;
        return nomes.contains(nome.substring(0, doisPontos).strip());
    }

    private static boolean soNoNavegador(Object plataformas) {
        return plataformas instanceof List<?> l && !l.isEmpty()
                && l.stream().allMatch(p -> p instanceof Number n && n.intValue() == PLATAFORMA_NAVEGADOR);
    }

    // ------------------------------------------------------------------ tempo para zerar

    /**
     * Horas para zerar o jogo: {historia, extras, completo} (as que existirem) e quantas pessoas informaram.
     * Map vazio se o jogo não tem esse dado; null se o IGDB não estiver disponível.
     */
    public Map<String, Object> tempoParaZerar(String titulo) {
        if (!disponivel() || titulo == null || titulo.isBlank()) return null;
        try {
            // O jogo: ver escolherJogo (há mods e relançamentos com o mesmo nome do original)
            String nome = textoSeguro(titulo);
            List<Map<String, Object>> jogos = consultar("games",
                    "search \"" + nome + "\"; fields id,name,game_type,total_rating_count; where version_parent = null; limit 10;");
            if (jogos == null) return null;
            if (jogos.isEmpty()) return Map.of();
            return tempoParaZerarDoJogo(((Number) escolherJogo(nome, jogos).get("id")).longValue());
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    /** Como {@link #tempoParaZerar(String)}, para um jogo já conhecido (id do IGDB). */
    public Map<String, Object> tempoParaZerarDoJogo(long id) {
        if (!disponivel()) return null;
        try {
            // O tempo para zerar informado pelos jogadores (em segundos)
            List<Map<String, Object>> tempos = consultar("game_time_to_beats",
                    "fields hastily,normally,completely,count; where game_id = " + id + "; limit 1;");
            if (tempos == null) return null;
            return tempos.isEmpty() ? Map.of() : horas(tempos.get(0));
        } catch (Exception e) {
            registrarFalha(e);
            return null;
        }
    }

    /**
     * O IGDB tem várias entradas com o mesmo nome: o jogo original, mods, DLCs e relançamentos ("Hollow Knight" mod,
     * sem avaliações, aparecia antes do original com 2.282). Ordem: nome igual ao buscado, jogo principal
     * (game_type 0) e, por fim, o mais avaliado.
     */
    static Map<String, Object> escolherJogo(String nome, List<Map<String, Object>> jogos) {
        String alvo = Provedores.normalizar(nome);
        return jogos.stream().max(Comparator
                .comparing((Map<String, Object> j) -> alvo.equals(Provedores.normalizar((String) j.get("name"))))
                .thenComparing(j -> j.get("game_type") instanceof Number t && t.intValue() == 0)
                .thenComparingInt(j -> j.get("total_rating_count") instanceof Number n ? n.intValue() : 0))
                .orElse(jogos.get(0));
    }

    static final int MIN_RESPOSTAS = 3;
    static final long MAX_HORAS = 300;   // o 100% do Elden Ring, um dos jogos mais longos, dá 175 h
    static final long MAX_PROPORCAO = 15;

    /**
     * Segundos do IGDB -> horas arredondadas (mínimo 1), só das medidas confiáveis. Os tempos são informados por
     * jogadores, e com poucas respostas aparecem absurdos (exemplos reais: "PEAK" 100% em 1.556 h; "R.E.P.O." história
     * em 350 h; "Call of Duty" história 100 h e extras 9 h com 2 respostas). Ficam de fora: jogos com menos de
     * {@link #MIN_RESPOSTAS} respostas, medidas acima de {@link #MAX_HORAS}, fora de ordem (extras menor que a
     * história, 100% menor que extras) ou mais de {@link #MAX_PROPORCAO} vezes maiores que a menor medida.
     */
    static Map<String, Object> horas(Map<String, Object> t) {
        int respostas = t.get("count") instanceof Number c ? c.intValue() : 0;
        if (respostas < MIN_RESPOSTAS) return Map.of();
        Map<String, Long> h = new LinkedHashMap<>();
        for (String[] par : new String[][]{{"hastily", "historia"}, {"normally", "extras"}, {"completely", "completo"}}) {
            if (t.get(par[0]) instanceof Number n && n.longValue() > 0) {
                long horas = Math.max(1, Math.round(n.doubleValue() / 3600));
                if (horas <= MAX_HORAS) h.put(par[1], horas);
            }
        }
        // ordem: história <= com extras <= 100%
        long maior = 0;
        for (String k : List.of("historia", "extras", "completo")) {
            Long v = h.get(k);
            if (v == null) continue;
            if (v < maior) h.remove(k);
            else maior = v;
        }
        long menor = h.values().stream().mapToLong(Long::longValue).min().orElse(0);
        h.values().removeIf(v -> v > menor * MAX_PROPORCAO);
        Map<String, Object> saida = new LinkedHashMap<>(h);
        if (!saida.isEmpty()) saida.put("respostas", respostas);
        return saida;
    }

    // ------------------------------------------------------------------ ordem da busca

    /** Abaixo disso o jogo é "desconhecido" (quase ninguém avaliou nem está esperando por ele). */
    static final int MIN_CONHECIDO = 20;
    /**
     * Quanto vale cada nível de parecença do nome (igual 3, começa com 2, contém 1) frente à popularidade (log10):
     * "zelda" mostra o Breath of the Wild (contém, 3.172) antes do Zelda II (começa com, 308).
     */
    static final double PESO_NOME = 0.75;

    /** Avaliações + pessoas esperando o lançamento (jogo novo ainda sem avaliação também conta). */
    static double popularidade(Map<String, Object> j) {
        double avaliacoes = j.get("total_rating_count") instanceof Number n ? n.doubleValue() : 0;
        double espera = j.get("hypes") instanceof Number n ? n.doubleValue() : 0;
        return avaliacoes + espera;
    }

    /**
     * Ordena pelo que mais combina: parecença do nome com o texto (igual, começa com, contém) e popularidade.
     * Quando há jogos conhecidos que combinam com o texto, saem os desconhecidos (sem nenhuma avaliação): em
     * "cyberpunk", o obscuro "Cyberpunk" daria lugar ao "Cyberpunk 2077"; em "gta", "GTA: La Heist" ao GTA V.
     * Um jogo desconhecido com o nome exato fica, se nenhum conhecido tiver o texto no nome (ex.: um indie recente).
     */
    static List<Map<String, Object>> ordenar(String consulta, List<Map<String, Object>> jogos) {
        String alvo = RecomendacaoController.chaveDeBusca(consulta);
        boolean haConhecidos = jogos.stream().anyMatch(j -> popularidade(j) >= MIN_CONHECIDO);
        boolean conhecidoComONome = jogos.stream().anyMatch(j -> popularidade(j) >= MIN_CONHECIDO
                && RecomendacaoController.chaveDeBusca(nome(j)).contains(alvo));
        return jogos.stream()
                .filter(j -> nome(j) != null && !nome(j).isBlank())
                .filter(j -> popularidade(j) > 0 || !haConhecidos
                        || (RecomendacaoController.semelhanca(consulta, nome(j)) == 3 && !conhecidoComONome))
                .sorted(Comparator.comparingDouble((Map<String, Object> j) ->
                        RecomendacaoController.semelhanca(consulta, nome(j)) * PESO_NOME + Math.log10(1 + popularidade(j))).reversed())
                .toList();
    }

    private static String nome(Map<String, Object> j) {
        return j.get("name") instanceof String s ? s : null;
    }

    // ------------------------------------------------------------------ dados para o catálogo

    /** Opção para a pessoa confirmar: {ref ("igdb:ID"), titulo, ano, imagem, detalhe (plataformas), refSteam}. */
    static Map<String, Object> candidato(Map<String, Object> j) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("ref", "igdb:" + ((Number) j.get("id")).longValue());
        c.put("titulo", nome(j));
        String ano = ano(j.get("first_release_date"));
        if (ano != null) c.put("ano", ano);
        String capa = imagem(j.get("cover"), "t_cover_big");
        if (capa != null) c.put("imagem", capa);
        String plataformas = plataformas(j.get("platforms"), 4, " · ");
        if (plataformas != null) c.put("detalhe", plataformas);
        String steam = steamDoJogo(j);
        if (steam != null) c.put("refSteam", "steam:" + steam);
        return c;
    }

    /**
     * Dados do jogo no formato do catálogo: {titulo, sinopse (em inglês), generos, poster, capa, ref ("igdb:ID"), ano,
     * outra fonte ("steam:APPID", para reconhecer o mesmo jogo que veio da Steam)}.
     */
    static String[] dadosDoJogo(Map<String, Object> j) {
        String poster = imagem(j.get("cover"), "t_cover_big_2x");
        String fundo = primeiraImagem(j.get("artworks"));
        if (fundo == null) fundo = primeiraImagem(j.get("screenshots"));
        String steam = steamDoJogo(j);
        String sinopse = j.get("summary") instanceof String s && !s.isBlank() ? s.strip() : null;
        return new String[]{nome(j), sinopse, generosParaTaxonomia(j), poster, fundo != null ? fundo : poster,
                "igdb:" + ((Number) j.get("id")).longValue(), ano(j.get("first_release_date")), steam == null ? null : "steam:" + steam};
    }

    /** Endereço de uma imagem do IGDB (capa, arte ou tela) no tamanho pedido; null se não houver. */
    static String imagem(Object img, String tamanho) {
        if (img instanceof Map<?, ?> m && m.get("image_id") instanceof String id && id.matches("[A-Za-z0-9_]{1,40}")) {
            return IMAGENS + tamanho + "/" + id + ".jpg";
        }
        return null;
    }

    private static String primeiraImagem(Object lista) {
        if (lista instanceof List<?> l) {
            for (Object o : l) {
                String u = imagem(o, "t_1080p");
                if (u != null) return u;
            }
        }
        return null;
    }

    static String ano(Object dataUnix) {
        if (!(dataUnix instanceof Number n) || n.longValue() <= 0) return null;
        return String.valueOf(Instant.ofEpochSecond(n.longValue()).atZone(ZoneOffset.UTC).getYear());
    }

    /** appid do jogo na Steam (fonte externa 1), ou null. */
    static String steamDoJogo(Map<String, Object> j) {
        if (j.get("external_games") instanceof List<?> lista) {
            for (Object o : lista) {
                if (o instanceof Map<?, ?> e && e.get("external_game_source") instanceof Number f && f.intValue() == FONTE_STEAM
                        && e.get("uid") instanceof String uid && uid.matches("[0-9]{1,10}")) return uid;
            }
        }
        return null;
    }

    // Gêneros, temas, modos e perspectivas do IGDB (slug) -> tags da taxonomia do IaService
    private static final Map<String, String> TAGS_IGDB = Map.ofEntries(
        // gêneros
        Map.entry("point-and-click", "Point and Click"), Map.entry("fighting", "Luta"), Map.entry("shooter", "Shooter"),
        Map.entry("music", "Ritmo"), Map.entry("platform", "Plataforma"), Map.entry("puzzle", "Puzzle"),
        Map.entry("racing", "Corrida"), Map.entry("real-time-strategy-rts", "RTS"), Map.entry("role-playing-rpg", "RPG"),
        Map.entry("simulator", "Simulação"), Map.entry("sport", "Esporte"), Map.entry("strategy", "Estratégia"),
        Map.entry("turn-based-strategy-tbs", "TBS"), Map.entry("tactical", "Estratégia"),
        Map.entry("hack-and-slash-beat-em-up", "Hack and Slash"), Map.entry("quiz-trivia", "Trivia"),
        Map.entry("adventure", "Aventura"), Map.entry("indie", "Indie"), Map.entry("arcade", "Retro"),
        Map.entry("visual-novel", "Visual Novel"), Map.entry("moba", "MOBA"),
        // temas
        Map.entry("action", "Ação"), Map.entry("fantasy", "Fantasia"), Map.entry("science-fiction", "Ficção Científica"),
        Map.entry("horror", "Terror"), Map.entry("thriller", "Suspense"), Map.entry("survival", "Sobrevivência"),
        Map.entry("historical", "Histórico"), Map.entry("stealth", "Furtividade"), Map.entry("comedy", "Comédia"),
        Map.entry("business", "Gerenciamento"), Map.entry("drama", "Drama"), Map.entry("sandbox", "Sandbox"),
        Map.entry("kids", "Família"), Map.entry("open-world", "Mundo Aberto"), Map.entry("warfare", "Guerra"),
        Map.entry("party", "Party Game"), Map.entry("4x-explore-expand-exploit-and-exterminate", "Estratégia"),
        Map.entry("mystery", "Mistério"), Map.entry("romance", "Romance"),
        // modos de jogo
        Map.entry("single-player", "Um Jogador"), Map.entry("multiplayer", "Multijogador"),
        Map.entry("co-operative", "Cooperativo"), Map.entry("split-screen", "Co-op Local"),
        Map.entry("massively-multiplayer-online-mmo", "MMO"), Map.entry("battle-royale", "Battle Royale"),
        // perspectivas
        Map.entry("virtual-reality", "VR"), Map.entry("bird-view-slash-isometric", "Isométrico")
    );

    /** Tags da taxonomia a partir dos gêneros, temas, modos e perspectivas (dica para a IA, que dá o DNA final). */
    static String generosParaTaxonomia(Map<String, Object> j) {
        Set<String> tags = new LinkedHashSet<>();
        for (String campo : List.of("genres", "themes", "game_modes", "player_perspectives")) {
            if (j.get(campo) instanceof List<?> lista) {
                for (Object o : lista) {
                    if (o instanceof Map<?, ?> m && m.get("slug") instanceof String slug && TAGS_IGDB.containsKey(slug)) {
                        tags.add(TAGS_IGDB.get(slug));
                    }
                }
            }
        }
        // tiro em primeira ou terceira pessoa
        if (tags.contains("Shooter") && temSlug(j, "player_perspectives", "first-person")) tags.add("FPS");
        if (tags.contains("Shooter") && temSlug(j, "player_perspectives", "third-person")) tags.add("TPS");
        return String.join(", ", tags);
    }

    private static boolean temSlug(Map<String, Object> j, String campo, String slug) {
        return j.get(campo) instanceof List<?> lista && lista.stream().anyMatch(o -> o instanceof Map<?, ?> m && slug.equals(m.get("slug")));
    }

    // ------------------------------------------------------------------ plataformas e lojas

    // Nome curto das plataformas (pela abreviação do IGDB), na ordem em que aparecem
    private static final List<String[]> NOMES_PLATAFORMAS = List.of(
        new String[]{"PC", "PC"}, new String[]{"Mac", "Mac"}, new String[]{"Linux", "Linux"},
        new String[]{"PS5", "PS5"}, new String[]{"PS4", "PS4"}, new String[]{"PS3", "PS3"}, new String[]{"PS2", "PS2"},
        new String[]{"PS1", "PS1"}, new String[]{"Vita", "PS Vita"}, new String[]{"PSP", "PSP"},
        new String[]{"Series X|S", "Xbox Series X|S"}, new String[]{"XONE", "Xbox One"}, new String[]{"X360", "Xbox 360"},
        new String[]{"XBOX", "Xbox"}, new String[]{"Switch 2", "Switch 2"}, new String[]{"Switch", "Switch"},
        new String[]{"WiiU", "Wii U"}, new String[]{"Wii", "Wii"}, new String[]{"3DS", "3DS"},
        new String[]{"iOS", "iOS"}, new String[]{"Android", "Android"}
    );

    /** "PC, PS5, Xbox Series X|S, Switch" (até {@code maximo}, "+N" para o resto); null se não houver plataformas. */
    static String plataformas(Object lista, int maximo) {
        return plataformas(lista, maximo, ", ");
    }

    static String plataformas(Object lista, int maximo, String separador) {
        if (!(lista instanceof List<?> l) || l.isEmpty()) return null;
        Set<String> abreviacoes = new HashSet<>();
        List<String> outras = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> p)) continue;
            if (p.get("abbreviation") instanceof String a && !a.isBlank()) abreviacoes.add(a);
            else if (p.get("name") instanceof String n && !n.isBlank()) outras.add(n);
        }
        List<String> nomes = new ArrayList<>();
        for (String[] par : NOMES_PLATAFORMAS) if (abreviacoes.remove(par[0])) nomes.add(par[1]);
        abreviacoes.stream().sorted().forEach(nomes::add);   // plataformas antigas (NES, Mega Drive...) pela abreviação
        outras.stream().sorted().forEach(nomes::add);
        if (nomes.isEmpty()) return null;
        if (nomes.size() <= maximo) return String.join(separador, nomes);
        return String.join(separador, nomes.subList(0, maximo)) + separador + "+" + (nomes.size() - maximo);
    }

    // Tipos de site do IGDB (website_types) -> loja
    private static final Map<Integer, String> LOJAS_SITE = Map.ofEntries(
        Map.entry(13, "Steam"), Map.entry(16, "Epic Games Store"), Map.entry(17, "GOG"), Map.entry(23, "PlayStation Store"),
        Map.entry(22, "Xbox"), Map.entry(24, "Nintendo eShop"), Map.entry(10, "App Store"), Map.entry(11, "App Store"),
        Map.entry(12, "Google Play"), Map.entry(15, "itch.io"), Map.entry(26, "Game Jolt"));
    // Fontes externas do IGDB (external_game_sources) -> loja
    private static final Map<Integer, String> LOJAS_EXTERNAS = Map.ofEntries(
        Map.entry(1, "Steam"), Map.entry(5, "GOG"), Map.entry(26, "Epic Games Store"), Map.entry(36, "PlayStation Store"),
        Map.entry(31, "Xbox"), Map.entry(11, "Xbox"), Map.entry(13, "App Store"), Map.entry(15, "Google Play"),
        Map.entry(30, "itch.io"));
    private static final List<String> ORDEM_LOJAS = List.of("Steam", "Epic Games Store", "GOG", "PlayStation Store", "Xbox",
            "Nintendo eShop", "App Store", "Google Play", "itch.io", "Game Jolt");
    private static final Pattern LINK_SEGURO = Pattern.compile("https://[^\\s\"'<>]{4,500}");

    // Plataformas (id do IGDB) cujas lojas oficiais têm busca no Brasil: usadas quando o IGDB não tem o link direto
    private static final Map<String, Set<Integer>> PLATAFORMAS_DA_LOJA = Map.of(
        "PlayStation Store", Set.of(48, 167), "Xbox", Set.of(49, 169), "Nintendo eShop", Set.of(130, 508));

    /**
     * Onde jogar: links diretos das lojas (sites e fontes externas do IGDB, nas versões brasileiras quando existem) e,
     * para consoles sem link direto, a busca da loja oficial no Brasil. Cada item: {nome, modalidade, link}.
     */
    static List<Map<String, Object>> lojas(Map<String, Object> j, String titulo) {
        Map<String, String> links = new LinkedHashMap<>();
        if (j.get("websites") instanceof List<?> sites) {
            for (Object o : sites) {
                if (o instanceof Map<?, ?> s && s.get("type") instanceof Number t && LOJAS_SITE.containsKey(t.intValue())
                        && s.get("url") instanceof String url) adicionarLoja(links, LOJAS_SITE.get(t.intValue()), url);
            }
        }
        if (j.get("external_games") instanceof List<?> externos) {
            for (Object o : externos) {
                if (!(o instanceof Map<?, ?> e) || !(e.get("external_game_source") instanceof Number f)) continue;
                String loja = LOJAS_EXTERNAS.get(f.intValue());
                if (loja == null) continue;
                String url = e.get("url") instanceof String u ? u : null;
                if (url == null && f.intValue() == FONTE_STEAM && e.get("uid") instanceof String uid && uid.matches("[0-9]{1,10}")) {
                    url = "https://store.steampowered.com/app/" + uid;
                }
                if (url != null) adicionarLoja(links, "Xbox".equals(loja) && !url.contains("xbox.com") ? "Microsoft Store" : loja, url);
            }
        }
        List<Map<String, Object>> lista = new ArrayList<>();
        for (String loja : ORDEM_LOJAS) {
            if (links.containsKey(loja)) lista.add(loja(loja, "Loja", links.remove(loja)));
        }
        links.forEach((loja, url) -> lista.add(loja(loja, "Loja", url)));   // Microsoft Store e outras

        // Consoles sem link direto: a busca da loja oficial (ex.: jogo de Switch sem a página da eShop no IGDB)
        Set<Integer> ids = new HashSet<>();
        if (j.get("platforms") instanceof List<?> ps) {
            for (Object o : ps) if (o instanceof Map<?, ?> p && p.get("id") instanceof Number id) ids.add(id.intValue());
        }
        String q = titulo == null ? "" : URLEncoder.encode(titulo, StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, String> buscas = Map.of(
            "PlayStation Store", "https://store.playstation.com/pt-br/search/" + q,
            "Xbox", "https://www.xbox.com/pt-BR/search/results/games?q=" + q,
            "Nintendo eShop", "https://www.nintendo.com/pt-br/search/#q=" + q);
        for (String loja : List.of("PlayStation Store", "Xbox", "Nintendo eShop")) {
            boolean temLink = lista.stream().anyMatch(m -> loja.equals(m.get("nome")));
            if (!temLink && !q.isEmpty() && PLATAFORMAS_DA_LOJA.get(loja).stream().anyMatch(ids::contains)) {
                lista.add(loja(loja, "Busca na loja", buscas.get(loja)));
            }
        }
        return lista;
    }

    private static void adicionarLoja(Map<String, String> links, String loja, String url) {
        String u = url.strip();
        if (u.startsWith("http://")) u = "https://" + u.substring(7);
        if (!LINK_SEGURO.matcher(u).matches()) return;
        links.putIfAbsent(loja, versaoBrasileira(u));
    }

    private static Map<String, Object> loja(String nome, String modalidade, String link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", nome);
        m.put("modalidade", modalidade);
        m.put("link", link);
        return m;
    }

    private static final Pattern NINTENDO = Pattern.compile("https://www\\.nintendo\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:store/products|games/detail)/([a-z0-9-]{1,150})/?");

    /** O mesmo endereço na loja brasileira (o IGDB guarda as páginas dos EUA): preço em reais e texto em português. */
    static String versaoBrasileira(String url) {
        Matcher n = NINTENDO.matcher(url);
        if (n.matches()) return "https://www.nintendo.com/pt-br/store/products/" + n.group(1) + "/";
        return url
                .replaceFirst("^https://www\\.xbox\\.com/[a-zA-Z]{2}-[a-zA-Z]{2}/", "https://www.xbox.com/pt-BR/")
                .replaceFirst("^https://store\\.playstation\\.com/[a-zA-Z]{2}-[a-zA-Z]{2}/", "https://store.playstation.com/pt-br/")
                .replaceFirst("^https://store\\.epicgames\\.com/[a-zA-Z]{2}-[a-zA-Z]{2}/", "https://store.epicgames.com/pt-BR/")
                .replaceFirst("^https://www\\.epicgames\\.com/store/[a-zA-Z]{2}-[a-zA-Z]{2}/", "https://store.epicgames.com/pt-BR/")
                .replaceFirst("^https://(?:apps|itunes)\\.apple\\.com/[a-z]{2}/app/", "https://apps.apple.com/br/app/")
                .replaceFirst("^https://(?:apps|itunes)\\.apple\\.com/app/", "https://apps.apple.com/br/app/");
    }

    // ------------------------------------------------------------------ acesso à API

    /** Tira do texto o que quebraria a consulta do IGDB (aspas, barra invertida, ponto e vírgula). */
    static String textoSeguro(String s) {
        return s == null ? "" : s.replaceAll("[\\\\\";*]", " ").replaceAll("\\s+", " ").strip();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consultar(String endpoint, String corpo) {
        String acesso = token();
        if (acesso == null) return null;
        HttpHeaders h = new HttpHeaders();
        h.set("Client-ID", clientId);
        h.setBearerAuth(acesso);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.setContentType(MediaType.TEXT_PLAIN);
        aguardarVez();
        try {
            Object r = carteiro.postForObject(API + endpoint, new HttpEntity<>(corpo, h), Object.class);
            return r instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            token = null;   // token revogado/vencido antes da hora: pede outro na próxima
            tokenVenceEm = Instant.MIN;
            throw e;
        }
    }

    /** No máximo 4 consultas por segundo (limite do IGDB): espera a vez quando várias chegam juntas. */
    private void aguardarVez() {
        long espera;
        synchronized (this) {
            long agora = System.currentTimeMillis();
            long vez = Math.max(agora, proximaConsulta);
            proximaConsulta = vez + INTERVALO_MS;
            espera = vez - agora;
        }
        if (espera > 0) {
            try { Thread.sleep(espera); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Token de aplicativo da Twitch (client credentials), reaproveitado até 1 hora antes de vencer. */
    @SuppressWarnings("unchecked")
    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenVenceEm)) return token;
        Map<String, Object> r = carteiro.postForObject(ClienteHttp.uri(TOKEN,
                Map.of("client_id", clientId, "client_secret", clientSecret, "grant_type", "client_credentials")), null, Map.class);
        if (r == null || !(r.get("access_token") instanceof String t)) return null;
        long segundos = r.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        token = t;
        tokenVenceEm = Instant.now().plusSeconds(Math.max(60, segundos - 3600));
        return token;
    }

    private void registrarFalha(Exception e) {
        indisponivelAte = Instant.now().plus(PAUSA_APOS_FALHA);
        System.out.println("IGDB indisponível (" + e.getMessage() + "); usando a Steam por " + PAUSA_APOS_FALHA.toMinutes() + " min.");
    }
}
