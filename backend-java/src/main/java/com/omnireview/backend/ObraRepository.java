package com.omnireview.backend;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;

/**
 * Persistência das obras no Neo4j.
 *
 * Modelo em grafo: (:Obra)-[:TEM_TAG]->(:Tag {nome}). A propriedade Obra.dnaCriativo continua
 * existindo (texto exibido na interface), e as relações TEM_TAG são sincronizadas a partir dela.
 */
public interface ObraRepository extends Neo4jRepository<Obra, String> {

    /** Recria as relações TEM_TAG de uma obra a partir do seu dnaCriativo. */
    @Query("MATCH (o:Obra {titulo: $titulo}) " +
           "OPTIONAL MATCH (o)-[r:TEM_TAG]->() DELETE r " +
           "WITH DISTINCT o " +
           "UNWIND [nome IN split(coalesce(o.dnaCriativo, ''), ',') | trim(nome)] AS nome " +
           "WITH o, nome WHERE nome <> '' AND NOT nome IN ['Deu erro', 'Sem classificação'] " +
           "MERGE (t:Tag {nome: nome}) " +
           "MERGE (o)-[:TEM_TAG]->(t)")
    void sincronizarTags(String titulo);

    /**
     * Recomendação por travessia no grafo, com peso IDF por tag:
     *   score(o) = soma, sobre as tags do perfil ligadas a o, de 1 + ln(N / df(tag))
     * onde N = total de obras e df = número de obras com a tag. Tags raras pesam mais que tags comuns.
     * O casamento é exato (nó Tag), então "Comédia" não casa com "Comédia Romântica".
     */
    @Query("MATCH (todas:Obra) WITH count(todas) AS n " +
           "MATCH (t:Tag) WHERE t.nome IN $tags " +
           "MATCH (t)<-[:TEM_TAG]-(o) " +   // só obras têm TEM_TAG: dispensa o filtro de rótulo
           "WITH n, t, collect(o) AS obras " +
           "WITH t, obras, 1.0 + log(toFloat(n) / size(obras)) AS peso " +
           "UNWIND obras AS o " +
           "WITH o, sum(peso) AS score, count(t) AS comuns " +
           "WHERE NOT o.titulo IN $excluir " +
           "RETURN o ORDER BY score DESC, comuns DESC, o.titulo LIMIT 60")
    List<Obra> recomendarPorTags(List<String> tags, List<String> excluir);
}
