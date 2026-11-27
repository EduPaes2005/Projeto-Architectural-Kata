# ADR 004: Protocolos de Comunicação, Contratos de Dados e Táticas de Resiliência Ativa

- **Status:** Proposto
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Disponibilidade, Escalabilidade, Performance de Rede e Privacidade/LGPD

---

## Contexto
O mapeamento de integrações do Diagrama C4 Nível 2 exigiu formalizar protocolos (HTTPS, gRPC, WebSockets, TCP/SQL e AMQP), contratos assíncronos críticos e mecanismos ativos de proteção contra falhas. Na análise de riscos, o **Serviço de Autenticação Centralizado** foi identificado como o **elo mais fraco**, por ser o único ponto singular no caminho crítico do login (4.000 logins/s de pico).

---

## Decisão
1. **Contrato Estrito em JSON Schema para `SessaoIniciada`:** Exigir os cinco campos obrigatórios (`eventId` UUID para idempotência, `playerId` pseudônimo LGPD, `realmId`, `timestamp` date-time e `sessionToken` assinado), conforme `docs/contracts/sessao-iniciada.schema.json`.
2. **Circuit Breaker com Retry no Serviço de Autenticação Centralizado:**
   - **Timeout:** `1,5 s` (teto p95).
   - **Retry:** `2 tentativas` com backoff exponencial (`200 ms`, `600 ms`).
   - **Abertura do circuito:** `5 falhas consecutivas` ou taxa de erro `> 50%` em `10 s`.
   - **Half-open:** `1 requisição de teste` a cada `10 s`.
3. **Degradação Graciosa no Front-end:** Com o circuito aberto, retornar erro amigável para novos logins, preservando a **reconexão de jogadores já autenticados via cache local de sessão**.

---

## Alternativas Rejeitadas
- **Chamadas síncronas sem Circuit Breaker ou com retries agressivos:** Provocam *thread starvation*, tempestade de retentativas e travamento do front-end em timeouts encadeados.
- **Payloads não tipados / sem `eventId`:** Expõem os realms a estados inconsistentes por entrega duplicada de eventos e vazamento de identificadores reais (violação LGPD).

---

## Consequências
### Positivas (+)
- Proteção efetiva contra falhas em cascata originadas na autenticação central.
- Front-end permanece responsivo em cenários de degradação sem travar em timeouts.
- Garantia de idempotência (`eventId`) e pseudonimização (`playerId`) no tráfego assíncrono.

### Negativas (-)
- Aumento da complexidade de infraestrutura (telemetria e calibração de Circuit Breaker/Retry).
- Maior complexidade no tratamento de estados de erro e cache de reconexão no cliente web.

---

## Governança e Fitness Functions
- **Validação de Schema no CI e em Runtime:** Rejeição automática de eventos `SessaoIniciada` fora do JSON Schema oficial.
- **Testes de Falha Controlada:** Simulação de indisponibilidade/latência > 1,5 s no Serviço de Autenticação verificando abertura do circuito, envio de fallback amigável e manutenção de reconexões via cache local.
