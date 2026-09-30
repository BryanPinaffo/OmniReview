package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.util.*;

@Service
public class IaService {

    private final String IP_DO_SERVIDOR = "100.68.96.53"; 
    private final String OLLAMA_URL = "http://" + IP_DO_SERVIDOR + ":11434/api/generate";

    
    private final List<String> TAXONOMIA_OFICIAL = Arrays.asList(
        "Sombrio", "Inspirador", "Sentimental", "Tenso", "Empolgante", "Nostálgico", "Escêntrico",
        "Reviravolta no Enredo", "Narrativa Não-Linear", "Antologia", "Baseado em Fatos Reais", "Final aberto",
        "Ficção Científica", "Cyberpunk", "Steampunk", "Ópera Espacial", "Pós-Apocalíptico", "Distopia", "Invasão Alienígena",
        "Terror", "Slasher", "Terror Psicológico", "Gore", "Sobrenatural", "Horror Corporal", "Trash",
        "Suspense", "Suspense Psicológico", "Neo-Noir", "Mistério", "Policial", "Crime Organizado",
        "Romance", "Comédia Romântica", "Romance Dramático", "Amor Proibido", "Histórias de Época",
        "Fantasia", "Musical", "Guerra", "Faroeste", "Documentário", "Animação", "Anime",
        "Simulação e Gerenciamento", "Simulador de Vida", "Construção de Cidades", "Simulador de Fazenda", "Tycoon", "Automação",
        "Aventura e Casual", "Quebra-cabeça", "Point and Click", "Simulador de Caminhada", "Visual Novel", "Arcade", "Ritmo",
        "Survival Horror", "Jumpscares", "Sobrevivência em Mundo Aberto",
        "2D", "3D", "Isométrico", "Pixel Art", "Low-Poly", "Fotorrealista", "Visão Superior", "Gráficos Estilizados",
        "Zumbis", "Piratas", "Segunda Guerra Mundial", "Mitologia", "Vampiros", "Robôs",
        "Infantil", "Família", "Adolescente", "Adulto", "Hardcore",
        "Blockbuster", "Indie", "Underground"
    );

    public String extrairDnaCriativo(String sinopse) {
        RestTemplate carteiro = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String listaPermitidaStr = String.join(", ", TAXONOMIA_OFICIAL);

        String prompt = 
                        "Responde APENAS com os nomes separados por vírgula, sem texto adicional. " +
                        "pegue as tags com base nessas fornecidas:"+ listaPermitidaStr +"use elas como base para analisar a sinopse"+ "Analise todas as sinopse detalhadamente de forma as tags façam sentido com a sinopse informada"+
                        "Sinopse: " + sinopse;

        Map<String, Object> corpoPedido = new HashMap<>();
        corpoPedido.put("model", "llama3.2:1b"); 
        corpoPedido.put("prompt", prompt);
        corpoPedido.put("stream", false); 

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(corpoPedido, headers);

        try {
            Map<String, Object> resposta = carteiro.postForObject(OLLAMA_URL, request, Map.class);
            
            if (resposta != null && resposta.containsKey("response")) {
                String textoGerado = (String) resposta.get("response");
                return normalizarETravarTaxonomia(textoGerado);
            } else {
                return "Deu erro";
            }
            
        } catch (Exception e) {
            System.out.println("Erro 404/500 no Ollama: Verifique a porta e o modelo. Detalhe: " + e.getMessage());
            return "Deu erro";
        }
    }
    private String limparEFormatarResposta(String respostaIA) {

        String limpo = respostaIA.replace("\n", "").replace("Tags:", "").trim();
        
        if (limpo.isEmpty()) {
            return "Deu erro";
        }
        
        String[] tags = limpo.split(",");
        List<String> listaFinal = new ArrayList<>();
        
        for (int i = 0; i < Math.min(tags.length, 4); i++) {
            String t = tags[i].trim();
            if (!t.isEmpty()) {
                
                listaFinal.add(t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase());
            }
        }
        
        return listaFinal.isEmpty() ? "Deu erro" : String.join(", ", listaFinal);
    }


    private String normalizarETravarTaxonomia(String respostaIA) {
        List<String> tagsValidas = new ArrayList<>();
        
        String[] partes = respostaIA.split(",");
        for (String parte : partes) {
            String candidato = parte.trim().toLowerCase();
            
            for (String oficial : TAXONOMIA_OFICIAL) {
                if (oficial.toLowerCase().equals(candidato)) {
                    if (!tagsValidas.contains(oficial)) {
                        tagsValidas.add(oficial);
                    }
                    break;
                }
            }
        }
        
        if (tagsValidas.isEmpty()) {
            return "Deu erro";
        }
        if (tagsValidas.size() > 4) {
            tagsValidas = tagsValidas.subList(0, 4);
        }
        
        return String.join(", ", tagsValidas);
    }
}