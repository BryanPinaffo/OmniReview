# OmniReview no CasaOS

O app tem dois containers: **omnireview-neo4j** (banco, sem portas abertas) e **omnireview** (o site, na porta
**18090** do servidor). A imagem do site é publicada automaticamente em `ghcr.io/bryanpinaffo/omnireview` pelo
GitHub Actions a cada atualização do back-end. O acesso público é feito pelo **Tailscale Funnel** (HTTPS), então
nenhuma porta precisa ser aberta no roteador.

Os dados ficam em `/DATA/AppData/omnireview/` (padrão do CasaOS):

| Pasta | Conteúdo |
|---|---|
| `neo4j/data` | Banco de dados |
| `neo4j/logs` | Logs do Neo4j |
| `backup` | Onde colocar o `neo4j.dump` para restaurar |

## 1. Levar o banco atual (opcional, antes de instalar)

Em **Files**, envie o `neo4j.dump` para `/DATA/AppData/omnireview/backup` e, no terminal do servidor:

```bash
sudo mkdir -p /DATA/AppData/omnireview/neo4j/data
sudo docker run --rm -v /DATA/AppData/omnireview/neo4j/data:/data -v /DATA/AppData/omnireview/backup:/backups \
  neo4j:2026.08.1 neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true
```

Sem esse passo, o app começa com o banco vazio (é só importar as tendências pela Administração).

## 2. Instalar o app

1. CasaOS → **App Store** → botão **+** (canto superior direito) → **Instalação personalizada** → **Importar**.
2. Cole o conteúdo do arquivo já preenchido com as suas senhas:
   `C:\Users\Bryan\Downloads\OmniReview_implantacao\omnireview-casaos.yml`
   (o modelo sem senhas é o `deploy/casaos/docker-compose.yml` deste repositório).
3. Confira a porta do site (**18090**; se estiver ocupada, troque só o número da esquerda) e clique em **Instalar**.
4. O OmniReview aparece como um app (com ícone) na tela inicial; clique nele para abrir.

Entre com o usuário **admin** e a senha que você já usava no site (o banco restaurado mantém a senha atual;
a senha do arquivo só vale se a conta ainda não existir).

> Na primeira vez o site pode levar até 1 minuto para responder: ele espera o Neo4j terminar de iniciar.

## 3. Publicar na internet (Tailscale Funnel)

O Tailscale do CasaOS roda em container, então os comandos são executados dentro dele:

```bash
TS=$(sudo docker ps --format '{{.Names}}' | grep -i tailscale | head -1)
sudo docker exec "$TS" tailscale funnel --bg http://172.17.0.1:18090
sudo docker exec "$TS" tailscale funnel status      # mostra o endereço público https://...ts.net
```

Na primeira vez o Tailscale mostra um link para ativar o Funnel e o HTTPS no seu tailnet: abra, confirme e rode o
comando de novo. Teste o endereço no celular **com o Wi-Fi desligado e o app do Tailscale desconectado**.

| O quê | Comando |
|---|---|
| Tirar da internet (o site continua rodando) | `sudo docker exec "$TS" tailscale funnel --https=443 off` |
| Só para quem está no seu Tailscale | `sudo docker exec "$TS" tailscale serve --bg http://172.17.0.1:18090` |
| Ligar / desligar o site | botões do app no painel do CasaOS |

## Atualizar o site

Cada atualização no GitHub gera uma imagem nova. No terminal: `sudo docker pull ghcr.io/bryanpinaffo/omnireview:latest`
e, no CasaOS, reinicie o app OmniReview (ou use **Verificar atualizações**). O banco não é afetado.

## Problemas comuns

- **Erro 502 ao clicar em Instalar**: rode `sudo docker pull neo4j:2026.08.1 && sudo docker pull ghcr.io/bryanpinaffo/omnireview:latest`
  e tente de novo. Se continuar, veja o motivo com `sudo journalctl -u casaos-app-management --no-pager -n 40`.
- **Obras novas só com gêneros, sem tags da IA**: o site não alcançou o Ollama. Confira `OMNIREVIEW_OLLAMA_URL`
  (o IP do Tailscale do servidor funciona de dentro do container; outra opção é `http://172.17.0.1:11434`).
- **Esqueci a senha do admin**: nas configurações do app, adicione a variável `OMNIREVIEW_ADMIN_FORCARSENHA=true`
  no container omnireview, salve (o app reinicia), entre com a senha do arquivo e depois remova a variável.
- **Ver logs**: CasaOS → app OmniReview → **Logs**, ou `sudo docker logs -f omnireview`.
