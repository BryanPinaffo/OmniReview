package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cria (ou corrige) a conta de administrador ao iniciar, a partir de config/segredos.properties:
 *   omnireview.admin.usuario=admin
 *   omnireview.admin.senha=...
 * A senha fica fora do código (o arquivo de segredos não vai para o Git). Depois de criada, a conta mantém a
 * senha redefinida pelo site; para voltar à senha configurada (senha esquecida), use omnireview.admin.forcar-senha=true.
 */
@Component
@Order(2)   // depois da InicializacaoGrafo, que espera o Neo4j ficar disponível
public class AdminInicial implements ApplicationRunner {

    private final AutenticacaoService auth;
    private final String usuario;
    private final String senha;
    private final boolean forcarSenha;

    public AdminInicial(AutenticacaoService auth,
                        @Value("${omnireview.admin.usuario:admin}") String usuario,
                        @Value("${omnireview.admin.senha:}") String senha,
                        @Value("${omnireview.admin.forcar-senha:false}") boolean forcarSenha) {
        this.auth = auth;
        this.usuario = usuario;
        this.senha = senha;
        this.forcarSenha = forcarSenha;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (senha == null || senha.isBlank()) {
            System.out.println("AVISO: omnireview.admin.senha não configurada; nenhuma conta de administrador foi criada.");
            return;
        }
        try {
            if (auth.garantirAdmin(usuario, senha, forcarSenha)) {
                System.out.println("Conta de administrador \"" + AutenticacaoService.normalizar(usuario) + "\" criada/atualizada.");
            }
        } catch (Exception e) {
            System.out.println("AVISO: não foi possível criar a conta de administrador: " + e.getMessage());
        }
    }
}
