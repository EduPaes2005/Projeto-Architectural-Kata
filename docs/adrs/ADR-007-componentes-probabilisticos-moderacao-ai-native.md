# ADR 007: Componentes Probabilísticos para Moderação de Chat via Arquitetura Defensiva

- **Status:** Aprovado
- **Data:** 20/11/2026
- **Decisores:** Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas
- **Nota de Renumeração:** Registrada originalmente no relatório da Sprint 4.1 como "ADR 006". Renumerada para **ADR 007** no documento consolidado para preservar a ADR 006 (Seleção da Linguagem Principal do Back-end: Go). O conteúdo da decisão permanece inalterado.

---

## Contexto
Os drivers prioritários envolvidos são **Performance de Rede**, **Escalabilidade/TCO** e **Privacidade/LGPD** (retenção máxima de 90 dias). Até a Sprint 3, a moderação dependia exclusivamente de regex e listas estáticas, falhando contra *leet speak* e substituições fonéticas, gerando falsos positivos e deixando passar ofensas sutis, assédio velado e *doxxing*. Por outro lado, integrar LLMs de forma ingênua introduziria latência acima de 400 ms, custo proibitivo em tokens, riscos de *prompt injection* e não-determinismo.

---

## Decisão
Adotar **SLMs locais e classificadores semânticos**, restritos ao **Bounded Context de Chat**, operando sob **Arquitetura Defensiva** no contexto isolado **Motor AI-Native e Autonomia**:
1. **Isolamento absoluto do gameplay:** Proibido acoplamento síncrono de IA aos servidores autoritativos em Go (60 Hz).
2. **Cache semântico local por região:** Toda mensagem sanitizada consulta o cache (Redis Stack/Valkey com HNSW) antes de qualquer modelo; similaridade `>= 0,93` libera ou bloqueia em `<= 5 ms` com custo zero de token.
3. **PII Masking obrigatório** antes de vetorizar ou enviar qualquer texto a modelos de IA (`[DADO_PESSOAL_REMOVIDO]`).
4. **Circuit Breaker de 250 ms** (abertura > 20% erro/timeout em 10 s) com **fallback para regras determinísticas** e **auditoria assíncrona via DLQ**.

---

## Alternativas Rejeitadas
- **Alternativa A — Moderação puramente determinística:** Incapaz de detectar toxicidade contextual, *leet speak* e assédio velado.
- **Alternativa B — Chamada síncrona de todas as mensagens a LLM em nuvem:** Gera custos na casa de milhões de dólares/mês no pico e latência superior a 400 ms.
- **Alternativa C — Inferência no cliente via WebGPU:** Exclui dispositivos modestos e permite adulteração/bypass no navegador do usuário.

---

## Impacto nos Atributos de Qualidade

| Atributo | Impacto Bruto | Mitigação Aplicada |
| :--- | :--- | :--- |
| **Performance (`p95 RTT <= 120 ms`)** | Negativo (latência de inferência) | Cache absorve 70–85% em `<= 5 ms`; timeout de `250 ms` limita o pior caso. |
| **Escalabilidade e TCO** | Negativo (custo em tokens) | Apenas ~5–10% das mensagens chegam à IA; custo de tokens reduzido em > 90%. |
| **Disponibilidade (SLA 99,9%)** | Negativo (provedores de IA falham) | Circuit Breaker e fallback determinístico com enfileiramento em DLQ. |
| **Privacidade / LGPD** | Positivo | PII mascarado antes da vetorização; embeddings dissociados do identificador real. |
| **Confiabilidade e Segurança** | Positivo | Detecção semântica de assédio velado, toxicidade contextual e *prompt injection*. |

---

## Fitness Functions
1. **Hit Ratio do Cache Semântico (Prometheus):** Manter `hit ratio >= 70%` no pico; queda abaixo de `60%` por mais de `15 minutos` dispara alerta de **severidade 1**.
2. **Latência de Cauda (CI):** Teste automatizado com **15.000 mensagens simultâneas** validando `p95 <= 40 ms` (mensagens cacheadas) e `p99 <= 300 ms` (modelo neural sob fallback).
3. **Auditoria Diária de PII:** Execução diária com **1.000 payloads sintéticos** (cartões, CPFs e e-mails) exigindo **100% de anonimização**; qualquer falha interrompe a esteira.
4. **Chaos do Circuit Breaker:** Simulando indisponibilidade total do provedor de IA, o circuito deve abrir em **menos de 10 s** mantendo o chat ativo sem queda de throughput.
