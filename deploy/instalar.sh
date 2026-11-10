#!/usr/bin/env bash
# Instala (ou atualiza) o OmniReview num servidor Linux e publica pelo Tailscale Funnel.
#
# Uso, a partir da pasta do projeto clonado:
#   sudo bash deploy/instalar.sh                          # instala ou atualiza (mantém o banco)
#   sudo bash deploy/instalar.sh --restaurar neo4j.dump   # também carrega o banco exportado (APAGA o banco do servidor)
#   sudo bash deploy/instalar.sh --sem-funnel             # não publica na internet (só testa localmente)
#
# Pode ser executado de novo depois de "git pull" para atualizar: recompila e reinicia o serviço.
set -euo pipefail

# Porta local do site (o público acessa pelo Funnel, em HTTPS na 443). Se não for informada com PORTA=...,
# reaproveita a da instalação anterior ou procura a primeira livre entre 8090 e 8099.
PORTA="${PORTA:-}"
APP=/opt/omnireview
SERVICO=/etc/systemd/system/omnireview.service
IMAGEM_NEO4J="neo4j:2026.08.1"
VOLUME_DADOS="omnireview_neo4j_dados"

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_ARQ="$RAIZ/deploy/docker-compose.servidor.yml"
ENV_ARQ="$RAIZ/deploy/.env"
SEGREDOS="$RAIZ/backend-java/config/segredos.properties"

RESTAURAR=""
FUNNEL=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --restaurar) RESTAURAR="$(realpath "${2:?informe o arquivo .dump}")"; shift 2 ;;
        --sem-funnel) FUNNEL=0; shift ;;
        *) echo "Opção desconhecida: $1"; exit 1 ;;
    esac
done

passo() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }
ok()    { printf '    \033[32m✔\033[0m %s\n' "$*"; }
aviso() { printf '    \033[33m!\033[0m %s\n' "$*"; }
falha() { printf '\n\033[31mERRO:\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || falha "rode com sudo: sudo bash deploy/instalar.sh"
USUARIO_DONO="${SUDO_USER:-root}"

# ------------------------------------------------------------------ pré-requisitos
passo "Verificando pré-requisitos"
command -v docker >/dev/null || falha "Docker não encontrado. Instale: https://docs.docker.com/engine/install/"
if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose); \
elif command -v docker-compose >/dev/null; then COMPOSE=(docker-compose); \
else falha "Docker Compose não encontrado (pacote docker-compose-plugin)."; fi
ok "Docker e Compose"

versao_java() { java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
if ! command -v java >/dev/null || [[ "$(versao_java)" -lt 17 ]] || ! command -v javac >/dev/null; then
    if command -v apt-get >/dev/null; then
        aviso "Instalando o Java 17 (JDK)…"
        apt-get update -qq && apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
    else
        falha "Instale o Java 17 ou mais novo (JDK) e rode de novo."
    fi
fi
ok "Java $(versao_java)"

command -v tailscale >/dev/null || falha "Tailscale não encontrado neste servidor."
if ! command -v curl >/dev/null; then
    command -v apt-get >/dev/null || falha "Instale o curl e rode de novo."
    apt-get install -y -qq curl >/dev/null
fi
ok "Tailscale e curl"

# ------------------------------------------------------------------ segredos
passo "Conferindo a configuração (backend-java/config/segredos.properties)"
if [[ ! -f "$SEGREDOS" ]]; then
    cp "$RAIZ/backend-java/config/segredos.exemplo.properties" "$SEGREDOS"
    falha "Criei $SEGREDOS a partir do modelo. Preencha (ou copie o do notebook) e rode de novo."
fi
grep -qE '=(SENHA_DO_NEO4J|SUA_CHAVE_TMDB|SENHA_DO_ADMIN)$' "$SEGREDOS" \
    && falha "$SEGREDOS ainda tem valores de exemplo. Preencha antes de continuar."
ler() { sed -n "s/^$1=//p" "$SEGREDOS" | tail -1 | tr -d '\r'; }
SENHA_NEO4J="$(ler 'spring\.neo4j\.authentication\.password')"
[[ -n "$SENHA_NEO4J" ]] || falha "spring.neo4j.authentication.password está vazio em $SEGREDOS."
URL_OLLAMA="$(ler 'omnireview\.ollama\.url')"
ok "segredos.properties preenchido"

# .env do Compose com a mesma senha do Neo4j (arquivo ignorado pelo Git)
umask 077
printf 'NEO4J_PASSWORD=%s\n' "$SENHA_NEO4J" > "$ENV_ARQ"
umask 022

if [[ -n "$URL_OLLAMA" ]] && curl -fsS -m 5 "$URL_OLLAMA/api/tags" >/dev/null 2>&1; then
    ok "Ollama respondendo em $URL_OLLAMA"
else
    aviso "Ollama não respondeu em ${URL_OLLAMA:-(não configurado)}. O site funciona, mas obras novas ficam só com os gêneros oficiais."
fi

# ------------------------------------------------------------------ Neo4j
dc() { "${COMPOSE[@]}" -f "$COMPOSE_ARQ" --env-file "$ENV_ARQ" "$@"; }

esperar_neo4j() {
    for _ in $(seq 1 60); do
        dc exec -T neo4j cypher-shell -u neo4j -p "$SENHA_NEO4J" "RETURN 1" >/dev/null 2>&1 && return 0
        sleep 2
    done
    falha "o Neo4j não respondeu. Veja: ${COMPOSE[*]} -f $COMPOSE_ARQ logs neo4j"
}

passo "Subindo o Neo4j ($IMAGEM_NEO4J, acessível só por 127.0.0.1)"
dc up -d neo4j
esperar_neo4j
ok "Neo4j no ar"

if [[ -n "$RESTAURAR" ]]; then
    passo "Restaurando o banco a partir de $RESTAURAR"
    [[ -f "$RESTAURAR" ]] || falha "arquivo não encontrado: $RESTAURAR"
    aviso "O banco atual do servidor será substituído pelo do arquivo."
    systemctl stop omnireview 2>/dev/null || true
    TMP="$(mktemp -d)"
    cp "$RESTAURAR" "$TMP/neo4j.dump"
    chmod 755 "$TMP"; chmod 644 "$TMP/neo4j.dump"
    dc stop neo4j
    docker run --rm -v "$VOLUME_DADOS:/data" -v "$TMP:/backups" "$IMAGEM_NEO4J" \
        neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true
    rm -rf "$TMP"
    dc start neo4j
    esperar_neo4j
    CONTAGEM="$(dc exec -T neo4j cypher-shell -u neo4j -p "$SENHA_NEO4J" --format plain \
        "MATCH (o:Obra) WITH count(o) AS o MATCH (u:Usuario) RETURN o + ' obras, ' + count(u) + ' usuários'" | tail -1)"
    ok "Banco restaurado: $CONTAGEM"
fi

# ------------------------------------------------------------------ build
passo "Compilando o back-end (pode levar alguns minutos na primeira vez)"
cd "$RAIZ/backend-java"
chmod +x mvnw
sudo -u "$USUARIO_DONO" ./mvnw -q -DskipTests package
JAR=""
for f in target/backend-*.jar; do [[ -f "$f" ]] && JAR="$f"; done   # o .jar.original não casa com *.jar
[[ -n "$JAR" ]] || falha "o .jar não foi gerado."
ok "Gerado $JAR"

# ------------------------------------------------------------------ instalação
passo "Instalando em $APP e criando o serviço omnireview"
id omnireview >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin omnireview
install -d -o root -g omnireview -m 750 "$APP" "$APP/config"
install -o root -g omnireview -m 640 "$JAR" "$APP/omnireview.jar"
install -o root -g omnireview -m 640 "$SEGREDOS" "$APP/config/segredos.properties"

# Escolha da porta (com o serviço parado, para a porta dele não contar como ocupada)
systemctl stop omnireview 2>/dev/null || true
porta_ocupada() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
if [[ -z "$PORTA" && -f "$SERVICO" ]]; then
    PORTA="$(sed -n 's/.*--server\.port=\([0-9]*\).*/\1/p' "$SERVICO" | head -1)"
    [[ -n "$PORTA" ]] && porta_ocupada "$PORTA" && PORTA=""   # a anterior foi tomada por outro programa
fi
if [[ -z "$PORTA" ]]; then
    for p in $(seq 8090 8099); do
        if ! porta_ocupada "$p"; then PORTA="$p"; break; fi
    done
    [[ -n "$PORTA" ]] || falha "nenhuma porta livre entre 8090 e 8099. Escolha uma: sudo PORTA=9000 bash deploy/instalar.sh"
elif porta_ocupada "$PORTA"; then
    falha "a porta $PORTA já está em uso por outro programa. Escolha outra: sudo PORTA=9000 bash deploy/instalar.sh"
fi
ok "Porta local do site: $PORTA"

sed -e "s|__PORTA__|$PORTA|" -e "s|/usr/bin/java|$(readlink -f "$(command -v java)")|" \
    "$RAIZ/deploy/omnireview.service" > "$SERVICO"
systemctl daemon-reload
systemctl enable omnireview >/dev/null 2>&1
systemctl restart omnireview

for _ in $(seq 1 90); do
    curl -fsS -o /dev/null "http://127.0.0.1:$PORTA/login.html" && break
    sleep 2
done
curl -fsS -o /dev/null "http://127.0.0.1:$PORTA/login.html" \
    || falha "o back-end não subiu. Veja: journalctl -u omnireview -n 80 --no-pager"
ok "Back-end respondendo em http://127.0.0.1:$PORTA (só local)"

# ------------------------------------------------------------------ Tailscale Funnel
if [[ $FUNNEL -eq 1 ]]; then
    passo "Publicando com o Tailscale Funnel (HTTPS)"
    aviso "Na primeira vez o Tailscale mostra um link para ativar o Funnel: abra, ative e aguarde aqui."
    if tailscale funnel --bg "$PORTA"; then
        ok "Funnel ativo"
    else
        falha "o Funnel não foi ativado. Abra o link acima (ou https://login.tailscale.com/admin/settings/features), \
ative HTTPS e Funnel para este servidor e rode o script de novo."
    fi
    ENDERECO="$(tailscale funnel status 2>/dev/null | grep -oE 'https://[^ ]+' | head -1 || true)"
fi

passo "Pronto!"
if [[ $FUNNEL -eq 1 ]]; then
    echo "    Endereço público: ${ENDERECO:-veja com: tailscale funnel status}"
fi
echo "    Logs do site:      journalctl -u omnireview -f"
echo "    Reiniciar o site:  sudo systemctl restart omnireview"
echo "    Tirar do ar:       sudo tailscale funnel reset"
echo "    Atualizar:         git pull && sudo bash deploy/instalar.sh"
