import { useCallback, useEffect, useState } from 'react';
import './App.css';

// Protótipo mobile do OmniReview. A interface principal fica em backend-java/src/main/resources/static/index.html.
const API = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const CHAVE_SESSAO = 'omnireview:sessao';
const TAGS_INVALIDAS = ['deu erro', 'sem classificação'];

const tagsDe = (obra) =>
  String(obra?.dnaCriativo || '')
    .split(',')
    .map((t) => t.trim())
    .filter((t) => t && !TAGS_INVALIDAS.includes(t.toLowerCase()));

const iconeDe = (tipo) => (tipo === 'Jogo' ? '🎮' : tipo === 'Serie' ? '📺' : '🎬');

const lerSessao = () => {
  try { return JSON.parse(localStorage.getItem(CHAVE_SESSAO) || 'null'); } catch { return null; }
};

function Login({ aoEntrar }) {
  const [modo, setModo] = useState('entrar');
  const [usuario, setUsuario] = useState('');
  const [senha, setSenha] = useState('');
  const [erro, setErro] = useState('');
  const [enviando, setEnviando] = useState(false);

  const enviar = async (e) => {
    e.preventDefault();
    setErro('');
    setEnviando(true);
    try {
      const resposta = await fetch(`${API}/api/auth/${modo}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usuario, senha }),
      });
      const dados = await resposta.json().catch(() => ({}));
      if (!resposta.ok) {
        setErro(dados.erro || 'Não foi possível entrar.');
        return;
      }
      aoEntrar({ token: dados.token, usuario: dados.usuario });
    } catch {
      setErro('Sem conexão com o back-end do OmniReview.');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <form className="perfil-usuario" onSubmit={enviar}>
      <p><strong>{modo === 'entrar' ? 'Entre na sua conta' : 'Crie sua conta'}</strong></p>
      <input className="seletor-obra" placeholder="Usuário" autoComplete="username" value={usuario}
        onChange={(e) => setUsuario(e.target.value.toLowerCase())} />
      <input className="seletor-obra" type="password" placeholder="Senha (mín. 8 caracteres)"
        autoComplete={modo === 'entrar' ? 'current-password' : 'new-password'} value={senha}
        onChange={(e) => setSenha(e.target.value)} />
      {erro && <p className="status erro">{erro}</p>}
      <button className="btn-recomendar" disabled={enviando || !usuario || !senha}>
        {enviando ? 'Aguarde...' : modo === 'entrar' ? 'Entrar' : 'Criar conta'}
      </button>
      <p className="status">
        <button type="button" className="link" onClick={() => { setModo(modo === 'entrar' ? 'cadastrar' : 'entrar'); setErro(''); }}>
          {modo === 'entrar' ? 'Ainda não tem conta? Criar conta' : 'Já tem conta? Entrar'}
        </button>
      </p>
    </form>
  );
}

function App() {
  const [sessao, setSessao] = useState(lerSessao);
  const [catalogo, setCatalogo] = useState([]);
  const [selecionada, setSelecionada] = useState('');
  const [recomendacoes, setRecomendacoes] = useState([]);
  const [status, setStatus] = useState(() => (lerSessao() ? 'Carregando catálogo...' : ''));

  const sair = useCallback(() => {
    localStorage.removeItem(CHAVE_SESSAO);
    setSessao(null);
    setCatalogo([]);
    setRecomendacoes([]);
  }, []);

  /** fetch autenticado: sessão expirada volta para o login. */
  const api = useCallback(async (rota) => {
    const resposta = await fetch(`${API}${rota}`, { headers: { Authorization: `Bearer ${sessao?.token}` } });
    if (resposta.status === 401) {
      sair();
      throw new Error('Sessão expirada');
    }
    if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
    return resposta.json();
  }, [sessao, sair]);

  const entrar = (nova) => {
    localStorage.setItem(CHAVE_SESSAO, JSON.stringify(nova));
    setStatus('Carregando catálogo...');
    setSessao(nova);
  };

  useEffect(() => {
    if (!sessao) return undefined;
    let ativo = true;   // ignora a resposta se a sessão mudar antes de ela chegar
    fetch(`${API}/api/obras`, { headers: { Authorization: `Bearer ${sessao.token}` } })
      .then(async (resposta) => {
        if (!ativo) return;
        if (resposta.status === 401) { sair(); return; }
        if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
        const obras = await resposta.json();
        if (!ativo) return;
        const ordenadas = [...obras].sort((a, b) => a.titulo.localeCompare(b.titulo, 'pt-BR'));
        setCatalogo(ordenadas);
        setStatus(ordenadas.length ? '' : 'O acervo está vazio.');
      })
      .catch(() => { if (ativo) setStatus('Não foi possível carregar o catálogo.'); });
    return () => { ativo = false; };
  }, [sessao, sair]);

  const obra = catalogo.find((o) => o.titulo === selecionada);

  const buscarRecomendacoes = async () => {
    const tags = tagsDe(obra);
    if (!tags.length) {
      setStatus('Esta obra ainda não tem DNA criativo.');
      return;
    }
    const params = new URLSearchParams();
    tags.forEach((t) => params.append('tags', t));
    params.append('excluir', obra.titulo);
    try {
      setStatus('Buscando recomendações...');
      const dados = await api(`/api/recomendar?${params}`);
      setRecomendacoes(Array.isArray(dados) ? dados : []);
      setStatus(Array.isArray(dados) && dados.length ? '' : 'Nenhuma recomendação encontrada.');
    } catch {
      setStatus('Erro ao consultar o motor de recomendação.');
    }
  };

  return (
    <div className="App">
      <h1>OmniReview 🌌</h1>
      <p>Sistema Unificado de Recomendação de Conteúdo Multimídia.</p>

      {!sessao ? <Login aoEntrar={entrar} /> : (
        <>
          <p className="status">
            Conectado como <strong>{sessao.usuario}</strong> ·{' '}
            <button type="button" className="link" onClick={sair}>Sair</button>
          </p>

          <div className="perfil-usuario">
            <label htmlFor="obra">Escolha uma obra que você gosta:</label>
            <select
              id="obra"
              className="seletor-obra"
              value={selecionada}
              onChange={(e) => { setSelecionada(e.target.value); setRecomendacoes([]); setStatus(''); }}
            >
              <option value="">Selecione...</option>
              {catalogo.map((o) => (
                <option key={o.titulo} value={o.titulo}>{iconeDe(o.tipo)} {o.titulo}</option>
              ))}
            </select>
            {obra && <p><em>DNA criativo: {tagsDe(obra).join(', ') || '—'}</em></p>}
            <button onClick={buscarRecomendacoes} className="btn-recomendar" disabled={!obra}>
              Gerar Recomendações Transmídia
            </button>
          </div>

          {status && <p className="status">{status}</p>}

          <div className="lista-obras">
            {recomendacoes.map((r) => (
              <div key={r.titulo} className="card-obra">
                <h3>{r.titulo}</h3>
                <p><strong>Mídia:</strong> {r.tipo === 'Serie' ? 'Série' : r.tipo} {iconeDe(r.tipo)}</p>
                <p><strong>DNA Criativo:</strong> {r.dnaCriativo}</p>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

export default App;
