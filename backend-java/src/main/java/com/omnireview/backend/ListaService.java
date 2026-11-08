package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * "Minhas listas": listas pessoais de obras que o usuário quer ver ou jogar.
 *
 * Modelo no grafo:
 *   (:Usuario)-[:CRIOU]->(:Lista {id, dono, nome, criadaEm, ordem, padrao})-[:CONTEM {em}]->(:Obra)
 * A propriedade "dono" repete o login para que as listas possam ser apagadas junto com a conta.
 * Toda conta começa com duas listas padrão: "Quero assistir" e "Quero jogar" (podem ser renomeadas ou excluídas).
 */
@Service
public class ListaService {

    static final int MAX_NOME = 40;
    static final int MAX_LISTAS = 30;
    static final int MAX_OBRAS_POR_LISTA = 500;
    static final List<String> LISTAS_PADRAO = List.of("Quero assistir", "Quero jogar");

    /** Resultado de uma operação: corpo da resposta ou erro com status HTTP. */
    public record Resultado(boolean ok, Object corpo, String erro, int status) {
        static Resultado sucesso(Object corpo) { return new Resultado(true, corpo, null, 200); }
        static Resultado falha(int status, String erro) { return new Resultado(false, null, erro, status); }
    }

    private final Neo4jClient neo4j;
    private final Clock relogio;

    @Autowired
    public ListaService(Neo4jClient neo4j) {
        this(neo4j, Clock.systemUTC());
    }

    ListaService(Neo4jClient neo4j, Clock relogio) {
        this.neo4j = neo4j;
        this.relogio = relogio;
    }

    // ---------------- Validação (pura) ----------------

    /** Nome sem quebras de linha/controles e com espaços normalizados. */
    static String limparNome(String nome) {
        if (nome == null) return "";
        return nome.replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").strip();
    }

    static String validarNome(String nomeLimpo) {
        if (nomeLimpo.isEmpty()) return "Dê um nome para a lista.";
        if (nomeLimpo.codePointCount(0, nomeLimpo.length()) > MAX_NOME) return "O nome pode ter no máximo " + MAX_NOME + " caracteres.";
        return null;
    }

    // ---------------- Consultas ----------------

    /** Todas as listas do usuário, com as obras (mais recentes primeiro). Cria as listas padrão no primeiro acesso. */
    public List<Map<String, Object>> listas(String eu) {
        criarPadraoSeNecessario(eu);
        Collection<Map<String, Object>> linhas = neo4j.query(
                "MATCH (l:Lista {dono: $eu}) " +
                "OPTIONAL MATCH (l)-[c:CONTEM]->(o:Obra) " +
                "WITH l, c, o ORDER BY c.em DESC " +
                "WITH l, collect(CASE WHEN o IS NULL THEN null ELSE {titulo: o.titulo, tipo: o.tipo, imagem: o.imagem, capa: o.capa, " +
                "     dnaCriativo: o.dnaCriativo, sinopse: o.sinopse, adicionadaEm: c.em} END) AS obras " +
                "RETURN l.id AS id, l.nome AS nome, l.criadaEm AS criadaEm, coalesce(l.padrao, false) AS padrao, obras " +
                "ORDER BY coalesce(l.ordem, 100), l.criadaEm, l.nome")
                .bindAll(Map.of("eu", eu)).fetch().all();
        return new ArrayList<>(linhas);
    }

    /** As duas listas padrão são criadas uma única vez por conta (se o usuário excluí-las, não voltam). */
    private synchronized void criarPadraoSeNecessario(String eu) {
        String agora = Instant.now(relogio).toString();
        neo4j.query(
                "MATCH (u:Usuario {usuario: $eu}) WHERE NOT coalesce(u.listasIniciadas, false) " +
                "SET u.listasIniciadas = true " +
                "CREATE (u)-[:CRIOU]->(:Lista {id: $id0, dono: $eu, nome: $nome0, criadaEm: $agora, ordem: 0, padrao: true}) " +
                "CREATE (u)-[:CRIOU]->(:Lista {id: $id1, dono: $eu, nome: $nome1, criadaEm: $agora, ordem: 1, padrao: true})")
                .bindAll(Map.of("eu", eu, "agora", agora,
                        "id0", UUID.randomUUID().toString(), "nome0", LISTAS_PADRAO.get(0),
                        "id1", UUID.randomUUID().toString(), "nome1", LISTAS_PADRAO.get(1)))
                .run();
    }

    // ---------------- Escrita ----------------

    public Resultado criar(String eu, String nomeInformado) {
        String nome = limparNome(nomeInformado);
        String erro = validarNome(nome);
        if (erro != null) return Resultado.falha(400, erro);
        criarPadraoSeNecessario(eu);
        long total = contar("MATCH (l:Lista {dono: $eu}) RETURN count(l) AS n", Map.of("eu", eu));
        if (total >= MAX_LISTAS) return Resultado.falha(400, "Você já tem " + MAX_LISTAS + " listas. Exclua alguma para criar outra.");
        if (nomeEmUso(eu, nome, null)) return Resultado.falha(409, "Você já tem uma lista com esse nome.");
        String id = UUID.randomUUID().toString();
        neo4j.query("MATCH (u:Usuario {usuario: $eu}) " +
                    "CREATE (u)-[:CRIOU]->(:Lista {id: $id, dono: $eu, nome: $nome, criadaEm: $agora, ordem: 100})")
                .bindAll(Map.of("eu", eu, "id", id, "nome", nome, "agora", Instant.now(relogio).toString())).run();
        return new Resultado(true, Map.of("id", id, "listas", listas(eu)), null, 201);
    }

    public Resultado renomear(String eu, String id, String nomeInformado) {
        String nome = limparNome(nomeInformado);
        String erro = validarNome(nome);
        if (erro != null) return Resultado.falha(400, erro);
        if (!existe(eu, id)) return Resultado.falha(404, "Lista não encontrada.");
        if (nomeEmUso(eu, nome, id)) return Resultado.falha(409, "Você já tem uma lista com esse nome.");
        neo4j.query("MATCH (l:Lista {id: $id, dono: $eu}) SET l.nome = $nome").bindAll(Map.of("eu", eu, "id", id, "nome", nome)).run();
        return Resultado.sucesso(Map.of("listas", listas(eu)));
    }

    public Resultado excluir(String eu, String id) {
        if (!existe(eu, id)) return Resultado.falha(404, "Lista não encontrada.");
        neo4j.query("MATCH (l:Lista {id: $id, dono: $eu}) DETACH DELETE l").bindAll(Map.of("eu", eu, "id", id)).run();
        return Resultado.sucesso(Map.of("listas", listas(eu)));
    }

    public Resultado adicionarObra(String eu, String id, String titulo) {
        if (!existe(eu, id)) return Resultado.falha(404, "Lista não encontrada.");
        long naLista = contar("MATCH (:Lista {id: $id, dono: $eu})-[c:CONTEM]->() RETURN count(c) AS n", Map.of("eu", eu, "id", id));
        if (naLista >= MAX_OBRAS_POR_LISTA) return Resultado.falha(400, "Esta lista já tem " + MAX_OBRAS_POR_LISTA + " obras.");
        long feito = neo4j.query(
                "MATCH (l:Lista {id: $id, dono: $eu}), (o:Obra {titulo: $titulo}) " +
                "MERGE (l)-[c:CONTEM]->(o) ON CREATE SET c.em = $agora RETURN count(c) AS n")
                .bindAll(Map.of("eu", eu, "id", id, "titulo", titulo == null ? "" : titulo, "agora", Instant.now(relogio).toString()))
                .fetchAs(Long.class).one().orElse(0L);
        if (feito == 0) return Resultado.falha(404, "Obra não encontrada.");
        return Resultado.sucesso(Map.of("listas", listas(eu)));
    }

    public Resultado removerObra(String eu, String id, String titulo) {
        if (!existe(eu, id)) return Resultado.falha(404, "Lista não encontrada.");
        neo4j.query("MATCH (:Lista {id: $id, dono: $eu})-[c:CONTEM]->(:Obra {titulo: $titulo}) DELETE c")
                .bindAll(Map.of("eu", eu, "id", id, "titulo", titulo == null ? "" : titulo)).run();
        return Resultado.sucesso(Map.of("listas", listas(eu)));
    }

    // ---------------- Auxiliares ----------------

    /** A lista existe e pertence ao usuário (lista de outra pessoa responde como inexistente). */
    private boolean existe(String eu, String id) {
        return contar("MATCH (l:Lista {id: $id, dono: $eu}) RETURN count(l) AS n", Map.of("eu", eu, "id", id == null ? "" : id)) > 0;
    }

    private boolean nomeEmUso(String eu, String nome, String ignorarId) {
        Map<String, Object> p = new HashMap<>();
        p.put("eu", eu);
        p.put("nome", nome.toLowerCase(Locale.ROOT));
        p.put("ignorar", ignorarId == null ? "" : ignorarId);
        return contar("MATCH (l:Lista {dono: $eu}) WHERE toLower(l.nome) = $nome AND l.id <> $ignorar RETURN count(l) AS n", p) > 0;
    }

    private long contar(String cypher, Map<String, Object> params) {
        return neo4j.query(cypher).bindAll(params).fetchAs(Long.class).one().orElse(0L);
    }
}
