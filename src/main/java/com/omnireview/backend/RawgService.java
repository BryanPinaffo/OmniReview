package com.omnireview.backend;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.List;

@Service
public class RawgService {

    // chave da RAWG 
    private final String API_KEY = "a672fd8101fa44bb956f3db93491dde3"; 

    public String[] buscarJogoNaInternet(String nomeDoJogo) {
        RestTemplate carteiro = new RestTemplate();
        
        try {
            String urlBusca = "https://api.rawg.io/api/games?search=" + nomeDoJogo + "&key=" + API_KEY;
            @SuppressWarnings("unchecked")
            Map<String, Object> respostaBusca = carteiro.getForObject(urlBusca, Map.class);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultados = (List<Map<String, Object>>) respostaBusca.get("results");
            Integer idJogo = (Integer) resultados.get(0).get("id");

            String urlDetalhes = "https://api.rawg.io/api/games/" + idJogo + "?key=" + API_KEY;
            @SuppressWarnings("unchecked")
            Map<String, Object> detalhes = carteiro.getForObject(urlDetalhes, Map.class);
            
            String titulo = (String) detalhes.get("name");
            String sinopse = (String) detalhes.get("description_raw");
            
            // Devolvemos o título e a sinopse
            return new String[]{titulo, sinopse};
            
        } catch (Exception e) {
            System.out.println("Ops! Falha ao buscar o jogo: " + e.getMessage());
            return null;
        }
    }
    public List<String> buscarTendencias() {
        RestTemplate carteiro = new RestTemplate();
        // Busca 50 jogos 
        String url = "https://api.rawg.io/api/games?key=" + API_KEY + "&ordering=-rating&page_size=50";
        try {
            Map<String, Object> resposta = carteiro.getForObject(url, Map.class);
            List<Map<String, Object>> resultados = (List<Map<String, Object>>) resposta.get("results");
            List<String> titulos = new java.util.ArrayList<>();
            for(Map<String, Object> jogo : resultados) {
                titulos.add((String) jogo.get("name"));
            }
            return titulos;
        } catch (Exception e) { return new java.util.ArrayList<>(); }
    }
}