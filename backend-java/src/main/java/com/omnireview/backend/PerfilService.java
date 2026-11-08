package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Perfil, obras concluídas e seguidores.
 *
 * Modelo no grafo:
 *   (:Usuario)-[:CONCLUIU {em}]->(:Obra)     assistiu o filme/série ou terminou o jogo
 *   (:Usuario)-[:SEGUE {em}]->(:Usuario)
 *   Usuario.foto / Usuario.capaFundo        imagens já redimensionadas no navegador (data URL JPEG/PNG/WebP)
 *   Usuario.bio                              texto curto
 */
@Service
public class PerfilService {

    static final int MAX_FOTO = 300_000;        // caracteres do data URL (~220 KB de imagem)
    static final int MAX_CAPA_FUNDO = 1_500_000; // ~1,1 MB de imagem
    static final int MAX_BIO = 280;
    // Só imagens rasterizadas em base64: SVG fica de fora (pode conter script)
    static final Pattern DATA_URL_IMAGEM = Pattern.compile("data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}");

    /** Resultado de uma operação: corpo da resposta ou erro com status HTTP. */
    public record Resultado(boolean ok, Object corpo, String erro, int status) {
        static Resultado sucesso(Object corpo) { return new Resultado(true, corpo, null, 200); }
        static Resultado falha(int status, String erro) { return new Resultado(false, null, erro, status); }
    }

    private final Neo4jClient neo4j;
    private final Clock relogio;

    @Autowired
    public PerfilService(Neo4jClient neo4j) {
        this(neo4j, Clock.systemUTC());
    }

    PerfilService(Neo4jClient neo4j, Clock relogio) {
        this.neo4j = neo4j;
        this.relogio = relogio;
    }

    // ---------------- Validação (pura, testada sem banco) ----------------

    /** null = imagem válida (ou vazia, que remove); senão a mensagem de erro. */
    static String validarImagem(String dataUrl, int maximo, String nome) {
        if (dataUrl == null || dataUrl.isEmpty()) return null;
        if (dataUrl.length() > maximo) return "A " + nome + " é grande demais. Escolha uma imagem menor.";
        if (!DATA_URL_IMAGEM.matcher(dataUrl).matches()) return "Formato de " + nome + " inválido. Use JPG, PNG ou WebP.";
        return null;
    }

    static String limparBio(String bio) {
        if (bio == null) return "";
        return bio.replaceAll("[\\p{Cc}&&[^\n]]", "").replaceAll("\n{2,}", "\n").strip();
    }

    // ---------------- Perfil ----------------

    /** Perfil completo de um usuário, do ponto de vista de quem está vendo (eu). */
    public Resultado perfil(String usuario, String eu) {
        Optional<Map<String, Object>> base = neo4j.query(
                "MATCH (u:Usuario {usuario: $usuario}) " +
                "OPTIONAL MATCH (u)-[:CONCLUIU]->(o:Obra) WITH u, collect(o.tipo) AS tipos " +
                "OPTIONAL MATCH (s:Usuario)-[:SEGUE]->(u) WITH u, tipos, count(s) AS seguidores " +
                "OPTIONAL MATCH (u)-[:SEGUE]->(t:Usuario) WITH u, tipos, seguidores, count(t) AS seguindo " +
                "RETURN u.usuario AS usuario, coalesce(u.papel, 'USUARIO') AS papel, u.criadoEm AS criadoEm, " +
                "       u.bio AS bio, u.foto AS foto, u.capaFundo AS capaFundo, " +
                "       size([x IN tipos WHERE x = 'Filme']) AS filmes, size([x IN tipos WHERE x = 'Serie']) AS series, " +
                "       size([x IN tipos WHERE x = 'Jogo']) AS jogos, seguidores, seguindo, " +
                "       EXISTS { MATCH (:Usuario {usuario: $eu})-[:SEGUE]->(u) } AS euSigo")
                .bindAll(Map.of("usuario", normalizar(usuario), "eu", eu)).fetch().one();
        if (base.isEmpty()) return Resultado.falha(404, "Usuário não encontrado.");
        Map<String, Object> b = base.get();
        String dono = (String) b.get("usuario");

        List<Map<String, Object>> concluidas = new ArrayList<>(neo4j.query(
                "MATCH (:Usuario {usuario: $usuario})-[c:CONCLUIU]->(o:Obra) " +
                "RETURN o.titulo AS titulo, o.tipo AS tipo, o.imagem AS imagem, o.capa AS capa, " +
                "       o.dnaCriativo AS dnaCriativo, o.sinopse AS sinopse, c.em AS em ORDER BY c.em DESC LIMIT 300")
                .bindAll(Map.of("usuario", dono)).fetch().all());
        List<Map<String, Object>> reviews = reviews(dono);

        Map<String, Object> estatisticas = new LinkedHashMap<>();
        estatisticas.put("jogos", inteiro(b.get("jogos")));
        estatisticas.put("filmes", inteiro(b.get("filmes")));
        estatisticas.put("series", inteiro(b.get("series")));
        estatisticas.put("reviews", reviews.size());
        estatisticas.put("notas", (int) reviews.stream().filter(r -> r.get("estrelas") != null).count());
        estatisticas.put("comentarios", reviews.stream().mapToInt(r -> (int) r.get("comentarios")).sum());
        estatisticas.put("seguidores", inteiro(b.get("seguidores")));
        estatisticas.put("seguindo", inteiro(b.get("seguindo")));

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("usuario", dono);
        corpo.put("papel", b.get("papel"));
        corpo.put("criadoEm", b.get("criadoEm"));
        corpo.put("bio", b.get("bio"));
        corpo.put("foto", b.get("foto"));
        corpo.put("capaFundo", b.get("capaFundo"));
        corpo.put("ehEu", dono.equals(eu));
        corpo.put("euSigo", Boolean.TRUE.equals(b.get("euSigo")));
        corpo.put("estatisticas", estatisticas);
        corpo.put("concluidas", concluidas);
        corpo.put("reviews", reviews.size() > 50 ? reviews.subList(0, 50) : reviews);
        return Resultado.sucesso(corpo);
    }

    /**
     * Reviews do usuário: uma por obra que ele avaliou com estrelas e/ou comentou
     * (com a nota e o comentário mais recente), da mais recente para a mais antiga.
     */
    private List<Map<String, Object>> reviews(String usuario) {
        Map<String, Map<String, Object>> porObra = new LinkedHashMap<>();
        for (Map<String, Object> l : neo4j.query(
                "MATCH (:Usuario {usuario: $usuario})-[a:AVALIOU]->(o:Obra) " +
                "RETURN o.titulo AS titulo, o.tipo AS tipo, o.imagem AS imagem, o.capa AS capa, a.estrelas AS estrelas, a.em AS em")
                .bindAll(Map.of("usuario", usuario)).fetch().all()) {
            Map<String, Object> r = review(porObra, l);
            r.put("estrelas", inteiro(l.get("estrelas")));
        }
        for (Map<String, Object> l : neo4j.query(
                "MATCH (c:Comentario {autor: $usuario})-[:SOBRE]->(o:Obra) " +
                "RETURN o.titulo AS titulo, o.tipo AS tipo, o.imagem AS imagem, o.capa AS capa, c.texto AS texto, c.criadoEm AS em " +
                "ORDER BY c.criadoEm DESC")
                .bindAll(Map.of("usuario", usuario)).fetch().all()) {
            Map<String, Object> r = review(porObra, l);
            if (r.get("texto") == null) r.put("texto", l.get("texto"));   // o mais recente (consulta em ordem decrescente)
            r.put("comentarios", (int) r.get("comentarios") + 1);
        }
        List<Map<String, Object>> lista = new ArrayList<>(porObra.values());
        lista.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("em"))).reversed());
        return lista;
    }

    private static Map<String, Object> review(Map<String, Map<String, Object>> porObra, Map<String, Object> l) {
        Map<String, Object> r = porObra.computeIfAbsent((String) l.get("titulo"), t -> {
            Map<String, Object> novo = new LinkedHashMap<>();
            novo.put("titulo", t);
            novo.put("tipo", l.get("tipo"));
            novo.put("imagem", l.get("imagem"));
            novo.put("capa", l.get("capa"));
            novo.put("estrelas", null);
            novo.put("texto", null);
            novo.put("comentarios", 0);
            novo.put("em", l.get("em"));
            return novo;
        });
        String em = (String) l.get("em");
        if (em != null && (r.get("em") == null || em.compareTo((String) r.get("em")) > 0)) r.put("em", em);
        return r;
    }

    /** Dados leves do próprio usuário para a interface: foto (menu) e títulos concluídos (botões e selos). */
    public Map<String, Object> resumoDoUsuario(String eu) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("foto", neo4j.query("MATCH (u:Usuario {usuario: $eu}) RETURN u.foto AS foto")
                .bindAll(Map.of("eu", eu)).fetchAs(String.class).one().orElse(null));
        corpo.put("concluidas", neo4j.query("MATCH (:Usuario {usuario: $eu})-[:CONCLUIU]->(o:Obra) RETURN o.titulo AS titulo")
                .bindAll(Map.of("eu", eu)).fetchAs(String.class).all());
        return corpo;
    }

    /** Atualiza foto, capa de fundo e/ou bio. Campo ausente = não muda; texto vazio = remove. */
    public Resultado atualizar(String eu, Map<String, Object> campos) {
        if (campos == null || campos.isEmpty()) return Resultado.falha(400, "Nada para atualizar.");
        Map<String, Object> mudancas = new LinkedHashMap<>();
        for (String campo : List.of("foto", "capaFundo", "bio")) {
            if (!campos.containsKey(campo)) continue;
            Object valor = campos.get(campo);
            if (valor != null && !(valor instanceof String)) return Resultado.falha(400, "Campo inválido: " + campo);
            String texto = (String) valor;
            if (campo.equals("bio")) {
                texto = limparBio(texto);
                if (texto.codePointCount(0, texto.length()) > MAX_BIO) return Resultado.falha(400, "A bio pode ter no máximo " + MAX_BIO + " caracteres.");
            } else {
                String erro = validarImagem(texto, campo.equals("foto") ? MAX_FOTO : MAX_CAPA_FUNDO,
                        campo.equals("foto") ? "foto de perfil" : "capa de fundo");
                if (erro != null) return Resultado.falha(400, erro);
            }
            mudancas.put(campo, texto == null || texto.isEmpty() ? null : texto);
        }
        if (mudancas.isEmpty()) return Resultado.falha(400, "Nada para atualizar.");
        // SET u += $mudancas: valores null removem a propriedade
        neo4j.query("MATCH (u:Usuario {usuario: $eu}) SET u += $mudancas")
                .bindAll(Map.of("eu", eu, "mudancas", mudancas)).run();
        return perfil(eu, eu);
    }

    // ---------------- Concluídas (assisti / terminei) ----------------

    public Resultado concluir(String eu, String titulo) {
        Optional<Long> n = neo4j.query(
                "MATCH (u:Usuario {usuario: $eu}), (o:Obra {titulo: $titulo}) " +
                "MERGE (u)-[c:CONCLUIU]->(o) ON CREATE SET c.em = $agora RETURN count(c) AS n")
                .bindAll(Map.of("eu", eu, "titulo", titulo == null ? "" : titulo, "agora", Instant.now(relogio).toString()))
                .fetchAs(Long.class).one();
        if (n.orElse(0L) == 0L) return Resultado.falha(404, "Obra não encontrada.");
        return Resultado.sucesso(Map.of("titulo", titulo, "concluida", true));
    }

    public Resultado desfazerConclusao(String eu, String titulo) {
        neo4j.query("MATCH (:Usuario {usuario: $eu})-[c:CONCLUIU]->(:Obra {titulo: $titulo}) DELETE c")
                .bindAll(Map.of("eu", eu, "titulo", titulo == null ? "" : titulo)).run();
        return Resultado.sucesso(Map.of("titulo", String.valueOf(titulo), "concluida", false));
    }

    // ---------------- Seguir ----------------

    public Resultado seguir(String eu, String alvo) {
        String a = normalizar(alvo);
        if (a.equals(eu)) return Resultado.falha(400, "Você não pode seguir a si mesmo.");
        Optional<Long> n = neo4j.query(
                "MATCH (eu:Usuario {usuario: $eu}), (alvo:Usuario {usuario: $alvo}) " +
                "MERGE (eu)-[s:SEGUE]->(alvo) ON CREATE SET s.em = $agora RETURN count(s) AS n")
                .bindAll(Map.of("eu", eu, "alvo", a, "agora", Instant.now(relogio).toString()))
                .fetchAs(Long.class).one();
        if (n.orElse(0L) == 0L) return Resultado.falha(404, "Usuário não encontrado.");
        return Resultado.sucesso(contagemSeguidores(a, true));
    }

    public Resultado deixarDeSeguir(String eu, String alvo) {
        String a = normalizar(alvo);
        neo4j.query("MATCH (:Usuario {usuario: $eu})-[s:SEGUE]->(:Usuario {usuario: $alvo}) DELETE s")
                .bindAll(Map.of("eu", eu, "alvo", a)).run();
        return Resultado.sucesso(contagemSeguidores(a, false));
    }

    private Map<String, Object> contagemSeguidores(String usuario, boolean euSigo) {
        long seguidores = neo4j.query("MATCH (:Usuario)-[s:SEGUE]->(:Usuario {usuario: $u}) RETURN count(s) AS n")
                .bindAll(Map.of("u", usuario)).fetchAs(Long.class).one().orElse(0L);
        return Map.of("usuario", usuario, "euSigo", euSigo, "seguidores", seguidores);
    }

    /** Seguidores (quem segue o usuário) ou seguindo (quem o usuário segue). */
    public Resultado conexoes(String usuario, String eu, boolean seguidores) {
        String u = normalizar(usuario);
        boolean existe = neo4j.query("MATCH (u:Usuario {usuario: $u}) RETURN count(u) AS n")
                .bindAll(Map.of("u", u)).fetchAs(Long.class).one().orElse(0L) > 0;
        if (!existe) return Resultado.falha(404, "Usuário não encontrado.");
        String padrao = seguidores ? "(p:Usuario)-[r:SEGUE]->(:Usuario {usuario: $u})" : "(:Usuario {usuario: $u})-[r:SEGUE]->(p:Usuario)";
        return Resultado.sucesso(pessoas("MATCH " + padrao + " WITH p, r ORDER BY r.em DESC", Map.of("u", u), eu));
    }

    /** Busca de pessoas para seguir (sem o próprio usuário). */
    public List<Map<String, Object>> buscar(String termo, String eu) {
        String t = normalizar(termo);
        return pessoas("MATCH (p:Usuario) WHERE p.usuario <> $eu AND p.usuario CONTAINS $t WITH p ORDER BY p.usuario LIMIT 30",
                Map.of("t", t), eu);
    }

    private List<Map<String, Object>> pessoas(String inicio, Map<String, Object> params, String eu) {
        Map<String, Object> todos = new HashMap<>(params);
        todos.put("eu", eu);
        return new ArrayList<>(neo4j.query(inicio + " " +
                "OPTIONAL MATCH (p)-[:CONCLUIU]->(o:Obra) WITH p, count(o) AS concluidas " +
                "RETURN p.usuario AS usuario, coalesce(p.papel, 'USUARIO') AS papel, p.foto AS foto, concluidas, " +
                "       EXISTS { MATCH (:Usuario {usuario: $eu})-[:SEGUE]->(p) } AS euSigo, p.usuario = $eu AS ehEu")
                .bindAll(todos).fetch().all());
    }

    // ---------------- Auxiliares ----------------

    static String normalizar(String usuario) {
        return AutenticacaoService.normalizar(usuario);
    }

    private static int inteiro(Object valor) {
        return valor instanceof Number n ? n.intValue() : 0;
    }
}
