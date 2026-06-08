# Calibragem de JVM — desempenho sob stress de payload grande

> **Escopo:** parâmetros de JVM do container proxy (`Containerfile` / `Containerfile.ocp`).  
> **Motivação:** falhas de `OutOfMemoryError` observadas durante testes de carga com 15 requisições simultâneas carregando payloads de 5 MB cada.

---

## 1. Contexto e problema observado

O proxy encaminha payloads grandes sem bufferização (`camel.main.stream-caching-enabled=false`). Em cenário de stress com 15 requisições simultâneas de 5 MB, a JVM esgotou o heap com G1GC e lançou:

```
java.lang.OutOfMemoryError: Java heap space
    at io.vertx.core.streams.impl.AsyncInputStream.doRead(...)
```

O erro ocorreu em loops de evento do Vert.x durante a leitura dos chunks do body — confirmando que o heap foi consumido pelo buffering transitório dos payloads no event loop, não por vazamento de memória.

**Configuração no momento da falha:**

- Container: `--memory 512m`
- JVM container-awareness default: `MaxRAMPercentage=25%`
- Heap disponível resultante: `512 × 25% = 128 MB`
- In-flight simultâneo: `15 × 5 MB = 75 MB`

---

## 2. Por que 128 MB de heap é insuficiente para o G1GC

O G1GC divide o heap em regiões de tamanho fixo (default 1 MB a 32 MB, calculado automaticamente). O coletor precisa de espaço suficiente para:

1. **Eden ativo**: recebe todos os novos objetos; precisa ter capacidade significativamente maior do que o volume in-flight para que o coletor tenha tempo de fazer minor GCs entre chegadas.
2. **Old Gen**: objetos promovidos que sobreviveram ao threshold de idade (inclui buffers Vert.x de maior duração).
3. **Overhead interno do G1**: remembered sets, marking bitmaps e region headers consomem ~15–20% do heap em memória nativa (fora do heap Java, mas contabilizada no RSS do processo).

Com 128 MB de heap:

| Dedução | Valor |
|---|---|
| Heap cap | 128 MB |
| G1 overhead nativo (~15%) | -19 MB |
| Heap Java efetivo utilizável | ~109 MB |
| In-flight peak (15 × 5 MB) | 75 MB |
| Margem para GC cycle | ~34 MB |

34 MB de margem é insuficiente para o G1 concluir um ciclo de marcação antes que novos objetos preencham o Eden — o coletor não acompanha a taxa de alocação e a JVM esgota o heap.

**Comportamento com SerialGC na mesma situação:** o SerialGC sobrevive ao mesmo cenário via stop-the-world agressivo — para o mundo inteiro, coleta tudo de uma vez, libera memória. O custo é pausa perceptível na latência, mas sem OOM. O G1GC, orientado a pausas curtas, evita STW total e falha mais rapidamente quando o heap é inadequado.

---

## 3. Análise das regiões de memória não-heap

Cada região foi avaliada e calibrada individualmente. Os valores medidos são de um container em idle após arranque completo (G1GC, 512 MB container):

| Região | Cap configurado | Medido (idle) | Estimativa estado estável (prod) |
|---|---|---|---|
| Heap | ver seção 5 | 26 MB committed | 150–280 MB committed |
| Metaspace | 160 MB | 33.5 MB | 90–110 MB |
| Code Cache | 96 MB | 9.1 MB | 40–60 MB |
| Compressed Class Space | (incluso no Metaspace) | 4.6 MB | ~8 MB |
| Direct Memory (Netty/Vert.x) | 64 MB | ~3–9 MB | 10–20 MB |
| JVM native (threads, GC metadata) | — | ~40 MB¹ | ~65–80 MB |

¹ Diferença entre RSS total (113 MB medido via `podman stats`) e JVM committed regions (73 MB somados via `/q/metrics`).

### Justificativas dos caps

**`MaxMetaspaceSize=160m`**  
Cobre Quarkus + Camel + Resilience4j + HTTP/2 codec + HPACK. O espaço de classes é maior que o projeto de referência (bridge mTLS) porque o pipeline deste proxy é mais rico em componentes. O teto de 160 MB evita crescimento silencioso do Metaspace em caso de classloading anormal.

**`ReservedCodeCacheSize=96m`**  
Cobre os caminhos JIT mais quentes: HTTP/2 encoder/decoder, pipeline de headers, lógica do circuit breaker por host, `toD` dinâmico. CodeCache cheio força o JVM a desabilitar JIT e regredir para interpretado — impacto severo de latência.

**`MaxDirectMemorySize=64m`**  
Vert.x/Netty usa Direct Memory para buffers de I/O e tabelas HPACK (HTTP/2). Sem teto, cresce de forma imperceptível. 64 MB cobre múltiplos pools de conexão simultâneos com margem.

---

## 4. Opções avaliadas para o heap

### Opção A — thresholds fixos (`-Xms128m -Xmx320m`)

Abordagem usada no `Containerfile.ocp` (produção).

| Pro | Con |
|---|---|
| Explícito, sem ambiguidade numérica | Não adapta se o limite do container mudar |
| Idêntico ao que OCP usa em produção | Dois valores para manter em sync com o container |
| Previsível em qualquer JVM | Ignora a container-awareness da JVM |

### Opção B — percentuais (`InitialRAMPercentage=25.0 / MaxRAMPercentage=62.5`)

Abordagem selecionada para o `Containerfile` local.

| Pro | Con |
|---|---|
| Preserva container-awareness — heap se ajusta se `--memory` mudar | Valor efetivo requer cálculo (512 × 62.5% = 320 MB) |
| Um único set de flags funciona em containers de tamanhos distintos | |
| Semanticamente equivalente ao OCP para o container de 512 MB | |

**Equivalência numérica para container de 512 MB:**

| Flag percentual | Cálculo | Equivalência fixa |
|---|---|---|
| `InitialRAMPercentage=25.0` | 512 × 25% = 128 MB | `-Xms128m` |
| `MaxRAMPercentage=62.5` | 512 × 62.5% = 320 MB | `-Xmx320m` |

A opção B foi escolhida para o container local porque preserva a capacidade de override via `-e JAVA_TOOL_OPTIONS=...` sem recalcular flags absolutas em função de um novo limite de container.

---

## 5. Avaliação de aumento do container (512 MB → ?)

O OOM observado foi um `OutOfMemoryError` Java — a JVM atingiu o próprio teto de heap, **não** o limite de memória do container. Isso foi confirmado por dois fatos:

1. `podman stats` mostrou uso em ~21% do limite (113 MB / 512 MB) no idle — o container tinha centenas de megabytes disponíveis.
2. A stack trace aponta `Java heap space`, não um `NativeOutOfMemoryError` ou kill por cgroup.

**Orçamento de memória com `MaxRAMPercentage=62.5` no container de 512 MB:**

| Estado | Heap committed | Non-heap + JVM native | RSS estimado | Headroom |
|---|---|---|---|---|
| Idle (medido) | 26 MB | 87 MB | 113 MB | 399 MB |
| 15 × 5 MB load (estimado) | 200–280 MB | 130–160 MB | 330–440 MB | 72–182 MB |

Com 72–182 MB de folga, o container de 512 MB comporta o cenário de teste extremo sem risco de OOM-kill pelo kernel. Aumentar o container mascararia o problema real (heap insuficiente) sem endereçar a causa raiz, e afastaria o ambiente local da referência de produção OCP, que opera com os mesmos 512 MB e `-Xmx320m`.

**Conclusão:** nenhum aumento de memória do container é necessário. A mudança correta é exclusivamente no teto do heap.

---

## 6. Configuração resultante

### `Containerfile` (local / Podman)

```dockerfile
# Heap dimensionado via container-awareness da JVM (cgroup limits).
# InitialRAMPercentage=25 e MaxRAMPercentage=62.5 equivalem a -Xms128m -Xmx320m
# num container de 512m, preservando auto-ajuste se o limite mudar.
# Sobrescrever via -e JAVA_TOOL_OPTIONS="..." no podman run ou no Deployment.
ENV JAVA_TOOL_OPTIONS="\
 -XX:InitialRAMPercentage=25.0\
 -XX:MaxRAMPercentage=62.5\
 -XX:MaxMetaspaceSize=160m\
 -XX:ReservedCodeCacheSize=96m\
 -XX:MaxDirectMemorySize=64m\
 -XX:+UseG1GC\
 -XX:MaxGCPauseMillis=200\
 -XX:InitiatingHeapOccupancyPercent=35"
```

### `Containerfile.ocp` (produção / OpenShift)

Mantém flags absolutas porque o OCP define limites de container explícitos no Deployment e a equivalência é exata:

```
-Xms128m -Xmx320m
-XX:MaxMetaspaceSize=160m
-XX:ReservedCodeCacheSize=96m
-XX:MaxDirectMemorySize=64m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:InitiatingHeapOccupancyPercent=35
```

### Parâmetro `InitiatingHeapOccupancyPercent=35`

O G1GC inicia o ciclo de marcação concorrente quando a Old Gen atinge esse percentual do heap. O default do JDK é 45%. Antecipando para 35% (com `-Xmx320m`, isso corresponde a ~112 MB de Old Gen), o coletor começa a limpar mais cedo — reduzindo o risco de Full GC em picos de carga onde múltiplos pools de conexão e objetos Resilience4j acumulam na Old Gen.

---

## 7. Métricas para monitoramento contínuo

Após o ajuste, as seguintes métricas devem ser acompanhadas em Prometheus para detectar necessidade de recalibração:

| Métrica | Threshold de alerta | Significado |
|---|---|---|
| `jvm_memory_used_bytes{area="heap"}` | > 80% de `jvm_memory_max_bytes` | Heap pressionando o teto — avaliar aumento de `MaxRAMPercentage` |
| `jvm_memory_used_bytes{id="Metaspace"}` | > 120 MB | Metaspace crescendo além do esperado — investigar classloading |
| `jvm_memory_used_bytes{id="CodeCache"}` | > 70 MB | Pressão JIT — investigar caminhos quentes não esperados |
| `jvm_gc_pause_seconds_sum` | P99 > 500 ms | G1 com dificuldade de cumprir `MaxGCPauseMillis=200` sob carga |
| `proxy_active_requests` | ≥ limite configurado | Backpressure ativo (HWM) — correlacionar com métricas de heap |

---

## 8. Orientações para recalibração

Se o limite do container for alterado e o `Containerfile` local estiver em uso (percentuais):

1. O heap se ajusta automaticamente — nenhuma flag de heap precisa mudar.
2. Os caps de non-heap (`MaxMetaspaceSize`, `ReservedCodeCacheSize`, `MaxDirectMemorySize`) são absolutos e podem precisar de ajuste manual se o workload mudar (mais extensões Quarkus, mais backends simultâneos).

Se o `Containerfile.ocp` for alterado:

1. Recalcular: `Xmx = novo_limite × 62.5%`, `Xms = novo_limite × 25%`.
2. Verificar que `Xmx + soma dos caps non-heap` cabe dentro do novo limite com pelo menos 20% de headroom para JVM native overhead e OS.

**Regra de bolso para o heap máximo:**

```
Xmx_recomendado = max(in_flight_bytes × 4, container_limit × 0.6)
```

O fator 4× sobre o in-flight cobre: payload em Eden + promoção para Old Gen + overhead do G1 + margem para GC cycle. O `0.6` (60%) do container é o piso quando não há estimativa de workload.
