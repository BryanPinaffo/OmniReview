package com.omnireview.backend;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Obra") 
public class Obra {
    
    @Id
    private String titulo;
    private String tipo; 
    private String dnaCriativo;
    private String sinopse;
    private String imagem; // pôster (vertical)
    private String capa;   // imagem de fundo (horizontal)
    private String adicionadaPor; // usuário que trouxe a obra para o acervo (nulo nas obras anteriores ao histórico)
    private String adicionadaEm;  // data/hora ISO-8601

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
    // Setters 
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public void setDnaCriativo(String dnaCriativo) { this.dnaCriativo = dnaCriativo; }

    public String getSinopse() { return sinopse; }
    public void setSinopse(String sinopse) { this.sinopse = sinopse; }
    public String getImagem() { return imagem; }
    public void setImagem(String imagem) { this.imagem = imagem; }
    public String getCapa() { return capa; }
    public void setCapa(String capa) { this.capa = capa; }
    public String getAdicionadaPor() { return adicionadaPor; }
    public void setAdicionadaPor(String adicionadaPor) { this.adicionadaPor = adicionadaPor; }
    public String getAdicionadaEm() { return adicionadaEm; }
    public void setAdicionadaEm(String adicionadaEm) { this.adicionadaEm = adicionadaEm; }
}