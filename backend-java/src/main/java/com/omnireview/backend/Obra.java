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
    private String fonte;         // identificador na API de origem (ex.: "tmdb:movie:438631", "ol:/works/OL893415W", "igdb:113112")
    // Outros identificadores da mesma obra (ex.: "steam:1145360" de um jogo do IGDB, ou a fonte anterior), para ela
    // ser reconhecida venha de onde vier (Top 15 da Steam, busca pelo IGDB) e não entrar duas vezes no catálogo
    private java.util.List<String> outrasFontes;

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
    public String getFonte() { return fonte; }
    public void setFonte(String fonte) { this.fonte = fonte; }
    public void setAdicionadaEm(String adicionadaEm) { this.adicionadaEm = adicionadaEm; }
    public java.util.List<String> getOutrasFontes() { return outrasFontes; }
    public void setOutrasFontes(java.util.List<String> outrasFontes) { this.outrasFontes = outrasFontes; }

    /** Guarda mais um identificador da obra (sem repetir, e sem repetir a fonte principal). */
    public void adicionarOutraFonte(String outra) {
        if (outra == null || outra.isBlank() || outra.equals(fonte)) return;
        if (outrasFontes == null) outrasFontes = new java.util.ArrayList<>();
        else if (!(outrasFontes instanceof java.util.ArrayList)) outrasFontes = new java.util.ArrayList<>(outrasFontes);
        if (!outrasFontes.contains(outra)) outrasFontes.add(outra);
    }

    /** Troca a fonte principal; a anterior continua valendo como outra fonte (a obra segue sendo reconhecida por ela). */
    public void trocarFonte(String nova) {
        if (nova == null || nova.equals(fonte)) return;
        String anterior = fonte;
        fonte = nova;
        if (outrasFontes != null && outrasFontes.contains(nova)) {
            outrasFontes = new java.util.ArrayList<>(outrasFontes);
            outrasFontes.remove(nova);
        }
        adicionarOutraFonte(anterior);
    }
}