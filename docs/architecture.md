# Arquitetura — quarkus-camel-vertx-proxy

## Visão geral

Reverse proxy HTTP construído sobre Quarkus + Apache Camel, com roteamento dinâmico de backend, suporte opcional a SSL em ambas as pernas e otimizado para payloads grandes via motor Vert.x reativo.

---

## Stack

| Item | Valor |
|---|---|
| Plataforma | Red Hat Quarkus `3.27.3.redhat-00003` |
| Java | 21 |
| Consumer | `camel-quarkus-platform-http` |
| Producer | `camel-quarkus-vertx-http` |
| TLS | `quarkus-tls-registry` |
| Circuit breaker | `resilience4j-circuitbreaker` + `resilience4j-micrometer` (direto, versão `2.2.0`) |

---

## Portas

| Porta | Propriedade | Papel |
|---|---|---|
| HTTP | `quarkus.http.port` | Tráfego de proxy (entrada) |
| HTTPS | `quarkus.http.ssl-port` | Tráfego de proxy com TLS (entrada) |
| Management | `quarkus.management.port` (default `9000`) | Health check e métricas exclusivamente |

A interface de management é habilitada via `quarkus.management.enabled=true`. Com isso, `/q/health` e `/q/metrics` migram para a porta de management e ficam completamente isolados do tráfego de proxy — eliminando qualquer ambiguidade com a rota wildcard do Camel e permitindo controle de acesso independente por firewall.

A configuração TLS é **nomeada** (`quarkus.tls.proxy-tls.*`) e vinculada exclusivamente ao HTTP server via `quarkus.http.tls-configuration-name=proxy-tls`. O management permanece em HTTP simples na porta 9000 — deliberado, pois essa porta não deve ser exposta externamente.

---

## Consumer (perna de entrada)

- Componente: `camel-platform-http`, registrado no servidor Vert.x embutido do Quarkus.
- **Duas portas de negócio expostas simultaneamente**: HTTP e HTTPS. O cliente escolhe; não há redirecionamento forçado.
- **HTTP/2 habilitado** (`quarkus.http.http2=true`) — suporte a HTTP/1.1 e HTTP/2 simultâneos nas portas de negócio.
- Configuração SSL exclusivamente no nível do servidor Quarkus via `quarkus-tls-registry` — nenhuma config SSL no endpoint URI do Camel.
- Rota wildcard captura todos os paths e métodos HTTP nas portas de negócio.
- StreamCache **desabilitado** na rota do proxy para suporte a payloads grandes sem bufferização do body.

---

## Producer (perna de saída)

- Componente: `camel-quarkus-vertx-http`, configuração de infra no nível do componente (`VertxHttpComponent`) — não por endpoint.
- Endpoint do `toD` contém apenas `scheme://host:port`; path e query são passados via headers do exchange:
  - `Exchange.HTTP_PATH` (`CamelHttpPath`) — path do recurso
  - `Exchange.HTTP_RAW_QUERY` (`CamelHttpRawQuery`) — query string sem re-encoding
- Essa separação mantém o endpoint estável por backend, permitindo que o pool de conexões Vert.x (chaveado por `scheme+host+port`) seja reutilizado entre requisições.
- **HTTP/2 habilitado no cliente Vert.x** — negocia HTTP/2 com o backend quando disponível; pool HTTP/2 configurável separadamente de HTTP/1.1.
- SSL dinâmico: o scheme de saída (`http` ou `https`) é determinado pela URL do backend resolvida — o proxy não faz tunneling TCP (sem suporte a `CONNECT`).
- Política TLS do componente (escopo laboratório): `trustAll=true` + `verifyHost=true` — aceita qualquer CA mas exige que o hostname bata com o certificado.

---

## Lógica de roteamento dinâmico

A base da URL de destino é resolvida em duas etapas, em ordem de prioridade:

### 1. Primário — header `x-proxy-backend-base-url`

Quando presente, contém exclusivamente `protocolo://host:porta` (sem path). Exemplo:

```
x-proxy-backend-base-url: https://backend-service:8443
```

### 2. Secundário — `Exchange.HTTP_URL`

Quando o header primário está ausente, o proxy extrai `scheme://host[:port]` de `Exchange.HTTP_URL`, que o Vert.x constrói como `scheme://host/path` a partir do scheme do servidor e do header `Host` da requisição.

**Modo forward-proxy:** o cliente define o header `Host` com o backend de destino (RFC 7230 §5.3.2), de forma que `Exchange.HTTP_URL` carrega a URL correta do backend.

**Sem header primário e sem forward-proxy:** o `Host` aponta para o próprio proxy, tornando `Exchange.HTTP_URL` auto-referencial — a guard de self-reference rejeita com 502.

> **Nota de scheme:** o scheme em `Exchange.HTTP_URL` reflete o scheme do servidor (HTTP ou HTTPS da porta de entrada), não o da request-line. Em forward-proxy para um backend HTTP via entrada HTTPS, o proxy acessará o backend em HTTPS.

### Guard — self-reference

Se a URL resolvida (em qualquer das etapas) apontar para o próprio proxy, a requisição é rejeitada imediatamente com **`502 Bad Gateway`** antes de qualquer tentativa de encaminhamento. Impede loops de roteamento e erros de configuração. Na ausência do header primário, esse guard é o mecanismo que transforma um request reverse-proxy sem rota explícita em 502.

### Composição da URL final de saída

```
base   = <resolvida pelo primário ou secundário>
path   → Exchange.HTTP_PATH
query  → Exchange.HTTP_RAW_QUERY

URL de saída = base + path [+ ?query]
```

---

## Tratamento de headers

### Manipulação pré-encaminhamento

| Header | Comportamento |
|---|---|
| `Host` | Recalculado para `host:port` do backend de destino |
| `x-proxy-backend-base-url` | Removido antes do encaminhamento |
| `Exchange.HTTP_URL` | Lido antes de qualquer remoção para resolução de backend (secundário) e para `X-Forwarded-Proto`; removido depois, junto com `HTTP_URI`, para que o produtor Vert.x use o endpoint do `toD` como base da URL de saída |
| `Exchange.HTTP_URI` | Removido antes do `toD` pelo mesmo motivo que `HTTP_URL` |
| Hop-by-hop (`Connection`, `Transfer-Encoding`, `TE`, `Upgrade`, `Keep-Alive`, `Trailers`) | Removidos antes do encaminhamento (RFC 7230) |
| `X-Forwarded-For` | Injetado com o IP do cliente original (appended se já existir) |
| `X-Forwarded-Proto` | Injetado com o scheme da porta de entrada (`http` ou `https`) |
| `X-Forwarded-Host` | Injetado com o valor original do header `Host` (antes do recálculo) |
| `X-Forwarded-Port` | Injetado com a porta de entrada utilizada pelo cliente |
| Demais headers | Passagem transparente (incluindo `traceparent`/`tracestate` W3C Trace Context) |

### Isolamento de headers de request na resposta

Sem tratamento explícito, os headers da requisição original do cliente persistem no `Message` do exchange durante todo o pipeline — incluindo o momento em que os headers da resposta do backend são adicionados. O resultado é uma resposta contaminada com headers do request original.

O isolamento é feito em duas camadas complementares:

**Camada 1 — `ProxyVertxHttpBinding`**

Sobrescreve `DefaultVertxHttpBinding.populateResponseHeaders`. Antes de qualquer header do backend ser copiado para o exchange, todo o `Message` é limpo:

```
exchange.getMessage().removeHeaders("*")
```

A partir desse ponto, o exchange contém exclusivamente os headers vindos da resposta HTTP do backend.

**Camada 2 — limpeza pós-resposta (`postProcess`)**

Após o retorno do `toD`, remove os headers internos do framework Camel (prefixo `Camel*`), preservando somente `CamelHttpResponseCode` — necessário para determinar o status da resposta ao cliente:

```
msg.removeHeaders("Camel*", Exchange.HTTP_RESPONSE_CODE)
```

**Resultado combinado:** o cliente recebe exclusivamente os headers que o backend enviou, sem nenhum artefato da requisição original nem do pipeline interno do Camel.

---

## Configuração externa (deployment-time)

Toda configuração numérica relevante é externalizada via namespace `proxy.*` em `application.properties`, sem acoplamento aos nomes internos do Camel. Os valores são injetados via `@ConfigProperty` no bean `@Produces VertxHttpComponent`, centralizando SSL e pool no mesmo ponto de configuração programática.

### Namespace `proxy.http.client.*`

| Propriedade | Default | Descrição |
|---|---|---|
| `proxy.http.client.pool.max-connections-per-host` | `10` | Máximo de conexões por `host:port` no producer |
| `proxy.http.client.pool.max-wait-queue-size` | `-1` | Fila de espera quando o pool está esgotado (`-1` = ilimitado) |
| `proxy.http.client.connect-timeout-ms` | `5000` | Timeout de estabelecimento de conexão TCP (ms) |
| `proxy.http.client.response-timeout-ms` | `5000` | Timeout para receber a resposta após conexão estabelecida (ms) |
| `proxy.http.client.idle-timeout-s` | `30` | Timeout de conexão ociosa no pool (s) |
| `proxy.http.client.pool.http2-max-connections-per-host` | `5` | Máximo de conexões HTTP/2 por `host:port` no producer |

Outras configs numéricas relevantes identificadas durante a implementação seguem o mesmo padrão e namespace.

### Namespace `proxy.active-requests.*`

| Propriedade | Default | Descrição |
|---|---|---|
| `proxy.active-requests.limit` | `-1` | Máximo de requests em voo simultâneos (global). `-1` desabilita o limite. |
| `proxy.active-requests.per-host-limit` | `-1` | Máximo de requests em voo por backend (`scheme+host:port`). `-1` desabilita o cap por host. |

Configuráveis em runtime via variáveis de ambiente `PROXY_ACTIVE_REQUESTS_LIMIT` e `PROXY_ACTIVE_REQUESTS_PER_HOST_LIMIT` (convenção de nome Quarkus).

### Backpressure em dois níveis e Readiness

O controle de concorrência opera em dois níveis independentes, alinhados à mesma granularidade do circuit breaker e do pool de conexões:

| Nível | Mecanismo | Granularidade | Afeta Readiness |
|---|---|---|---|
| Global | `AtomicInteger` (`ConnectionPressureMonitor`) | Aplicação inteira | **Sim** → DOWN |
| Por host | Resilience4j `BulkheadRegistry` | `scheme+host:port` | Não |

**Lógica do Readiness (SmallRye Health `@Readiness`):**

```
ativo_global >= proxy.active-requests.limit  →  DOWN
caso contrário (ou limite = -1)              →  UP
```

O esgotamento por host (`per-host-limit`) rejeita a requisição com 503, mas não altera o Readiness — outros backends continuam acessíveis e o pod permanece nos endpoints do Kubernetes Service.

Quando o limite **global** é atingido, dois comportamentos ocorrem simultaneamente:

1. **Readiness → DOWN**: o Kubernetes remove o pod dos endpoints do Service (sem restart).
2. **Rejeição ativa → `503 Service Unavailable` RFC 9457**: novas requisições são rejeitadas imediatamente.

**API do `ConnectionPressureMonitor`:**

| Método | Comportamento |
|---|---|
| `tryAcquire()` | Increment-then-check atômico: incrementa, verifica, desfaz se estourou. Retorna `false` sem incremento quando o limite seria excedido. |
| `release()` | Decrementa o contador. |
| `isUnderPressure()` | Leitura não-atômica do estado atual. Usado exclusivamente pelo Readiness check. |

**Bulkhead por host (`BulkheadRegistry`):**

Instâncias criadas sob demanda por chave `scheme+host:port`, espelhando o `CircuitBreakerRegistry`. Quando `per-host-limit = -1`, o `maxConcurrentCalls` é configurado com `Integer.MAX_VALUE` — instâncias são criadas mesmo assim para exportar métricas por host via `TaggedBulkheadMetrics`.

O gauge `proxy.active.requests` expõe o contador global ao Prometheus. Métricas por host (`resilience4j.bulkhead.active` tagged por `name=host:port`) são exportadas automaticamente pelo listener do registry.

A propriedade de exchange `PROP_ACTIVE_ACQUIRED` armazena a chave do host (`String scheme+host:port`) somente quando **todos** os guards (global, bulkhead e circuit breaker) foram adquiridos com sucesso. O release em `postProcess` e no handler de exceção de conectividade usa essa chave para liberar o bulkhead e o contador global atomicamente.

---

## Graceful shutdown

Em ambientes Kubernetes, o pod recebe `SIGTERM` antes de ser terminado. O Quarkus aguarda a conclusão das requisições em voo antes de encerrar, pelo tempo configurado em `quarkus.shutdown.timeout`. Esse valor deve ser menor que o `terminationGracePeriodSeconds` do Pod spec para garantir que o Quarkus finalize antes que o Kubernetes force o encerramento.

| Propriedade | Escopo | Descrição |
|---|---|---|
| `quarkus.shutdown.timeout` | Quarkus nativo | Tempo máximo aguardando in-flight requests (ex: `30S`) |

---

## Circuit breaker (Resilience4j)

- Dependências diretas: `io.github.resilience4j:resilience4j-circuitbreaker:2.2.0` e `resilience4j-micrometer:2.2.0` — declaradas explicitamente no `pom.xml`, sem depender de exposição transitiva.
- Bind ao Prometheus feito explicitamente no `@Produces CircuitBreakerRegistry` via `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)` — registra um listener no registry que exporta métricas (estado, failure rate, contagem de calls) automaticamente para qualquer CB criado dinamicamente por `host:port`.
- Instâncias gerenciadas via `CircuitBreakerRegistry` como bean CDI — uma instância por `host:port` dinâmico, criada sob demanda.
- **Gatilho exclusivo**: exceções de conectividade (`ConnectException`, `UnknownHostException`, `SocketTimeoutException`, `SSLException` e variantes Vert.x). Respostas HTTP do backend (incluindo 4xx/5xx) não acionam o CB.
- Estados HALF-OPEN: falha de conectividade retorna o CB para OPEN e devolve 502 RFC 9457.

**Fluxo na rota:**

```
[antes do toD]  CB do host está OPEN?
                └─ sim → 502 RFC 9457 (circuit open), sem tentativa de conexão

[toD executa]
                └─ sucesso              → CB.onSuccess()
                └─ exceção conectividade → CB.onError() → 502 RFC 9457
                └─ resposta 3xx/429     → reescrita para 502 RFC 9457 (ver abaixo)
                └─ demais respostas HTTP → passagem transparente
```

**Propriedades configuráveis (`proxy.http.client.circuit-breaker.*`):**

| Propriedade | Default | Descrição |
|---|---|---|
| `proxy.http.client.circuit-breaker.sliding-window-size` | `10` | Chamadas na janela deslizante |
| `proxy.http.client.circuit-breaker.minimum-number-of-calls` | `5` | Mínimo de chamadas antes de avaliar abertura |
| `proxy.http.client.circuit-breaker.failure-rate-threshold` | `50` | % de falhas para abrir o circuito |
| `proxy.http.client.circuit-breaker.wait-duration-open-ms` | `30000` | Tempo em OPEN antes de ir para HALF-OPEN (ms) |
| `proxy.http.client.circuit-breaker.permitted-calls-half-open` | `3` | Chamadas permitidas em HALF-OPEN |

---

## Reescrita de respostas do backend

As respostas e condições a seguir não são retransmitidas ao cliente — são reescritas pelo proxy:

| Condição | Status retornado | Motivo |
|---|---|---|
| `3xx` do backend | `502 RFC 9457` | Redirects não devem ser seguidos nem repassados pelo proxy |
| `429` do backend | `502 RFC 9457` | Indica pressão no backend; exposto como falha do gateway |
| `Upgrade: websocket` no request | `501 Not Implemented` | Proxy não suporta WebSocket; detectado antes do encaminhamento |

O redirect automático do Vert.x é **desabilitado no componente** via `WebClientOptions.setFollowRedirects(false)` — decisão arquitetural fixa, não configurável externamente.

O campo `detail` do RFC 9457 indica o status original recebido:

```json
{
  "type":     "/problems/bad-gateway",
  "title":    "Bad Gateway",
  "status":   502,
  "detail":   "Unexpected backend response: 301",
  "instance": "/api/recurso/123"
}
```

Esses casos **não acionam o circuit breaker** — são respostas HTTP válidas do ponto de vista de transporte.

---

## Formato de erro RFC 9457

Todas as respostas de erro originadas pelo proxy (self-reference, circuit breaker, reescrita) seguem o mesmo formato:

```json
Content-Type: application/problem+json

{
  "type":     "/problems/bad-gateway",
  "title":    "Bad Gateway",
  "status":   502,
  "detail":   "<descrição específica do caso>",
  "instance": "<path do request original>"
}
```

| Caso | Status | `detail` |
|---|---|---|
| Self-reference | `502` | `"Loop detected: request targets the proxy itself"` |
| Conectividade (CB fechado) | `502` | `"Connection failure: <host:port> — <mensagem da exceção>"` |
| Circuito aberto | `502` | `"Circuit open: <host:port>"` |
| Redirect do backend | `502` | `"Unexpected backend response: <3xx status>"` |
| 429 do backend | `502` | `"Unexpected backend response: 429"` |
| Limite global excedido | `503` | `"Connection queue threshold exceeded"` |
| Limite por host excedido | `503` | `"Backend connection limit exceeded: <host:port>"` |
| WebSocket upgrade | `501` | `"WebSocket not supported"` |

---

## Diagrama de fluxo

```
Cliente (HTTP/1.1 ou HTTP/2)         Operações
  │                                      │
  ├─ HTTP  → quarkus.http.port           │
  └─ HTTPS → quarkus.http.ssl-port   quarkus.management.port
                  │                  /q/health, /q/metrics
         [camel-platform-http]
         Rota wildcard /*
                  │
         Upgrade: websocket? ────────→ 501 RFC 9457
                  │
         Resolver base URL
         ┌────────┴───────────────────┐
         │ x-proxy-backend-base-url   │ primário
         └────────┬───────────────────┘
                  │ ausente
         ┌────────┴───────────────────┐
         │ Exchange.HTTP_URL          │ secundário
         │ (scheme://host[:port])     │
         └────────┬───────────────────┘
                  │ null → 502 RFC 9457
                  │
         host == proxy? ──────────────→ 502 RFC 9457 (loop / sem rota explícita)
                  │
         Global HWM excedido? ────────→ 503 RFC 9457 (backpressure global) → Readiness DOWN
                  │
         Bulkhead por host excedido? ─→ 503 RFC 9457 (backpressure por host)
                  │
         CB do host está OPEN? ───────→ 502 RFC 9457 (circuit open)
                  │
         Remover hop-by-hop + header primário + pseudo-headers
         Injetar X-Forwarded-* headers
         Remover HTTP_URI + HTTP_URL
         Setar HTTP_PATH + HTTP_RAW_QUERY
                  │
         [camel-vertx-http]                 followRedirects=false
         toD("scheme://host:port")          HTTP/1.1 + HTTP/2
         pool de conexões por host:port
                  │
              Backend
                  │
         resposta 3xx ou 429? ────────→ 502 RFC 9457 (reescrita)
                  │
         exceção de conectividade? ───→ CB.onError() → 502 RFC 9457
                  │
         CB.onSuccess()
                  │
              Cliente
```

---

## Infraestrutura de container

### Imagem base

`eclipse-temurin:21-jre-alpine` — alinhada ao Java 21 da stack.

### Estratégia de Containerfiles

Dois arquivos, seguindo o padrão do projeto de referência:

| Arquivo | Heap | Contexto |
|---|---|---|
| `Containerfile` | JVM container-awareness (sem `-Xms`/`-Xmx`) | Lab local / Podman |
| `Containerfile.ocp` | Flags explícitos | OpenShift / produção |

O `Containerfile` local expõe os parâmetros não-heap via `JAVA_TOOL_OPTIONS`, permitindo override em runtime sem rebuild.

### Parâmetros JVM (`Containerfile.ocp`)

```
-Xms128m
-Xmx320m
-XX:MaxMetaspaceSize=160m
-XX:ReservedCodeCacheSize=96m
-XX:MaxDirectMemorySize=64m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:InitiatingHeapOccupancyPercent=35
```

| Flag | Valor | Justificativa |
|---|---|---|
| `-Xms` | `128m` | Streaming sem body buffering — heap cresce gradualmente; pré-alocação agressiva seria desperdício |
| `-Xmx` | `320m` | Cobre: exchanges Camel leves, estado CB por host, MeterRegistry, ConnectionPressureMonitor |
| `MaxMetaspaceSize` | `160m` | Mais bytecode que o bridge: Resilience4j, HTTP/2 codec/HPACK, mais DSL Camel |
| `ReservedCodeCacheSize` | `96m` | Mais caminhos JIT: HTTP/2 encoder/decoder, CB por host, pipeline de headers, `toD` dinâmico |
| `MaxDirectMemorySize` | `64m` | Vert.x/Netty usa direct memory para HTTP/2 (janelas de conexão + HPACK tables por conn); teto explícito evita crescimento silencioso |
| `UseG1GC` | — | Coletor padrão JDK 21; orientado a pausas curtas — adequado para proxy |
| `MaxGCPauseMillis` | `200` | Alvo de pausa adequado para proxy; prioriza latência sobre throughput |
| `InitiatingHeapOccupancyPercent` | `35` | Antecipa ciclo GC concurrent antes de pressionar o teto — relevante com múltiplos pools dinâmicos abertos |

### Balanço de memória no container

Limite do container: **512m**

| Região | Cap configurado | Uso estimado em estado estável |
|---|---|---|
| Heap | 320m | ~150–200m |
| Metaspace | 160m | ~90–110m |
| Code Cache | 96m | ~40–60m |
| Direct Memory | 64m | ~10–20m |
| JVM overhead + threads | — | ~30m |
| **Total** | **640m** (caps) | **~320–420m** (real) |

### `Containerfile` local — `JAVA_TOOL_OPTIONS`

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=160m \
                       -XX:ReservedCodeCacheSize=96m \
                       -XX:MaxDirectMemorySize=64m"
```

Heap dimensionado automaticamente pelo container-awareness da JVM a partir do limite de 512m do container.

### Recursos OCP (`QoS Guaranteed`)

```
requests=memory=512Mi,cpu=100m
limits=memory=512Mi,cpu=1000m
```

Requests = limits garante QoS Guaranteed — sem risco de evicção por pressão de memória e sem throttling de CPU por outros pods no nó. O proxy é event-loop reativo; CPU baixo em estado estável, limite alto cobre picos de JIT na inicialização.

### Scripts de infraestrutura

Estrutura replicada do projeto de referência:

| Script | Função |
|---|---|
| `infra/start-podman.sh` | Build Maven + build de imagem + execução local via Podman; idempotente |
| `infra/start-ocp.sh` | Build + deploy completo no OpenShift com health probes, rolling update, PDB e scale |

Padrões mantidos do projeto de referência:
- `MSYS_NO_PATHCONV=1` em todos os comandos com paths de container
- Rolling update: `maxUnavailable=0`, `maxSurge=1`
- `PodDisruptionBudget`: `minAvailable=1` (com 2 réplicas default)
- Health probes na porta de management (`9000`)

Diferenças em relação ao bridge:
- Três portas expostas: HTTP + HTTPS + management (vs duas no bridge)
- Sem montagem de volume de certificados de cliente (TLS server via `quarkus-tls-registry`)
- Variáveis de ambiente do namespace `proxy.*` em vez das variáveis do bridge
- `PROXY_ACTIVE_REQUESTS_LIMIT` configura `proxy.active-requests.limit` em runtime sem rebuild de imagem

---

## Serviço de eco para testes

Container Podman auxiliar, independente do proxy, usado para validação de roteamento e comportamento de headers/payload.

### Tecnologia

Python — servidor HTTP leve, sem dependências externas além da stdlib.

### Comportamento

Aceita qualquer método e path. Responde sempre `200 OK` com `Content-Type: application/json` contendo:

```json
{
  "method":       "POST",
  "path":         "/api/recurso/123",
  "query":        "foo=bar",
  "headers":      { "host": "...", "x-forwarded-for": "...", "...": "..." },
  "payload_hash": "sha256:<hex>"
}
```

- `payload_hash`: hash SHA-256 do body de entrada, presente somente quando o body não estiver vazio.
- O payload em si **não é retornado** — apenas o hash, para evitar replay e manter a resposta leve independentemente do tamanho do body.
- Todos os headers recebidos são refletidos, permitindo validar `X-Forwarded-*`, `Host` recalculado, remoção de hop-by-hop e ausência do header `x-proxy-backend-base-url`.

**Header opcional `x-delay`:** aceita um valor inteiro em milissegundos; quando presente, aplica um `sleep` antes de montar a resposta. Usado em testes de backpressure (HWM) para manter requests em voo durante a janela de verificação.

### Infraestrutura

- Script: `infra/start-echo.sh` — build + run do container de eco via Podman, na mesma rede do proxy (`proxy-net`).
- Containerfile próprio na subpasta `echo/`.
- Porta configurável via variável de ambiente (default: `8080`).
- Sem script OCP — exclusivamente para uso local em lab.

---

## Testes de validação (hot)

Cenários obrigatórios a serem executados sempre que uma mudança for aplicada. Requerem o container `proxy-app` em execução via `infra/start-podman.sh`. Backend externo: `https://httpbin.org`.

### T-01 — Health check

Verifica que o management está up e respondendo em HTTP simples.

```bash
curl -s http://localhost:9000/q/health
```

**Esperado:** `{"status":"UP","checks":[{"name":"proxy-connection-queue","status":"UP"}]}`

---

### T-02 — GET via HTTP → backend HTTPS (header primário)

Valida roteamento dinâmico pelo header `x-proxy-backend-base-url`, passagem de headers do client, Host recalculado e isolamento de headers de request na resposta.

```bash
curl -s \
  -H "x-proxy-backend-base-url: https://httpbin.org" \
  http://localhost:8080/get
```

**Esperado:** `200 OK`, `"Host": "httpbin.org"` nos headers refletidos pelo backend, ausência do header `x-proxy-backend-base-url` e de headers internos Camel na resposta.

---

### T-03 — GET via HTTPS → backend HTTPS (HTTP/2 nas duas pernas)

Valida TLS na perna de entrada, negociação HTTP/2 via ALPN na perna de saída e roteamento correto com certificado do proxy.

```bash
curl -sk \
  -H "x-proxy-backend-base-url: https://httpbin.org" \
  https://localhost:8443/get
```

**Esperado:** `200 OK`, `"Host": "httpbin.org"` nos headers refletidos, `"url"` mostrando `https://httpbin.org/get` (httpbin usa `Host` recebido para construir o campo `url`).

---

### T-04 — POST com body

Valida passagem transparente de body e `Content-Type` sem bufferização (stream caching desabilitado).

```bash
curl -s -X POST \
  -H "x-proxy-backend-base-url: https://httpbin.org" \
  -H "Content-Type: application/json" \
  -d '{"proxy":"test"}' \
  http://localhost:8080/post
```

**Esperado:** `200 OK`, campo `"data"` com o JSON enviado, campo `"json"` parseado pelo httpbin.

---

### T-05 — Self-reference (detecção de loop)

Valida o guard de auto-referência: requisição apontando para o próprio proxy deve ser rejeitada antes de qualquer tentativa de encaminhamento.

```bash
curl -s http://localhost:8080/get
```

*Ausência do header `x-proxy-backend-base-url` faz o proxy usar o `Host` da requisição, que aponta para `localhost:8080` — o próprio proxy.*

**Esperado:** `502`, `Content-Type: application/problem+json`, `"detail": "Loop detected: request targets the proxy itself"`.

---

### T-06 — Backend inexistente (circuit breaker + DNS failure)

Valida que falhas de conectividade geram 502 RFC 9457 com detalhe da causa raiz, e que o circuit breaker registra o erro.

```bash
curl -s \
  -H "x-proxy-backend-base-url: http://host-inexistente-xyz.local" \
  http://localhost:8080/anything
```

**Esperado:** `502`, `"detail"` contendo `"Connection failure: host-inexistente-xyz.local"` e a mensagem de DNS (`Name does not resolve` ou similar).

---

### T-07 — Modo forward-proxy: URI com Host de backend via HTTPS

Valida o secundário de roteamento: o cliente abre um `SSLSocket` bruto no proxy e envia `GET http://httpbin.org/get HTTP/1.1` com `Host: httpbin.org` (sem header `x-proxy-backend-base-url`). O Vert.x constrói `Exchange.HTTP_URL` como `https://httpbin.org/get` (scheme do servidor + Host do cliente), de onde o proxy extrai `https://httpbin.org` como destino.

```bash
# Compilar (a partir da raiz do projeto)
javac -d temp infra/proxy-test/ProxyTest.java

# Build da imagem de teste
MSYS_NO_PATHCONV=1 podman build -t proxy-test:latest -f infra/proxy-test/Containerfile .

# Executar
MSYS_NO_PATHCONV=1 podman run --rm --network proxy-net \
  -e BACKEND_URL=http://httpbin.org/get \
  proxy-test:latest
```

**Esperado:** logs do proxy mostram `→ [GET https://httpbin.org/get]` com `Host=httpbin.org`, `← backend [200]`, `← client [200]`. O scheme de saída é sempre o scheme do servidor (HTTPS), independentemente do scheme na request-line.

---

### T-08 — Modo forward-proxy: URI com Host de backend HTTPS via HTTPS

Mesma mecânica do T-07, mas `BACKEND_URL` já carrega `https://` — confirma que o proxy acessa o backend em HTTPS.

```bash
MSYS_NO_PATHCONV=1 podman run --rm --network proxy-net \
  -e BACKEND_URL=https://httpbin.org/get \
  proxy-test:latest
```

**Esperado:** logs do proxy mostram `→ [GET https://httpbin.org/get]` com `Host=httpbin.org`, `← backend [200]`, `← client [200]`.

---

### Checklist de verificação dos logs (DEBUG)

Para inspecionar headers em cada fase, iniciar o container com `QUARKUS_LOG_CATEGORY__PROXY_ROUTE__LEVEL=DEBUG` e verificar as três linhas por requisição:

| Prefixo no log | O que observar |
|---|---|
| `→ [METHOD target/path]` | Headers enviados ao backend: sem `*`, sem `x-proxy-backend-base-url`, `Host` recalculado, `X-Forwarded-*` presentes |
| `← backend [STATUS]` | Headers brutos da resposta: `CamelHttpResponseText` presente, sem headers de request |
| `← client [STATUS]` | Headers finais ao cliente: sem `CamelHttpResponseText`, sem outros `Camel*`, somente headers do backend |

---

## Convenções de código

| Convenção | Regra |
|---|---|
| `final` | Obrigatório em variáveis locais e parâmetros de método quando o valor não for reatribuído |
| Logger | `org.jboss.logging.Logger` — logger padrão do Quarkus; nunca SLF4J direto (`LoggerFactory`) nem `java.util.logging` |
| Declaração | `private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(Classe.class)` |
| Formatação | `log.warnf(...)` / `log.debugf(...)` com `%s` (printf-style); nunca `{}` de SLF4J |
| Comentários | Apenas quando o **porquê** é não-óbvio; sem javadoc de rotina, sem blocos multi-linha |

---

## Fora do escopo

- Suporte ao método `CONNECT` (tunneling TCP)
- Múltiplas políticas TLS por backend
- Transformação ou inspeção do body
- Autenticação no proxy
- Isolamento de concorrência intra-host: o bulkhead opera por `scheme+host:port`; endpoints de latência heterogênea no mesmo host (ex.: `tools/call` vs `prompts/get` via MCP JSON-RPC no mesmo path) compartilham o mesmo contador. Resolver esse caso exigiria inspeção do body (incompatível com `stream-caching-enabled=false`) ou um header de classificação do caller (`x-proxy-bulkhead-group`) — ambos fora do perfil de laboratório atual.
