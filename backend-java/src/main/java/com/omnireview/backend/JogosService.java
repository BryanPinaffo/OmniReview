package com.omnireview.backend;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jogos de todas as plataformas: o IGDB é a fonte principal (PC, PlayStation, Xbox, Nintendo e celular, com lojas e
 * tempo para zerar); a Steam dá a sinopse em português dos jogos de PC e fica de reserva quando o IGDB está fora do
 * ar, sem credenciais ou não conhece o jogo. Substitui a RAWG, que saiu do ar (HTTP 522 desde novembro de 2026).
 */
@Service
public class JogosService {

    static final int MAX_TENDENCIAS = 15;

    private final IgdbService igdb;
    private final SteamService steam;

    public JogosService(IgdbService igdb, SteamService steam) {
        this.igdb = igdb;
        this.steam = steam;
    }

    /** O jogo que mais combina com o nome: {titulo, sinopse, generos, poster, capa, ref, ano, outra fonte} ou null. */
    public String[] buscarJogoNaInternet(String nome) {
        List<Map<String, Object>> achados = igdb.pesquisar(nome, 1);
        if (achados != null && !achados.isEmpty()) {
            String[] dados = porIgdb(((Number) achados.get(0).get("id")).longValue());
            if (dados != null) return dados;
        }
        return steam.buscarJogo(nome);
    }

    /** Até {@code maximo} opções da busca: {ref, titulo, ano, imagem, detalhe (plataformas), refSteam}. */
    public List<Map<String, Object>> candidatos(String nome, int maximo) {
        List<Map<String, Object>> achados = igdb.pesquisar(nome, maximo);
        if (achados == null || achados.isEmpty()) return steam.candidatos(nome, maximo);
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Map<String, Object> j : achados) lista.add(IgdbService.candidato(j));
        return lista;
    }

    /** Jogo pelo id do IGDB ("igdb:ID"); null se não existir ou se o IGDB falhar. */
    public String[] porIgdb(long id) {
        Map<String, Object> j = igdb.jogo(id);
        if (j == null || j.isEmpty()) return null;
        return comSinopseDaSteam(IgdbService.dadosDoJogo(j));
    }

    /**
     * Jogo da Steam ("steam:APPID", ex.: do Top 15): o mesmo jogo no IGDB, com todas as plataformas e a fonte do IGDB
     * (assim ele não entra duas vezes se alguém já o trouxe pela busca); se o IGDB não o conhecer, só a Steam.
     */
    public String[] porSteam(String appid) {
        Long id = igdb.idPelaSteam(appid);
        if (id != null && id > 0) {
            String[] dados = porIgdb(id);
            if (dados != null) {
                if (dados[7] == null) dados[7] = "steam:" + appid;
                return dados;
            }
        }
        return steam.porAppId(appid);
    }

    /** O resumo do IGDB é sempre em inglês: se a Steam tem a descrição em português, ela entra no lugar. */
    private String[] comSinopseDaSteam(String[] dados) {
        String outra = dados[7];
        if (outra == null || !outra.startsWith("steam:")) return dados;
        String sinopse = steam.sinopseEmPortugues(outra.substring("steam:".length()));
        if (sinopse != null) dados[1] = sinopse;
        return dados;
    }

    /** Jogos que mais gente está jogando agora (IGDB, todas as plataformas); se o IGDB falhar, os mais vendidos da Steam. */
    public List<String> buscarTendencias() {
        List<String[]> jogando = igdb.jogandoAgora(MAX_TENDENCIAS);
        if (jogando != null && !jogando.isEmpty()) return jogando.stream().map(j -> j[1]).toList();
        return steam.buscarTendencias(MAX_TENDENCIAS);
    }

    /**
     * Top 15 de jogos principal: jogando agora em todas as plataformas (IGDB), {ref, nome}. Vazio se o IGDB falhar
     * (o Top mantém a lista anterior; a da Steam é outra lista, que aparece na aba Jogos).
     */
    public List<String[]> jogandoAgora(int maximo) {
        List<String[]> lista = igdb.jogandoAgora(maximo);
        return lista == null ? List.of() : lista;
    }

    /** Mais jogados agora na Steam (só PC), {ref ("steam:ID"), nome}: a segunda lista de jogos, na aba Jogos. */
    public List<String[]> maisJogadosNaSteam(int maximo) {
        return steam.maisJogados(maximo);
    }

    /**
     * Ficha do jogo: plataformas, onde jogar (lojas de cada plataforma) e tempo para zerar, pelo IGDB. Se o IGDB não
     * responder ou não conhecer o jogo, ao menos o link da Steam (fonte "Steam"). Null se nada for encontrado.
     * "tempoIndisponivel" avisa que o tempo para zerar falhou agora (a ficha fica pouco tempo no cache).
     */
    public Map<String, Object> ficha(Obra obra) {
        String titulo = RecomendacaoController.semSufixoDeMidia(obra.getTitulo());
        Map<String, Object> j = jogoDaObra(obra, titulo);
        if (j != null && !j.isEmpty() && j.get("id") instanceof Number id) {
            Map<String, Object> ficha = new LinkedHashMap<>();
            ficha.put("fonte", "IGDB");
            String plataformas = IgdbService.plataformas(j.get("platforms"), 12);
            if (plataformas != null) ficha.put("plataformas", plataformas);
            ficha.put("ondeJogar", IgdbService.lojas(j, titulo));
            Map<String, Object> zerar = igdb.tempoParaZerarDoJogo(id.longValue());
            if (zerar == null) ficha.put("tempoIndisponivel", true);
            else if (!zerar.isEmpty()) ficha.put("tempoParaZerar", zerar);
            return ficha;
        }
        String link = steam.linkDaLoja(titulo);
        if (link == null) return null;
        Map<String, Object> ficha = new LinkedHashMap<>();
        ficha.put("fonte", "Steam");
        ficha.put("ondeJogar", List.of(Map.of("nome", "Steam", "modalidade", "Loja", "link", link)));
        return ficha;
    }

    /**
     * O jogo da obra no IGDB: pela fonte (id do IGDB, ou o da Steam) e, nas obras sem fonte, pelo título.
     * Map vazio se o IGDB não conhece o jogo; null se ele estiver indisponível.
     */
    private Map<String, Object> jogoDaObra(Obra obra, String titulo) {
        List<String> fontes = new ArrayList<>();
        if (obra.getFonte() != null) fontes.add(obra.getFonte());
        if (obra.getOutrasFontes() != null) fontes.addAll(obra.getOutrasFontes());
        for (String fonte : fontes) {
            if (fonte.matches("igdb:[0-9]{1,10}")) return igdb.jogo(Long.parseLong(fonte.substring(5)));
        }
        for (String fonte : fontes) {
            if (fonte.matches("steam:[0-9]{1,10}")) {
                Long id = igdb.idPelaSteam(fonte.substring(6));
                if (id == null) return null;
                if (id > 0) return igdb.jogo(id);
            }
        }
        List<Map<String, Object>> achados = igdb.pesquisar(titulo, 5);
        if (achados == null) return null;
        if (achados.isEmpty()) return Map.of();
        Map<String, Object> melhor = IgdbService.escolherJogo(titulo, achados);
        return igdb.jogo(((Number) melhor.get("id")).longValue());
    }
}
