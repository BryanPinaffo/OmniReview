package com.omnireview.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class AutenticacaoServiceTest {

    /** Relógio que o teste pode adiantar. */
    static class RelogioAjustavel extends Clock {
        Instant agora = Instant.parse("2026-11-08T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
        @Override public Instant instant() { return agora; }
        void adiantar(Duration d) { agora = agora.plus(d); }
    }

    private final Map<String, Usuario> banco = new HashMap<>();
    private final RelogioAjustavel relogio = new RelogioAjustavel();
    private AutenticacaoService auth;

    @BeforeEach
    void preparar() {
        UsuarioRepository repo = Mockito.mock(UsuarioRepository.class);
        Mockito.when(repo.existsById(anyString())).thenAnswer(i -> banco.containsKey(i.<String>getArgument(0)));
        Mockito.when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(banco.get(i.<String>getArgument(0))));
        Mockito.when(repo.save(any(Usuario.class))).thenAnswer(i -> { Usuario u = i.getArgument(0); banco.put(u.getUsuario(), u); return u; });
        Mockito.doAnswer(i -> banco.remove(i.<String>getArgument(0))).when(repo).deleteById(anyString());
        auth = new AutenticacaoService(repo, relogio);
    }

    @Test
    void cadastroGuardaApenasOHashDaSenha() {
        var r = auth.cadastrar("Maria.Silva", "senha-forte-123");
        assertTrue(r.ok());
        assertEquals("maria.silva", r.usuario());
        String hash = banco.get("maria.silva").getSenhaHash();
        assertTrue(hash.startsWith("pbkdf2$"));
        assertFalse(hash.contains("senha-forte-123"));
        assertEquals(Optional.of("maria.silva"), auth.usuarioDaSessao(r.token()));
    }

    @Test
    void cadastroValidaUsuarioSenhaEDuplicidade() {
        assertEquals(400, auth.cadastrar("ab", "senha-forte-123").status());
        assertEquals(400, auth.cadastrar("nome com espaço", "senha-forte-123").status());
        assertEquals(400, auth.cadastrar("joao", "curta").status());
        assertEquals(400, auth.cadastrar("joaozinho", "JOAOZINHO").status());
        assertTrue(auth.cadastrar("joao", "senha-forte-123").ok());
        assertEquals(409, auth.cadastrar("JOAO", "outra-senha-456").status());
    }

    @Test
    void loginAceitaSenhaCertaERecusaErradaComMensagemGenerica() {
        auth.cadastrar("ana", "senha-forte-123");
        assertTrue(auth.entrar("ANA", "senha-forte-123").ok());
        var errada = auth.entrar("ana", "senha-errada");
        var inexistente = auth.entrar("ninguem", "senha-errada");
        assertEquals(401, errada.status());
        assertEquals(errada.erro(), inexistente.erro(), "não pode revelar se o usuário existe");
    }

    @Test
    void bloqueiaAposCincoTentativasErradasELiberaDepois() {
        auth.cadastrar("bia", "senha-forte-123");
        for (int i = 0; i < AutenticacaoService.MAX_TENTATIVAS; i++) {
            assertEquals(401, auth.entrar("bia", "errada-" + i).status());
        }
        assertEquals(429, auth.entrar("bia", "senha-forte-123").status(), "bloqueado mesmo com a senha certa");
        relogio.adiantar(AutenticacaoService.BLOQUEIO.plusSeconds(1));
        assertTrue(auth.entrar("bia", "senha-forte-123").ok());
    }

    @Test
    void sessaoExpiraESairEncerraOToken() {
        String token = auth.cadastrar("caio", "senha-forte-123").token();
        relogio.adiantar(AutenticacaoService.DURACAO_SESSAO.minusMinutes(1));
        assertTrue(auth.usuarioDaSessao(token).isPresent());
        relogio.adiantar(Duration.ofMinutes(2));
        assertTrue(auth.usuarioDaSessao(token).isEmpty());

        String outro = auth.entrar("caio", "senha-forte-123").token();
        auth.sair(outro);
        assertTrue(auth.usuarioDaSessao(outro).isEmpty());
        assertTrue(auth.usuarioDaSessao(null).isEmpty());
        assertTrue(auth.usuarioDaSessao("token-inventado").isEmpty());
    }

    @Test
    void excluirContaExigeSenhaEEncerraSessoes() {
        String token = auth.cadastrar("davi", "senha-forte-123").token();
        assertEquals(401, auth.excluirConta("davi", "errada").status());
        assertTrue(auth.excluirConta("davi", "senha-forte-123").ok());
        assertFalse(banco.containsKey("davi"));
        assertTrue(auth.usuarioDaSessao(token).isEmpty());
    }

    @Test
    void hashUsaSalAleatorioEConfereCorretamente() {
        String a = Senhas.gerarHash("mesma-senha-123");
        String b = Senhas.gerarHash("mesma-senha-123");
        assertNotEquals(a, b);
        assertTrue(Senhas.confere("mesma-senha-123", a));
        assertFalse(Senhas.confere("outra-senha-123", a));
        assertFalse(Senhas.confere("mesma-senha-123", "formato-invalido"));
    }

    @Test
    void adminVemDaConfiguracaoEEntraComPapelDeAdmin() {
        assertTrue(auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false));
        assertFalse(auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false), "segunda execução não muda nada");
        var r = auth.entrar("admin", "Senha-Admin-Teste-1");
        assertTrue(r.ok());
        assertEquals(Usuario.ADMIN, r.papel());
        assertTrue(auth.sessao(r.token()).orElseThrow().admin());
        assertFalse(banco.get("admin").getSenhaHash().contains("Senha-Admin-Teste-1"));
    }

    @Test
    void configuracaoPrevaleceSobreUmAdminCadastradoComoComum() {
        var invasor = auth.cadastrar("admin", "senha-do-invasor");
        assertTrue(invasor.ok());
        assertEquals(Usuario.COMUM, invasor.papel());
        assertTrue(auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false));
        assertTrue(auth.sessao(invasor.token()).isEmpty(), "sessão antiga derrubada");
        assertEquals(401, auth.entrar("admin", "senha-do-invasor").status());
        assertEquals(Usuario.ADMIN, auth.entrar("admin", "Senha-Admin-Teste-1").papel());
    }

    @Test
    void cadastroEPainelSoCriamUsuariosComuns() {
        auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false);
        var publico = auth.cadastrar("ana", "senha-forte-123");
        assertEquals(Usuario.COMUM, publico.papel());
        assertFalse(auth.sessao(publico.token()).orElseThrow().admin());

        var pelaPainel = auth.criarUsuarioComum("bruno", "senha-forte-123", "admin");
        assertTrue(pelaPainel.ok());
        assertNull(pelaPainel.token(), "criar pelo painel não abre sessão");
        assertEquals("admin", banco.get("bruno").getCriadoPor());
        assertEquals(Usuario.COMUM, auth.entrar("bruno", "senha-forte-123").papel());
        assertEquals(409, auth.criarUsuarioComum("ana", "senha-forte-123", "admin").status());
        assertEquals(409, auth.criarUsuarioComum("admin", "senha-forte-123", "admin").status());
    }

    @Test
    void adminExcluiComunsMasNaoAdmins() {
        auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false);
        var ana = auth.cadastrar("ana", "senha-forte-123");
        assertEquals(204, auth.excluirUsuarioComum("ANA").status());
        assertTrue(auth.sessao(ana.token()).isEmpty(), "sessão do excluído é encerrada");
        assertEquals(404, auth.excluirUsuarioComum("ana").status());
        assertEquals(403, auth.excluirUsuarioComum("admin").status());
        assertEquals(403, auth.excluirConta("admin", "Senha-Admin-Teste-1").status());
        assertTrue(banco.containsKey("admin"));
    }

    @Test
    void qualquerUsuarioRedefineASenhaInclusiveOAdmin() {
        auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false);
        var sessao1 = auth.entrar("admin", "Senha-Admin-Teste-1");
        var sessao2 = auth.entrar("admin", "Senha-Admin-Teste-1");
        assertEquals(403, auth.alterarSenha("admin", "errada-123", "Nova-senha-456", sessao1.token()).status());
        assertEquals(400, auth.alterarSenha("admin", "Senha-Admin-Teste-1", "curta", sessao1.token()).status());
        assertEquals(400, auth.alterarSenha("admin", "Senha-Admin-Teste-1", "Senha-Admin-Teste-1", sessao1.token()).status());
        assertEquals(204, auth.alterarSenha("admin", "Senha-Admin-Teste-1", "Nova-senha-456", sessao1.token()).status());
        assertTrue(auth.sessao(sessao1.token()).isPresent(), "a sessão em uso continua");
        assertTrue(auth.sessao(sessao2.token()).isEmpty(), "as outras sessões caem");
        assertEquals(401, auth.entrar("admin", "Senha-Admin-Teste-1").status());
        assertEquals(Usuario.ADMIN, auth.entrar("admin", "Nova-senha-456").papel());

        // Reiniciar o servidor não desfaz a senha redefinida, a não ser com forcar-senha
        assertFalse(auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false));
        assertTrue(auth.entrar("admin", "Nova-senha-456").ok());
        assertTrue(auth.garantirAdmin("admin", "Senha-Admin-Teste-1", true));
        assertTrue(auth.entrar("admin", "Senha-Admin-Teste-1").ok());

        var ana = auth.cadastrar("ana", "senha-forte-123");
        assertEquals(204, auth.alterarSenha("ana", "senha-forte-123", "outra-senha-789", ana.token()).status());
        assertTrue(auth.entrar("ana", "outra-senha-789").ok());
    }

    @Test
    void senhaAtualErradaContaParaOBloqueio() {
        var ana = auth.cadastrar("ana", "senha-forte-123");
        for (int i = 0; i < AutenticacaoService.MAX_TENTATIVAS; i++) {
            assertEquals(403, auth.alterarSenha("ana", "chute-" + i + "-xyz", "nova-senha-123", ana.token()).status());
        }
        assertEquals(429, auth.alterarSenha("ana", "senha-forte-123", "nova-senha-123", ana.token()).status());
    }

    @Test
    void adminRedefineSenhaSoDeComuns() {
        auth.garantirAdmin("admin", "Senha-Admin-Teste-1", false);
        var ana = auth.cadastrar("ana", "senha-forte-123");
        assertEquals(204, auth.redefinirSenhaDeComum("ana", "senha-nova-321").status());
        assertTrue(auth.sessao(ana.token()).isEmpty(), "sessões da pessoa caem");
        assertTrue(auth.entrar("ana", "senha-nova-321").ok());
        assertEquals(403, auth.redefinirSenhaDeComum("admin", "senha-nova-321").status());
        assertEquals(404, auth.redefinirSenhaDeComum("ninguem", "senha-nova-321").status());
        assertEquals(400, auth.redefinirSenhaDeComum("ana", "curta").status());
    }
}
