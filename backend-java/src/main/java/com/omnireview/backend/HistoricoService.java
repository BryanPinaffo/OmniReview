package com.omnireview.backend;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Histórico de atividades para o administrador: quem adicionou ou excluiu obras, criou contas, avaliou,
 * comentou e mexeu em listas.
 *
 * Cada evento é um nó (:Evento {id, ms, em, usuario, categoria, acao, alvo, obra, detalhe}):
 *  - ms: data em milissegundos (ordenação e paginação); em: a mesma data em ISO-8601 (exibição);
 *  - categoria: obra | conta | avaliacao | lista;
 *  - acao: frase curta no passado ("adicionou a obra"); alvo: sobre o quê (título, lista, conta);
 *  - obra: título da obra envolvida (para abrir a obra pelo histórico); detalhe: complemento opcional.
 * Nunca guarda senhas. Registrar nunca derruba a ação principal: falhas só vão para o log.
 */
@Service
public class HistoricoService {

    static final int MAX_EVENTOS = 20_000;   // os mais antigos são apagados na inicialização
    static final int MAX_DETALHE = 160;
    static final Set<String> CATEGORIAS = Set.of("obra", "conta", "avaliacao", "lista");

    private final Neo4jClient neo4j;

    public HistoricoService(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    public void registrar(String usuario, String categoria, String acao, String alvo) {
        registrar(usuario, categoria, acao, alvo, null, null);
    }

    public void registrar(String usuario, String categoria, String acao, String alvo, String obra, String detalhe) {
        try {
            Instant agora = Instant.now();
            Map<String, Object> p = new HashMap<>();
            p.put("id", UUID.randomUUID().toString());
            p.put("ms", agora.toEpochMilli());
            p.put("em", agora.toString());
            p.put("usuario", usuario);
            p.put("categoria", categoria);
            p.put("acao", acao);
            p.put("alvo", alvo);
            p.put("obra", obra);
            p.put("detalhe", detalhe == null ? null : resumir(detalhe));
            neo4j.query("CREATE (:Evento {id: $id, ms: $ms, em: $em, usuario: $usuario, categoria: $categoria, " +
                        "acao: $acao, alvo: $alvo, obra: $obra, detalhe: $detalhe})").bindAll(p).run();
        } catch (Exception e) {
            System.out.println("Histórico: falha ao registrar '" + acao + "' de " + usuario + ": " + e.getMessage());
        }
    }

    /**
     * Eventos do mais recente para o mais antigo. Filtros opcionais: categoria, texto (no usuário, alvo ou obra)
     * e "antes" (ms do último evento da página anterior). Devolve {eventos, proximo}; proximo = null no fim.
     */
    public Map<String, Object> listar(String categoria, String texto, Long antes, int limite) {
        int n = Math.max(1, Math.min(limite, 200));
        Map<String, Object> p = new HashMap<>();
        p.put("categoria", categoria != null && CATEGORIAS.contains(categoria) ? categoria : null);
        p.put("texto", texto == null || texto.isBlank() ? null : texto.strip().toLowerCase(Locale.ROOT));
        p.put("antes", antes);
        p.put("limite", n + 1);
        List<Map<String, Object>> linhas = new ArrayList<>(neo4j.query(
                "MATCH (e:Evento) WHERE ($categoria IS NULL OR e.categoria = $categoria) " +
                "  AND ($antes IS NULL OR e.ms < $antes) " +
                "  AND ($texto IS NULL OR toLower(coalesce(e.usuario, '')) CONTAINS $texto " +
                "       OR toLower(coalesce(e.alvo, '')) CONTAINS $texto OR toLower(coalesce(e.obra, '')) CONTAINS $texto) " +
                "RETURN e.id AS id, e.ms AS ms, e.em AS em, e.usuario AS usuario, e.categoria AS categoria, e.acao AS acao, " +
                "       e.alvo AS alvo, e.obra AS obra, e.detalhe AS detalhe " +
                "ORDER BY e.ms DESC LIMIT $limite").bindAll(p).fetch().all());
        boolean temMais = linhas.size() > n;
        if (temMais) linhas = linhas.subList(0, n);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("eventos", linhas);
        r.put("proximo", temMais ? linhas.get(linhas.size() - 1).get("ms") : null);
        return r;
    }

    /** Quantas obras cada usuário adicionou ao acervo (só as adicionadas depois que o histórico existe). */
    public List<Map<String, Object>> autores() {
        return new ArrayList<>(neo4j.query(
                "MATCH (o:Obra) WHERE o.adicionadaPor IS NOT NULL " +
                "RETURN o.adicionadaPor AS usuario, count(o) AS obras, max(o.adicionadaEm) AS ultima " +
                "ORDER BY obras DESC, usuario LIMIT 50").fetch().all());
    }

    /** Título da obra de um comentário (para registrar a exclusão antes de o comentário sumir). */
    public Optional<String> obraDoComentario(String idComentario) {
        return neo4j.query("MATCH (:Comentario {id: $id})-[:SOBRE]->(o:Obra) RETURN o.titulo AS t")
                .bindAll(Map.of("id", idComentario == null ? "" : idComentario)).fetchAs(String.class).one();
    }

    /** Mantém só os eventos mais recentes. */
    public void podar() {
        neo4j.query("MATCH (e:Evento) WITH e ORDER BY e.ms DESC SKIP $max DETACH DELETE e")
                .bindAll(Map.of("max", MAX_EVENTOS)).run();
    }

    static String resumir(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= MAX_DETALHE ? t : t.substring(0, MAX_DETALHE) + "…";
    }

    static String nomeDoTipo(String tipo) {
        String t = Provedores.normalizar(tipo);
        if (t.startsWith("jogo")) return "o jogo";
        if (t.startsWith("serie")) return "a série";
        if (t.startsWith("livro")) return "o livro";
        return "o filme";
    }
}
