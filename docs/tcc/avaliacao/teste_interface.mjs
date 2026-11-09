// Teste automatizado da interface (index.html) via Chrome DevTools Protocol, sem dependências externas.
// Requer Node 22+ (WebSocket nativo) e Microsoft Edge ou Google Chrome instalados.
// Uso: node teste_interface.mjs [http://localhost:8080/]  -> grava teste_interface.json
// Cria uma conta temporária pela própria tela de cadastro e a exclui ao final.
import { spawn, execSync } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtempSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const URL_APP = process.argv[2] || 'http://localhost:8080/';
// Porta livre escolhida pelo sistema (evita conectar a um navegador de execução anterior)
const PORTA = await new Promise((ok) => { const srv = createServer(); srv.listen(0, () => { const { port } = srv.address(); srv.close(() => ok(port)); }); });
const NAVEGADORES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium',
];
const exe = NAVEGADORES.find(existsSync);
if (!exe) throw new Error('Edge/Chrome não encontrado');

const perfil = mkdtempSync(join(tmpdir(), 'omni-teste-'));
// Perfil limpo de verdade: sem sincronizar a conta do Windows/Microsoft, sem extensões e sem telas de primeiro uso
// (a janela de confirmação de sincronização do Edge prendia o navegador e travava as capturas de tela)
const nav = spawn(exe, ['--headless=new', '--disable-gpu', '--disable-sync', '--disable-extensions', '--no-first-run',
  '--no-default-browser-check', '--disable-features=msImplicitSignin,msEdgeSyncConsent', `--remote-debugging-port=${PORTA}`,
  `--user-data-dir=${perfil}`, '--window-size=1440,900', 'about:blank'], { stdio: 'ignore', detached: process.platform !== 'win32' });

// Encerra o navegador e todos os processos filhos (no Windows, kill() só encerra o processo inicial)
function encerrarNavegador() {
  try {
    if (process.platform === 'win32') execSync(`taskkill /PID ${nav.pid} /T /F`, { stdio: 'ignore' });
    else process.kill(-nav.pid, 'SIGKILL');
  } catch { /* já encerrado */ }
}
process.on('exit', encerrarNavegador);

const esperar = (ms) => new Promise((r) => setTimeout(r, ms));
let alvo;
for (let i = 0; i < 50 && !alvo; i++) {
  await esperar(200);
  try {
    alvo = (await (await fetch(`http://127.0.0.1:${PORTA}/json`)).json()).find((t) => t.type === 'page');
  } catch { /* navegador ainda iniciando */ }
}
const ws = new WebSocket(alvo.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const pendentes = new Map();
const errosConsole = [];
ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pendentes.has(msg.id)) { pendentes.get(msg.id)(msg); pendentes.delete(msg.id); }
  if (msg.method === 'Runtime.exceptionThrown') errosConsole.push(msg.params.exceptionDetails.text);
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errosConsole.push(msg.params.args.map((a) => a.value).join(' '));
  // Bloqueios da Content-Security-Policy chegam por aqui (origem "security"), não como exceção de JavaScript
  if (msg.method === 'Log.entryAdded' && (msg.params.entry.source === 'security' || /Content Security Policy/i.test(msg.params.entry.text))) {
    errosConsole.push('CSP: ' + msg.params.entry.text);
  }
});
const cdp = (method, params = {}) => new Promise((r) => { const id = ++seq; pendentes.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const avaliar = async (expr) => (await cdp('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
const aguardar = async (expr, ms = 15000) => {
  for (let t = 0; t < ms; t += 200) { if (await avaliar(expr)) return true; await esperar(200); }
  return false;
};

await cdp('Runtime.enable');
await cdp('Log.enable');
await cdp('Page.enable');
const casos = [];
const caso = (id, descricao, ok, detalhe = '') => { casos.push({ id, descricao, ok, detalhe }); console.log(`${ok ? 'OK  ' : 'FALHA'} ${id} ${descricao} ${detalhe}`); };

// Capturas opcionais para conferência visual: OMNI_CAPTURAS=<pasta>
const capturar = async (nome) => {
  if (!process.env.OMNI_CAPTURAS) return;
  const resposta = await cdp('Page.captureScreenshot', { format: 'png' });
  (await import('node:fs')).writeFileSync(`${process.env.OMNI_CAPTURAS}/${nome}.png`, Buffer.from(resposta.result.data, 'base64'));
};

// ---------- Login ----------
const usuarioTeste = 'teste_ui_' + Math.random().toString(36).slice(2, 8);
let senhaTeste = 'Senha-' + Math.random().toString(36).slice(2, 10) + '!9';
const preencher = (id, valor) => avaliar(`(() => { const e = document.getElementById('${id}'); e.value = ${JSON.stringify(valor)}; e.dispatchEvent(new Event('input', { bubbles: true })); })()`);

await cdp('Page.navigate', { url: URL_APP });
const foiParaLogin = await aguardar("location.pathname === '/login.html' && !!document.getElementById('form') && document.readyState === 'complete'");
caso('T00', 'Sem sessão, o site redireciona para a tela de login', foiParaLogin, await avaliar('location.pathname'));

await preencher('usuario', usuarioTeste);
await preencher('senha', 'senha-errada-123');
await avaliar("document.getElementById('form').requestSubmit()");
const erroLogin = await aguardar("!document.getElementById('alerta').hidden", 15000);
caso('T11', 'Login com senha errada mostra mensagem de erro e não entra', erroLogin && await avaliar("location.pathname") === '/login.html',
  (await avaliar("document.getElementById('alerta-texto')?.textContent")) || `página: ${await avaliar('location.href')}`);

await avaliar("document.getElementById('aba-cadastrar').click()");
await preencher('usuario', usuarioTeste);
await preencher('senha', senhaTeste);
await preencher('confirmacao', senhaTeste + 'x');
const naoConfere = await avaliar("document.getElementById('dica-confirmacao').textContent.includes('não conferem')");
await preencher('confirmacao', senhaTeste);
await avaliar("document.getElementById('form').requestSubmit()");
const entrou = await aguardar("location.pathname === '/' && document.querySelectorAll('#grid-catalogo .card').length > 0", 15000);
caso('T12', 'Cadastro pela tela valida a confirmação e entra no catálogo', naoConfere && entrou,
  `usuário ${usuarioTeste}; menu mostra "${await avaliar("document.getElementById('usuario-nome')?.textContent")}"`);
const tokenTeste = await avaliar("JSON.parse(localStorage.getItem('omnireview:sessao')).token");

caso('T01', 'Catálogo carrega cards do back-end', await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0"),
  `${await avaliar("document.querySelectorAll('#grid-catalogo .card').length")} cards`);

const comImagem = await avaliar(`(async () => {
  await new Promise(r => setTimeout(r, 4000));
  const imgs = [...document.querySelectorAll('#grid-catalogo .card img.poster')];
  return { total: document.querySelectorAll('#grid-catalogo .card').length, carregadas: imgs.filter(i => i.complete && i.naturalWidth > 0).length };
})()`);
caso('T02', 'Cards exibem pôster (imagens carregadas na primeira dobra)', comImagem.carregadas > 0, `${comImagem.carregadas} imagens carregadas`);

// ---------- "O que você quer assistir hoje?" ----------
const digitarBusca = (valor) => avaliar(`(() => { const e = document.getElementById('busca-hero-texto'); e.focus(); e.value = ${JSON.stringify(valor)}; e.dispatchEvent(new Event('input', { bubbles: true })); })()`);
const tituloAcervo = await avaliar("catalogo[0].titulo");
await digitarBusca(tituloAcervo.slice(0, Math.max(3, Math.ceil(tituloAcervo.length / 2))).toLowerCase());
const sugeriu = await aguardar(`!document.getElementById('busca-hero-lista').hidden && [...document.querySelectorAll('#busca-hero-lista .busca-opcao strong')].some(e => e.textContent === ${JSON.stringify(tituloAcervo)})`, 3000);
await capturar('busca_sugestoes');
await digitarBusca(tituloAcervo.toUpperCase());
await avaliar("document.getElementById('busca-hero').requestSubmit()");
const abriu = await aguardar(`document.getElementById('modal-fundo').classList.contains('aberto') && document.getElementById('modal-titulo').textContent === ${JSON.stringify(tituloAcervo)}`, 5000);
const naoAdicionou = await avaliar("catalogo.length") === await avaliar("document.querySelectorAll('#grid-catalogo .card').length");
caso('T16', 'Busca da tela inicial sugere obras do acervo e abre a obra digitada', sugeriu && abriu && naoAdicionou, `"${tituloAcervo}"`);
await avaliar("fecharModal()");

// Opcional (altera o acervo): OMNI_TESTE_ADICIONAR="<título que não está no acervo>"
if (process.env.OMNI_TESTE_ADICIONAR) {
  const nome = process.env.OMNI_TESTE_ADICIONAR;
  const antes = await avaliar("catalogo.length");
  await digitarBusca(nome);
  const ofereceWeb = await aguardar("!!document.querySelector('#busca-hero-lista .busca-opcao.web')", 3000);
  await avaliar("document.getElementById('busca-hero').requestSubmit()");
  await esperar(1500);
  await capturar('busca_adicionando');
  const adicionou = await aguardar(`catalogo.length === ${antes + 1} && document.getElementById('modal-fundo').classList.contains('aberto')`, 240000);
  await esperar(800);
  await capturar('busca_adicionada');
  caso('T17', 'Título fora do acervo é buscado na web e adicionado automaticamente', ofereceWeb && adicionou,
    `${await avaliar("document.getElementById('modal-titulo')?.textContent")} — ${await avaliar("document.getElementById('busca-hero-status').textContent")}`);
  await avaliar("fecharModal()");
}

// Favoritos ficam no servidor: valem depois de recarregar e mesmo com os dados do navegador apagados
const chaveCurtidas = `omnireview:curtidas:${usuarioTeste}`;
const favoritosServidor = () => avaliar("api('/api/favoritos').then(r => r.json())");
await cdp('Page.reload');
await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0");
const titulo = await avaliar("document.querySelector('#grid-catalogo .card').dataset.titulo");
const tituloSel = titulo.replace(/"/g, '\\"');
await avaliar("document.querySelector('#grid-catalogo .card [data-fav]').click()");
await aguardar(`api('/api/favoritos').then(r => r.json()).then(l => l.includes(${JSON.stringify(titulo)}))`, 6000);
const salvo = await favoritosServidor();
// simula outro aparelho: apaga tudo que o site guardou no navegador, menos a sessão
await avaliar("Object.keys(localStorage).filter(k => k !== 'omnireview:sessao').forEach(k => localStorage.removeItem(k))");
await cdp('Page.reload');
await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0");
await aguardar("document.getElementById('contador-curtidas').textContent === '1'", 6000);
const contador = await avaliar("document.getElementById('contador-curtidas').textContent");
const ativo = await aguardar(`document.querySelector('[data-fav="${tituloSel}"]')?.classList.contains('ativo')`, 4000);
caso('T03', 'Favorito fica salvo na conta (servidor): volta após recarregar e sem os dados do navegador (RF06)', salvo.includes(titulo) && contador === '1' && ativo === true,
  `"${titulo}", servidor=${salvo.length}, contador=${contador}`);

// Migração: favoritos antigos que estavam só no navegador vão para o servidor e saem do navegador
const favAntigo = await avaliar(`catalogo.find(o => o.titulo !== ${JSON.stringify(titulo)}).titulo`);
await avaliar(`localStorage.setItem('${chaveCurtidas}', JSON.stringify([${JSON.stringify(favAntigo)}, '__titulo_que_nao_existe__']))`);
await cdp('Page.reload');
await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0");
const migrou = await aguardar(`api('/api/favoritos').then(r => r.json()).then(l => l.length === 2 && l.includes(${JSON.stringify(favAntigo)}))`, 6000);
const localLimpo = await aguardar(`localStorage.getItem('${chaveCurtidas}') === null`, 4000);
await avaliar(`alternarCurtida(${JSON.stringify(favAntigo)})`);   // desfaz, para os próximos casos contarem 1 favorito
const desfez = await aguardar(`api('/api/favoritos').then(r => r.json()).then(l => l.length === 1)`, 6000);
caso('T29', 'Favoritos antigos do navegador migram para a conta; desfavoritar remove do servidor', migrou && localLimpo && desfez,
  `migrou=${migrou}, navegador limpo=${localLimpo}, desfez=${desfez}`);

await avaliar("document.querySelector('#grid-catalogo .card').click()");
await aguardar("document.getElementById('modal-fundo').classList.contains('aberto')");
const modal = await avaliar(`({
  aberto: document.getElementById('modal-fundo').classList.contains('aberto'),
  titulo: document.getElementById('modal-titulo')?.textContent,
  sinopse: document.querySelector('.modal .sinopse:not(.sem)')?.textContent?.length || 0,
  tags: document.querySelectorAll('.modal-texto .tag').length
})`);
caso('T04', 'Janela flutuante mostra título, sinopse e tags (RF05)', modal.aberto && modal.sinopse > 20 && modal.tags > 0,
  `sinopse com ${modal.sinopse} caracteres, ${modal.tags} tags`);
const semelhantes = await aguardar("document.querySelectorAll('#modal-trilho .card').length > 0", 10000);
caso('T05', 'Janela mostra obras com o mesmo DNA (RF08)', semelhantes, `${await avaliar("document.querySelectorAll('#modal-trilho .card').length")} obras`);

// ---------- Avaliação por estrelas e comentários ----------
await aguardar("!!document.getElementById('seletor-estrelas')", 10000);
const totalAntes = await avaliar("avaliacao.dados.total");
await avaliar("document.querySelector('#seletor-estrelas [data-estrela=\"4\"]').click()");
const notaSalva = await aguardar(`avaliacao.dados.minhaNota === 4 && avaliacao.dados.total === ${totalAntes + 1}`, 8000);
const notaTela = await avaliar(`({
  modal: document.getElementById('modal-nota')?.textContent.trim(),
  card: [...document.querySelectorAll('#grid-catalogo .nota-card')].find(e => e.dataset.nota === ${JSON.stringify(modal.titulo)})?.textContent.trim(),
  acesas: document.querySelectorAll('#seletor-estrelas .aceso').length
})`);
caso('T14', 'Avaliar com estrelas atualiza a média ao lado do título (modal e card)',
  notaSalva && notaTela.acesas === 4 && /\d,\d/.test(notaTela.modal || '') && /\d,\d/.test(notaTela.card || ''),
  `modal: "${notaTela.modal}", card: "${notaTela.card}"`);

const textoComentario = `Comentário de teste <b>${usuarioTeste}</b>`;
await avaliar(`(() => { const t = document.getElementById('comentario-texto'); t.value = ${JSON.stringify(textoComentario)}; t.dispatchEvent(new Event('input', { bubbles: true })); })()`);
await avaliar("document.getElementById('comentario-form').requestSubmit()");
const publicado = await aguardar(`[...document.querySelectorAll('#aval-lista .comentario p')].some(p => p.textContent === ${JSON.stringify(textoComentario)})`, 8000);
const semHtml = await avaliar("!document.querySelector('#aval-lista .comentario p b')");
await capturar('modal_topo');
await avaliar("document.getElementById('modal-avaliacoes').scrollIntoView()");
await esperar(400);
await capturar('modal_avaliacoes');
await avaliar("document.querySelector('#aval-lista [data-excluir-comentario]').click()");
await avaliar("document.querySelector('#aval-lista [data-excluir-comentario]').click()");
const removido = await aguardar(`![...document.querySelectorAll('#aval-lista .comentario p')].some(p => p.textContent === ${JSON.stringify(textoComentario)})`, 8000);
caso('T15', 'Comentário é publicado (texto escapado, sem HTML) e o autor pode excluí-lo', publicado && semHtml && removido,
  `publicado=${publicado}, escapado=${semHtml}, excluído=${removido}`);
await avaliar("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))");
caso('T06', 'Tecla Esc fecha a janela', await aguardar("!document.getElementById('modal-fundo').classList.contains('aberto')", 3000));
if (process.env.OMNI_CAPTURAS) { await avaliar("document.querySelector('#grid-catalogo .card').scrollIntoView({ block: 'center' })"); await esperar(600); await capturar('catalogo_com_nota'); await avaliar('window.scrollTo(0, 0)'); }

await avaliar("irPara('paravoce')");
const recs = await aguardar("document.querySelectorAll('#grid-recomendacoes .card').length > 0", 10000);
const tiposRec = await avaliar("[...new Set([...document.querySelectorAll('#grid-recomendacoes .card .tipo')].map(e => e.textContent))]");
caso('T07', 'Aba "Para você" gera recomendações de várias mídias (RF07)', recs && tiposRec.length >= 2, tiposRec.join(', '));

// Filtro de mídia no "Para você": cada opção só traz aquela mídia (ou o aviso de que não há cruzamento)
{
  const porTipo = {};
  for (const tipo of ['filme', 'serie', 'jogo', 'livro']) {
    await avaliar(`document.querySelector('#filtro-paravoce [data-tipo="${tipo}"]').click()`);
    await aguardar(`document.getElementById('contagem-recomendacoes').textContent.includes('de ') || !!document.querySelector('#grid-recomendacoes .vazio')`, 10000);
    await esperar(200);
    porTipo[tipo] = await avaliar(`(() => {
      const cards = [...document.querySelectorAll('#grid-recomendacoes .card')];
      return { n: cards.length, todos: cards.every(c => c.querySelector('.tipo')?.classList.contains('${tipo}')),
               vazio: !!document.querySelector('#grid-recomendacoes .vazio') };
    })()`);
    if (tipo === 'livro') await capturar('paravoce_livros');
  }
  await avaliar(`document.querySelector('#filtro-paravoce [data-tipo=""]').click()`);
  const voltou = await aguardar("new Set([...document.querySelectorAll('#grid-recomendacoes .card .tipo')].map(e => e.textContent)).size >= 2", 10000);
  const coerente = Object.values(porTipo).every(r => (r.n > 0 && r.todos) || (r.n === 0 && r.vazio));
  caso('T36', '"Para você" filtra por mídia (Filmes, Séries, Jogos, Livros) e volta a misturar em "Todos"',
    coerente && Object.values(porTipo).some(r => r.n > 0) && voltou,
    Object.entries(porTipo).map(([t, r]) => `${t}: ${r.n}`).join(', ') + `; todos de novo=${voltou}`);
}

await avaliar("irPara('catalogo')");
await avaliar("(() => { const i = document.getElementById('filtro-texto'); i.value = 'zzzz-inexistente'; i.dispatchEvent(new Event('input')); })()");
const vazio = await avaliar("document.querySelectorAll('#grid-catalogo .card').length === 0 && !!document.querySelector('#grid-catalogo .vazio')");
await avaliar("(() => { const i = document.getElementById('filtro-texto'); i.value = ''; i.dispatchEvent(new Event('input')); })()");
await avaliar("document.querySelector('#filtro-tipo [data-tipo=jogo]').click()");
const soJogos = await avaliar("[...document.querySelectorAll('#grid-catalogo .card .tipo')].every(e => e.classList.contains('jogo'))");
caso('T08', 'Busca e filtro por mídia funcionam (RF04)', vazio && soJogos);
await avaliar("document.querySelector('#filtro-tipo [data-tipo=TODOS]').click()");

// ---------- Assisti / terminei e perfil ----------
const obraFilme = await avaliar("catalogo.find(o => tipoDe(o) === 'filme').titulo");
const obraJogo = await avaliar("catalogo.find(o => tipoDe(o) === 'jogo').titulo");
const marcar = async (titulo) => {
  await avaliar(`abrirModal(${JSON.stringify(titulo)})`);
  await aguardar("!!document.querySelector('#modal [data-concluir]')", 3000);
  await avaliar("document.querySelector('#modal [data-concluir]').click()");
  return aguardar(`minhasConcluidas.has(${JSON.stringify(titulo)}) && document.querySelector('#modal [data-concluir]').getAttribute('aria-pressed') === 'true'`, 8000);
};
const marcouFilme = await marcar(obraFilme);
const textoBotaoFilme = await avaliar("document.querySelector('#modal [data-concluir]').textContent.trim()");
await avaliar('fecharModal()');
const marcouJogo = await marcar(obraJogo);
const textoBotaoJogo = await avaliar("document.querySelector('#modal [data-concluir]').textContent.trim()");
await avaliar('fecharModal()');
const seloNoCard = await avaliar(`[...document.querySelectorAll('#grid-catalogo [data-selo-concluida]')].some(e => e.dataset.seloConcluida === ${JSON.stringify(obraFilme)} && !e.hidden)`);
caso('T21', 'Botão "assisti / terminei o jogo" marca a obra e mostra o selo no card', marcouFilme && marcouJogo && seloNoCard,
  `filme: "${textoBotaoFilme}", jogo: "${textoBotaoJogo}"`);

await avaliar("abrirPerfil(sessao.usuario)");
await aguardar("!!document.querySelector('.perfil-stats')", 8000);
const numeros = await avaliar("[...document.querySelectorAll('.perfil-stat')].map(e => e.querySelector('b').textContent + ' ' + e.querySelector('span').textContent.trim())");
const numero = rotulo => numeros.find(n => n.includes(rotulo)) || '';
caso('T22', 'Perfil mostra jogos jogados, filmes, séries e livros concluídos e reviews', numero('jogos jogados') === '1 jogos jogados'
  && numero('filmes assistidos') === '1 filmes assistidos' && numero('séries').startsWith('0 séries') && numero('livros lidos') === '0 livros lidos'
  && /^1 review/.test(numero('review')), numeros.join(' | '));

// Editar perfil: foto e capa de fundo geradas no próprio navegador + bio
await avaliar('abrirEditorPerfil()');
const enviarImagem = (campo, w, h, cor) => avaliar(`(async () => {
  const c = document.createElement('canvas'); c.width = ${w}; c.height = ${h};
  const g = c.getContext('2d'); const grad = g.createLinearGradient(0, 0, ${w}, ${h});
  grad.addColorStop(0, '${cor}'); grad.addColorStop(1, '#3dd6f5'); g.fillStyle = grad; g.fillRect(0, 0, ${w}, ${h});
  const blob = await new Promise(ok => c.toBlob(ok, 'image/png'));
  const dt = new DataTransfer(); dt.items.add(new File([blob], '${campo}.png', { type: 'image/png' }));
  campoImagemEscolhido = '${campo}';
  const input = document.getElementById('arquivo-imagem'); input.files = dt.files;
  input.dispatchEvent(new Event('change'));
})()`);
await enviarImagem('foto', 600, 800, '#8b7bff');
await enviarImagem('capaFundo', 2400, 900, '#f6b94f');
await aguardar("rascunhoPerfil.foto.startsWith('data:image/jpeg') && rascunhoPerfil.capaFundo.startsWith('data:image/jpeg')", 5000);
await preencher('editor-bio', 'Fã de terror e RPG. <script>alert(1)</script>');
await avaliar("salvarPerfil()");
const salvouPerfil = await aguardar("document.getElementById('dialogo-fundo').hidden && !!document.querySelector('.perfil-cabeca .avatar img') && document.getElementById('perfil-capa').style.backgroundImage.includes('data:image/jpeg')", 10000);
const bioTexto = await avaliar("document.querySelector('.perfil-identidade .bio')?.textContent");
const fotoNoMenu = await avaliar("!!document.querySelector('#usuario-avatar img')");
await capturar('perfil_proprio');
caso('T23', 'Perfil aceita foto, capa de fundo e bio (texto escapado)', salvouPerfil && fotoNoMenu && bioTexto?.includes('<script>'),
  `bio: "${bioTexto}"`);

// Seguir outro usuário (criado só para o teste)
const outroUsuario = 'teste_seg_' + Math.random().toString(36).slice(2, 7);
const outro = await avaliar(`fetch('/api/auth/cadastrar', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: '${outroUsuario}', senha: 'Senha-outro-123' }) }).then(r => r.json())`);
await avaliar("document.querySelector('.perfil-abas [data-perfil-aba=pessoas]').click()");
await aguardar("!!document.getElementById('busca-pessoas')", 3000);
await preencher('busca-pessoas', outroUsuario);
await aguardar(`!!document.querySelector('#lista-pessoas [data-seguir="${outroUsuario}"]')`, 6000);
await avaliar(`document.querySelector('#lista-pessoas [data-seguir="${outroUsuario}"]').click()`);
const seguiu = await aguardar(`document.querySelector('[data-seguir="${outroUsuario}"]')?.getAttribute('aria-pressed') === 'true'`, 6000);
const seguindoNum = await avaliar("perfilAtual.estatisticas.seguindo");
await avaliar(`abrirPerfil('${outroUsuario}')`);
await aguardar(`perfilAtual?.usuario === '${outroUsuario}'`, 6000);
const visao = await avaliar("({ seguidores: perfilAtual.estatisticas.seguidores, botao: document.querySelector('.perfil-acoes [data-seguir]')?.textContent })");
await avaliar(`document.querySelector('.perfil-acoes [data-seguir]').click()`);
const deixou = await aguardar("perfilAtual.estatisticas.seguidores === 0 && document.querySelector('.perfil-acoes [data-seguir]').textContent === 'Seguir'", 6000);
caso('T24', 'Seguir e deixar de seguir outro usuário atualiza os contadores', seguiu && seguindoNum === 1 && visao.seguidores === 1 && visao.botao === 'Seguindo' && deixou,
  `seguindo=${seguindoNum}, seguidores do outro=${visao.seguidores}`);
await avaliar(`fetch('/api/auth/conta', { method: 'DELETE', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${outro?.token}' },
  body: JSON.stringify({ senha: 'Senha-outro-123' }) })`);

// Redefinir a própria senha pela tela
await avaliar('abrirRedefinirSenha()');
const novaSenhaTeste = 'Nova-' + Math.random().toString(36).slice(2, 10) + '!7';
await preencher('senha-atual', 'senha-errada-999');
await preencher('senha-nova', novaSenhaTeste);
await preencher('senha-confirmacao', novaSenhaTeste);
await avaliar("document.getElementById('form-senha').requestSubmit()");
const recusouErrada = await aguardar("document.getElementById('status-senha')?.textContent.includes('incorreta')", 6000);
await preencher('senha-atual', senhaTeste);
await avaliar("document.getElementById('form-senha').requestSubmit()");
const trocou = await aguardar("document.getElementById('dialogo-fundo').hidden", 8000);
const loginNovo = await avaliar(`fetch('/api/auth/entrar', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: '${usuarioTeste}', senha: ${JSON.stringify(novaSenhaTeste)} }) }).then(r => r.status)`);
const loginAntigo = await avaliar(`fetch('/api/auth/entrar', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: '${usuarioTeste}', senha: ${JSON.stringify(senhaTeste)} }) }).then(r => r.status)`);
const continuaLogado = await avaliar("api('/api/auth/eu').then(r => r.status)");
if (trocou) senhaTeste = novaSenhaTeste;
caso('T25', 'Redefinir senha: recusa a senha atual errada, troca e mantém a sessão', recusouErrada && trocou && loginNovo === 200 && loginAntigo === 401 && continuaLogado === 200,
  `nova senha: ${loginNovo}, antiga: ${loginAntigo}, sessão atual: ${continuaLogado}`);
await avaliar("irPara('catalogo')");

// ---------- Minhas listas ----------
await aguardar("minhasListas.length >= 2", 8000);
const padroes = await avaliar("minhasListas.map(l => l.nome)");
await avaliar(`abrirModal(${JSON.stringify(obraFilme)})`);
await aguardar("!!document.querySelector('#modal [data-salvar-lista]')", 3000);
await avaliar("document.querySelector('#modal [data-salvar-lista]').click()");
await aguardar("!document.getElementById('popover-listas').hidden", 3000);
await capturar('popover_listas');
await avaliar("[...document.querySelectorAll('#popover-listas [data-alternar-lista]')].find(b => b.textContent.includes('Quero assistir')).click()");
const salvouNaPadrao = await aguardar(`minhasListas.find(l => l.nome === 'Quero assistir').obras.some(o => o.titulo === ${JSON.stringify(obraFilme)})
  && document.querySelector('#modal [data-salvar-lista]').textContent.includes('Quero assistir')`, 8000);
await avaliar("fecharModal()");
// cria uma lista nova pelo menu da obra, já com um jogo
await avaliar(`abrirModal(${JSON.stringify(obraJogo)})`);
await aguardar("!!document.querySelector('#modal [data-salvar-lista]')", 3000);
await avaliar("document.querySelector('#modal [data-salvar-lista]').click()");
await preencher('nova-lista-nome', 'Zerar nas férias');
await avaliar("document.getElementById('nova-lista-form').requestSubmit()");
const criouComJogo = await aguardar(`minhasListas.some(l => l.nome === 'Zerar nas férias' && l.obras.some(o => o.titulo === ${JSON.stringify(obraJogo)}))`, 8000);
await avaliar("fecharModal()");
// página Minhas listas
await avaliar("irPara('listas')");
await aguardar("document.querySelectorAll('#listas-abas .lista-chip').length >= 3", 5000);
await avaliar("[...document.querySelectorAll('#listas-abas .lista-chip')].find(c => c.textContent.includes('Quero assistir')).click()");
const mostraFilme = await aguardar(`[...document.querySelectorAll('#lista-conteudo .card')].some(c => c.dataset.titulo === ${JSON.stringify(obraFilme)})`, 3000);
const seloAssistido = await avaliar(`!document.querySelector('#lista-conteudo [data-selo-concluida="${obraFilme.replace(/"/g, '\\"')}"]')?.hidden`);
await capturar('minhas_listas');
await avaliar("document.querySelector('#lista-conteudo [data-remover-da-lista]').click()");
const removeu = await aguardar("minhasListas.find(l => l.nome === 'Quero assistir').obras.length === 0 && !!document.querySelector('#lista-conteudo .vazio')", 6000);
// renomeia e exclui a lista criada
await avaliar("[...document.querySelectorAll('#listas-abas .lista-chip')].find(c => c.textContent.includes('Zerar nas férias')).click()");
await avaliar("abrirRenomearLista()");
await preencher('nome-lista', 'Zerar no fim de semana');
await avaliar("document.getElementById('form-lista').requestSubmit()");
const renomeou = await aguardar("minhasListas.some(l => l.nome === 'Zerar no fim de semana') && document.querySelector('#lista-conteudo h2').textContent === 'Zerar no fim de semana'", 6000);
await avaliar("abrirExcluirLista()");
await avaliar("document.getElementById('confirmar-exclusao-lista').click()");
const excluiuLista = await aguardar("minhasListas.length === 2 && document.getElementById('dialogo-fundo').hidden", 6000);
caso('T26', 'Minhas listas: listas padrão, salvar pela obra, criar, ver, remover, renomear e excluir',
  padroes.join('|') === 'Quero assistir|Quero jogar' && salvouNaPadrao && criouComJogo && mostraFilme && seloAssistido && removeu && renomeou && excluiuLista,
  `padrão=${padroes.join(', ')}; salvou=${salvouNaPadrao}, criou=${criouComJogo}, mostrou=${mostraFilme}, selo=${seloAssistido}, removeu=${removeu}, renomeou=${renomeou}, excluiu=${excluiuLista}`);
await avaliar("irPara('catalogo')");

// ---------- Compartilhar obra: link direto, prévia pública e retorno após o login ----------
{
  const base = URL_APP.replace(/\/$/, '');
  const urlAtual = () => avaliar('location.pathname + location.search');
  await avaliar(`abrirModal(${JSON.stringify(obraFilme)})`);
  await avaliar("document.querySelector('#modal [data-compartilhar]').click()");
  await aguardar("!!document.getElementById('link-compartilhar')", 3000);
  await capturar('compartilhar');
  const link = await avaliar("document.getElementById('link-compartilhar').value");
  const linkOk = link === `${base}/obra?t=${encodeURIComponent(obraFilme)}`;
  const zap = await avaliar("document.querySelector('.compartilhar-redes a').href");
  // prévia: a página /obra é pública (sem token) e traz título e pôster para o WhatsApp
  const previa = await avaliar(`fetch(${JSON.stringify(link)}).then(async r => ({ status: r.status, html: await r.text() }))`);
  const previaOk = previa.status === 200 && previa.html.includes('property="og:title"') && previa.html.includes('og:image')
    && previa.html.includes(obraFilme.replace(/&/g, '&amp;').replace(/"/g, '&quot;'));
  await avaliar('fecharDialogo(); fecharModal()');
  // logado: o link abre a obra direto e limpa o endereço
  await cdp('Page.navigate', { url: link });
  const abriuLogado = await aguardar(`document.getElementById('modal-titulo')?.textContent === ${JSON.stringify(obraFilme)}`, 15000);
  const enderecoLimpo = (await urlAtual()) === '/';
  // deslogado: vai para o login guardando o destino; com a sessão de volta, o login devolve para a obra
  const sessaoGuardada = await avaliar("localStorage.getItem('omnireview:sessao')");
  await avaliar("localStorage.removeItem('omnireview:sessao')");
  await cdp('Page.navigate', { url: link });
  await aguardar("location.pathname === '/login.html'", 10000);
  const loginComVoltar = (await urlAtual()).includes('voltar=' + encodeURIComponent('/?obra='));
  await avaliar(`localStorage.setItem('omnireview:sessao', ${JSON.stringify(sessaoGuardada)})`);
  await cdp('Page.reload');
  const voltouParaObra = await aguardar(`location.pathname === '/' && document.getElementById('modal-titulo')?.textContent === ${JSON.stringify(obraFilme)}`, 15000);
  // o "voltar" do login não aceita outro domínio
  await cdp('Page.navigate', { url: `${base}/login.html?voltar=${encodeURIComponent('/\\exemplo.com')}` });
  await aguardar("location.pathname === '/' && document.querySelectorAll('#grid-catalogo .card').length > 0", 10000);
  const semRedirExterno = (await avaliar('location.host')) === new URL(base).host;
  await avaliar('fecharModal()');
  caso('T30', 'Compartilhar: gera link direto, prévia pública com pôster, abre a obra (logado ou após o login) e não redireciona para fora',
    linkOk && zap.startsWith('https://wa.me/') && previaOk && abriuLogado && enderecoLimpo && loginComVoltar && voltouParaObra && semRedirExterno,
    `link=${linkOk}, prévia=${previaOk}, abriu=${abriuLogado}, endereço limpo=${enderecoLimpo}, login guarda destino=${loginComVoltar}, voltou=${voltouParaObra}, sem redirecionamento externo=${semRedirExterno}`);
}

// ---------- Listas em conjunto e compartilhadas (três pessoas no mesmo navegador, trocando a sessão) ----------
{
  const base = URL_APP.replace(/\/$/, '');
  const sessaoDono = await avaliar("localStorage.getItem('omnireview:sessao')");
  const cadastrar = nome => avaliar(`fetch('/api/auth/cadastrar', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ usuario: '${nome}', senha: 'Senha-grupo-123' }) }).then(r => r.json())`);
  const sufixo = Math.random().toString(36).slice(2, 7);
  const membro = await cadastrar('teste_mb_' + sufixo);
  const visitante = await cadastrar('teste_vi_' + sufixo);
  const entrarComo = async s => { await avaliar(`localStorage.setItem('omnireview:sessao', ${JSON.stringify(typeof s === 'string' ? s : JSON.stringify({ token: s.token, usuario: s.usuario, papel: s.papel }))})`); };
  const listaSel = "minhasListas.find(l => l.id === listaSelecionada)";

  // dono: cria a lista, adiciona um filme, convida o participante e cria o link
  await avaliar("irPara('listas')");
  await avaliar("abrirNovaLista()");
  await preencher('nome-lista', 'Maratona do grupo');
  await avaliar("document.getElementById('form-lista').requestSubmit()");
  await aguardar(`${listaSel}?.nome === 'Maratona do grupo'`, 6000);
  const idLista = await avaliar('listaSelecionada');
  await avaliar(`mudarListas('/api/listas/${idLista}/obras', json({ titulo: ${JSON.stringify(obraFilme)} }))`);
  await avaliar("abrirParticipantes()");
  await preencher('novo-membro', membro.usuario);
  await avaliar("document.getElementById('form-membro').requestSubmit()");
  const convidou = await aguardar(`${listaSel}.membros.includes('${membro.usuario}') && !!document.querySelector('[data-remover-membro="${membro.usuario}"]')`, 6000);
  await capturar('lista_participantes');
  await avaliar('fecharDialogo()');
  const chipGrupo = await aguardar(`!!document.querySelector('#listas-abas [data-lista="${idLista}"] path[d^="M3 19.5"]')`, 3000);
  await avaliar("abrirCompartilharLista()");
  await avaliar("document.getElementById('ativar-link-lista').click()");
  await aguardar("!!document.getElementById('link-compartilhar')", 6000);
  await capturar('lista_compartilhar');
  const linkLista = await avaliar("document.getElementById('link-compartilhar').value");
  await avaliar('fecharDialogo()');
  await capturar('lista_em_conjunto_dono');

  // visitante (não participa): o link mostra a lista só para ver
  await entrarComo(visitante);
  await cdp('Page.navigate', { url: linkLista });
  const visitaOk = await aguardar(`document.querySelector('#dialogo h2')?.textContent === 'Maratona do grupo'
    && !!document.querySelector('#dialogo [data-abrir-obra="${obraFilme.replace(/"/g, '\\"')}"]')`, 15000);
  await capturar('lista_visitante');
  const visitanteSemLista = await avaliar(`!minhasListas.some(l => l.id === '${idLista}')`);
  await avaliar(`document.querySelector('#dialogo [data-abrir-obra]').click()`);
  const abriuObraDaVisita = await aguardar(`document.getElementById('modal-titulo')?.textContent === ${JSON.stringify(obraFilme)}`, 6000);
  await avaliar('fecharModal()');

  // participante: o link leva para a lista; ele adiciona um jogo pela janela da obra e não vê as opções do dono
  await entrarComo(membro);
  await cdp('Page.navigate', { url: linkLista });
  const membroNaLista = await aguardar(`!document.getElementById('aba-listas').hidden && listaSelecionada === '${idLista}'
    && document.querySelector('.lista-participantes')?.textContent.includes('${usuarioTeste}')`, 15000);
  const botoesMembro = await avaliar("[...document.querySelectorAll('.lista-topo .acoes .btn')].map(b => b.textContent.trim())");
  await avaliar(`abrirModal(${JSON.stringify(obraJogo)})`);
  await aguardar("!!document.querySelector('#modal [data-salvar-lista]')", 3000);
  await avaliar("document.querySelector('#modal [data-salvar-lista]').click()");
  await aguardar(`!!document.querySelector('#popover-listas [data-alternar-lista="${idLista}"]')`, 3000);
  const mostraDono = await avaliar(`document.querySelector('#popover-listas [data-alternar-lista="${idLista}"]').textContent.includes('de ${usuarioTeste}')`);
  await avaliar(`document.querySelector('#popover-listas [data-alternar-lista="${idLista}"]').click()`);
  const membroAdicionou = await aguardar(`${listaSel}?.obras.some(o => o.titulo === ${JSON.stringify(obraJogo)} && o.adicionadaPor === '${membro.usuario}')`, 6000);
  await avaliar('fecharModal()');

  // dono de novo: vê o jogo "adicionado por" o participante; desativa o link
  await entrarComo(sessaoDono);
  await cdp('Page.navigate', { url: base + '/' });
  await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0", 15000);
  await avaliar(`listaSelecionada = '${idLista}'; irPara('listas')`);
  const donoVePor = await aguardar(`[...document.querySelectorAll('#lista-conteudo .item-lista small')].some(s => s.textContent.includes('por ${membro.usuario}'))`, 8000);
  await avaliar("abrirCompartilharLista()");
  await avaliar("document.getElementById('desativar-link-lista').click()");
  const desativou = await aguardar(`!${listaSel}.link && !!document.getElementById('ativar-link-lista')`, 6000);
  await avaliar('fecharDialogo()');
  const linkMorto = await avaliar(`api('/api/listas/compartilhada/' + ${JSON.stringify(new URL(linkLista).searchParams.get('c'))}).then(r => r.status)`);

  // limpeza: participante e visitante excluem as contas; o dono exclui a lista
  for (const s of [membro, visitante]) {
    await avaliar(`fetch('/api/auth/conta', { method: 'DELETE', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${s.token}' }, body: JSON.stringify({ senha: 'Senha-grupo-123' }) })`);
  }
  await avaliar(`mudarListas('/api/listas/${idLista}', { method: 'DELETE' })`);
  await avaliar("irPara('catalogo')");
  caso('T31', 'Listas em conjunto e compartilhadas: dono convida e gera link; visitante só vê; participante adiciona; dono vê quem adicionou e desativa o link',
    convidou && chipGrupo && linkLista.startsWith(base + '/lista?c=') && visitaOk && visitanteSemLista && abriuObraDaVisita
    && membroNaLista && botoesMembro.join('|') === 'Participantes|Sair da lista' && mostraDono && membroAdicionou && donoVePor && desativou && linkMorto === 404,
    `convidou=${convidou}, ícone=${chipGrupo}, visitante vê=${visitaOk}/${visitanteSemLista}/${abriuObraDaVisita}, participante=${membroNaLista} [${botoesMembro.join(', ')}], popover=${mostraDono}, adicionou=${membroAdicionou}, dono vê "por"=${donoVePor}, link desativado=${desativou}/${linkMorto}`);
}

// ---------- Livros: filtro, ficha (autor, páginas, onde ler), "marcar como lido" e busca da tela inicial ----------
{
  await avaliar("irPara('catalogo')");
  await avaliar("document.querySelector('#filtro-tipo [data-tipo=livro]').click()");
  await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0", 5000);
  const livros = await avaliar("[...document.querySelectorAll('#grid-catalogo .card')].map(c => c.querySelector('.tipo')?.classList.contains('livro'))");
  const contador = await avaliar("Number(document.getElementById('stat-livro').textContent)");
  const umLivro = await avaliar("catalogo.find(o => tipoDe(o) === 'livro' && o.imagem)?.titulo");
  await avaliar(`abrirModal(${JSON.stringify(umLivro)})`);
  await aguardar("!document.querySelector('#modal-ficha .ficha-carregando')", 20000);
  await esperar(300);
  const ficha = await avaliar(`({
    fatos: [...document.querySelectorAll('#modal-ficha .ficha-fatos dt')].map(d => d.textContent),
    rotulo: document.querySelector('#modal-ficha .rotulo')?.textContent,
    lojas: [...document.querySelectorAll('#modal-ficha a.provedor b')].map(b => b.textContent),
    concluir: document.querySelector('#modal [data-concluir]')?.textContent.trim(),
    tipo: document.querySelector('#modal .tipo')?.textContent.trim() })`);
  await avaliar("document.getElementById('modal-ficha').scrollIntoView({ block: 'center' })");
  await esperar(300);
  await capturar('livro_modal');
  await avaliar('fecharModal()');
  await avaliar("document.querySelector('#filtro-tipo [data-tipo=TODOS]').click()");
  await capturar('catalogo_livros');
  caso('T34', 'Livros: filtro "Livros", ficha com autor, páginas e onde ler/comprar, e botão "Marcar como lido"',
    livros.length > 0 && livros.every(Boolean) && contador === livros.length && ficha.tipo === 'Livro'
    && ficha.fatos.includes('Páginas') && ficha.fatos.some(f => f.startsWith('Autor')) && ficha.rotulo === 'Onde ler ou comprar'
    && ficha.lojas.includes('Amazon') && ficha.concluir === 'Marcar como lido',
    `${livros.length} livros (contador ${contador}); "${umLivro}": ${ficha.fatos.join('/')} · ${ficha.lojas.join(', ')} · botão "${ficha.concluir}"`);
}

// ---------- Mesmo nome em mais de uma mídia: a busca pergunta qual (sem salvar nada até a escolha) ----------
{
  await avaliar("irPara('catalogo')");
  const antes = await avaliar("api('/api/obras').then(r => r.json()).then(l => l.length)");
  const jaTemDuna = await avaliar("catalogo.some(o => chaveBusca(o.titulo) === 'duna')");
  if (!jaTemDuna) {
    await avaliar("document.querySelector('#busca-hero-tipos [data-tipo=\"\"]').click()");
    await preencher('busca-hero-texto', 'Duna');
    await avaliar("document.getElementById('busca-hero').requestSubmit()");
    const perguntou = await aguardar("document.querySelectorAll('#dialogo [data-escolher-midia]').length >= 2", 60000);
    const opcoes = await avaliar("[...document.querySelectorAll('#dialogo [data-escolher-midia] .tipo')].map(e => e.textContent.trim())");
    await capturar('escolher_midia');
    await avaliar("[...document.querySelectorAll('#dialogo .dialogo-acoes .btn')].find(b => b.textContent.includes('Nenhum')).click()");
    const fechou = await aguardar("document.getElementById('dialogo-fundo').hidden", 3000);
    const depois = await avaliar("api('/api/obras').then(r => r.json()).then(l => l.length)");
    caso('T35', 'Busca "Qualquer": nome em mais de uma mídia abre a escolha (filme ou livro) e nada é salvo sem escolher',
      perguntou && opcoes.includes('Filme') && opcoes.includes('Livro') && fechou && depois === antes,
      `opções: ${opcoes.join(', ')}; obras antes/depois: ${antes}/${depois}`);
  }
}

// ---------- Mesmo com a mídia escolhida, a busca na web pede confirmação e mostra os títulos repetidos ----------
{
  await avaliar("irPara('catalogo')");
  const antes = await avaliar("api('/api/obras').then(r => r.json()).then(l => l.length)");
  await avaliar("document.querySelector('#busca-hero-tipos [data-tipo=\"livro\"]').click()");
  await preencher('busca-hero-texto', 'Duna');
  await avaliar("procurarNaWeb('Duna')");
  const abriu = await aguardar("document.querySelectorAll('#dialogo [data-escolher-midia]').length >= 2", 60000);
  const opcoes = await avaliar(`[...document.querySelectorAll('#dialogo [data-escolher-midia]')].map(b => ({
    tipo: b.querySelector('.tipo')?.textContent.trim(), titulo: b.querySelector('b')?.textContent.trim() }))`);
  await capturar('confirmar_livro');
  await avaliar("[...document.querySelectorAll('#dialogo .dialogo-acoes .btn')].find(b => /Nenhum|Não é/.test(b.textContent)).click()");
  const fechou = await aguardar("document.getElementById('dialogo-fundo').hidden", 3000);
  const depois = await avaliar("api('/api/obras').then(r => r.json()).then(l => l.length)");
  await avaliar("document.querySelector('#busca-hero-tipos [data-tipo=\"\"]').click(); document.getElementById('busca-hero-texto').value = ''");
  caso('T37', 'Busca com mídia escolhida (Livro) também pede confirmação e lista os títulos parecidos; nada é salvo sem escolher',
    abriu && opcoes.length >= 2 && opcoes.every(o => o.tipo === 'Livro') && fechou && depois === antes,
    `${opcoes.length} opções: ${opcoes.slice(0, 4).map(o => o.titulo).join(' | ')}; obras antes/depois: ${antes}/${depois}`);
}

// ---------- Comentários: quem só deu estrelas aparece; spoiler fica borrado até tocar ----------
{
  await avaliar("irPara('catalogo')");
  const alvo = await avaliar("catalogo.find(o => tipoDe(o) === 'serie')?.titulo");
  await avaliar(`abrirModal(${JSON.stringify(alvo)})`);
  await aguardar("!!document.getElementById('seletor-estrelas')", 10000);
  const minhaSoNota = "[...document.querySelectorAll('#aval-lista .so-nota')].some(li => li.querySelector('.voce'))";
  await avaliar("document.querySelector('#seletor-estrelas [data-estrela=\"3\"]').click()");
  const apareceuSoNota = await aguardar(minhaSoNota, 6000);
  // comenta com spoiler
  await preencher('comentario-texto', 'O vilão é o pai dela!');
  await avaliar("document.getElementById('comentario-spoiler').checked = true; document.getElementById('comentario-form').requestSubmit()");
  const publicou = await aguardar("!!document.querySelector('#aval-lista .comentario .spoiler')", 6000);
  const borrado = await avaliar("getComputedStyle(document.querySelector('#aval-lista .spoiler > p')).filter.includes('blur')");
  const soNotaSumiu = await avaliar(`!(${minhaSoNota})`);
  await avaliar("document.querySelector('#aval-lista .spoiler').scrollIntoView({ block: 'center' })");
  await esperar(300);
  await capturar('comentario_spoiler');
  await avaliar("document.querySelector('#aval-lista [data-revelar-spoiler]').click()");
  const revelado = await aguardar("getComputedStyle(document.querySelector('#aval-lista .spoiler > p')).filter === 'none'", 3000);
  // exclui o comentário: volta a aparecer só com a nota
  const btnExcluir = "[...document.querySelectorAll('#aval-lista [data-excluir-comentario]')].find(b => b.closest('.comentario').querySelector('.voce'))";
  await avaliar(`${btnExcluir}.click()`);
  await avaliar(`${btnExcluir}.click()`);
  const voltouSoNota = await aguardar(`!document.querySelector('#aval-lista .spoiler') && ${minhaSoNota}`, 6000);
  // no servidor também: outra pessoa vê a nota sem comentário
  const servidor = await avaliar(`api('/api/avaliacoes?titulo=' + encodeURIComponent(${JSON.stringify(alvo)})).then(r => r.json())
    .then(d => d.soNotas.some(n => n.meu && n.estrelas === 3) && !d.comentarios.some(c => c.meu))`);
  await avaliar("document.querySelector('[data-remover-nota]')?.click()");   // limpeza: tira a nota
  await aguardar(`!(${minhaSoNota})`, 6000);
  await avaliar('fecharModal()');
  caso('T38', 'Comentários: quem só deu estrelas aparece na lista; comentário com spoiler fica borrado até tocar',
    apareceuSoNota && publicou && borrado && soNotaSumiu && revelado && voltouSoNota && servidor,
    `só nota=${apareceuSoNota}, spoiler borrado=${publicou}/${borrado}, só nota some ao comentar=${soNotaSumiu}, revelou=${revelado}, volta ao excluir=${voltouSoNota}, servidor=${servidor}`);
}

// ---------- Encontrar pessoas: cartões um embaixo do outro, com o nome legível (regressão de CSS) ----------
{
  await avaliar("abrirPerfil(sessao.usuario)");
  await aguardar("!!document.querySelector('[role=tab][data-perfil-aba=\"pessoas\"]')", 8000);
  await avaliar("document.querySelector('[role=tab][data-perfil-aba=\"pessoas\"]').click()");
  await aguardar("document.querySelectorAll('#lista-pessoas .pessoa').length > 0", 8000);
  await capturar('encontrar_pessoas');
  const layout = await avaliar(`(() => { try {
    const ul = document.getElementById('lista-pessoas');
    const itens = [...ul.querySelectorAll('.pessoa')];
    const nomes = itens.map(li => li.querySelector('.pessoa-nome strong').getBoundingClientRect());
    return { display: getComputedStyle(ul).display, largura: Math.min(...itens.map(li => li.getBoundingClientRect().width)),
             empilhados: itens.every((li, i) => i === 0 || li.getBoundingClientRect().top > itens[i - 1].getBoundingClientRect().top),
             nomeEmUmaLinha: nomes.every(r => r.height < 30), qtd: itens.length };
  } catch (e) { return { erro: String(e), html: document.getElementById('aba-perfil')?.innerHTML.slice(0, 300) }; } })()`) || {};
  caso('T32', 'Encontrar pessoas: cartões empilhados e largos, nome em uma linha (sem quebrar letra por letra)',
    layout.display === 'grid' && layout.largura > 300 && layout.empilhados && layout.nomeEmUmaLinha, JSON.stringify(layout));
  await avaliar("irPara('catalogo')");
}

// ---------- Ficha da obra: onde assistir/jogar, duração, temporadas, tempo de jogo ----------
const fichaDe = async (titulo, nomeCaptura) => {
  await avaliar(`abrirModal(${JSON.stringify(titulo)})`);
  await aguardar("!document.querySelector('#modal-ficha .ficha-carregando')", 20000);
  await esperar(300);
  const r = await avaliar(`({
    fatos: [...document.querySelectorAll('#modal-ficha .ficha-fatos dt')].map(d => d.textContent),
    rotulo: document.querySelector('#modal-ficha .rotulo')?.textContent || '',
    links: [...document.querySelectorAll('#modal-ficha a.provedor')].map(a => ({ href: a.href, alvo: a.target, rel: a.rel })),
    texto: document.getElementById('modal-ficha').innerText.replace(/\\s+/g, ' ').slice(0, 160) })`);
  if (nomeCaptura) { await avaliar("document.getElementById('modal-ficha').scrollIntoView({ block: 'center' })"); await esperar(400); await capturar(nomeCaptura); }
  await avaliar("fecharModal()");
  return r;
};
const linksOk = l => l.every(a => /^https:\/\//.test(a.href) && a.alvo === '_blank' && a.rel.includes('noopener'));
const obraSerie = await avaliar("catalogo.find(o => tipoDe(o) === 'serie')?.titulo");
const fFilme = await fichaDe(obraFilme, 'ficha_filme');
const fSerie = obraSerie ? await fichaDe(obraSerie, 'ficha_serie') : { fatos: [], links: [] };
const fJogo = await fichaDe(obraJogo, 'ficha_jogo');
caso('T28', 'Ficha da obra: duração do filme, temporadas da série, tempo de jogo e onde assistir/jogar com links',
  fFilme.fatos.includes('Duração') && fFilme.rotulo === 'Onde assistir'
  && fSerie.fatos.includes('Temporadas') && fSerie.fatos.includes('Episódios')
  && fJogo.fatos.includes('Tempo para zerar') && fJogo.rotulo === 'Onde jogar' && fJogo.links.length > 0
  && linksOk([...fFilme.links, ...fSerie.links, ...fJogo.links]),
  `filme: ${fFilme.fatos.join('/')} + ${fFilme.links.length} serviço(s); série: ${fSerie.fatos.join('/')} + ${fSerie.links.length}; jogo: ${fJogo.fatos.join('/')} + ${fJogo.links.length} loja(s)`);

await cdp('Emulation.setDeviceMetricsOverride', { width: 400, height: 860, deviceScaleFactor: 1, mobile: true });
await cdp('Page.reload');
await aguardar("document.querySelectorAll('#grid-catalogo .card').length > 0");
await esperar(800);
const largura = await avaliar("({ scroll: document.documentElement.scrollWidth, tela: window.innerWidth })");
caso('T09', 'Sem rolagem horizontal em 400 px (RNF08)', largura.scroll <= largura.tela, `scrollWidth=${largura.scroll}, largura=${largura.tela}`);
if (process.env.OMNI_CAPTURAS) {
  await avaliar("document.querySelector('#grid-catalogo .card').click()");
  await aguardar("!!document.getElementById('seletor-estrelas')", 10000);
  await capturar('mobile_modal_topo');
  await avaliar("document.getElementById('modal-avaliacoes').scrollIntoView()");
  await esperar(400);
  await capturar('mobile_modal_avaliacoes');
  await avaliar("fecharModal()");
  await avaliar("abrirPerfil(sessao.usuario)");
  await aguardar("!!document.querySelector('.perfil-stats')", 8000);
  await esperar(500);
  await capturar('mobile_perfil');
  console.log('perfil 400px scrollWidth:', await avaliar("document.documentElement.scrollWidth + '/' + window.innerWidth"));
  await avaliar("irPara('catalogo')");
}

// ---------- Usuário comum não acessa a administração ----------
await avaliar("irPara('admin')");
const semAdminNaTela = await avaliar("document.getElementById('nav-admin').hidden && document.getElementById('aba-admin').hidden && !document.getElementById('aba-catalogo').hidden");
const statusAdmin = await avaliar(`Promise.all([
  fetch('/api/admin/usuarios', { headers: { Authorization: 'Bearer ${tokenTeste}' } }).then(r => r.status),
  fetch('/api/tendencias/filmes', { headers: { Authorization: 'Bearer ${tokenTeste}' } }).then(r => r.status),
  fetch('/api/completar-detalhes', { method: 'POST', headers: { Authorization: 'Bearer ${tokenTeste}' } }).then(r => r.status),
  fetch('/api/admin/usuarios', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${tokenTeste}' }, body: JSON.stringify({ usuario: 'nao_deve_existir', senha: 'senha-qualquer-1' }) }).then(r => r.status),
  fetch('/api/admin/obras?titulo=' + encodeURIComponent(catalogo[0].titulo), { method: 'DELETE', headers: { Authorization: 'Bearer ${tokenTeste}' } }).then(r => r.status)
])`);
await avaliar("abrirModal(catalogo[0].titulo)");
await aguardar("!!document.querySelector('#modal [data-concluir]')", 3000);
const semBotaoExcluir = await avaliar("!document.querySelector('#modal [data-excluir-obra]')");
await avaliar("fecharModal()");
caso('T18', 'Usuário comum não vê a Administração nem "Excluir obra" e o servidor recusa as rotas de admin (403)',
  semAdminNaTela && semBotaoExcluir && statusAdmin.every((x) => x === 403), `status: ${statusAdmin.join(', ')}; botão excluir oculto: ${semBotaoExcluir}`);

caso('T10', 'Nenhum erro de JavaScript no console nem bloqueio da Content-Security-Policy', errosConsole.length === 0, errosConsole.slice(0, 3).join(' | '));

// ---------- Sair e limpeza ----------
await cdp('Emulation.clearDeviceMetricsOverride');
await avaliar("document.getElementById('usuario-botao').click()");
await avaliar("[...document.querySelectorAll('#usuario-painel [role=menuitem]')].find(b => b.textContent.includes('Sair')).click()");
const voltouLogin = await aguardar("location.pathname === '/login.html'", 8000);
const statusAposSair = await avaliar(`fetch('/api/obras', { headers: { Authorization: 'Bearer ${tokenTeste}' } }).then(r => r.status)`);
caso('T13', 'Sair volta ao login e invalida o token no servidor', voltouLogin && statusAposSair === 401, `GET /api/obras com o token antigo: ${statusAposSair}`);

// Remove a conta criada pelo teste
const login = await avaliar(`fetch('/api/auth/entrar', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ usuario: '${usuarioTeste}', senha: ${JSON.stringify(senhaTeste)} }) }).then(r => r.json())`);
const excluida = await avaliar(`fetch('/api/auth/conta', { method: 'DELETE', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ${login?.token}' },
  body: JSON.stringify({ senha: ${JSON.stringify(senhaTeste)} }) }).then(r => r.status)`);
console.log(`conta de teste ${usuarioTeste} excluída: ${excluida === 204 ? 'sim' : 'não (' + excluida + ')'}`);

// ---------- Administrador (opcional): OMNI_ADMIN_SENHA=<senha do admin> ----------
if (process.env.OMNI_ADMIN_SENHA) {
  await cdp('Page.navigate', { url: new URL('/login.html', URL_APP).href });
  await aguardar("!!document.getElementById('form') && document.readyState === 'complete'");
  await preencher('usuario', process.env.OMNI_ADMIN_USUARIO || 'admin');
  await preencher('senha', process.env.OMNI_ADMIN_SENHA);
  await avaliar("document.getElementById('form').requestSubmit()");
  const entrouAdmin = await aguardar("location.pathname === '/' && !document.getElementById('nav-admin').hidden", 15000);
  await avaliar("irPara('admin')");
  const listou = await aguardar("[...document.querySelectorAll('#admin-usuarios .admin-usuario')].some(li => li.dataset.usuario === 'admin')", 8000);
  const novo = 'teste_adm_' + Math.random().toString(36).slice(2, 7);
  await preencher('admin-novo-usuario', novo);
  await avaliar("document.getElementById('admin-gerar-senha').click()");
  await avaliar("document.getElementById('admin-form').requestSubmit()");
  const criado = await aguardar(`[...document.querySelectorAll('#admin-usuarios .admin-usuario')].some(li => li.dataset.usuario === '${novo}' && li.textContent.includes('Comum'))`, 8000);
  await capturar('admin_painel');
  const senhaNovo = await avaliar("document.getElementById('admin-nova-senha').value");
  const btnExcluir = `document.querySelector('[data-excluir-usuario="${novo}"]')`;
  await avaliar(`${btnExcluir}.click()`);
  await avaliar(`${btnExcluir}.click()`);
  const excluido = await aguardar(`!${btnExcluir}`, 8000);
  const adminProtegido = await avaliar("!document.querySelector('[data-excluir-usuario=\"admin\"]')");
  caso('T19', 'Administrador vê os usuários, cria uma conta comum e a exclui', entrouAdmin && listou && criado && excluido && adminProtegido,
    `entrou=${entrouAdmin}, listou=${listou}, criou=${criado}, excluiu=${excluido}, admin protegido=${adminProtegido}`);
  await cdp('Emulation.setDeviceMetricsOverride', { width: 400, height: 860, deviceScaleFactor: 1, mobile: true });
  await esperar(500);
  const larguraAdmin = await avaliar("({ scroll: document.documentElement.scrollWidth, tela: window.innerWidth })");
  await capturar('admin_mobile');
  await cdp('Emulation.clearDeviceMetricsOverride');
  caso('T20', 'Administração sem rolagem horizontal em 400 px', larguraAdmin.scroll <= larguraAdmin.tela, `scrollWidth=${larguraAdmin.scroll}`);

  // Histórico: a conta criada e excluída no T19 aparece, com filtro por categoria e busca
  await avaliar("irPara('admin')");
  const eventosConta = `[...document.querySelectorAll('#historico-lista .evento')].filter(li => li.textContent.includes('${novo}')).map(li => li.querySelector('.evento-texto').textContent.replace(/\\s+/g, ' ').trim())`;
  const historicoInicial = await aguardar(`${eventosConta}.length >= 2`, 10000);
  await avaliar("document.querySelector('#historico-filtro [data-categoria=\"conta\"]').click()");
  const soContas = await aguardar("[...document.querySelectorAll('#historico-lista .evento')].length > 0 && [...document.querySelectorAll('#historico-lista .evento')].every(li => li.dataset.categoria === 'conta')", 8000);
  await preencher('historico-busca', novo);
  const buscou = await aguardar(`${eventosConta}.length === 2 && document.querySelectorAll('#historico-lista .evento').length === 2`, 8000);
  const frasesConta = await avaliar(eventosConta);
  await avaliar("document.getElementById('historico-lista').scrollIntoView({ block: 'center' })");
  await capturar('admin_historico');
  const autoria = await (async () => {
    await avaliar("abrirModal(catalogo[0].titulo)");
    const t = await aguardar("!!document.querySelector('#modal .obra-autoria')", 3000);
    const texto = await avaliar("document.querySelector('#modal .obra-autoria')?.textContent || ''");
    await avaliar('fecharModal()');
    return t && /Adicionada (ao acervo por|antes do histórico)/.test(texto);
  })();
  caso('T33', 'Histórico do administrador: registra quem fez o quê, filtra por categoria, busca e mostra quem adicionou a obra',
    historicoInicial && soContas && buscou && autoria
    && frasesConta.some(f => f.includes('criou a conta de ' + novo)) && frasesConta.some(f => f.includes('excluiu a conta de ' + novo)),
    `${frasesConta.join(' | ')}; filtro=${soContas}, busca=${buscou}, autoria na obra=${autoria}`);

  // Excluir obra pela janela (opcional, APAGA a obra): OMNI_OBRA_EXCLUIR="<título de uma obra de teste>"
  if (process.env.OMNI_OBRA_EXCLUIR) {
    const alvo = process.env.OMNI_OBRA_EXCLUIR;
    await avaliar("irPara('catalogo')");
    await aguardar(`catalogo.some(o => o.titulo === ${JSON.stringify(alvo)})`, 8000);
    await avaliar(`abrirModal(${JSON.stringify(alvo)})`);
    const temBotao = await aguardar("!!document.querySelector('#modal [data-excluir-obra]')", 3000);
    await avaliar("document.querySelector('#modal [data-excluir-obra]').click()");
    const confirmacao = await aguardar("!!document.getElementById('confirmar-excluir-obra')", 6000);
    const impactoTela = await avaliar("[...document.querySelectorAll('.impacto-exclusao li')].map(li => li.textContent.trim()).join(' · ')");
    await capturar('excluir_obra_confirmacao');
    await avaliar("document.getElementById('confirmar-excluir-obra').click()");
    const sumiu = await aguardar(`!catalogo.some(o => o.titulo === ${JSON.stringify(alvo)}) && !document.getElementById('modal-fundo').classList.contains('aberto')
      && ![...document.querySelectorAll('#grid-catalogo .card')].some(c => c.dataset.titulo === ${JSON.stringify(alvo)})`, 10000);
    const noServidor = await avaliar("api('/api/obras').then(r => r.json()).then(l => l.length)");
    caso('T27', 'Administrador exclui uma obra pela janela, com confirmação, e ela some do catálogo', temBotao && confirmacao && sumiu,
      `confirmação: "${impactoTela}"; obras no servidor: ${noServidor}`);
  }
  await avaliar("sair()");
  await aguardar("location.pathname === '/login.html'", 8000);
}

const resumo = { url: URL_APP, executado_em: new Date().toISOString(), aprovados: casos.filter((c) => c.ok).length, total: casos.length, casos };
writeFileSync(new URL('./teste_interface.json', import.meta.url), JSON.stringify(resumo, null, 2));
ws.close();
// Fecha o navegador inteiro pelo próprio protocolo (encerra todos os processos filhos)
try {
  const { webSocketDebuggerUrl } = await (await fetch(`http://127.0.0.1:${PORTA}/json/version`)).json();
  const wsBrowser = new WebSocket(webSocketDebuggerUrl);
  await new Promise((r) => wsBrowser.addEventListener('open', r, { once: true }));
  wsBrowser.send(JSON.stringify({ id: 1, method: 'Browser.close' }));
  await esperar(1500);
} catch { /* navegador já fechado */ }
encerrarNavegador();
await esperar(500);
try { rmSync(perfil, { recursive: true, force: true }); } catch { /* perfil ainda em uso */ }
console.log(`${resumo.aprovados}/${resumo.total} casos aprovados`);
process.exit(resumo.aprovados === resumo.total ? 0 : 1);
