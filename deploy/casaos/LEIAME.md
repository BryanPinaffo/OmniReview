# OmniReview no CasaOS

O app tem três containers: **neo4j** (banco, sem portas abertas), **restaurar-banco** (carrega o banco exportado
na primeira vez) e **omnireview** (o site, na porta **8090** do servidor). A imagem do site é publicada
automaticamente em `ghcr.io/bryanpinaffo/omnireview` pelo GitHub Actions a cada atualização do back-end.
O acesso público é feito pelo **Tailscale Funnel** (HTTPS), então nenhuma porta precisa ser aberta no roteador.

Os dados ficam em `/DATA/AppData/omnireview/` (padrão do CasaOS):

| Pasta | Conteúdo |
|---|---|
| `neo4j/data` | Banco de dados |
| `neo4j/logs` | Logs do Neo4j |
| `backup` | Coloque aqui o `neo4j.dump` para restaurar |

## 1. Levar o banco atual (antes de instalar)

No CasaOS, abra **Files** e crie a pasta `/DATA/AppData/omnireview/backup`. Envie para ela o arquivo
`neo4j.dump` (no notebook: `C:\Users\Bryan\Downloads\OmniReview_implantacao\neo4j.dump`).

> Faça isso **antes** de instalar o app: na primeira vez que ele sobe, o container `restaurar-banco` carrega o
> arquivo e cria a marca `.omnireview-restaurado`, para nunca mais sobrescrever o banco.

## 2. Instalar o app

1. CasaOS → **App Store** → botão **+** (canto superior direito) → **Instalação personalizada** → **Importar**.
2. Cole o conteúdo do arquivo já preenchido com as suas senhas:
   `C:\Users\Bryan\Downloads\OmniReview_implantacao\omnireview-casaos.yml`
   (o modelo sem senhas é o `deploy/casaos/docker-compose.yml` deste repositório).
3. Confira a porta do site (**8090**; se estiver ocupada, troque só o número da esquerda) e clique em **Instalar**.
4. Aguarde o ícone do OmniReview ficar verde e abra: `http://<ip-do-servidor>:8090`.

Entre com o usuário **admin** e a senha que você já usava no site (o banco restaurado mantém a senha atual;
a senha do arquivo só vale se a conta ainda não existir).

## 3. Publicar na internet (Tailscale Funnel)

No terminal do servidor (SSH ou o terminal do CasaOS):

```bash
sudo tailscale funnel --bg 8090
tailscale funnel status          # mostra o endereço público https://...ts.net
```

Na primeira vez o Tailscale mostra um link para ativar o Funnel e o HTTPS no seu tailnet: abra, confirme e rode o
comando de novo. Se o Tailscale estiver instalado como app do CasaOS (em container), use
`docker exec <nome-do-container-do-tailscale> tailscale funnel --bg 8090`.

Teste o endereço no celular **com o Wi-Fi desligado**.

## Atualizar o site

Cada atualização no GitHub gera uma imagem nova. No CasaOS, abra as configurações do app OmniReview e use
**Verificar atualizações / Atualizar** (ou, no terminal: `docker pull ghcr.io/bryanpinaffo/omnireview:latest`
e reinicie o app). O banco não é afetado.

## Problemas comuns

- **"pull access denied" ao instalar**: o pacote da imagem no GitHub ainda está privado. Em
  github.com/BryanPinaffo → **Packages** → **omnireview** → **Package settings** → **Change visibility** → **Public**.
- **Obras novas só com gêneros, sem tags da IA**: o site não alcançou o Ollama. Confira `OMNIREVIEW_OLLAMA_URL`
  (o IP do Tailscale do servidor funciona de dentro do container; outra opção é `http://172.17.0.1:11434`).
- **Restaurar de novo** (substitui o banco do servidor): pare o app, apague o arquivo
  `/DATA/AppData/omnireview/neo4j/data/.omnireview-restaurado`, coloque o novo `neo4j.dump` em `backup` e inicie o app.
- **Esqueci a senha do admin**: nas configurações do app, adicione a variável `OMNIREVIEW_ADMIN_FORCARSENHA=true`
  no container omnireview, salve (o app reinicia), entre com a senha do arquivo e depois remova a variável.
- **Ver logs**: CasaOS → app OmniReview → **Logs**, ou `docker logs -f omnireview-omnireview-1`.
