# OmniReview

Plataforma de recomendação **transmídia** de filmes, séries e jogos. Cada obra recebe um *DNA criativo*
(1 a 4 tags de uma taxonomia de 179 tags), gerado por um classificador híbrido (gêneros oficiais das APIs +
LLM local via Ollama). Obras e tags ficam no Neo4j como `(:Obra)-[:TEM_TAG]->(:Tag)`, e a recomendação é feita
por travessia no grafo com peso IDF, intercalando filmes, séries e jogos.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `backend-java/` | API Spring Boot 4.1 (Java 17) + interface web em `src/main/resources/static/index.html` |
| `frontend-react/` | Protótipo mobile em React (Vite) |
| `docs/tcc/` | Documentação do TCC (DOCX/PDF), diagramas UML (PlantUML), scripts e resultados da avaliação |
| `docker-compose.yml` | Neo4j |

## Como executar

1. **Neo4j:** copie `.env.exemplo` para `.env`, defina `NEO4J_PASSWORD` e rode `docker compose up -d`
2. **Segredos:** copie `backend-java/config/segredos.exemplo.properties` para
   `backend-java/config/segredos.properties` e preencha a senha do Neo4j, as chaves do TMDB e da RAWG e a URL do
   Ollama. Esse arquivo é ignorado pelo Git. Também é possível usar variáveis de ambiente
   (`OMNIREVIEW_TMDB_API_KEY`, `OMNIREVIEW_RAWG_API_KEY`, `OMNIREVIEW_OLLAMA_URL`, `SPRING_NEO4J_AUTHENTICATION_PASSWORD`).
3. **Ollama:** `ollama pull llama3.2:3b` no servidor de IA.
4. **Back-end:** `cd backend-java && ./mvnw spring-boot:run` e abra <http://localhost:8080>.
   Na primeira execução, o grafo de tags é criado automaticamente a partir das obras existentes.
   A página inicial leva à tela de login (`/login.html`). A conta de administrador é criada ao iniciar, com o usuário e a
   senha definidos em `omnireview.admin.usuario` / `omnireview.admin.senha` no `segredos.properties`; outras pessoas
   usam **Criar conta** (usuário comum) ou recebem uma conta criada pelo administrador.
5. **Protótipo React (opcional):** `cd frontend-react && npm install && npm run dev`.

## Login

- Usuário: 3 a 30 caracteres (letras minúsculas, números, `.`, `-`, `_`); senha: 8 a 128 caracteres.
- Senhas guardadas apenas como hash PBKDF2-HMAC-SHA256 (310.000 iterações, sal aleatório) em nós `(:Usuario)` no Neo4j.
- Sessão por token aleatório de 256 bits, válido por 12 horas (guardado em memória: reiniciar o servidor exige novo login).
- Após 5 senhas erradas, o usuário fica bloqueado por 15 minutos. Mensagens de erro não revelam se o usuário existe.
- Toda a API (exceto cadastrar e entrar) exige o cabeçalho `Authorization: Bearer <token>`.
- Os favoritos ficam no navegador, separados por usuário.
- **Redefinir senha** (menu do avatar ou perfil): qualquer usuário, inclusive o administrador, troca a própria senha
  informando a atual; as outras sessões dele são encerradas. O administrador também pode gerar uma nova senha para um
  usuário comum na aba Administração.

## "O que você quer assistir hoje?"

Campo de busca na tela inicial. Enquanto a pessoa digita, aparecem as obras do acervo que combinam. Se o título não
estiver no acervo, ele é buscado na web (filmes e séries no TMDB, jogos na RAWG/Steam, em paralelo), o resultado mais
parecido com o que foi digitado é classificado pela IA e adicionado automaticamente, e a janela da obra abre em seguida.
Os botões Filme/Série/Jogo restringem a busca a uma mídia (útil quando o mesmo nome existe em mais de uma).

## Administrador e usuários comuns

| | Administrador | Usuário comum |
|---|---|---|
| Catálogo, busca, favoritos, recomendações, notas e comentários | ✔ | ✔ |
| Adicionar obra pela busca "O que você quer assistir hoje?" | ✔ | ✔ |
| Perfil, seguir pessoas, marcar obras como assistidas/terminadas, redefinir a própria senha | ✔ | ✔ |
| Aba **Administração**: ver todos os usuários, criar, excluir e redefinir a senha de contas comuns | ✔ | — |
| Adicionar obra manualmente, importar tendências, manutenção do acervo | ✔ | — |

- A conta de administrador vem da configuração (fora do Git) e é criada no primeiro início; se alguém tiver se
  cadastrado antes com o mesmo nome, a configuração prevalece e as sessões dessa conta são encerradas. Depois disso a
  senha redefinida pelo site é mantida; para voltar à senha do arquivo (senha esquecida), use
  `omnireview.admin.forcar-senha=true` e reinicie (depois volte para `false`).
- O cadastro público e o painel só criam usuários comuns. Contas de administrador não podem ser excluídas pelo site.
- A interface esconde as opções, mas quem barra é o servidor: as rotas de administração respondem 403 para usuários comuns.

## Minhas listas

- Listas pessoais do que a pessoa quer ver ou jogar. Toda conta começa com **Quero assistir** e **Quero jogar**
  (criadas uma única vez; podem ser renomeadas ou excluídas) e pode criar outras (até 30, nome de até 40 caracteres).
- Na janela de qualquer obra, **Salvar em lista** abre um menu para marcar/desmarcar listas ou criar uma nova já com a obra.
- A aba **Minhas listas** mostra cada lista com filtro por mídia, a opção de ocultar o que já foi visto/jogado,
  e permite remover obras, renomear e excluir a lista. Cada usuário só vê e altera as próprias listas.
- No grafo: `(:Usuario)-[:CRIOU]->(:Lista)-[:CONTEM]->(:Obra)`; excluir a conta apaga as listas.

## Perfil e seguidores

- Aba **Perfil**: foto, capa de fundo e bio (as imagens são recortadas e reduzidas no navegador; só JPG, PNG ou WebP),
  e os números de jogos jogados, filmes assistidos, séries assistidas, reviews, seguidores e seguindo.
- Cada obra tem o botão **Marcar como assistido** (filmes e séries) ou **Terminei o jogo** (jogos); as obras marcadas
  ganham um selo no card e aparecem em "Concluídos" no perfil.
- Review = obra que a pessoa avaliou com estrelas e/ou comentou (conta uma vez por obra).
- Clique no nome de quem comentou para abrir o perfil; em "Encontrar pessoas" dá para buscar e seguir outros usuários.
- No grafo: `(:Usuario)-[:CONCLUIU]->(:Obra)` e `(:Usuario)-[:SEGUE]->(:Usuario)`.

## Avaliações e comentários

- Ao abrir uma obra, o usuário dá de 1 a 5 estrelas (uma nota por obra; clicar de novo troca a nota) e pode removê-la.
- A média aparece ao lado do título, no card e na janela da obra, com a distribuição das notas; o catálogo pode ser
  ordenado por "Melhor avaliadas".
- Comentários de até 1000 caracteres, exibidos do mais recente ao mais antigo com a nota do autor; só o autor exclui o próprio.
- No grafo: `(:Usuario)-[:AVALIOU {estrelas}]->(:Obra)` e `(:Usuario)-[:ESCREVEU]->(:Comentario)-[:SOBRE]->(:Obra)`.
  Excluir a conta apaga as notas e os comentários do usuário.

## API

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/cadastrar` | Cria a conta (`{"usuario", "senha"}`) e devolve o token |
| POST | `/api/auth/entrar` | Login (`{"usuario", "senha"}`) e devolve o token |
| GET | `/api/auth/eu` | Usuário da sessão atual |
| POST | `/api/auth/sair` | Encerra a sessão |
| DELETE | `/api/auth/conta` | Exclui a conta (`{"senha"}`) |
| GET | `/api/avaliacoes/resumo` | Média e total de notas de cada obra avaliada |
| GET | `/api/avaliacoes?titulo=...` | Média, distribuição, sua nota e comentários de uma obra |
| POST | `/api/avaliacoes/nota` | Dá ou troca a nota (`{"titulo", "estrelas"}`, 1 a 5) |
| DELETE | `/api/avaliacoes/nota?titulo=...` | Remove a sua nota |
| POST | `/api/avaliacoes/comentarios` | Publica um comentário (`{"titulo", "texto"}`) |
| DELETE | `/api/avaliacoes/comentarios/{id}` | Exclui um comentário seu |
| GET | `/api/admin/usuarios` | (admin) Lista os usuários com papel e atividade |
| POST | `/api/admin/usuarios` | (admin) Cria um usuário comum (`{"usuario", "senha"}`) |
| DELETE | `/api/admin/usuarios/{usuario}` | (admin) Exclui um usuário comum, com notas e comentários |
| POST | `/api/auth/senha` | Redefine a própria senha (`{"senhaAtual", "novaSenha"}`) |
| POST | `/api/admin/usuarios/{usuario}/senha` | (admin) Define uma nova senha para um usuário comum (`{"novaSenha"}`) |
| GET | `/api/perfil/{usuario}` | Perfil: números, concluídos, reviews, foto, capa e bio |
| POST | `/api/perfil/eu` | Atualiza foto, capa de fundo e/ou bio (`""` remove) |
| GET | `/api/perfil/eu/resumo` | Sua foto e os títulos que você concluiu |
| POST / DELETE | `/api/perfil/concluidas` | Marca / desmarca obra como assistida ou terminada |
| POST / DELETE | `/api/perfil/{usuario}/seguir` | Segue / deixa de seguir |
| GET | `/api/perfil/{usuario}/seguidores` · `/seguindo` | Listas de seguidores e de quem a pessoa segue |
| GET | `/api/perfil?q=...` | Busca pessoas pelo nome de usuário |
| GET | `/api/listas` | Suas listas com as obras (cria as padrão no primeiro acesso) |
| POST | `/api/listas` | Cria uma lista (`{"nome"}`) |
| POST / DELETE | `/api/listas/{id}` | Renomeia (`{"nome"}`) / exclui a lista |
| POST / DELETE | `/api/listas/{id}/obras` | Adiciona (`{"titulo"}`) / remove (`?titulo=`) uma obra |
| GET | `/api/obras` | Catálogo completo |
| GET | `/api/recomendar?tags=Ação&tags=RPG&excluir=Título` | Recomendações transmídia |
| POST | `/api/adicionar/{filme\|serie\|jogo}?nome=...` | (admin) Busca a obra, gera o DNA e salva (404 se não encontrar) |
| POST | `/api/buscar-ou-adicionar?nome=...[&tipo=filme\|serie\|jogo]` | Busca da tela inicial: devolve a obra do acervo ou a busca na web, gera o DNA e adiciona (`{"obra", "adicionada"}`) |
| GET | `/api/tendencias/{filmes\|series\|jogos}` | (admin) Títulos em alta |
| POST | `/api/completar-detalhes` | (admin) Preenche sinopses e imagens faltantes |
| POST | `/api/reprocessar-dna[?todas=true]` | (admin) Refaz as tags que falharam (ou todas) |

## Testes e avaliação

```bash
cd backend-java && ./mvnw test                       # testes JUnit
node docs/tcc/avaliacao/teste_interface.mjs          # testes de interface (Edge/Chrome); OMNI_ADMIN_SENHA=... inclui os do admin
python docs/tcc/avaliacao/avaliar.py                 # métricas de tags, recomendação e desempenho
python docs/tcc/gerar_documento.py                   # gera a documentação do TCC
```
