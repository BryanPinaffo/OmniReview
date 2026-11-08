package com.omnireview.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Cadastro, login, sessões e papéis (administrador / usuário comum).
 * - Sessões: token aleatório de 256 bits, válido por {@link #DURACAO_SESSAO}, guardado em memória
 *   (reiniciar o servidor encerra as sessões; basta entrar de novo).
 * - Força bruta: após {@link #MAX_TENTATIVAS} senhas erradas, o usuário fica bloqueado por {@link #BLOQUEIO}.
 * - Mensagens genéricas: o login não revela se o usuário existe.
 * - O cadastro público e o painel do administrador só criam usuários comuns; a conta de administrador
 *   vem da configuração ({@link #garantirAdmin}).
 */
@Service
public class AutenticacaoService {

    static final Duration DURACAO_SESSAO = Duration.ofHours(12);
    static final int MAX_TENTATIVAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);
    static final Pattern FORMATO_USUARIO = Pattern.compile("[a-z0-9._-]{3,30}");
    static final int MIN_SENHA = 8;
    static final int MAX_SENHA = 128;

    // Hash de uma senha qualquer, usado para gastar o mesmo tempo quando o usuário não existe
    private static final String HASH_FICTICIO = Senhas.gerarHash("senha-ficticia-para-tempo-constante");
    private static final SecureRandom ALEATORIO = new SecureRandom();

    /** Resultado de uma operação de autenticação. */
    public record Resultado(boolean ok, String token, String usuario, String papel, String erro, int status) {
        static Resultado sucesso(String token, String usuario, String papel) { return new Resultado(true, token, usuario, papel, null, 200); }
        static Resultado falha(int status, String erro) { return new Resultado(false, null, null, null, erro, status); }
    }

    /** Dados da sessão: dono e papel. */
    public record Sessao(String usuario, String papel, Instant expiraEm) {
        public boolean admin() { return Usuario.ADMIN.equals(papel); }
    }

    private record Tentativas(int falhas, Instant bloqueadoAte) {}

    private final UsuarioRepository usuarios;
    private final Clock relogio;
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    private final Map<String, Tentativas> tentativas = new ConcurrentHashMap<>();

    @Autowired
    public AutenticacaoService(UsuarioRepository usuarios) {
        this(usuarios, Clock.systemUTC());
    }

    AutenticacaoService(UsuarioRepository usuarios, Clock relogio) {
        this.usuarios = usuarios;
        this.relogio = relogio;
    }

    /** Cadastro público: cria um usuário comum e já abre a sessão. */
    public Resultado cadastrar(String usuarioInformado, String senha) {
        Resultado criado = criarConta(usuarioInformado, senha, null);
        return criado.ok() ? Resultado.sucesso(abrirSessao(criado.usuario(), Usuario.COMUM), criado.usuario(), Usuario.COMUM) : criado;
    }

    /** Painel do administrador: cria um usuário comum sem abrir sessão. */
    public Resultado criarUsuarioComum(String usuarioInformado, String senha, String criadoPor) {
        Resultado criado = criarConta(usuarioInformado, senha, criadoPor);
        return criado.ok() ? new Resultado(true, null, criado.usuario(), Usuario.COMUM, null, 201) : criado;
    }

    private Resultado criarConta(String usuarioInformado, String senha, String criadoPor) {
        String usuario = normalizar(usuarioInformado);
        if (!FORMATO_USUARIO.matcher(usuario).matches()) {
            return Resultado.falha(400, "O usuário deve ter de 3 a 30 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado.");
        }
        if (senha == null || senha.length() < MIN_SENHA || senha.length() > MAX_SENHA) {
            return Resultado.falha(400, "A senha deve ter de " + MIN_SENHA + " a " + MAX_SENHA + " caracteres.");
        }
        if (senha.equalsIgnoreCase(usuario)) {
            return Resultado.falha(400, "A senha não pode ser igual ao usuário.");
        }
        synchronized (this) {
            if (usuarios.existsById(usuario)) {
                return Resultado.falha(409, "Este nome de usuário já está em uso.");
            }
            Usuario novo = new Usuario(usuario, Senhas.gerarHash(senha), Instant.now(relogio).toString(), Usuario.COMUM);
            novo.setCriadoPor(criadoPor);
            usuarios.save(novo);
        }
        return Resultado.sucesso(null, usuario, Usuario.COMUM);
    }

    /**
     * Garante a conta de administrador (omnireview.admin.usuario / omnireview.admin.senha).
     * - Não existe: cria com a senha configurada.
     * - Existe como usuário comum (alguém se cadastrou antes com esse nome): vira administrador com a senha configurada.
     * - Já é administrador: mantém a senha atual (que pode ter sido redefinida pelo site),
     *   a não ser que forcarSenha seja true (recuperação de senha esquecida).
     * @return true se a conta foi criada ou corrigida
     */
    public synchronized boolean garantirAdmin(String usuarioInformado, String senha, boolean forcarSenha) {
        String usuario = normalizar(usuarioInformado);
        Optional<Usuario> atual = usuarios.findById(usuario);
        if (atual.isPresent() && atual.get().isAdmin()
                && (!forcarSenha || Senhas.confere(senha, atual.get().getSenhaHash()))) {
            return false;
        }
        Usuario admin = atual.orElseGet(() -> new Usuario(usuario, null, Instant.now(relogio).toString()));
        admin.setPapel(Usuario.ADMIN);
        admin.setSenhaHash(Senhas.gerarHash(senha));
        usuarios.save(admin);
        sessoes.values().removeIf(s -> s.usuario().equals(usuario));   // sessões antigas desse nome não valem mais
        return true;
    }

    public Resultado entrar(String usuarioInformado, String senha) {
        String usuario = normalizar(usuarioInformado);
        Instant agora = Instant.now(relogio);
        Tentativas t = tentativas.get(usuario);
        if (t != null && t.bloqueadoAte() != null && agora.isBefore(t.bloqueadoAte())) {
            long minutos = Math.max(1, Duration.between(agora, t.bloqueadoAte()).toMinutes());
            return Resultado.falha(429, "Muitas tentativas. Tente novamente em " + minutos + " min.");
        }
        Optional<Usuario> encontrado = usuario.isEmpty() ? Optional.empty() : usuarios.findById(usuario);
        boolean senhaCorreta = Senhas.confere(senha, encontrado.map(u -> u.getSenhaHash()).orElse(HASH_FICTICIO))
                && encontrado.isPresent();
        if (!senhaCorreta) {
            registrarFalha(usuario, agora);
            return Resultado.falha(401, "Usuário ou senha inválidos.");
        }
        tentativas.remove(usuario);
        String papel = encontrado.get().getPapel();
        return Resultado.sucesso(abrirSessao(usuario, papel), usuario, papel);
    }

    /** Sessão do token, se existir e não tiver expirado. */
    public Optional<Sessao> sessao(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Sessao s = sessoes.get(token);
        if (s == null) return Optional.empty();
        if (Instant.now(relogio).isAfter(s.expiraEm())) {
            sessoes.remove(token);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /** Usuário dono do token, se a sessão existir e não tiver expirado. */
    public Optional<String> usuarioDaSessao(String token) {
        return sessao(token).map(s -> s.usuario());
    }

    public void sair(String token) {
        if (token != null) sessoes.remove(token);
    }

    /**
     * O próprio usuário (qualquer papel, inclusive o administrador) redefine a senha informando a atual.
     * As outras sessões dele são encerradas; a sessão em uso continua válida.
     * Senha atual errada conta como tentativa de login (bloqueio após {@link #MAX_TENTATIVAS}).
     */
    public Resultado alterarSenha(String usuario, String senhaAtual, String novaSenha, String tokenAtual) {
        Instant agora = Instant.now(relogio);
        Tentativas t = tentativas.get(usuario);
        if (t != null && t.bloqueadoAte() != null && agora.isBefore(t.bloqueadoAte())) {
            long minutos = Math.max(1, Duration.between(agora, t.bloqueadoAte()).toMinutes());
            return Resultado.falha(429, "Muitas tentativas. Tente novamente em " + minutos + " min.");
        }
        Optional<Usuario> u = usuarios.findById(usuario);
        if (u.isEmpty() || !Senhas.confere(senhaAtual, u.get().getSenhaHash())) {
            registrarFalha(usuario, agora);
            return Resultado.falha(403, "A senha atual está incorreta.");   // 403, e não 401: a sessão continua válida
        }
        tentativas.remove(usuario);
        String erro = validarNovaSenha(usuario, novaSenha);
        if (erro != null) return Resultado.falha(400, erro);
        if (novaSenha.equals(senhaAtual)) return Resultado.falha(400, "A nova senha deve ser diferente da atual.");
        gravarSenha(u.get(), novaSenha);
        sessoes.entrySet().removeIf(e -> e.getValue().usuario().equals(usuario) && !e.getKey().equals(tokenAtual));
        return new Resultado(true, null, usuario, u.get().getPapel(), null, 204);
    }

    /** O administrador redefine a senha de um usuário comum (e encerra as sessões dele). */
    public Resultado redefinirSenhaDeComum(String alvoInformado, String novaSenha) {
        String alvo = normalizar(alvoInformado);
        Optional<Usuario> u = usuarios.findById(alvo);
        if (u.isEmpty()) return Resultado.falha(404, "Usuário não encontrado.");
        if (u.get().isAdmin()) return Resultado.falha(403, "A senha de um administrador só pode ser trocada por ele mesmo.");
        String erro = validarNovaSenha(alvo, novaSenha);
        if (erro != null) return Resultado.falha(400, erro);
        gravarSenha(u.get(), novaSenha);
        sessoes.values().removeIf(s -> s.usuario().equals(alvo));
        tentativas.remove(alvo);
        return new Resultado(true, null, alvo, Usuario.COMUM, null, 204);
    }

    private static String validarNovaSenha(String usuario, String senha) {
        if (senha == null || senha.length() < MIN_SENHA || senha.length() > MAX_SENHA) {
            return "A senha deve ter de " + MIN_SENHA + " a " + MAX_SENHA + " caracteres.";
        }
        if (senha.equalsIgnoreCase(usuario)) return "A senha não pode ser igual ao usuário.";
        return null;
    }

    private void gravarSenha(Usuario u, String senha) {
        u.setSenhaHash(Senhas.gerarHash(senha));
        u.setSenhaAlteradaEm(Instant.now(relogio).toString());
        usuarios.save(u);
    }

    /** O próprio usuário exclui a conta (exige a senha atual) e encerra todas as suas sessões. */
    public Resultado excluirConta(String usuario, String senha) {
        Optional<Usuario> u = usuarios.findById(usuario);
        if (u.isEmpty() || !Senhas.confere(senha, u.get().getSenhaHash())) {
            return Resultado.falha(401, "Senha incorreta.");
        }
        if (u.get().isAdmin()) {
            return Resultado.falha(403, "A conta de administrador não pode ser excluída.");
        }
        apagar(usuario);
        return Resultado.sucesso(null, usuario, u.get().getPapel());
    }

    /** O administrador exclui um usuário comum. */
    public Resultado excluirUsuarioComum(String alvoInformado) {
        String alvo = normalizar(alvoInformado);
        Optional<Usuario> u = usuarios.findById(alvo);
        if (u.isEmpty()) return Resultado.falha(404, "Usuário não encontrado.");
        if (u.get().isAdmin()) return Resultado.falha(403, "Contas de administrador não podem ser excluídas pelo painel.");
        apagar(alvo);
        return new Resultado(true, null, alvo, Usuario.COMUM, null, 204);
    }

    private void apagar(String usuario) {
        usuarios.deleteById(usuario);
        sessoes.values().removeIf(s -> s.usuario().equals(usuario));
        tentativas.remove(usuario);
    }

    private String abrirSessao(String usuario, String papel) {
        sessoes.values().removeIf(s -> Instant.now(relogio).isAfter(s.expiraEm()));   // limpeza das expiradas
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessoes.put(token, new Sessao(usuario, papel, Instant.now(relogio).plus(DURACAO_SESSAO)));
        return token;
    }

    private void registrarFalha(String usuario, Instant agora) {
        tentativas.compute(usuario, (k, atual) -> {
            int falhas = (atual == null || (atual.bloqueadoAte() != null && agora.isAfter(atual.bloqueadoAte()))) ? 1 : atual.falhas() + 1;
            return new Tentativas(falhas, falhas >= MAX_TENTATIVAS ? agora.plus(BLOQUEIO) : null);
        });
    }

    static String normalizar(String usuario) {
        return usuario == null ? "" : usuario.trim().toLowerCase(Locale.ROOT);
    }
}
