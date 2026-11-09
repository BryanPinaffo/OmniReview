# Imagem do OmniReview (back-end + interface web). Publicada em ghcr.io/bryanpinaffo/omnireview
# pelo GitHub Actions (.github/workflows/imagem-docker.yml) para amd64 e arm64.
#
# A configuração vem de variáveis de ambiente (nada de segredos dentro da imagem):
#   SPRING_NEO4J_URI, SPRING_NEO4J_AUTHENTICATION_PASSWORD, OMNIREVIEW_TMDB_API_KEY, OMNIREVIEW_RAWG_API_KEY, OMNIREVIEW_GOOGLEBOOKS_API_KEY,
#   OMNIREVIEW_OLLAMA_URL, OMNIREVIEW_ADMIN_USUARIO, OMNIREVIEW_ADMIN_SENHA

# 1) Compila o .jar na arquitetura de quem está construindo (o .jar serve para qualquer arquitetura)
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp target/backend-*.jar /app.jar   # o .jar.original não casa com *.jar

# 2) Imagem final só com o Java de execução
FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.source="https://github.com/BryanPinaffo/OmniReview" \
      org.opencontainers.image.title="OmniReview" \
      org.opencontainers.image.description="Recomendação transmídia de filmes, séries e jogos (Spring Boot + Neo4j + Ollama)"
RUN useradd --system --no-create-home --shell /usr/sbin/nologin omnireview
WORKDIR /app
COPY --from=build /app.jar /app/omnireview.jar
USER omnireview
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-jar", "/app/omnireview.jar"]
