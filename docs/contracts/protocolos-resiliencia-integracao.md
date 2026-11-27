# Protocolos de Integração, Contrato de Dados e Resiliência Ativa

## 1. Mapeamento Completo das Interações (C4 Nível 2)

| Interação | Protocolo | Natureza | Observações Arquiteturais |
| :--- | :--- | :---: | :--- |
| Solicitar autenticação | HTTPS | Síncrono | Plano de controle via Gateway Global (rate limiting justo + autoscaling até 4.000 logins/s). |
| Encaminhar validação de conta | HTTPS / JSON | Síncrono | Protegido por Circuit Breaker e Retry no Serviço de Autenticação Centralizado. |
| Solicitar sala privada | HTTPS | Síncrono | Plano de controle (até 5.334 req/s de pico) via Facade no Gateway Global. |
| Trafegar inputs de comandos FPS | gRPC / WebSockets *(WebRTC DataChannel UDP no gameplay — ADR 006)* | Síncrono | Fluxo interno do Realm (60 Hz; p95 gateway -> partida <= 20 ms; RTT p95 <= 120 ms). |
| Redirecionar tráfego de chat | WebSockets / TCP | Síncrono | Conexões persistentes no shard local (~333 msg/s por realm no pico). |
| Gravar estado operacional / histórico local | TCP / SQL | Síncrono | Persistência estritamente isolada no banco do próprio shard (Zero Cross-Realm Access). |
| Publicar `SessaoIniciada` | AMQP | Assíncrono | Contrato estrito em JSON Schema; sem acesso direto do serviço central ao banco do shard. |
| Alocar sala (`realmId + roomId`) no Realm | Pub/Sub via Barramento | Assíncrono | Fixação de afinidade da sala no realm escolhido. |
| Publicar resumo de partida finalizada | AMQP | Assíncrono | Apenas o resumo consolidado (~66,67 resumos/s); frames de movimento nunca vão ao barramento. |
| Enviar mensagens de canais globais | Pub/Sub AMQP | Assíncrono | Fan-out hierárquico consolidado pelo Agregador de Canais Globais. |

---

## 2. Contrato Crítico de Dados: Evento `SessaoIniciada`

Formalizado em [`./sessao-iniciada.schema.json`](./sessao-iniciada.schema.json) (`type: object`, com os cinco campos abaixo obrigatórios em `required`). É o contrato mais crítico do sistema por conectar a autenticação centralizada ao realm de destino sem acoplamento de banco de dados.

| Campo | Tipo | Obrigatório | Descrição |
| :--- | :--- | :---: | :--- |
| `eventId` | `string (uuid)` | Sim | Identificador único do evento (controle de idempotência) |
| `playerId` | `string` | Sim | Identificador pseudônimo do jogador (minimização/LGPD) |
| `realmId` | `string` | Sim | Realm de destino da sessão |
| `timestamp` | `string (date-time)` | Sim | Momento de geração do evento |
| `sessionToken` | `string` | Sim | Token de sessão assinado, válido para o realm |

---

## 3. Resiliência Ativa: Circuit Breaker na Autenticação Centralizada

O **Serviço de Autenticação Centralizado** foi identificado como o **elo mais fraco** da arquitetura por constituir ponto singular no caminho crítico do login (além de acumular cobrança/pagamento nesta fase).

### Parâmetros de Configuração (ADR 004)
- **Timeout:** `1,5 s` *(alinhado ao teto p95 do Cenário QAW 1)*.
- **Retry:** `2 tentativas` com **backoff exponencial** (`200 ms`, depois `600 ms`).
- **Critério de Abertura do Circuito (Open):** `5 falhas consecutivas` **ou** taxa de erro `> 50%` em janela de `10 s`.
- **Estado Half-Open:** `1 requisição de teste` a cada `10 s` para sondar recuperação.
- **Fallback (Degradação Graciosa):**
  - Novos logins recebem erro amigável sem travar o front-end nem gerar cascata de timeouts.
  - Jogadores já autenticados mantêm permissão de reconexão usando **sessão em cache local**.

---

## 4. Resiliência de Chat em Tempo Real e Retenção LGPD (ADR 003)

### Operação sob Falha (Cenário QAW 3)
- **Cenário:** Falha em instância de chat ou partição do broker durante pico de `500.000` usuários ativos e `33.334 msg/s` (`~333 msg/s` por realm).
- **Mecanismo de Recuperação:**
  1. Nó com falha é removido imediatamente do balanceamento;
  2. Clientes reconectam de forma transparente ao **mesmo realm** (`realmId + channelId`);
  3. Mensagens confirmadas são retomadas de forma **idempotente**;
  4. Canais globais permanecem ativos por meio do **Agregador de Canais Globais**.
- **Metas Mensuráveis:** `RTO <= 10 s`, perda `<= 0,1%`, duplicação `<= 0,1%`, `p95` de entrega `<= 200 ms` e disponibilidade mensal `>= 99,95%`.

### Governança de Dados de Chat (LGPD)
- **Retenção máxima:** janela móvel de **90 dias** para chat identificável, com TTL por categoria *(hipótese arquitetural sujeita a revisão jurídica)*.
- **Impacto de Capacidade:** Reduz o armazenamento em 5 anos de `2,365 PB` (retenção integral com 3 cópias) para **`116,64 TB`** (3 cópias), diminuindo simultaneamente o TCO e a superfície de vazamento de dados pessoais.
