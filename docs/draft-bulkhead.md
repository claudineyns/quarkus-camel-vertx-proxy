# [DRAFT] Estratégia de bulkhead por backend

> **Status:** decisão registrada — implementação pendente.  
> **Contexto:** proxy de IA intermediando chamadas a múltiplos backends de agentes/modelos e servidores MCP.  
> **Objetivo do projeto:** validar o Vert.x (producer + consumer) como motor de proxy em alta concorrência com payloads grandes. Simplicidade de arquitetura é critério de projeto.

---

## 1. Estado atual e o problema

O `proxy.active-requests.limit` (HWM) é um contador **global**: uma única `AtomicInteger` controla o total de requisições em voo, independentemente do backend de destino.

```
Cliente A → [proxy] → backend-lento:8080   ─┐
Cliente B → [proxy] → backend-rapido:9090   ─┤→ AtomicInteger global = 20
Cliente C → [proxy] → backend-lento:8080   ─┘
```

**Problema:** se `backend-lento` satura os 20 slots globais, clientes aguardando `backend-rapido` recebem 503 — não porque o backend rápido está saturado, mas porque o lento monopolizou o recurso compartilhado. O HWM global oferece proteção de memória, mas não isolamento entre backends.

O **bulkhead pattern** resolve isso particionando o limite por backend: cada backend tem seu próprio teto de concorrência, de forma que a saturação de um não afeta os demais.

---

## 2. O que já é por backend

| Mecanismo | Granularidade atual |
|---|---|
| Circuit breaker (Resilience4j) | Por `scheme+host:port` |
| Pool de conexões (Vert.x) | Por `scheme+host:port` |
| HWM / backpressure | **Global** ← ponto de discussão |

Há consistência natural em manter o bulkhead alinhado à mesma granularidade dos outros mecanismos.

---

## 3. Opções em análise

### Opção A — Bulkhead por host (`scheme+host:port`)

Cada backend identificado por `scheme+host:port` recebe seu próprio contador, espelhando a granularidade já usada pelo circuit breaker e pelo pool.

```
backend-lento:8080   → AtomicInteger(limit=10)
backend-rapido:9090  → AtomicInteger(limit=10)
```

**Implementação:** estender `ConnectionPressureMonitor` de um único `AtomicInteger` para um `ConcurrentHashMap<String, AtomicInteger>`, onde a chave é o backend resolvido. A chave já está disponível no ponto de `tryAcquire()` — o backend é resolvido antes do HWM no pipeline atual.

**Vantagens:**
- Alinha-se com a granularidade do CB e do pool — um único modelo mental para todos os mecanismos de resiliência.
- A chave é determinística e simples: `scheme+host:port`.
- Backends dinâmicos são suportados naturalmente: o `ConcurrentHashMap` cria o contador na primeira requisição para um host ainda não visto.
- Sem configuração de URL patterns — mantém o caráter dinâmico do proxy.

**Desvantagens:**
- Não distingue endpoints de latência muito diferente no mesmo host.  
  Exemplo: `api.openai.com/v1/chat/completions` (30–120 s) vs `api.openai.com/v1/embeddings` (1–3 s) compartilham o mesmo contador de host.
- O limite por host precisa ser definido. Opções:
  - Limite fixo configurado por host (requer configuração explícita).
  - Limite calculado como `total_global / n_hosts_ativos` (dinâmico, mas impreciso).
  - Limite global dividido igualmente por default, com override por host (configuração opcional).

---

### Opção B — Bulkhead por URL completa (path incluído)

O contador é chaveado por `scheme+host:port+path`, ou por um prefixo de path configurado.

```
api.openai.com:443/v1/chat/completions  → AtomicInteger(limit=5)
api.openai.com:443/v1/embeddings        → AtomicInteger(limit=15)
```

**Vantagens:**
- Isolamento de alta granularidade: endpoints lentos não bloqueiam endpoints rápidos no mesmo host.
- Reflete melhor a realidade de APIs de IA, onde o mesmo provedor expõe rotas com SLAs muito distintos.

**Desvantagens:**
- O path muda a cada requisição — o mapa cresce indefinidamente se o path contém IDs dinâmicos (ex: `/v1/files/{file_id}/content`). Requer normalização (extração do prefixo estável do path).
- Requer configuração explícita de patterns e seus limites: o proxy não pode inferir automaticamente quais paths merecem qual teto.
- Quebra o alinhamento com CB e pool (que operam por host) — cria dois modelos mentais para resiliência.
- Aumenta a complexidade operacional: a cada novo endpoint consumido pelo cliente, pode ser necessário adicionar um pattern de bulkhead.

---

### Opção C — Híbrido: host primário + classificação de endpoint

Um meio-termo: o bulkhead opera por host, mas com um multiplicador de peso por tipo de endpoint, configurável.

```
api.openai.com:443  → limite base = 10
  + /v1/chat/*       → peso = 2  → cada slot consome 2 unidades
  + /v1/embeddings   → peso = 1  → cada slot consome 1 unidade
```

A lógica de `tryAcquire` subtrairia o peso correspondente ao path em vez de sempre subtrair 1.

**Vantagens:** expressividade sem proliferar contadores; um único limite por host com sensibilidade ao custo relativo do endpoint.

**Desvantagens:** significativamente mais complexo de implementar e de raciocinar — o limite deixa de ser diretamente interpretável como "N requisições simultâneas".

---

## 4. O caso MCP e a lacuna das opções A, B e C

Servidores MCP (Model Context Protocol) expõem operações de latência heterogênea sobre o **mesmo endpoint HTTP**. O protocolo usa JSON-RPC 2.0: toda chamada é `POST` para o mesmo path (ex: `POST /mcp`), e a distinção entre categorias de operação está no campo `method` do corpo:

| Categoria MCP | Exemplos de method | Latência típica |
|---|---|---|
| `prompt` | `prompts/list`, `prompts/get` | Muito baixa (lookup estático) |
| `resource` | `resources/list`, `resources/read` | Baixa a média (I/O de arquivo/DB) |
| `tool` | `tools/list`, `tools/call` | Alta (chamada a serviço externo, inferência) |

Como o path é idêntico para as três categorias, as Opções A, B e C produzem o mesmo resultado prático para MCP: um `tools/call` de 60 s ocupa o mesmo slot que um `prompts/get` de 200 ms. Nenhuma das opções resolve esse caso sem inspecionar o corpo da requisição — o que é incompatível com `stream-caching-enabled=false` e com o propósito de proxy transparente.

A única saída sem body inspection seria uma **Opção D**: um header de classificação (`x-proxy-bulkhead-group: tool`) que o orquestrador/agente adiciona antes de enviar ao proxy, mapeando para contadores por grupo. Isso transfere a responsabilidade de classificação para o caller e adiciona acoplamento que foge do perfil de laboratório atual.

---

## 5. Decisão

**Opção A — Bulkhead por `scheme+host:port`, alinhado ao circuit breaker.**

### Racional

O objetivo do projeto é validar o Vert.x como motor de proxy em alta concorrência e payloads grandes. Complexidade de arquitetura desnecessária obscurece esse objetivo. A Opção A:

- Mantém um único modelo mental para todos os mecanismos de resiliência: CB, pool e bulkhead operam pela mesma chave.
- Reutiliza a infraestrutura já existente no `CircuitBreakerRegistry` — o ciclo de vida dos contadores por host segue o mesmo padrão dos CBs.
- Não exige configuração prévia de backends: o `ConcurrentHashMap` cria o contador ao primeiro acesso a um host desconhecido, preservando o roteamento dinâmico.
- A chave (`scheme+host:port`) já está resolvida no ponto de `tryAcquire()` — sem mudança no pipeline.

### O que a Opção A não resolve (e por que isso é aceitável agora)

O problema de endpoints de latência heterogênea no mesmo host — crítico para MCP — não é endereçado. Isso é aceito porque:

1. O isolamento entre **hosts distintos** (o caso dominante no lab) já é coberto.
2. O isolamento intra-host requer ou body inspection (incompatível) ou cooperação do caller via header (Opção D) — ambas adicionam complexidade fora do escopo atual.
3. O critério de projeto é validação do motor, não exaustividade de padrões de resiliência.

A Opção D fica registrada como evolução futura, condicionada à necessidade de suporte a MCP em produção com SLA diferenciado por tipo de operação.

### Implicações de implementação

| Elemento | Estado atual | Com Opção A |
|---|---|---|
| `ConnectionPressureMonitor` | `AtomicInteger` único | `AtomicInteger` global + `ConcurrentHashMap<String, AtomicInteger>` por host |
| Chave do contador por host | — | `scheme+host:port` (resolvido antes do HWM no pipeline) |
| `proxy.active-requests.limit` | Limite global | Limite global — mantém semântica atual |
| `proxy.active-requests.per-host-limit` | — | Limite por host, mesmo valor para todos; `-1` desabilita |
| Gauge Prometheus | `proxy.active.requests` (escalar) | `proxy.active.requests` (global) + `proxy.active.requests{host="..."}` (tagged, por host) |
| Readiness probe | DOWN quando global ≥ limite | DOWN **apenas** quando global ≥ limite; esgotamento por host não afeta o Readiness |

**Semântica de recusa diferenciada (mesmo status HTTP 503):**

| Condição disparada | `detail` RFC 9457 | Readiness |
|---|---|---|
| Global esgotado | `"Connection queue threshold exceeded"` | DOWN |
| Host esgotado | `"Backend connection limit exceeded: <host:port>"` | Inalterado |

**Ordem de aquisição:** global primeiro, host depois. Se o global falha: rollback global, 503 + Readiness DOWN. Se o host falha: rollback host e global, 503 + Readiness inalterado. A propriedade `PROP_ACTIVE_ACQUIRED` no exchange evolui de `Boolean.TRUE` para a **chave do host** (`String`), permitindo que o `release()` decremente o counter correto.

**Limitação de design — configuração estática:** os tetos são lidos em startup via `@ConfigProperty`. Em produção, a configuração ideal estaria em uma store externa (ex: Redis), permitindo ajuste em runtime por host individual sem restart. Essa é uma simplificação consciente do laboratório.

---

### Valores estabelecidos

```properties
proxy.active-requests.limit=100          # teto global
proxy.active-requests.per-host-limit=25  # teto por host (mesmo para todos)
```

A relação `global / per-host = 4` implica que até **4 backends em plena capacidade simultânea** podem coexistir sem que um monopolize os demais. Um quinto backend em saturação seria recusado no nível de host enquanto os outros 4 continuam operando normalmente — sem impacto no Readiness.

---

## 6. Decisão de implementação

### Teto por host — Resilience4j `SemaphoreBulkhead`

O teto por host será implementado via `BulkheadRegistry` (Resilience4j), seguindo o mesmo padrão já estabelecido pelo `CircuitBreakerRegistry`:

- Um novo bean `@ApplicationScoped` `BulkheadRegistryProducer` produz o `BulkheadRegistry` com a configuração base (`maxConcurrentCalls=25`, `maxWaitDuration=ZERO`).
- Bulkheads são criados sob demanda por chave `scheme+host:port` — sem configuração prévia de backends.
- `TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry)` registra automaticamente gauges Prometheus tagged por host, sem código adicional de métrica.
- A API `tryAcquirePermission()` / `releasePermission()` é fail-fast e semanticamente equivalente ao `AtomicInteger` atual.
- Nova dependência: `io.github.resilience4j:resilience4j-bulkhead:2.2.0` no `pom.xml`. `resilience4j-micrometer` já declarado contém o `TaggedBulkheadMetrics`.

### Teto global — `AtomicInteger` customizado (inalterado)

O contador global no `ConnectionPressureMonitor` permanece como `AtomicInteger`. Ele é a fonte de verdade do Readiness probe e não tem problema a resolver — substituí-lo por um `Bulkhead` introduziria indireção desnecessária na leitura do estado pelo health check.

### Divisão de responsabilidades resultante

| Mecanismo | Implementação | Granularidade | Readiness |
|---|---|---|---|
| Teto global | `AtomicInteger` customizado | Aplicação inteira | **Fonte de verdade** |
| Teto por host (bulkhead) | Resilience4j `BulkheadRegistry` | Por `scheme+host:port` | Não afeta |
| Circuit breaker | Resilience4j `CircuitBreakerRegistry` | Por `scheme+host:port` | Não afeta |
| Pool de conexões | Vert.x `WebClientOptions` | Por `scheme+host:port` | Não afeta |

Os três mecanismos de resiliência por host operam na mesma granularidade e no mesmo ecossistema Resilience4j — modelo mental uniforme para operação e observabilidade.

### Evolução do `PROP_ACTIVE_ACQUIRED`

A propriedade de exchange `PROP_ACTIVE_ACQUIRED` evolui de `Boolean.TRUE` para a **chave do host** (`String scheme+host:port`). O `release()` usa essa chave para localizar o bulkhead correto no registry e invocar `releasePermission()`, além de decrementar o contador global. Ausência da propriedade significa que o `tryAcquire` não foi completado — nenhum release é executado.

---

## 7. Tabela comparativa (revisada)

| Critério | Global (atual) | **Por host — A (decisão)** | Por URL — B | Híbrido — C | Por grupo/header — D |
|---|---|---|---|---|---|
| Isolamento entre hosts distintos | Não | **Sim** | Sim | Sim | Sim |
| Isolamento intra-host (ex: MCP) | Não | Não | Não¹ | Não¹ | **Sim** |
| Alinhamento com CB e pool | — | **Total** | Parcial | Parcial | Parcial |
| Backends dinâmicos sem config | Sim | **Sim** | Não | Não | Não |
| Complexidade de implementação | Baixa | **Média** | Alta | Muito alta | Alta |
| Complexidade operacional | Baixa | **Média** | Alta | Alta | Alta |
| Compatível com stream-caching=false | Sim | **Sim** | Sim | Sim | Sim² |
| Readiness com semântica clara | Sim | **Sim³** | Complexo | Complexo | Complexo |

¹ Para MCP, URL e path são idênticos entre categorias — equivale à Opção A.  
² Desde que o caller adicione o header; não requer body inspection.  
³ DOWN quando qualquer host atinge o limite — semântica conservadora e operacionalmente simples.
