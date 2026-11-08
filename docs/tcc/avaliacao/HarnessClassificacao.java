import com.omnireview.backend.IaService;
import com.omnireview.backend.RawgService;
import com.omnireview.backend.SteamService;
import com.omnireview.backend.TmdbService;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Harness de avaliação do classificador (usa as classes de produção, sem Spring).
 * Para cada obra do conjunto de referência:
 *  - busca título/sinopse/gêneros na API da mídia (TMDB ou RAWG->Steam) e mede o tempo;
 *  - executa a etapa do LLM N vezes (mede latência e estabilidade);
 *  - registra as tags do LLM, os gêneros oficiais e o resultado combinado (pipeline de produção).
 * Saída: um JSON por linha (JSONL).
 *
 * Uso: java -cp <classpath> HarnessClassificacao segredos.properties conjunto_referencia.csv saida.jsonl 2
 */
public class HarnessClassificacao {

    public static void main(String[] args) throws Exception {
        Properties cfg = new Properties();
        try (var in = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            cfg.load(in);
        }
        List<String> linhas = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        int execucoes = args.length > 3 ? Integer.parseInt(args[3]) : 2;

        IaService ia = new IaService(cfg.getProperty("omnireview.ollama.url"), cfg.getProperty("omnireview.ollama.modelo", "llama3.2:3b"));
        TmdbService tmdb = new TmdbService(cfg.getProperty("omnireview.tmdb.api-key"));
        RawgService jogos = new RawgService(new SteamService(), cfg.getProperty("omnireview.rawg.api-key"));

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8))) {
            for (String linha : linhas.subList(1, linhas.size())) {
                String[] c = linha.split(";");
                String titulo = c[0], tipo = c[1];

                long t0 = System.nanoTime();
                String[] dados = switch (tipo) {
                    case "Filme" -> tmdb.buscarFilmeNaInternet(titulo);
                    case "Serie" -> tmdb.buscarSerieNaInternet(titulo);
                    default -> jogos.buscarJogoNaInternet(titulo);
                };
                double msBusca = (System.nanoTime() - t0) / 1e6;

                String sinopse = dados != null ? dados[1] : null;
                String generosTexto = dados != null && dados.length > 2 ? dados[2] : "";
                List<String> generos = IaService.extrairTagsValidas(generosTexto);

                StringBuilder sb = new StringBuilder();
                sb.append("{\"titulo\":").append(json(titulo))
                  .append(",\"tipo\":").append(json(tipo))
                  .append(",\"ms_busca_api\":").append(String.format(Locale.ROOT, "%.1f", msBusca))
                  .append(",\"generos_api\":").append(json(String.join(", ", generos)))
                  .append(",\"tem_sinopse\":").append(sinopse != null && !sinopse.isBlank() && !sinopse.startsWith("Sem sinopse"))
                  .append(",\"execucoes\":[");
                for (int i = 0; i < execucoes; i++) {
                    long t1 = System.nanoTime();
                    List<String> doLlm = ia.classificarComLlm(titulo, sinopse, tipo, generos);
                    List<String> finais = IaService.combinar(doLlm, generos);
                    double ms = (System.nanoTime() - t1) / 1e6;
                    if (i > 0) sb.append(',');
                    sb.append("{\"llm\":").append(json(String.join(", ", doLlm)))
                      .append(",\"tags\":").append(json(finais.isEmpty() ? IaService.SEM_CLASSIFICACAO : String.join(", ", finais)))
                      .append(",\"ms\":").append(String.format(Locale.ROOT, "%.1f", ms)).append('}');
                }
                sb.append("]}");
                out.println(sb);
                out.flush();
                System.out.println("OK " + titulo);
            }
        }
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return b.append('"').toString();
    }
}
