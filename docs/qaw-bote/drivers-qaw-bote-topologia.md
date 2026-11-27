# Drivers Arquiteturais, QAW, BOTE e Topologia de Dados — World of Webcraft

## 1. Contexto do Problema
O **World of Webcraft** é um MMORPG distribuído que executa inteiramente no navegador web. Autenticação, partidas multiplayer e comunicação social ocorrem por HTTPS, WebSockets e WebRTC, impondo restrições rigorosas de latência, escalabilidade e gestão de conexões persistentes.

Três fontes de pressão técnica delimitam o projeto:
1. Milhões de sessões simultâneas;
2. Partidas privadas autoritativas (salas de torneio);
3. Comunicação em tempo real (chat).

O custo dominante não é armazenamento nem volume de requisições HTTP, mas sim **rede e computação de tempo real**. Sob o ponto de vista legal, contas, identificadores, IPs, telemetria vinculável e conteúdo de chat estão sujeitos à **LGPD** (finalidade, necessidade, transparência, segurança, prestação de contas e eliminação).

---

## 2. Drivers Arquiteturais (Priorizados via QAW)

| # | Driver | Prioridade | Por que pode inviabilizar o sistema |
| :--- | :--- | :--- | :--- |
| 1 | **Escalabilidade** | Crítica | Sem particionamento e escala horizontal, login, salas e fan-out saturam um núcleo central. |
| 2 | **Performance de rede** | Crítica | Jogo em tempo real perde jogabilidade com latência, jitter ou filas. O QPS HTTP isolado não representa o fluxo real de eventos. |
| 3 | **Disponibilidade** | Alta | Quedas em torneios encerram partidas, geram perda de receita e de confiança. |
| 4 | **Modulabilidade** | Alta | Permite evoluir login, chat e jogo separadamente. *(Subcaracterística de manutenibilidade — materializada nas ADRs 000 e 005).* |
| 5 | **Elasticidade** | Alta | Picos curtos de demanda exigem crescer e reduzir recursos conforme métricas de conexão, eventos e filas. |
| 6 | **Privacidade/LGPD** | Obrigatória | Dados de conta, chat, telemetria e potencialmente de menores exigem governança de ciclo de vida desde a concepção. |

### Atributos Transversais (Não Elencados como Drivers Centrais)
- **Segurança:** Isolamento entre autenticação e estado dos realms (ADR 001) e pseudonimização de identificadores.
- **Testabilidade:** Fitness functions em todas as ADRs (ArchUnit, testes de carga, auditoria de PII e testes de caos).
- **Manutenibilidade:** Sharding por realm com desacoplamento dos domínios (ADR 000) e limites explícitos de Bounded Contexts (ADR 005).

---

## 3. Cenários QAW

### Cenário 1: Login em massa *(Driver primário: Escalabilidade)*
| Campo | Descrição |
| :--- | :--- |
| **Fonte do estímulo** | Jogadores acessando após lançamento de temporada ou início de torneio |
| **Estímulo** | 1.200.000 logins em 10 min: 2.000 req/s médios, 4.000 req/s de pico de projeto |
| **Artefato** | Gateway global, serviço central de autenticação/conta e serviço de sessão do realm |
| **Ambiente** | Pico planejado: 2 milhões de usuários simultâneos em 100 realms |
| **Resposta** | Gateway aplica rate limiting justo e autoscaling; autenticação valida a conta; evento `SessaoIniciada` é publicado no barramento, sem acesso direto ao banco do shard |
| **Medida** | 4.000 logins/s; sucesso >= 99,9%; p95 <= 1,5 s; nenhum realm acima de 30.000 sessões; fila estabilizada em até 5 min |

### Cenário 2: Salas privadas de torneio *(Drivers: Performance de rede + Escalabilidade)*
| Campo | Descrição |
| :--- | :--- |
| **Fonte do estímulo** | Organizadores iniciando rodada; 800.000 jogadores entrando em salas privadas |
| **Estímulo** | 800.000 entradas em 5 min (2.667 req/s médios, 5.334 req/s de projeto); 10 eventos/s por jogador |
| **Artefato** | Alocador de salas, gateway do realm e servidor autoritativo da partida |
| **Ambiente** | 80.000 salas simultâneas de 10 jogadores em 100 realms |
| **Resposta** | Alocador fixa cada sala em um único realm, escala nós dentro do shard e mantém o loop local; só eventos de domínio e o resumo final seguem pelo barramento |
| **Medida** | Sucesso de entrada >= 99,9%; p95 de entrada <= 2 s; p95 gateway -> servidor de partida <= 20 ms; p95 RTT ao usuário <= 120 ms; zero chamadas síncronas cross-realm durante a partida |

### Cenário 3: Chat em tempo real sob falha *(Drivers: Disponibilidade + Performance de rede)*
| Campo | Descrição |
| :--- | :--- |
| **Fonte do estímulo** | Falha de infraestrutura em instância de chat ou partição do broker durante o pico |
| **Estímulo** | Uma instância falha com 500.000 usuários ativos no chat; pico de 33.334 msg/s (~333 msg/s por realm) |
| **Artefato** | Gateway de conexões, serviço de chat do realm, partição do barramento e agregador de canais globais |
| **Ambiente** | Operação de pico com conexões persistentes e canais locais/globais |
| **Resposta** | Nó removido do balanceamento; clientes reconectam ao mesmo realm; mensagens confirmadas retomadas de forma idempotente; chat global continua pelo agregador |
| **Medida** | RTO <= 10 s; perda <= 0,1% e duplicação <= 0,1%; p95 de entrega <= 200 ms; disponibilidade mensal >= 99,95% |

### Trade-offs Revelados pelo QAW
- **Performance vs. Consistência:** Gameplay e chat toleram consistência eventual; pagamento e saldo exigem consistência forte.
- **Disponibilidade vs. Ordem Global:** Ordem total entre realms elevaria latência e acoplamento; ordena-se por canal/partição.
- **Elasticidade vs. Afinidade:** Conexões longas e salas fixas dificultam redistribuição instantânea; exigem escala preventiva e drenagem.
- **Privacidade vs. Observabilidade:** Logs não devem registrar tokens, chat completo ou dados pessoais sem finalidade definida.

---

## 4. Análise BOTE (Back-of-the-Envelope)
Unidades adotadas: `1 TB = 10^12 bytes` e `1 PB = 10^15 bytes`. Os valores representam hipóteses de dimensionamento mantidas consistentes entre QAW, BOTE e ADRs.

### 4.1. Premissas
| Premissa | Valor |
| :--- | :--- |
| Usuários simultâneos no pico | 2.000.000 |
| Realms ativos | 100 (20.000 sessões/realm; limite de projeto 30.000) |
| Contas ao final de 5 anos | 50.000.000 |
| Login de evento | 1.200.000 em 10 min (2.000 req/s; rajada 2x) |
| Participação em torneios | 40% do pico (800.000 jogadores) |
| Tamanho da sala | 10 jogadores (80.000 salas) |
| Entrada em salas | 800.000 em 5 min (2.667 req/s; rajada 2x) |
| Eventos de jogo | 10/jogador/s (8.000.000/s de entrada) |
| Usuários ativos no chat | 25% do pico (500.000) |
| Frequência de chat | 1 msg/30 s (16.667 msg/s; rajada 2x) |
| Duração média de partida | 20 min (66,67 resumos/s) |
| Horizonte | 5 anos = 157.680.000 s |

### 4.2. Distribuição por Realm
| Indicador | Sistema | Por Realm |
| :--- | :--- | :--- |
| Sessões simultâneas | 2.000.000 | 20.000 |
| Jogadores em torneios | 800.000 | 8.000 |
| Salas privadas simultâneas | 80.000 | 800 |
| Eventos de entrada | 8.000.000/s | 80.000/s |
| Entregas (fan-out 9 pares) | 72.000.000/s | 720.000/s |
| Mensagens de chat no pico | ~33.334/s | ~333/s |

### 4.3. Carga por Segundo (Plano de Controle vs. Plano de Tempo Real)
| Fluxo | Média | Pico de Projeto |
| :--- | :--- | :--- |
| Login *(Plano de controle)* | 2.000 req/s | 4.000 req/s |
| Entrada em salas *(Plano de controle)* | 2.667 req/s | 5.334 req/s |
| **Plano de controle (total)** | **4.667 req/s** | **9.334 req/s** |
| Chat (entrada — tempo real) | 16.667 msg/s | 33.334 msg/s |
| Gameplay (entrada — tempo real) | 8.000.000 eventos/s | Acima da média |
| Gameplay (entregas — tempo real) | 72.000.000/s | Acima da média |

### 4.4. Armazenamento em 5 Anos
| Conjunto | Bruto | Com 3 Cópias / Índices |
| :--- | :--- | :--- |
| Perfis/contas (50M x 12 KB) | 0,60 TB | 2,34 TB |
| Resumos de partida (66,67/s x 20 KB) | 210,24 TB | 630,72 TB |
| Chat, retenção integral (16.667/s x 300 B) | 788,40 TB | 2,365 PB |
| Gameplay bruto, se persistido (8M/s x 120 B) | 151,373 PB | 454,118 PB |

> Persistir todos os dados integralmente exigiria aproximadamente **457,1 PB** com três cópias, inviabilizando a retenção indiscriminada.

### 4.5. Política de Retenção Recomendada (LGPD)
| Dado | Retenção | Capacidade |
| :--- | :--- | :--- |
| Perfis/contas | Durante a relação + política de eliminação | 2,34 TB |
| Resumos de partida | 5 anos, se houver justificativa | 630,72 TB |
| Chat identificável | 90 dias, janela móvel *(hipótese arquitetural)* | 116,64 TB (3 cópias) |
| Eventos brutos de gameplay | Buffer curto; não reter por 5 anos | Fora do histórico |
| Telemetria analítica | Amostrada e anonimizada/agregada | A dimensionar |

- **Capacidade inicial consolidada:** `2,34 + 630,72 + 116,64 = 749,70 TB`.
- **Com 30% de folga operacional:** `974,61 TB (~0,98 PB)` antes de backup externo.

---

## 5. Nexo Arquitetural: Topologia de Dados
A equipe selecionou **Topologia de Dados** como Nexo Arquitetural por conectar diretamente sharding, escalabilidade, disponibilidade, consistência e LGPD.

**Erro fatal que inviabiliza o projeto:** manter um banco global único para o estado de jogo ou permitir que um realm consulte o banco de outro. Isso transformaria o sharding em ilusão operacional, gerando gargalo de concorrência, falhas em cascata, latência cross-region no caminho crítico e impossibilidade de auditar exclusão/retificação de dados pessoais.

### Regras de Particionamento
- Chave principal: `realmId`; salas: `realmId + roomId`; chat: `realmId + channelId`.
- Afinidade: sala de torneio permanece em um único realm durante toda a partida.
- Identidade, conta e pagamento ficam fora dos shards; pagamento mantém consistência forte.
- Comunicação cross-realm ocorre apenas por eventos versionados, idempotentes e rastreáveis.
- Eventos de jogo trafegam apenas identificador operacional pseudônimo (`playerId`).

### Falhas de Topologia e Mitigações
| Falha | Impacto | Mitigação / Fitness Function |
| :--- | :--- | :--- |
| Realm acessa banco de outro | Acoplamento, latência, falha em cascata | Bloqueio em rede/IAM e teste arquitetural (zero acessos) |
| Partição quente | Um realm satura enquanto outros ficam ociosos | Alertas em 70%/85%; bloqueio no limite de 30.000; split/migração fora de partidas |
| Evento duplicado / fora de ordem | Estado projetado incorretamente | `eventId`, `idempotencyKey`, sequenciamento por agregado |
| Identidade copiada em todos os shards | Exclusão e superfície de vazamento ampliadas | Pseudonimização, minimização, catálogo de retenção |
| Barramento no loop de tick | Latência e throughput inviáveis | Frames ficam no realm; barramento recebe apenas eventos discretos e resumos |
