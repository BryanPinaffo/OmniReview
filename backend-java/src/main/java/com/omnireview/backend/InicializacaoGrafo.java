package com.omnireview.backend;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Executado ao iniciar a aplicação (idempotente):
 *  - cria as restrições de unicidade (que também criam índices) para Obra.titulo, Tag.nome, Usuario.usuario e Comentario.id;
 *  - cria/atualiza as relações (:Obra)-[:TEM_TAG]->(:Tag) para todas as obras existentes;
 *  - remove tags que ficaram sem nenhuma obra.
 */
@Component
public class InicializacaoGrafo implements ApplicationRunner {

    private final Neo4jClient neo4j;

    public InicializacaoGrafo(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            neo4j.query("CREATE CONSTRAINT obra_titulo IF NOT EXISTS FOR (o:Obra) REQUIRE o.titulo IS UNIQUE").run();
            neo4j.query("CREATE CONSTRAINT tag_nome IF NOT EXISTS FOR (t:Tag) REQUIRE t.nome IS UNIQUE").run();
            neo4j.query("CREATE CONSTRAINT usuario_login IF NOT EXISTS FOR (u:Usuario) REQUIRE u.usuario IS UNIQUE").run();
            neo4j.query("CREATE CONSTRAINT comentario_id IF NOT EXISTS FOR (c:Comentario) REQUIRE c.id IS UNIQUE").run();
            neo4j.query("CREATE INDEX comentario_autor IF NOT EXISTS FOR (c:Comentario) ON (c.autor)").run();
            neo4j.query("CREATE CONSTRAINT lista_id IF NOT EXISTS FOR (l:Lista) REQUIRE l.id IS UNIQUE").run();
            neo4j.query("CREATE INDEX lista_dono IF NOT EXISTS FOR (l:Lista) ON (l.dono)").run();
            neo4j.query("MATCH (o:Obra) " +
                        "OPTIONAL MATCH (o)-[r:TEM_TAG]->() DELETE r " +
                        "WITH DISTINCT o " +
                        "UNWIND [nome IN split(coalesce(o.dnaCriativo, ''), ',') | trim(nome)] AS nome " +
                        "WITH o, nome WHERE nome <> '' AND NOT nome IN ['Deu erro', 'Sem classificação'] " +
                        "MERGE (t:Tag {nome: nome}) " +
                        "MERGE (o)-[:TEM_TAG]->(t)").run();
            neo4j.query("MATCH (t:Tag) WHERE NOT (t)<-[:TEM_TAG]-() DELETE t").run();
            Long tags = neo4j.query("MATCH (t:Tag) RETURN count(t) AS n").fetchAs(Long.class).one().orElse(0L);
            Long rels = neo4j.query("MATCH ()-[r:TEM_TAG]->() RETURN count(r) AS n").fetchAs(Long.class).one().orElse(0L);
            System.out.println("Grafo de tags sincronizado: " + tags + " tags, " + rels + " relações TEM_TAG.");
        } catch (Exception e) {
            System.out.println("AVISO: não foi possível sincronizar o grafo de tags: " + e.getMessage());
        }
    }
}
