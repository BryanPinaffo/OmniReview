# Colocar o OmniReview online (servidor Linux + Tailscale Funnel)

O site roda no servidor (Neo4j no Docker, back-end como serviço do sistema e o Ollama que já está lá) e é
publicado pelo **Tailscale Funnel**, que dá um endereço público com HTTPS, por exemplo
`https://bryan-server-7010.<seu-tailnet>.ts.net`. Só o site fica público: o Neo4j escuta apenas em `127.0.0.1`
e o back-end também, recebendo as visitas pelo Funnel.

## O que o `instalar.sh` faz

1. Confere Docker, Docker Compose, Java 17 (instala pelo `apt` se faltar), Tailscale e `curl`.
2. Confere o `backend-java/config/segredos.properties` e testa se o Ollama responde.
3. Sobe o Neo4j (`deploy/docker-compose.servidor.yml`): versão fixa, porta só local e volume com nome.
4. Com `--restaurar arquivo.dump`, carrega o banco exportado do notebook.
5. Compila o back-end, instala em `/opt/omnireview` e cria o serviço `omnireview` (sobe sozinho ao ligar o servidor).
6. Ativa o Funnel na porta 8080 e mostra o endereço público.

Pode ser rodado de novo a qualquer momento para atualizar (mantém o banco, a não ser que use `--restaurar`).

## Passo a passo

### 1. No notebook (PowerShell): enviar o banco e a configuração para o servidor

Os arquivos já estão em `C:\Users\Bryan\Downloads\OmniReview_implantacao` (fora do Git, pois têm senhas e chaves).

```powershell
tailscale file cp "C:\Users\Bryan\Downloads\OmniReview_implantacao\neo4j.dump" bryan-server-7010:
tailscale file cp "C:\Users\Bryan\Downloads\OmniReview_implantacao\segredos.properties" bryan-server-7010:
```

### 2. No servidor (terminal ou SSH)

```bash
# Receber os arquivos enviados pelo Tailscale
mkdir -p ~/omnireview-arquivos && sudo tailscale file get ~/omnireview-arquivos

# Baixar o projeto
git clone https://github.com/BryanPinaffo/OmniReview.git
cd OmniReview
cp ~/omnireview-arquivos/segredos.properties backend-java/config/

# Instalar, restaurar o banco e publicar
sudo bash deploy/instalar.sh --restaurar ~/omnireview-arquivos/neo4j.dump
```

Na primeira vez, o Tailscale mostra um link para **ativar o Funnel** (e o HTTPS) no seu tailnet: abra o link,
confirme e aguarde; o script continua sozinho. No fim ele mostra o endereço público.

### 3. Testar

Abra o endereço no celular **com o Wi-Fi desligado** (assim você confirma que funciona de fora da sua rede).

### 4. Depois que estiver no ar

- **Troque a senha do admin** pelo site (menu do avatar → Redefinir senha).
- Apague os arquivos copiados: `rm -r ~/omnireview-arquivos` (o `segredos.properties` já foi instalado em `/opt/omnireview`).
- A partir de agora, o banco "oficial" é o do servidor. O que for feito no site local do notebook não aparece online.

## Comandos úteis no servidor

| O quê | Comando |
|---|---|
| Ver os logs do site | `journalctl -u omnireview -f` |
| Reiniciar o site | `sudo systemctl restart omnireview` |
| Atualizar para a versão do GitHub | `cd OmniReview && git pull && sudo bash deploy/instalar.sh` |
| Ver o endereço público | `tailscale funnel status` |
| Tirar o site da internet (continua rodando local) | `sudo tailscale funnel reset` |
| Publicar de novo | `sudo tailscale funnel --bg 8080` |
| Fazer backup do banco | `docker compose -f deploy/docker-compose.servidor.yml --env-file deploy/.env stop neo4j && docker run --rm -v omnireview_neo4j_dados:/data -v "$PWD":/backups neo4j:2026.08.1 neo4j-admin database dump neo4j --to-path=/backups && docker compose -f deploy/docker-compose.servidor.yml --env-file deploy/.env start neo4j` |

## Problemas comuns

- **"Ollama não respondeu"**: o site funciona, mas obras novas ficam só com os gêneros oficiais. Confira se o
  Ollama está rodando (`systemctl status ollama`) e se a `omnireview.ollama.url` do `segredos.properties` está certa.
- **Porta 8080 ocupada no servidor**: use outra, por exemplo `sudo PORTA=8090 bash deploy/instalar.sh`.
- **O site não sobe**: `journalctl -u omnireview -n 80 --no-pager` mostra o erro (normalmente senha do Neo4j ou
  arquivo de segredos).
- **Esqueci a senha do admin**: coloque `omnireview.admin.forcar-senha=true` em `/opt/omnireview/config/segredos.properties`,
  rode `sudo systemctl restart omnireview`, entre com a senha do arquivo e depois volte a opção para `false`.
