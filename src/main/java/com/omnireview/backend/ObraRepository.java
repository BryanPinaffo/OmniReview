package com.omnireview.backend;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import java.util.List;

public interface ObraRepository extends Neo4jRepository<Obra, String> {
    
    
    @Query("MATCH (o:Obra) WHERE o.dnaCriativo CONTAINS $dna AND NOT o.titulo = $tituloOriginal RETURN o")
    List<Obra> recomendarTransmidia(String dna, String tituloOriginal);
}