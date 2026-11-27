# Arquitetura Defensiva e Motor AI-Native e Autonomia

## 1. Problema de Engenharia
Submeter `16.667` a `33.334 msg/s` de chat diretamente a LLMs em nuvem inviabilizaria custo (milhões de dólares/mês), violaria a meta de latência (`p95 RTT <= 120 ms` / inferência > 400 ms) e exporia dados pessoais sob a **LGPD**. Por outro lado, listas estáticas e regex puras falham contra *leet speak*, substituições fonéticas, assédio velado e *doxxing*.

A solução adotada é a **Defesa em Profundidade em 6 Tiers** dentro de um contexto isolado (**Motor AI-Native e Autonomia**), onde filtros determinísticos e cache semântico absorvem mais de **90%** do volume antes de qualquer inferência neural.

---

## 2. Pipeline Defensivo em 6 Tiers

| Tier | Componente | Responsabilidade | SLA |
| :---: | :--- | :--- | :---: |
| **0** | **Ingress Gateway (Realm)** | Validação de JSON Schema e Rate Limiting (Token Bucket por conexão WebSocket; > 5 req/s -> HTTP 429) | `<= 1 ms` |
| **1** | **Input Guardrails** | Sanitização XSS, PII Masking (LGPD), Bloom Filter (> 50.000 termos, `O(1)` < 1 ms), anti-jailbreak/prompt injection | `<= 2 ms` |
| **2** | **Cache Semântico (Redis Stack / Valkey)** | Embedding ultraleve local (`MiniLM-L6-v2` quantizado ONNX, 384d < 2 ms) e busca HNSW (cosseno `>= 0,93`) | `<= 5 ms (HIT)` |
| **3** | **Buffer e Fila Prioritária** | Fila local AMQP que amortece rajadas e desacopla o tempo de resposta da IA | `5-15 ms` |
| **4** | **Inferência Probabilística (Model Tiering)** | **SLM Tier 1** local (1B-7B quantizado, `<= 40 ms`) ou **LLM Tier 2** sob Circuit Breaker (`250 ms`) e fallback | `150-250 ms (MISS)` |
| **5** | **Output Guardrails e Egress** | Validação anti-alucinação em JSON Schema estrito, filtragem residual, escrita no cache e broadcast | `<= 2 ms` |

---

## 3. Especificação de Guardrails, Cache Semântico e Resiliência

### 3.1. Guardrails de Entrada
- **Rate Limiting e Contrato:** Token Bucket por conexão WebSocket; rajadas acima de `5 req/s` ou violação de schema resultam em descarte imediato com código `429`.
- **PII Masking (LGPD):** Expressões regulares e autômatos detectam **CPF, cartões de crédito, e-mails e telefones**, substituindo-os por `[DADO_PESSOAL_REMOVIDO]` **antes** de qualquer vetorização ou envio a modelos.
- **Bloom Filter:** Mais de `50.000` termos de toxicidade, links maliciosos e spam verificados em `O(1)` em `< 1 ms`.
- **Anti-Jailbreak e Prompt Injection:** Classificadores determinísticos protegem módulos onde a IA interage com comandos de jogadores.

### 3.2. Cache Semântico
| Dimensão | Especificação |
| :--- | :--- |
| **Modelo de Embedding** | Ultraleve local (ex.: `MiniLM-L6-v2` quantizado em ONNX), 384 dimensões, `< 2 ms` |
| **Armazenamento** | Redis Stack / Valkey na mesma subnet dos Gateways em Go, com índice **HNSW** |
| **Limiar (Cosseno)** | `>= 0,93` = **HIT** (`<= 5 ms`, custo zero de token); `< 0,93` = **MISS**, segue ao modelo neural |
| **Invalidação** | Política **LRU** + TTL móvel de **48 h** (mensagens comuns) e **7 dias** (ofensas catalogadas) |

### 3.3. Guardrails de Saída e Circuit Breaker
- **JSON Schema Estrito de Saída:**
  - `status`: `"FLAGGED"` ou `"CLEAN"`
  - `confidence_score`: número entre `0.0` e `1.0`
  - `violation_category`: `enum` fechado
  - *Respostas fora do schema são barradas automaticamente.*
- **Filtragem Residual:** Impede que mensagens marcadas como moderadas sejam ecoadas no canal.
- **Circuit Breaker de IA:** Timeout de **`250 ms`**; o circuito abre se erros ou timeouts ultrapassarem **`20%` em `10 s`**.
- **Fallback Gracioso:** Com o circuito aberto, aplica-se aprovação provisória via regras heurísticas locais e a mensagem é enfileirada em uma **Dead Letter Queue (DLQ)** para auditoria assíncrona, mantendo o chat disponível.

---

## 4. Componentes Internos do Motor AI-Native (C4 Nível 3)

| Componente | Papel e Tecnologia | SLA | Impacto em TCO |
| :--- | :--- | :---: | :--- |
| **AI Gateway** | Fachada e roteador; desacopla provedores; métricas e rate limiting por rota | `<= 3 ms` | Baixo (Go em Graviton) |
| **Guardrails** | Filtros de entrada (prompt injection, PII) e saída (JSON Schema, anti-alucinação) | `<= 2 ms` | Mínimo |
| **Banco Vetorial** | Índice HNSW para semantic caching e RAG (Redis Stack / Valkey) | `<= 5 ms` | Médio (RAM) |
| **SLM, Tier 1** | Modelos quantizados de 1B a 7B parâmetros (ex.: `Llama-3.2-1B`, `MiniLM`) | `<= 40 ms` | Econômico |
| **LLM, Tier 2** | Modelos de grande porte para análise profunda e disputas de torneio | `200-500 ms` *(CB 250 ms)* | Alto por token, sob demanda residual |

### Fluxos de Integração e Isolamento do Gameplay
- **Fluxo Síncrono (Chat):** `Chat Local` -> `AI Gateway` (`gRPC/HTTPS`, Circuit Breaker `250 ms`) -> `Guardrails` -> `Banco Vetorial`. Em **HIT** (`>= 0,93`), retorna em `<= 5 ms`. Em **MISS**, segue ao **SLM Tier 1** (resolve ~85% dos misses com alta certeza e grava no cache). Somente casos com **ambiguidade severa (confiança entre `0,40` e `0,70`)** ou infrações complexas sobem ao **LLM Tier 2**.
- **Fluxo Assíncrono (RAG e Governança):** `Agregador de Canais Globais` e `Serviços de Realm` -> `Barramento Central` -> `AI Gateway` (`AMQP/PubSub`), alimentando o pipeline RAG e relatórios sem competir com o chat em tempo real.
- **Imunidade do Loop de Física:** É **proibido** acoplamento síncrono entre IA e o servidor autoritativo em Go (60 Hz, `p95 <= 20 ms`, WebRTC UDP). O Motor AI-Native escala elasticamente de forma independente (GPUs/vetores vs. nós `c7g` Graviton) e pode ser substituído ou desligado sem interromper gameplay ou autenticação.
