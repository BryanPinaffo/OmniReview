package com.omnireview.backend;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Obra") /
public class Obra {
    
    @Id
    private String titulo;
    private String tipo; 
    private String dnaCriativo;

    // Construtor
    public Obra() {}

    public Obra(String titulo, String tipo, String dnaCriativo) {
        this.titulo = titulo;
        this.tipo = tipo;
        this.dnaCriativo = dnaCriativo;
    }

    // Getters 
    public String getTitulo() { return titulo; }
    public String getTipo() { return tipo; }
    public String getDnaCriativo() { return dnaCriativo; }
}