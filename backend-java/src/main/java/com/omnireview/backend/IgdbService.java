package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Tempo para zerar jogos pelo IGDB (base de jogos da Twitch): só a história, com extras e 100%.
 * Precisa de um aplicativo gratuito da Twitch (omnireview.igdb.client-id / client-secret, ou OMNIREVIEW_IGDB_CLIENT_ID /
 * OMNIREVIEW_IGDB_CLIENT_SECRET). O token de acesso é pedido à Twitch e renovado sozinho antes de vencer.
 * Sem credenciais, ou se o IGDB falhar, a ficha do jogo continua só com o link do HowLongToBeat.
 */
@Service
public class IgdbService {

    private static final String API = "https://api.igdb.com/v4/";
    private static final String TOKEN = "https://id.twitch.tv/oauth2/token";
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(10);

    private final String clientId;
    private final String clientSecret;
    private final RestTemplate carteiro = ClienteHttp.criar(5_000, 10_000);
    private volatile String token;
    private volatile Instant tokenVenceEm = Instant.MIN;
    private volatile Instant indisponivelAte = Instant.MIN;

    public IgdbService(@Value("${omnireview.igdb.client-id:}") String clientId,
                       @Value("${omnireview.igdb.client-secret:}") String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        if (this.clientId.isEmpty() || this.clientSecret.isEmpty()) {
            System.out.println("AVISO: credenciais do IGDB não configuradas; o tempo para zerar dos jogos fica só no link do HowLongToBeat.");
        }
    }

    public boolean disponivel() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && Instant.now().isAfter(indisponivelAte);
    }

    /**
     * Horas para zerar o jogo: {historia, extras, completo} (as que existirem) e quantas pessoas informaram.
     * Map vazio se o jogo não tem esse dado; null se o IGDB não estiver disponível.
     */
    public Map<String, Object> tempoParaZerar(String titulo) {
        if (!disponivel() || titulo == null || titulo.isBlank()) return null;
        try {
            // 1) O jogo: ver escolherJogo (há mods e relançamentos com o mesmo nome do original)
            String nome = titulo.replace("\\", " ").replace("\"", " ").strip();
            List<Map<String, Object>> jogos = consultar("games",
                    "search \"" + nome + "\"; fields id,name,game_type,total_rating_count; where version_parent = null; limit 10;");
            if (jogos == null) return null;
            if (jogos.isEmpty()) return Map.of();
            Map<String, Object> jogo = escolherJogo(nome, jogos);

            // 2) O tempo para zerar informado pelos jogadores (em segundos)
            List<Map<String, Object>> tempos = consultar("game_time_to_beats",
                    "fields hastily,normally,completely,count; where game_id = " + ((Number) jogo.get("id")).longValue() + "; limit 1;");
            if (tempos == null) return null;
            if (tempos.isEmpty()) return Map.of();
            return horas(tempos.get(0));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consultar(String endpoint, String corpo) {
        String acesso = token();
        if (acesso == null) return null;
        HttpHeaders h = new HttpHeaders();
        h.set("Client-ID", clientId);
        h.setBearerAuth(acesso);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.setContentType(MediaType.TEXT_PLAIN);
        try {
            Object r = carteiro.postForObject(API + endpoint, new HttpEntity<>(corpo, h), Object.class);
            return r instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            token = null;   // token revogado/vencido antes da hora: pede outro na próxima
            tokenVenceEm = Instant.MIN;
            throw e;
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
        System.out.println("IGDB indisponível (" + e.getMessage() + "); tentando de novo em " + PAUSA_APOS_FALHA.toMinutes() + " min.");
    }
}
