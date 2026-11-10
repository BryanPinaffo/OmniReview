package com.omnireview.backend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IgdbServiceTest {

    @Test
    void converteSegundosEmHorasDasMedidasQueExistem() {
        // formato de game_time_to_beats: segundos para zerar e quantos jogadores informaram
        Map<String, Object> h = IgdbService.horas(Map.of("hastily", 98_000, "normally", 151_200, "completely", 226_800, "count", 412));
        assertEquals(Map.of("historia", 27L, "extras", 42L, "completo", 63L, "respostas", 412), h);
        assertEquals(Map.of("historia", 1L, "respostas", 5), IgdbService.horas(Map.of("hastily", 600, "count", 5)), "menos de 1 h vira 1 h");
        assertTrue(IgdbService.horas(Map.of("count", 3)).isEmpty(), "sem medidas, nada é mostrado");
    }

    @Test
    void descartaTemposAbsurdosInformadosPorPoucosJogadores() {
        // casos reais do acervo
        assertTrue(IgdbService.horas(Map.of("completely", 720_000, "count", 1)).isEmpty(), "ARK: 1 resposta só");
        assertTrue(IgdbService.horas(Map.of("hastily", 360_000, "normally", 32_400, "count", 2)).isEmpty(), "Call of Duty: 2 respostas");
        assertEquals(Map.of("historia", 6L, "extras", 6L, "respostas", 8),
                IgdbService.horas(Map.of("hastily", 21_600, "normally", 21_600, "completely", 5_601_600, "count", 8)), "PEAK: 100% em 1.556 h sai");
        assertTrue(IgdbService.horas(Map.of("hastily", 1_260_000, "count", 7)).isEmpty(), "R.E.P.O.: história em 350 h sai");
        assertEquals(Map.of("historia", 10L, "respostas", 4),
                IgdbService.horas(Map.of("hastily", 36_000, "normally", 18_000, "count", 4)), "extras menor que a história sai");
        // dados bons continuam: Resident Evil Requiem, 36 jogadores
        assertEquals(Map.of("historia", 8L, "extras", 13L, "completo", 30L, "respostas", 36),
                IgdbService.horas(Map.of("hastily", 28_800, "normally", 46_800, "completely", 108_000, "count", 36)));
    }

    @Test
    void escolheOJogoOriginalENaoOModComMesmoNome() {
        // resultados reais do IGDB para "Hollow Knight"
        var jogos = java.util.List.<Map<String, Object>>of(
                Map.of("id", 365702, "name", "Hollow Knight", "game_type", 5),
                Map.of("id", 14593, "name", "Hollow Knight", "game_type", 0, "total_rating_count", 2282),
                Map.of("id", 115289, "name", "Hollow Knight: Silksong", "game_type", 0, "total_rating_count", 528));
        assertEquals(14593, IgdbService.escolherJogo("Hollow Knight", jogos).get("id"));
        assertEquals(115289, IgdbService.escolherJogo("Hollow Knight: Silksong", jogos).get("id"));
    }

    @Test
    void semCredenciaisFicaDesligadoSemChamarNada() {
        IgdbService igdb = new IgdbService("", "");
        assertFalse(igdb.disponivel());
        assertNull(igdb.tempoParaZerar("Hollow Knight"));
        assertNull(igdb.pesquisar("Hades", 5), "null = indisponível: o JogosService usa a Steam");
        assertNull(igdb.jogo(113112));
        assertNull(igdb.idPelaSteam("1145360"));
        assertNull(igdb.jogandoAgora(15));
    }

    @Test
    void jogandoAgoraSemEdicaoRepetidaNemJogoSoDeLive() {
        // ordem real do "Playing" do IGDB (nov/2026)
        var ranking = IgdbService.ranking(java.util.List.of(
                Map.of("id", 1, "name", "Roblox", "platforms", java.util.List.of(6, 34, 39)),
                Map.of("id", 2, "name", "Minecraft", "platforms", java.util.List.of(6, 130)),
                Map.of("id", 3, "name", "Minecraft: Java Edition", "platforms", java.util.List.of(6, 14, 3)),
                Map.of("id", 4, "name", "Pokémon Community Game", "platforms", java.util.List.of(82)),
                Map.of("id", 5, "name", "Marbles on Stream", "platforms", java.util.List.of(6, 34, 39)),
                Map.of("id", 6, "name", "Hades", "platforms", java.util.List.of(6))), 4);
        assertEquals(java.util.List.of("Roblox", "Minecraft", "Marbles on Stream", "Hades"), ranking.stream().map(r -> r[1]).toList());
        assertEquals("igdb:1", ranking.get(0)[0]);
        assertFalse(IgdbService.edicaoDeOutro("Minecraft: Java Edition", java.util.Set.of("Roblox")), "sem o jogo base, a edição fica");
        assertFalse(IgdbService.edicaoDeOutro("Hollow Knight: Silksong", java.util.Set.of("Hollow Knight")), "continuação não é edição");
    }

    private static Map<String, Object> jogo(int id, String nome, int avaliacoes, int espera) {
        return Map.of("id", id, "name", nome, "total_rating_count", avaliacoes, "hypes", espera);
    }

    @Test
    void buscaTiraJogosDesconhecidosQuandoHaConhecidosComONome() {
        // resultados reais do IGDB: a busca por relevância devolvia o obscuro "Cyberpunk" antes do Cyberpunk 2077
        var cyberpunk = IgdbService.ordenar("cyberpunk", java.util.List.of(
                jogo(1, "Cyberpunk", 0, 0), jogo(2, "Cyberpunk Madness", 0, 0), jogo(3, "Cyberpunk 2", 0, 9),
                jogo(4, "Cyberpunk 2077", 1685, 1038), jogo(5, "VA-11 Hall-A: Cyberpunk Bartender Action", 159, 2)));
        assertEquals("Cyberpunk 2077", cyberpunk.get(0).get("name"));
        assertTrue(cyberpunk.stream().noneMatch(j -> j.get("name").equals("Cyberpunk")), "desconhecido sai mesmo com o nome igual");
        assertTrue(cyberpunk.stream().anyMatch(j -> j.get("name").equals("Cyberpunk 2")), "com gente esperando (hypes) fica");

        // "gta": nenhum conhecido tem "gta" no nome, mas a busca do IGDB entende o apelido
        var gta = IgdbService.ordenar("gta", java.util.List.of(
                jogo(1, "Grand Theft Auto: San Andreas", 4112, 2), jogo(2, "GTA: La Heist", 0, 0),
                jogo(3, "Grand Theft Auto V", 6051, 0), jogo(4, "Grand Theft Auto VI", 0, 1142)));
        assertEquals("Grand Theft Auto V", gta.get(0).get("name"));
        assertEquals(3, gta.size(), "GTA: La Heist (ninguém avaliou) sai");

        // nome exato de um jogo pequeno fica, se nenhum conhecido tiver o texto no nome
        var indie = IgdbService.ordenar("moon sweepers", java.util.List.of(
                jogo(1, "Moonlighter", 900, 0), jogo(2, "Moon Sweepers", 0, 0)));
        assertTrue(indie.stream().anyMatch(j -> j.get("name").equals("Moon Sweepers")));

        // "zelda": o mais conhecido que contém o nome ganha do pouco conhecido que começa com ele
        var zelda = IgdbService.ordenar("zelda", java.util.List.of(
                jogo(1, "Zelda II: The Adventure of Link", 308, 0), jogo(2, "Zelda", 0, 0),
                jogo(3, "The Legend of Zelda: Breath of the Wild", 3030, 142), jogo(4, "The Legend of Zelda", 739, 0)));
        assertEquals("The Legend of Zelda: Breath of the Wild", zelda.get(0).get("name"));
        assertEquals(3, zelda.size(), "o obscuro \"Zelda\" sai");

        // nome igual ganha de "começa com", entre conhecidos
        var hollow = IgdbService.ordenar("hollow knight", java.util.List.of(
                jogo(1, "Hollow Knight: Silksong", 528, 3000), jogo(2, "Hollow Knight", 2282, 0)));
        assertEquals("Hollow Knight", hollow.get(0).get("name"));
    }

    // Formato real de um jogo do IGDB (Hades), com os campos de IgdbService.CAMPOS_JOGO
    private static Map<String, Object> hades() {
        Map<String, Object> j = new java.util.HashMap<>();
        j.put("id", 113112);
        j.put("name", "Hades");
        j.put("summary", "Defy the god of the dead as you hack and slash out of the Underworld.");
        j.put("first_release_date", 1600300800);
        j.put("genres", java.util.List.of(Map.of("slug", "hack-and-slash-beat-em-up"), Map.of("slug", "role-playing-rpg"), Map.of("slug", "indie")));
        j.put("themes", java.util.List.of(Map.of("slug", "action"), Map.of("slug", "fantasy")));
        j.put("game_modes", java.util.List.of(Map.of("slug", "single-player")));
        j.put("platforms", java.util.List.<Map<String, Object>>of(Map.of("id", 169, "abbreviation", "Series X|S"), Map.of("id", 48, "abbreviation", "PS4"),
                Map.of("id", 6, "abbreviation", "PC"), Map.of("id", 39, "abbreviation", "iOS"), Map.of("id", 167, "abbreviation", "PS5"),
                Map.of("id", 14, "abbreviation", "Mac"), Map.of("id", 49, "abbreviation", "XONE"), Map.of("id", 130, "abbreviation", "Switch")));
        j.put("cover", Map.of("image_id", "cob9kr"));
        j.put("artworks", java.util.List.of(Map.of("image_id", "ar17ut")));
        j.put("websites", java.util.List.<Map<String, Object>>of(
                Map.of("type", 22, "url", "https://www.xbox.com/en-us/games/store/hades/9P8DL6W0JBB8"),
                Map.of("type", 24, "url", "https://www.nintendo.com/games/detail/hades-switch/"),
                Map.of("type", 23, "url", "https://store.playstation.com/en-us/concept/10002648"),
                Map.of("type", 13, "url", "https://store.steampowered.com/app/1145360"),
                Map.of("type", 16, "url", "https://www.epicgames.com/store/en-US/product/hades/home"),
                Map.of("type", 1, "url", "https://www.supergiantgames.com/games/hades")));   // site oficial: não é loja
        j.put("external_games", java.util.List.<Map<String, Object>>of(
                Map.of("external_game_source", 1, "uid", "1145360", "url", "https://store.steampowered.com/app/1145360"),
                Map.of("external_game_source", 20, "uid", "B09BNLT4BT", "url", "https://amazon.jp/dp/B09BNLT4BT")));
        return j;
    }

    @Test
    void dadosDoJogoNoFormatoDoCatalogo() {
        String[] d = IgdbService.dadosDoJogo(hades());
        assertEquals("Hades", d[0]);
        assertTrue(d[1].startsWith("Defy the god"), "o resumo do IGDB vem em inglês (a Steam ou a IA põem em português)");
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/cob9kr.jpg", d[3]);
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p/ar17ut.jpg", d[4]);
        assertEquals("igdb:113112", d[5]);
        assertEquals("2020", d[6]);
        assertEquals("steam:1145360", d[7], "o id da Steam reconhece o mesmo jogo que veio do Top 15");
        for (String tag : d[2].split(", ")) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(tag), tag);
        assertTrue(d[2].contains("Hack and Slash") && d[2].contains("RPG") && d[2].contains("Fantasia") && d[2].contains("Um Jogador"), d[2]);
    }

    @Test
    void todasAsTagsDoIgdbExistemNaTaxonomia() {
        String[] slugs = {"point-and-click", "fighting", "shooter", "music", "platform", "puzzle", "racing", "real-time-strategy-rts",
                "role-playing-rpg", "simulator", "sport", "strategy", "turn-based-strategy-tbs", "tactical", "hack-and-slash-beat-em-up",
                "quiz-trivia", "adventure", "indie", "arcade", "visual-novel", "moba", "action", "fantasy", "science-fiction", "horror",
                "thriller", "survival", "historical", "stealth", "comedy", "business", "drama", "sandbox", "kids", "open-world", "warfare",
                "party", "mystery", "romance", "single-player", "multiplayer", "co-operative", "split-screen",
                "massively-multiplayer-online-mmo", "battle-royale", "virtual-reality", "bird-view-slash-isometric"};
        var lista = java.util.Arrays.stream(slugs).map(s -> Map.of("slug", s)).toList();
        String tags = IgdbService.generosParaTaxonomia(Map.of("genres", lista,
                "player_perspectives", java.util.List.of(Map.of("slug", "first-person"))));
        for (String tag : tags.split(", ")) assertTrue(IaService.TAXONOMIA_OFICIAL.contains(tag), tag);
        assertTrue(tags.contains("FPS"), "shooter em primeira pessoa");
        assertFalse(IgdbService.generosParaTaxonomia(Map.of("themes", java.util.List.of(Map.of("slug", "erotic")))).contains("Erótico"));
    }

    @Test
    void plataformasComNomesCurtosNaOrdemDeSempre() {
        assertEquals("PC, Mac, PS5, PS4, Xbox Series X|S, Xbox One, Switch, iOS", IgdbService.plataformas(hades().get("platforms"), 12));
        assertEquals("PC · Mac · PS5 · PS4 · +4", IgdbService.plataformas(hades().get("platforms"), 4, " · "));
        assertEquals("NES", IgdbService.plataformas(java.util.List.of(Map.of("id", 18, "abbreviation", "NES")), 5), "antigas pela abreviação");
        assertNull(IgdbService.plataformas(java.util.List.of(), 5));
    }

    @Test
    void lojasDeCadaPlataformaNasVersoesBrasileiras() {
        var lojas = IgdbService.lojas(hades(), "Hades");
        var nomes = lojas.stream().map(l -> l.get("nome")).toList();
        assertEquals(java.util.List.of("Steam", "Epic Games Store", "PlayStation Store", "Xbox", "Nintendo eShop"), nomes,
                "uma vez cada loja, sem o site oficial nem a Amazon do Japão");
        Map<Object, Object> links = new java.util.HashMap<>();
        lojas.forEach(l -> links.put(l.get("nome"), l.get("link")));
        assertEquals("https://www.xbox.com/pt-BR/games/store/hades/9P8DL6W0JBB8", links.get("Xbox"));
        assertEquals("https://store.playstation.com/pt-br/concept/10002648", links.get("PlayStation Store"));
        assertEquals("https://www.nintendo.com/pt-br/store/products/hades-switch/", links.get("Nintendo eShop"));
        assertEquals("https://store.epicgames.com/pt-BR/product/hades/home", links.get("Epic Games Store"));
        assertTrue(lojas.stream().allMatch(l -> "Loja".equals(l.get("modalidade"))));
    }

    @Test
    void consoleSemLinkDiretoGanhaABuscaDaLojaOficial() {
        // Zelda: Tears of the Kingdom só tem a página da Nintendo; um jogo de PS5 sem o link da PlayStation Store
        var semLink = IgdbService.lojas(Map.of("platforms", java.util.List.of(Map.of("id", 167), Map.of("id", 130))), "Jogo: Edição & Cia");
        assertEquals(2, semLink.size());
        assertEquals("https://store.playstation.com/pt-br/search/Jogo%3A%20Edi%C3%A7%C3%A3o%20%26%20Cia", semLink.get(0).get("link"));
        assertEquals("Busca na loja", semLink.get(0).get("modalidade"));
        assertEquals("Nintendo eShop", semLink.get(1).get("nome"));
        // links que não são https (ou com aspas) não entram
        var ruins = IgdbService.lojas(Map.of("websites", java.util.List.of(
                Map.of("type", 13, "url", "javascript:alert(1)"), Map.of("type", 17, "url", "https://gog.com/\"><script>"))), "X");
        assertTrue(ruins.isEmpty());
    }

    @Test
    void textoDaBuscaNaoQuebraAConsulta() {
        assertEquals("Hades II", IgdbService.textoSeguro("Hades \"II\";"));
        assertEquals("a b", IgdbService.textoSeguro("a\\*b"));
    }
}
