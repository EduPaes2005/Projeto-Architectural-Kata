# Projeto Architectural Kata — World of Webcraft

**Disciplina:** Arquitetura de Software | **Período:** 6º Período — Engenharia de Software  
**Turma:** Quinta-feira | **Sprint / Entrega:** Sprint 4 — Final do Projeto por Equipes  
**Equipe:** Equipe Aurora | **Professor:** Prof. Sidney | **Data:** 26/11/2026

---

## Identificação da Equipe

| Nome do Integrante | Função na Sprint 4 (Liderança / Pesquisador / Executor) | GitHub |
| :--- | :--- | :--- |
| **Eduardo Paes** (Líder) | Líder & Arquiteto Principal |
| **Giovanna Gonçalves** | Pesquisador & Modelagem DDD |
| **Nikolas** | Pesquisador & Engenheiro de Resiliência & Integração |
| **Nicole Assis** | Executor & Especialista em IA-Native & Autonomia; Executor & QA & Desenvolvedor de Fitness Functions |

---

## 1. Visão Geral do Sistema e Domínio de Negócio

### 1.1. Contexto do Projeto Selecionado
- **Architectural Kata:** World of Webcraft — MMORPG distribuído inteiramente via navegador web.
- **Desafio Principal de Engenharia:** Sustentar milhões de sessões simultâneas via navegador (HTTPS/WebSockets/WebRTC), partidas privadas autoritativas (salas de torneio) e comunicação social em tempo real (chat) sob estrita conformidade com a LGPD. Em escala de pico (2.000.000 de usuários simultâneos em 100 realms, 80.000 salas privadas de 10 jogadores, 8.000.000 de eventos/s de entrada, 72.000.000 de entregas/s via fan-out e 33.334 msg/s de chat), o gargalo dominante não é o QPS HTTP nem armazenamento bruto, mas sim a rede (egresso representa ~79% do TCO) e a computação de tempo real, exigindo baixa latência sem comprometer a governança de dados pessoais (minimização, pseudonimização e retenção limitada).

### 1.2. Resumo da Solução Proposta
A equipe adotou uma **Arquitetura Orientada a Eventos com Sharding por Realm (ADR 000)**, particionando o sistema em 100 realms quase independentes (limite de projeto de 30.000 sessões por realm) com isolamento total de bancos de dados (zero acesso cross-shard). O tráfego foi dividido em dois planos distintos: o **plano de controle** (HTTP/API para login centralizado com rate limiting/autoscaling e alocação de salas por afinidade `realmId + roomId`) e o **plano de tempo real**, no qual o loop autoritativo de gameplay roda localmente no shard em Go (60 Hz sobre WebRTC DataChannel/UDP) e o chat opera localmente via WebSockets/TCP. O Barramento de Eventos Central (pub/sub AMQP) transporta apenas eventos discretos de domínio e resumos de partida (~66,67/s), nunca frames de movimento ou disparo.

Sob a perspectiva de modelagem e evolução, os limites do sistema foram formalizados em três Bounded Contexts (**Identidade & Conta**, **Gameplay** e **Chat**) integrados exclusivamente via eventos assíncronos (OHS + Published Language no upstream e Camadas Anti-Corrupção no downstream), sustentados por princípios SOLID e nove padrões GoF aplicados a variações reais. A seleção tecnológica definiu **Go** (ARM64/Graviton em `sa-east-1`) como linguagem principal do back-end após avaliação em matriz ponderada (106 pontos contra 101 do Rust e 76 do Node.js) e análise de TCO.

Para atender à moderação contextual do chat sem violar o orçamento de latência (p95 RTT <= 120 ms), o TCO ou a LGPD, a solução incorpora um contexto isolado denominado **Motor AI-Native e Autonomia (ADR 007)** sob Arquitetura Defensiva em 6 camadas. Filtros determinísticos (Rate Limiting, PII Masking, Bloom Filter) e um Cache Semântico em Redis Stack/Valkey (índice HNSW, similaridade >= 0,93) absorvem mais de 90% do volume antes que casos ambíguos sejam roteados para inferência em camadas (SLM Tier 1 e LLM Tier 2) protegida por Circuit Breaker (250 ms) e fallback gracioso com DLQ.

---

## 2. Atributos de Qualidade e Matriz QAW

Documentação detalhada de Drivers, Cenários QAW, BOTE e Topologia de Dados em [`./docs/qaw-bote/drivers-qaw-bote-topologia.md`](./docs/qaw-bote/drivers-qaw-bote-topologia.md).

Os seis drivers priorizados no QAW são: **Escalabilidade** (Crítica), **Performance de rede** (Crítica), **Disponibilidade** (Alta), **Modulabilidade** (Alta — subcaracterística de manutenibilidade), **Elasticidade** (Alta) e **Privacidade/LGPD** (Obrigatória).

| Atributo de Qualidade | Cenário Padronizado (Fonte -> Estímulo -> Artefato -> Ambiente -> Resposta) | Medida da Resposta (Métrica Objetivo) |
| :--- | :--- | :--- |
| **Escalabilidade** *(Login em massa)* | **Fonte:** Jogadores acessando após lançamento de temporada ou início de torneio. **Estímulo:** 1.200.000 logins em 10 min (2.000 req/s médios, 4.000 req/s de pico de projeto). **Artefato:** Gateway global, serviço central de autenticação/conta e serviço de sessão do realm. **Ambiente:** Pico planejado de 2 milhões de usuários simultâneos em 100 realms. **Resposta:** Gateway aplica rate limiting justo e autoscaling; autenticação valida a conta; evento `SessaoIniciada` é publicado no barramento, sem acesso direto ao banco do shard. | 4.000 logins/s; sucesso >= 99,9%; p95 <= 1,5 s; nenhum realm acima de 30.000 sessões; fila estabilizada em até 5 min. |
| **Performance de Rede + Escalabilidade** *(Salas privadas de torneio)* | **Fonte:** Organizadores iniciando rodada; 800.000 jogadores entrando em salas privadas. **Estímulo:** 800.000 entradas em 5 min (2.667 req/s médios, 5.334 req/s de projeto); 10 eventos/s por jogador. **Artefato:** Alocador de salas, gateway do realm e servidor autoritativo da partida. **Ambiente:** 80.000 salas simultâneas de 10 jogadores em 100 realms. **Resposta:** Alocador fixa cada sala em um único realm, escala nós dentro do shard e mantém o loop local; só eventos de domínio e o resumo final seguem pelo barramento. | Sucesso de entrada >= 99,9%; p95 de entrada <= 2 s; p95 gateway-servidor de partida <= 20 ms; p95 RTT ao usuário <= 120 ms; zero chamadas síncronas cross-realm durante a partida. |
| **Disponibilidade + Performance de Rede** *(Chat em tempo real sob falha)* | **Fonte:** Falha de infraestrutura em instância de chat ou partição do broker durante o pico. **Estímulo:** Uma instância falha com 500.000 usuários ativos no chat; pico de 33.334 msg/s (~333 msg/s por realm). **Artefato:** Gateway de conexões, serviço de chat do realm, partição do barramento e agregador de canais globais. **Ambiente:** Operação de pico com conexões persistentes e canais locais/globais. **Resposta:** Nó removido do balanceamento; clientes reconectam ao mesmo realm; mensagens confirmadas retomadas de forma idempotente; chat global continua pelo agregador. | RTO <= 10 s; perda <= 0,1% e duplicação <= 0,1%; p95 de entrega <= 200 ms; disponibilidade mensal >= 99,95%. |

---

## 3. Visão Geral da Arquitetura (Modelo C4)

A arquitetura está documentada conforme as abstrações do Modelo C4 em `./docs/c4/`:
- 📄 **Nível 1 — Diagrama de Contexto:** [`./docs/c4/c1-contexto.puml`](./docs/c4/c1-contexto.puml)
- 📄 **Nível 2 — Diagrama de Contêineres:** [`./docs/c4/c2-conteineres.puml`](./docs/c4/c2-conteineres.puml)
- 📄 **Nível 3 — Diagrama de Componentes (Motor AI-Native):** [`./docs/c4/c3-componentes-ai-native.puml`](./docs/c4/c3-componentes-ai-native.puml) *(PR da Nicole)*
- 📄 **Diagrama de Classes (SOLID):** [`./docs/c4/diagrama-classes-solid.puml`](./docs/c4/diagrama-classes-solid.puml) *(PR da Giovanna)*

### Mapeamento de Protocolos e Natureza (C4 — Nível 2)

| Interação | Protocolo | Natureza |
| :--- | :--- | :--- |
| Solicitar autenticação | HTTPS | Síncrono |
| Encaminhar validação de conta | HTTPS / JSON | Síncrono |
| Solicitar sala privada | HTTPS | Síncrono |
| Trafegar inputs de comandos FPS | gRPC / WebSockets *(WebRTC DataChannel UDP no gameplay — ADR 006)* | Síncrono |
| Redirecionar tráfego de chat | WebSockets / TCP | Síncrono |
| Gravar estado operacional / histórico local | TCP / SQL | Síncrono |
| Publicar `SessaoIniciada` | AMQP | Assíncrono |
| Alocar sala (`realmId + roomId`) no Realm | Pub/Sub via Barramento | Assíncrono |
| Publicar resumo de partida finalizada | AMQP | Assíncrono |
| Enviar mensagens de canais globais | Pub/Sub AMQP | Assíncrono |

---

## 4. Domain-Driven Design (DDD) & Limites de Contexto

- Documentação completa em `./docs/ddd/bounded-contexts-mapa-contexto.md` e `./docs/adrs/ADR-005-ddd-bounded-contexts-integracao.md`.

### 4.1. Classificação dos Subdomínios
- **Core Domain (Domínio Principal):** **Gameplay** *(Alocador de Salas Global, Gateway do Realm, Servidor de Gameplay FPS, Serviço de Salas do Realm e Banco do Realm).*
- **Supporting Subdomain (Suporte):** **Chat** *(Serviço de Chat Local, Banco de Dados do Chat com retenção de 90 dias/LGPD e Agregador de Canais Globais).*
- **Generic Subdomain (Genérico / Suporte Global):** **Identidade & Conta** *(Serviço de Autenticação Centralizado, absorvendo temporariamente cobrança e pagamento; OAuth e Gateway de Pagamento são provedores externos).*

### 4.2. Mapa de Contexto e Camada Anti-Corrupção (ACL)
A integração ocorre exclusivamente por eventos assíncronos (**OHS + Published Language** no upstream) com **Anti-Corruption Layer (ACL)** no downstream via padrão Adapter:
- **Identidade -> Gameplay:** `SessaoIniciada` (OHS) traduzido em jogador pseudônimo (ACL).
- **Identidade -> Chat:** `SessaoIniciada` (OHS) convertido em participante de canal (ACL).
- **Gameplay -> Chat:** `SalaCriada` e `JogadorEntrouNaSala` (OHS) mapeados de `realmId + roomId` para `realmId + channelId` (ACL / `ChatSalaObserver`).
- **Provedor OAuth -> Identidade** e **Provedor de Pagamento -> Identidade:** ACLs protegem o modelo interno.

---

## 5. Padrões de Projeto & Princípios SOLID

### 5.1. Princípios SOLID Aplicados e Análise de Acoplamento
Organização em 5 namespaces lógicos (`Plano_Central`, `Processadores_Eventos`, `Abstracoes_Interfaces`, `Eventos_Dominio`, `Shard_Realm`):
- **SRP:** Separação entre `ContaUsuario`, `ServicoAutenticacao` e `IPoliticaRetencaoLGPD`.
- **OCP:** Extensão de novos eventos via `IProcessadorEvento` e novos formatos de torneio via subclasses de `AlocadorSalas`.
- **LSP:** Subclasses de `EventoDominio` processadas uniformemente no barramento.
- **ISP:** Interfaces segregadas (`IProcessadorGameplay`, `IPoliticaRetencaoLGPD`).
- **DIP:** Serviços centrais e de realm dependem de `IBarramentoEventos` e `IRepositorioRealm` em `Abstracoes_Interfaces`.
- **Métricas de Acoplamento (24/09/2026):** `Plano_Central` (D=0,00), `Processadores_Eventos` (D=0,00), `Abstracoes_Interfaces` (D=0,20), `Shard_Realm` (D=0,40 — atenção) e **`Eventos_Dominio` (D=0,86 — Zona de Dor)**, com refatoração planejada via extração de `IEvento` e padrão Adapter.

### 5.2. Catálogo de Padrões GoF Utilizados (3 Conjuntos)

| Padrão GoF | Categoria | Aplicação na Arquitetura | Problema de Engenharia Resolvido |
| :--- | :--- | :--- | :--- |
| **Builder** | Criacional | `RealmBuilder` | Construção passo a passo de Realms complexos com validação de invariantes. |
| **Adapter** | Estrutural | Consumidores de `Eventos_Dominio` e ACLs externas | Redução de acoplamento lógico em `Eventos_Dominio` (D=0,86) e isolamento de APIs de pagamento/OAuth. |
| **Strategy** | Comportamental | Roteamento do Chat (~33 mil msg/s no pico) | Alternância polimórfica entre chat local e global sem condicionais complexas. |
| **Composite** | Estrutural | `EntidadeJogo`, `JogadorLeaf`, `EsquadraoComposite` | Ações em lote uniformes sobre jogadores, esquadrões e guildas, e permissões hierárquicas de chat. |
| **Bridge** | Estrutural | `BarramentoEventosAbstrato` e `ProvedorTransporte` | Desacopla serviços de domínio dos drivers de rede (gRPC, WebSocket, AMQP). |
| **Flyweight** | Estrutural | `TipoProjetilFlyweight`, `FabricaProjetil`, `ProjetilEmJogo` | Separa estado intrínseco do extrínseco nos picos de 8M eventos/s (estimativa >90% de economia de RAM). |
| **Facade** | Estrutural | Gateway Global (`SolicitarSalaPrivada`) | Ponto único de entrada ocultando validação de sessão, rate limiting e alocação (DIP). |
| **Factory Method** | Criacional | `AlocadorSalas` (`CriarPartida`) | Instanciação extensível de formatos de torneio (eliminatória, pontos corridos) sem alterar o alocador (OCP). |
| **Observer** | Comportamental | Barramento de Eventos (`ChatSalaObserver`) | Abertura automática de canais de chat via assinatura de `SalaCriada` sem acoplamento ao Gameplay (OCP/DIP). |

---

## 6. Resiliência, Integração e IA-Native

### 6.1. Padrões de Resiliência e Contratos
- **Contrato `SessaoIniciada`:** JSON Schema estrito com `eventId` (UUID), `playerId` (pseudônimo LGPD), `realmId`, `timestamp` e `sessionToken`.
- **Circuit Breaker & Retry na Autenticação (ADR 004):** Timeout de 1,5 s, 2 retries com backoff exponencial (200 ms, 600 ms), abertura após 5 falhas seguidas ou erro > 50% em 10 s, Half-open (1 teste a cada 10 s) e fallback gracioso com reconexão em cache local.
- **Load Leveling e DLQ:** Filas locais AMQP absorvem picos no pipeline de moderação com Dead Letter Queue para auditoria; failover de chat com RTO <= 10 s e reconexão idempotente.

### 6.2. Motor AI-Native e Autonomia (ADR 007)
- **Defesa em Profundidade (6 Tiers) e Model Tiering:** Isolado do loop de 60 Hz em Go. Combina **Ingress Gateway** (Token Bucket > 5 req/s -> HTTP 429, <= 1 ms), **Input Guardrails** (XSS, PII Masking `[DADO_PESSOAL_REMOVIDO]`, Bloom Filter > 50.000 termos, anti-jailbreak, <= 2 ms), **Cache Semântico** (MiniLM-L6-v2 ONNX 384d + Redis Stack/Valkey HNSW, similaridade >= 0,93 -> HIT <= 5 ms), **Fila AMQP**, **SLM Tier 1** (1B–7B quantizado, <= 40 ms) e **LLM Tier 2** (200–500 ms sob Circuit Breaker de 250 ms com fallback heurístico + DLQ), encerrando em **Output Guardrails** com JSON Schema estrito (`status`, `confidence_score`, `violation_category`).

---

## 7. Registros de Decisões Arquiteturais (ADRs)

| ADR | Título da Decisão Arquitetural | Status | Arquivo |
| :--- | :--- | :--- | :--- |
| **ADR-000** | Estilo Arquitetural Orientado a Eventos com Sharding por Realm | Aceito | [`ADR-000`](./docs/adrs/ADR-000-estilo-arquitetural-sharding-realm.md) |
| **ADR-001** | Autenticação Centralizada e Isolamento de Bancos de Dados por Shard de Realm | Proposto | [`ADR-001`](./docs/adrs/ADR-001-autenticacao-centralizada-isolamento-shards.md) |
| **ADR-002** | Alocação Local de Salas e Processamento Local de Gameplay por Afinidade | Proposto | [`ADR-002`](./docs/adrs/ADR-002-alocacao-local-salas-gameplay-afinidade.md) |
| **ADR-003** | Gerenciamento de Canais de Chat por Shard Local e Agregação de Canais Globais | Proposto | `ADR-003` *(PR do Nikolas)* |
| **ADR-004** | Protocolos de Comunicação, Contratos de Dados e Táticas de Resiliência Ativa | Proposto | `ADR-004` *(PR do Nikolas)* |
| **ADR-005** | Mapeamento de Contextos (DDD) e Estratégia de Integração entre Bounded Contexts | Proposto | `ADR-005` *(PR da Giovanna)* |
| **ADR-006** | Seleção da Linguagem Principal do Back-end: Go *(12/11/2026)* | Proposto | [`ADR-006`](./docs/adrs/ADR-006-selecao-tecnologica-backend-go.md) |
| **ADR-007** | Componentes Probabilísticos para Moderação de Chat via Arquitetura Defensiva *(20/11/2026)* | Aprovado | `ADR-007` *(PR da Nicole)* |

---

## 8. Testes & Fitness Functions (Governança Automatizada)

- **Isolamento Cross-Shard (ArchUnit / CI):** Zero conexões ou importações de clientes de banco entre realms (ADRs 000, 001, 005 e 006).
- **Imunidade do Loop de Gameplay (CI):** Falha de build se houver publicação de frames no barramento central (ADR 002).
- **Contract Tests (CI):** Validação entre produtores OHS e consumidores ACL com schemas versionados (ADR 005).
- **Carga e Performance em Go:** 4.000 logins/s (p95 <= 1,5 s); 8.000 jogadores/realm (p95 <= 20 ms, RTT p95 <= 120 ms, >= 5.000 pacotes/s/vCPU) (ADRs 001 e 006).
- **Privacidade LGPD (Diário):** Verificação de 100% de exclusão no TTL de 90 dias do chat e 100% de anonimização em 1.000 payloads sintéticos de PII (ADRs 003 e 007).
- **Chaos & Cache Semântico:** Hit ratio >= 70% (alerta sev. 1 < 60% por 15 min), p95 <= 40 ms cacheadas / p99 <= 300 ms fallback (15.000 msg simultâneas), abertura de Circuit Breaker < 10 s e failover de chat com RTO <= 10 s (ADRs 003 e 007).
- **Licenciamento e TCO (CI):** Apenas MIT, Apache 2.0 ou BSD; monitoramento de CVEs e alertas de orçamento de egresso de rede (ADR 006).
