package com.omnireview.backend;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/** Usuário da plataforma. A senha nunca é guardada: só o hash PBKDF2 com sal (ver {@link Senhas}). */
@Node("Usuario")
public class Usuario {

    public static final String ADMIN = "ADMIN";
    public static final String COMUM = "USUARIO";

    @Id
    private String usuario;   // nome de login, sempre em minúsculas
    private String senhaHash;
    private String criadoEm;  // data/hora ISO-8601
    private String papel;     // ADMIN ou USUARIO (nulo em contas antigas = USUARIO)
    private String criadoPor; // quem criou a conta pelo painel do administrador (nulo = cadastro próprio)
    private String senhaAlteradaEm;
    // Campos do perfil (gravados pelo PerfilService); ficam aqui para não se perderem quando a conta é salva
    private String foto;
    private String capaFundo;
    private String bio;
    private Boolean listasIniciadas; // listas padrão ("Quero assistir"/"Quero jogar") já criadas
    private Boolean boasVindasPendente; // conta nova que ainda não passou pelas boas-vindas (escolher filmes, séries...)

    public Usuario() {}

    public Usuario(String usuario, String senhaHash, String criadoEm) {
        this(usuario, senhaHash, criadoEm, COMUM);
    }

    public Usuario(String usuario, String senhaHash, String criadoEm, String papel) {
        this.usuario = usuario;
        this.senhaHash = senhaHash;
        this.criadoEm = criadoEm;
        this.papel = papel;
    }

    public boolean isAdmin() { return ADMIN.equals(papel); }

    public String getUsuario() { return usuario; }
    public String getSenhaHash() { return senhaHash; }
    public String getCriadoEm() { return criadoEm; }
    public String getPapel() { return papel == null ? COMUM : papel; }
    public String getCriadoPor() { return criadoPor; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public void setSenhaHash(String senhaHash) { this.senhaHash = senhaHash; }
    public void setCriadoEm(String criadoEm) { this.criadoEm = criadoEm; }
    public void setPapel(String papel) { this.papel = papel; }
    public void setCriadoPor(String criadoPor) { this.criadoPor = criadoPor; }
    public String getSenhaAlteradaEm() { return senhaAlteradaEm; }
    public void setSenhaAlteradaEm(String senhaAlteradaEm) { this.senhaAlteradaEm = senhaAlteradaEm; }
    public String getFoto() { return foto; }
    public void setFoto(String foto) { this.foto = foto; }
    public String getCapaFundo() { return capaFundo; }
    public void setCapaFundo(String capaFundo) { this.capaFundo = capaFundo; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public Boolean getListasIniciadas() { return listasIniciadas; }
    public void setListasIniciadas(Boolean listasIniciadas) { this.listasIniciadas = listasIniciadas; }
    public Boolean getBoasVindasPendente() { return boasVindasPendente; }
    public void setBoasVindasPendente(Boolean boasVindasPendente) { this.boasVindasPendente = boasVindasPendente; }
}
