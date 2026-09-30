package com.omnireview.backend;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RecomendacaoController {

    private final ObraRepository repository;
    private final TmdbService tmdbService;
    private final RawgService rawgService;
    private final IaService iaService;

    public RecomendacaoController(ObraRepository repository, TmdbService tmdbService, RawgService rawgService, IaService iaService) {
        this.repository = repository;
        this.tmdbService = tmdbService;
        this.rawgService = rawgService;
        this.iaService = iaService;
    }

    // Rota 1: Adicionar qualquer filme
    @PostMapping("/adicionar/filme")
    public Obra adicionarFilme(@RequestParam String nome) {
        System.out.println("A procurar filme: " + nome);
        String[] dados = tmdbService.buscarFilmeNaInternet(nome);
        
        if (dados != null) {
            String dna = iaService.extrairDnaCriativo(dados[1]);
            Obra novaObra = new Obra(dados[0], "Filme", dna.trim());
            return repository.save(novaObra);
        }
        return null;
    }

   
    @PostMapping("/adicionar/jogo")
    public Obra adicionarJogo(@RequestParam String nome) {
        System.out.println("A procurar jogo: " + nome);
        String[] dados = rawgService.buscarJogoNaInternet(nome);
        
        if (dados != null) {
            String dna = iaService.extrairDnaCriativo(dados[1]);
            Obra novaObra = new Obra(dados[0], "Jogo", dna.trim());
            return repository.save(novaObra);
        }
        return null;
    }
    

    @PostMapping("/adicionar/serie")
    public Obra adicionarSerie(@RequestParam String nome) {
        System.out.println("A procurar série: " + nome);
        String[] dados = tmdbService.buscarSerieNaInternet(nome);
        
        if (dados != null) {
            String dna = iaService.extrairDnaCriativo(dados[1]);
            Obra novaObra = new Obra(dados[0], "Serie", dna.trim());
            return repository.save(novaObra);
        }
        return null;
    }

    
    @GetMapping("/recomendar")
    public List<Obra> buscarRecomendacoes(@RequestParam String dna, @RequestParam String titulo) {
        return repository.recomendarTransmidia(dna, titulo);
    }
 @GetMapping("/obras")
    public List<Obra> listarTodasObras() {
        return repository.findAll(); 
    }

    @GetMapping("/tendencias/filmes")
    public List<String> tendenciasFilmes() { 
        return tmdbService.buscarTendenciasFilmes(); 
    }

    @GetMapping("/tendencias/jogos")
    public List<String> tendenciasJogos() { 
        return rawgService.buscarTendencias(); 
    }

    @GetMapping("/tendencias/series")
    public List<String> tendenciasSeries() { 
        
        return tmdbService.buscarTendenciasSeries(); 
    }
}