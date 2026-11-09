package com.omnireview.backend;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contador de uso por chave em janelas de tempo fixas (ex.: "cadastro|ip" até 5 por hora).
 * Fica em memória: reiniciar o servidor zera os contadores, o que é aceitável para conter abuso.
 */
@Component
public class LimitadorDeTaxa {

    private record Janela(Instant inicio, Duration duracao, int usos) {}

    private final Map<String, Janela> janelas = new ConcurrentHashMap<>();
    private final AtomicLong chamadas = new AtomicLong();
    private final Clock relogio;

    public LimitadorDeTaxa() {
        this(Clock.systemUTC());
    }

    LimitadorDeTaxa(Clock relogio) {
        this.relogio = relogio;
    }

    /** Conta um uso; devolve 0 se está dentro do limite ou, se passou, quantos segundos faltam para liberar. */
    public long usar(String chave, int maximo, Duration janela) {
        Instant agora = Instant.now(relogio);
        if (chamadas.incrementAndGet() % 2_000 == 0) limpar(agora);
        Janela j = janelas.compute(chave, (k, atual) ->
                atual == null || !agora.isBefore(atual.inicio().plus(atual.duracao()))
                        ? new Janela(agora, janela, 1)
                        : new Janela(atual.inicio(), atual.duracao(), atual.usos() + 1));
        if (j.usos() <= maximo) return 0;
        return Math.max(1, Duration.between(agora, j.inicio().plus(j.duracao())).toSeconds());
    }

    private void limpar(Instant agora) {
        janelas.entrySet().removeIf(e -> !agora.isBefore(e.getValue().inicio().plus(e.getValue().duracao())));
    }
}
