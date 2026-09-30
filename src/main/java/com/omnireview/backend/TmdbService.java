package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

@Service
public class TmdbService {

    private final String API_KEY = "2b5854a03f31a4a3b4e7cd62c0e86f44"; 

    public String[] buscarFilmeNaInternet(String nomeDoFilme) {
        String url = "https://api.themoviedb.org/3/search/movie?query=" + nomeDoFilme + "&api_key=" + API_KEY + "&language=pt-BR";
        RestTemplate carteiro = new RestTemplate();
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resposta = carteiro.getForObject(url, Map.class);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultados = (List<Map<String, Object>>) resposta.get("results");
            
            Map<String, Object> primeiroFilme = resultados.get(0);
            
            String titulo = (String) primeiroFilme.get("title");
            String sinopse = (String) primeiroFilme.get("overview");
            
            
            return new String[]{titulo, sinopse};
            
        } catch (Exception e) {
            System.out.println("Ops! Falha ao organizar os dados: " + e.getMessage());
            return null;
        }
    }
  
    public List<String> buscarTendenciasFilmes() {
        RestTemplate carteiro = new RestTemplate();
        List<String> titulos = new java.util.ArrayList<>();
        try {
            for (int p = 1; p <= 3; p++) {
                String url = "https://api.themoviedb.org/3/trending/movie/week?api_key=" + API_KEY + "&language=pt-BR&page=" + p;
                Map<String, Object> resposta = carteiro.getForObject(url, Map.class);
                List<Map<String, Object>> resultados = (List<Map<String, Object>>) resposta.get("results");
                for (Map<String, Object> item : resultados) {
                    if (titulos.size() < 50) titulos.add((String) item.get("title")); // Filmes usam "title"
                }
            }
            return titulos;
        } catch (Exception e) { return titulos; }
    }

    
   public String[] buscarSerieNaInternet(String nomeSerie) {
        RestTemplate carteiro = new RestTemplate();
        try {
            String url = "https://api.themoviedb.org/3/search/tv?api_key=" + API_KEY + "&query=" + java.net.URLEncoder.encode(nomeSerie, "UTF-8") + "&language=pt-BR";
            Map<String, Object> resposta = carteiro.getForObject(url, Map.class);
            List<Map<String, Object>> resultados = (List<Map<String, Object>>) resposta.get("results");
            if (resultados != null && !resultados.isEmpty()) {
                Map<String, Object> serie = resultados.get(0);
                String titulo = (String) serie.get("name");
                String overview = (String) serie.get("overview");
                String sinopse = (overview != null && !overview.isEmpty()) ? overview : "Sem sinopse disponível.";
                return new String[]{titulo, sinopse};
            }
        } catch (Exception e) {}
        return null;
    }
    public List<String> buscarTendenciasSeries() {
        RestTemplate carteiro = new RestTemplate();
        List<String> titulos = new ArrayList<>();
        try {
            for (int p = 1; p <= 3; p++) {
                String url = "https://api.themoviedb.org/3/trending/tv/week?api_key=" + API_KEY + "&language=pt-BR&page=" + p;
                Map<String, Object> resposta = carteiro.getForObject(url, Map.class);
                List<Map<String, Object>> resultados = (List<Map<String, Object>>) resposta.get("results");
                if (resultados != null) {
                    for (Map<String, Object> item : resultados) {
                        String nomeSerie = (String) item.get("name");
                        if (nomeSerie != null && titulos.size() < 50) {
                            titulos.add(nomeSerie);
                        }
                    }
                }
            }
            return titulos;
        } catch (Exception e) { 
            return titulos; 
        }
    }
}   