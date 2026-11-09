package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notas (1 a 5 estrelas) e comentários dos usuários sobre as obras.
 *
 * Modelo no grafo:
 *   (:Usuario)-[:AVALIOU {estrelas, em}]->(:Obra)            uma nota por usuário e obra (refazer substitui)
 *   (:Usuario)-[:ESCREVEU]->(:Comentario {id, autor, texto, criadoEm})-[:SOBRE]->(:Obra)
 */
@Service
public class AvaliacaoService {

    static final int MIN_ESTRELAS = 1;
    static final int MAX_ESTRELAS = 5;
    static final int MAX_COMENTARIO = 1000;
    static final int LIMITE_COMENTARIOS = 200;

    /** Resultado de uma operação: corpo da resposta ou erro com status HTTP. */
    public record Resultado(boolean ok, Map<String, Object> corpo, String erro, int status) {
        static Resultado sucesso(Map<String, Object> corpo) { return new Resultado(true, corpo, null, 200); }
        static Resultado falha(int status, String erro) { return new Resultado(false, null, erro, status); }
    }

    private final Neo4jClient neo4j;
    private final Clock relogio;

    @Autowired
    public AvaliacaoService(Neo4jClient neo4j) {
        this(neo4j, Clock.systemUTC());
    }

    AvaliacaoService(Neo4jClient neo4j, Clock relogio) {
        this.neo4j = neo4j;
        this.relogio = relogio;
    }

    // ---------------- Validação (pura, testada sem banco) ----------------

    static Optional<String> validarEstrelas(Integer estrelas) {
        if (estrelas == null || estrelas < MIN_ESTRELAS || estrelas > MAX_ESTRELAS) {
            return Optional.of("A nota deve ser de " + MIN_ESTRELAS + " a " + MAX_ESTRELAS + " estrelas.");
        }
        return Optional.empty();
    }

    /** Remove espaços nas pontas, padroniza quebras de linha e limita linhas em branco seguidas. */
    static String limparTexto(String texto) {
        if (texto == null) return "";
        return texto.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\n\t]]", "")   // remove caracteres de controle
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    static Optional<String> validarComentario(String textoLimpo) {
        if (textoLimpo.isEmpty()) return Optional.of("Escreva algo antes de publicar.");
        if (textoLimpo.codePointCount(0, textoLimpo.length()) > MAX_COMENTARIO) {
            return Optional.of("O comentário pode ter no máximo " + MAX_COMENTARIO + " caracteres.");
        }
        return Optional.empty();
    }

    /** Média arredondada para 1 casa (null quando não há notas). */
    static Double arredondar(Double media) {
        return media == null ? null : Math.round(media * 10.0) / 10.0;
    }

    // ---------------- Consultas ----------------

    /** Média e total de notas de todas as obras avaliadas (para os cards do catálogo). */
    public List<Map<String, Object>> resumoGeral() {
        Collection<Map<String, Object>> linhas = neo4j.query(
                "MATCH (:Usuario)-[a:AVALIOU]->(o:Obra) " +
                "RETURN o.titulo AS titulo, avg(a.estrelas) AS media, count(a) AS total ORDER BY titulo").fetch().all();
        List<Map<String, Object>> resumo = new ArrayList<>();
        for (Map<String, Object> l : linhas) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("titulo", l.get("titulo"));
            item.put("media", arredondar(numero(l.get("media"))));
            item.put("total", inteiro(l.get("total")));
            resumo.add(item);
        }
        return resumo;
    }

    /** Média, distribuição, nota do usuário e comentários de uma obra. */
    public Resultado detalhes(String titulo, String usuario) {
        Optional<Map<String, Object>> notas = neo4j.query(
                "MATCH (o:Obra {titulo: $titulo}) " +
                "OPTIONAL MATCH (u:Usuario)-[a:AVALIOU]->(o) " +
                "WITH o, collect({usuario: u.usuario, estrelas: a.estrelas}) AS todas " +   // agrupar por o: sem obra, sem linha (404)
                "WITH [x IN todas WHERE x.estrelas IS NOT NULL] AS notas " +
                "RETURN size(notas) AS total, " +
                "       CASE WHEN size(notas) = 0 THEN null ELSE reduce(s = 0.0, x IN notas | s + x.estrelas) / size(notas) END AS media, " +
                "       head([x IN notas WHERE x.usuario = $usuario | x.estrelas]) AS minha, " +
                "       [k IN range(1, 5) | size([x IN notas WHERE x.estrelas = k])] AS distribuicao")
                .bindAll(Map.of("titulo", titulo == null ? "" : titulo, "usuario", usuario))
                .fetch().one();
        if (notas.isEmpty()) return Resultado.falha(404, "Obra não encontrada.");

        Collection<Map<String, Object>> linhas = neo4j.query(
                "MATCH (c:Comentario)-[:SOBRE]->(o:Obra {titulo: $titulo}) " +
                "OPTIONAL MATCH (autor:Usuario {usuario: c.autor}) OPTIONAL MATCH (autor)-[a:AVALIOU]->(o) " +
                "RETURN c.id AS id, c.autor AS autor, c.texto AS texto, c.criadoEm AS criadoEm, a.estrelas AS estrelas, autor.foto AS foto " +
                "ORDER BY c.criadoEm DESC LIMIT " + LIMITE_COMENTARIOS)
                .bindAll(Map.of("titulo", titulo)).fetch().all();
        List<Map<String, Object>> comentarios = new ArrayList<>();
        for (Map<String, Object> l : linhas) {
            comentarios.add(comentario(l, usuario));
        }

        Map<String, Object> n = notas.get();
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("titulo", titulo);
        corpo.put("media", arredondar(numero(n.get("media"))));
        corpo.put("total", inteiro(n.get("total")));
        corpo.put("minhaNota", n.get("minha") == null ? null : inteiro(n.get("minha")));
        corpo.put("distribuicao", n.get("distribuicao"));
        corpo.put("comentarios", comentarios);
        return Resultado.sucesso(corpo);
    }

    // ---------------- Escrita ----------------

    public Resultado avaliar(String titulo, Integer estrelas, String usuario) {
        Optional<String> erro = validarEstrelas(estrelas);
        if (erro.isPresent()) return Resultado.falha(400, erro.get());
        Optional<Long> feito = neo4j.query(
                "MATCH (u:Usuario {usuario: $usuario}), (o:Obra {titulo: $titulo}) " +
                "MERGE (u)-[a:AVALIOU]->(o) SET a.estrelas = $estrelas, a.em = $agora RETURN count(a) AS n")
                .bindAll(Map.of("usuario", usuario, "titulo", titulo == null ? "" : titulo,
                        "estrelas", estrelas, "agora", Instant.now(relogio).toString()))
                .fetchAs(Long.class).one();
        if (feito.orElse(0L) == 0L) return Resultado.falha(404, "Obra não encontrada.");
        return resumoDaObra(titulo, usuario);
    }

    public Resultado removerNota(String titulo, String usuario) {
        neo4j.query("MATCH (:Usuario {usuario: $usuario})-[a:AVALIOU]->(:Obra {titulo: $titulo}) DELETE a")
                .bindAll(Map.of("usuario", usuario, "titulo", titulo == null ? "" : titulo)).run();
        return resumoDaObra(titulo, usuario);
    }

    public Resultado comentar(String titulo, String texto, String usuario) {
        String limpo = limparTexto(texto);
        Optional<String> erro = validarComentario(limpo);
        if (erro.isPresent()) return Resultado.falha(400, erro.get());
        String id = UUID.randomUUID().toString();
        Optional<Map<String, Object>> criado = neo4j.query(
                "MATCH (u:Usuario {usuario: $usuario}), (o:Obra {titulo: $titulo}) " +
                "CREATE (u)-[:ESCREVEU]->(c:Comentario {id: $id, autor: $usuario, texto: $texto, criadoEm: $agora})-[:SOBRE]->(o) " +
                "WITH c, u, o OPTIONAL MATCH (u)-[a:AVALIOU]->(o) " +
                "RETURN c.id AS id, c.autor AS autor, c.texto AS texto, c.criadoEm AS criadoEm, a.estrelas AS estrelas, u.foto AS foto")
                .bindAll(Map.of("usuario", usuario, "titulo", titulo == null ? "" : titulo, "id", id,
                        "texto", limpo, "agora", Instant.now(relogio).toString()))
                .fetch().one();
        if (criado.isEmpty()) return Resultado.falha(404, "Obra não encontrada.");
        Resultado r = Resultado.sucesso(comentario(criado.get(), usuario));
        return new Resultado(true, r.corpo(), null, 201);
    }

    /** Só o autor pode excluir o próprio comentário. */
    public Resultado excluirComentario(String id, String usuario) {
        Optional<String> autor = neo4j.query("MATCH (c:Comentario {id: $id}) RETURN c.autor AS autor")
                .bindAll(Map.of("id", id == null ? "" : id)).fetchAs(String.class).one();
        if (autor.isEmpty()) return Resultado.falha(404, "Comentário não encontrado.");
        if (!autor.get().equals(usuario)) return Resultado.falha(403, "Você só pode excluir os seus comentários.");
        neo4j.query("MATCH (c:Comentario {id: $id}) DETACH DELETE c").bindAll(Map.of("id", id)).run();
        return new Resultado(true, null, null, 204);
    }

    /** Apaga notas, comentários e listas de um usuário (usado ao excluir a conta). */
    public void removerTudoDe(String usuario) {
        neo4j.query("MATCH (c:Comentario {autor: $usuario}) DETACH DELETE c").bindAll(Map.of("usuario", usuario)).run();
        neo4j.query("MATCH (:Usuario {usuario: $usuario})-[a:AVALIOU]->() DELETE a").bindAll(Map.of("usuario", usuario)).run();
        // Listas pessoais (a propriedade "dono" permite apagar mesmo depois que o nó do usuário já saiu)
        neo4j.query("MATCH (l:Lista {dono: $usuario}) DETACH DELETE l").bindAll(Map.of("usuario", usuario)).run();
        // Participação em listas de outras pessoas (as obras que a pessoa adicionou continuam nelas)
        neo4j.query("MATCH (:Usuario {usuario: $usuario})-[r:COLABORA]->(:Lista) DELETE r").bindAll(Map.of("usuario", usuario)).run();
    }

    // ---------------- Auxiliares ----------------

    private Resultado resumoDaObra(String titulo, String usuario) {
        Resultado d = detalhes(titulo, usuario);
        if (!d.ok()) return d;
        Map<String, Object> corpo = new LinkedHashMap<>(d.corpo());
        corpo.remove("comentarios");
        return Resultado.sucesso(corpo);
    }

    private static Map<String, Object> comentario(Map<String, Object> l, String usuario) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", l.get("id"));
        c.put("autor", l.get("autor"));
        c.put("texto", l.get("texto"));
        c.put("criadoEm", l.get("criadoEm"));
        c.put("estrelas", l.get("estrelas") == null ? null : inteiro(l.get("estrelas")));
        c.put("meu", usuario != null && usuario.equals(l.get("autor")));
        c.put("foto", l.get("foto"));
        return c;
    }

    private static Double numero(Object valor) {
        return valor instanceof Number n ? n.doubleValue() : null;
    }

    private static int inteiro(Object valor) {
        return valor instanceof Number n ? n.intValue() : 0;
    }
}
