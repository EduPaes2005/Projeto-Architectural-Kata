# Projeto Architectural Kata — World of Webcraft

**Disciplina:** Arquitetura de Software | **Período:** 6º Período — Engenharia de Software  
**Turma:** [Quinta-feira] | **Sprint / Entrega:** Sprint 4 - Final do Projeto por Equipes  
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

Para atender à moderação contextual do chat sem violar o orçamento de latência ($p95\text{ RTT} \le 120\text{ ms}$), o TCO ou a LGPD, a solução incorpora um contexto isolado denominado **Motor AI-Native e Autonomia (ADR 007)** sob Arquitetura Defensiva em 6 camadas. Filtros determinísticos (Rate Limiting, PII Masking, Bloom Filter) e um Cache Semântico em Redis Stack/Valkey (índice HNSW, similaridade $\ge 0,93$) absorvem mais de 90% do volume antes que casos ambíguos sejam roteados para inferência em camadas (SLM Tier 1 e LLM Tier 2) protegida por Circuit Breaker (250 ms) e fallback gracioso com DLQ.

---

## 2. Atributos de Qualidade e Matriz QAW

Os seis drivers priorizados no QAW são: **Escalabilidade** (Crítica), **Performance de rede** (Crítica), **Disponibilidade** (Alta), **Modulabilidade** (Alta — subcaracterística de manutenibilidade), **Elasticidade** (Alta) e **Privacidade/LGPD** (Obrigatória).

| Atributo de Qualidade | Cenário Padronizado (Fonte -> Estímulo -> Artefato -> Ambiente -> Resposta) | Medida da Resposta (Métrica Objetivo) |
| :--- | :--- | :--- |
| **Escalabilidade** *(Login em massa)* | **Fonte:** Jogadores acessando após lançamento de temporada ou início de torneio. **Estímulo:** 1.200.000 logins em 10 min (2.000 req/s médios, 4.000 req/s de pico de projeto). **Artefato:** Gateway global, serviço central de autenticação/conta e serviço de sessão do realm. **Ambiente:** Pico planejado de 2 milhões de usuários simultâneos em 100 realms. **Resposta:** Gateway aplica rate limiting justo e autoscaling; autenticação valida a conta; evento `SessaoIniciada` é publicado no barramento, sem acesso direto ao banco do shard. | 4.000 logins/s; sucesso $\ge 99,9\%$; $p95 \le 1,5\text{ s}$; nenhum realm acima de 30.000 sessões; fila estabilizada em até 5 min. |
| **Performance de Rede + Escalabilidade** *(Salas privadas de torneio)* | **Fonte:** Organizadores iniciando rodada; 800.000 jogadores entrando em salas privadas. **Estímulo:** 800.000 entradas em 5 min (2.667 req/s médios, 5.334 req/s de projeto); 10 eventos/s por jogador. **Artefato:** Alocador de salas, gateway do realm e servidor autoritativo da partida. **Ambiente:** 80.000 salas simultâneas de 10 jogadores em 100 realms. **Resposta:** Alocador fixa cada sala em um único realm, escala nós dentro do shard e mantém o loop local; só eventos de domínio e o resumo final seguem pelo barramento. | Sucesso de entrada $\ge 99,9\%$; $p95$ de entrada $\le 2\text{ s}$; $p95$ gateway-servidor de partida $\le 20\text{ ms}$; $p95$ RTT ao usuário $\le 120\text{ ms}$; zero chamadas síncronas cross-realm durante a partida. |
| **Disponibilidade + Performance de Rede** *(Chat em tempo real sob falha)* | **Fonte:** Falha de infraestrutura em instância de chat ou partição do broker durante o pico. **Estímulo:** Uma instância falha com 500.000 usuários ativos no chat; pico de 33.334 msg/s (~333 msg/s por realm). **Artefato:** Gateway de conexões, serviço de chat do realm, partição do barramento e agregador de canais globais. **Ambiente:** Operação de pico com conexões persistentes e canais locais/globais. **Resposta:** Nó removido do balanceamento; clientes reconectam ao mesmo realm; mensagens confirmadas retomadas de forma idempotente; chat global continua pelo agregador. | $\text{RTO} \le 10\text{ s}$; perda $\le 0,1\%$ e duplicação $\le 0,1\%$; $p95$ de entrega $\le 200\text{ ms}$; disponibilidade mensal $\ge 99,95\%$. |

### Síntese BOTE e Política de Retenção (LGPD)
- **Carga de Pico:** 2.000.000 de sessões simultâneas (20.000/realm), 80.000 salas (800/realm), 8.000.000 de eventos/s de gameplay (72.000.000 entregas/s) e ~33.334 msg/s de chat.
- **Armazenamento (5 anos com 3 cópias):** Persistir tudo exigiria ~457,1 PB (inviável). Com a política recomendada — Perfis/contas (2,34 TB durante a relação + eliminação), Resumos de partida por 5 anos (630,72 TB), Chat identificável em janela móvel de 90 dias (116,64 TB) e eventos brutos de gameplay fora do histórico — a capacidade inicial cai para **749,70 TB** (~974,61 TB / 0,98 PB com 30% de folga).

---

## 3. Visão Geral da Arquitetura (Modelo C4)

A arquitetura está documentada conforme as abstrações do Modelo C4 (Diagrams-as-Code na pasta `./docs/c4/`):
- 📄 **Nível 1 — Diagrama de Contexto:** `./docs/c4/c1-contexto.puml` *(Jogadores e Admin interagindo via HTTPS/WebSockets com o sistema World of Webcraft, integrado aos provedores externos OAuth/SSO e Gateway de Pagamento).*
- 📄 **Nível 2 — Diagrama de Contêineres:** `./docs/c4/c2-conteineres.puml` *(Fronteira Global: Gateway Global, Serviço de Autenticação Centralizado, Alocador de Salas Global, Barramento de Eventos Central e Agregador de Canais Globais; Fronteira do Realm — limite de 30.000 conexões: Gateway do Realm, Servidor de Gameplay FPS, Serviço de Salas do Realm, Serviço de Chat Local, Banco de Dados do Realm e Banco de Dados do Chat).*
- 📄 **Nível 3 — Diagrama de Componentes:** `./docs/c4/c3-componentes-ai-native.puml` *(Bloco isolado "Motor AI-Native e Autonomia" com AI Gateway, Guardrails, Banco Vetorial, SLM Tier 1 e LLM Tier 2)* e Diagrama de Classes em `./docs/c4/diagrama-classes-solid.puml`.

### Visualização e Mapeamento de Protocolos (C4 — Nível 2)

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

*(Inserir aqui a renderização do diagrama C4 Nível 2 gerado a partir de `./docs/c4/c2-conteineres.puml`.)*

---

## 4. Domain-Driven Design (DDD) & Limites de Contexto

- Documentação completa em `./docs/ddd/bounded-contexts-mapa-contexto.md` e `./docs/adrs/ADR-005-ddd-bounded-contexts-integracao.md`.

### 4.1. Classificação dos Subdomínios
- **Core Domain (Domínio Principal):** **Gameplay** *(distribuído — Alocador de Salas Global, Gateway do Realm, Servidor de Gameplay FPS, Serviço de Salas do Realm e Banco do Realm; efêmero e baseado em ticks).*
- **Supporting Subdomain (Suporte):** **Chat** *(local por Realm e global — Serviço de Chat Local, Banco de Dados do Chat com retenção de 90 dias/LGPD e Agregador de Canais Globais).*
- **Generic Subdomain (Genérico / Suporte Global):** **Identidade & Conta** *(global — Serviço de Autenticação Centralizado, que nesta fase absorve cobrança e pagamento; OAuth e Gateway de Pagamento são provedores externos).*

*(Nota: Global e Realm são fronteiras de implantação, não contextos isolados. Um Bounded Context exclusivo para Pagamento — moeda, carteira, loja e evento `CompraConfirmada` — foi formalmente adiado.)*

### 4.2. Mapa de Contexto e Camada Anti-Corrupção (ACL)
A integração entre contextos ocorre exclusivamente por eventos assíncronos (**Open Host Service + Published Language** no upstream) com **Anti-Corruption Layer (ACL)** padronizada no downstream (implementada via padrão Adapter), isolando a Linguagem Ubíqua de cada contexto e bloqueando bancos compartilhados ou chamadas síncronas:
- **Identidade → Gameplay:** OHS publica `SessaoIniciada`; ACL do Gameplay traduz a sessão em jogador pseudônimo.
- **Identidade → Chat:** OHS publica `SessaoIniciada`; ACL do Chat converte a sessão em participante de canal.
- **Gameplay → Chat:** OHS publica `SalaCriada` e `JogadorEntrouNaSala`; ACL do Chat (`ChatSalaObserver`) mapeia sala (`realmId + roomId`) em canal (`realmId + channelId`).
- **Provedor OAuth → Identidade:** ACL isola o modelo externo de autenticação.
- **Provedor de Pagamento → Identidade:** ACL protege o modelo interno de conta e cobrança.

---

## 5. Padrões de Projeto & Princípios SOLID

### 5.1. Princípios SOLID Aplicados e Métricas de Acoplamento
A modelagem orienta-se em 5 pacotes lógicos (`Plano_Central`, `Processadores_Eventos`, `Abstracoes_Interfaces`, `Eventos_Dominio`, `Shard_Realm`):
- **Single Responsibility Principle (SRP):** `ContaUsuario` gerencia dados/perfil; `ServicoAutenticacao` valida credenciais e emite `TokenJWT`; a retenção LGPD é isolada em `IPoliticaRetencaoLGPD`.
- **Open/Closed Principle (OCP):** `IProcessadorEvento` permite adicionar novos eventos (`EventoCompraItem`, `EventoFimTorneio`) ao barramento sem alterá-lo; `AlocadorSalas` permite novos formatos de torneio via subclasses.
- **Liskov Substitution Principle (LSP):** Todas as derivadas de `EventoDominio` (`EventoSessaoIniciada`, `EventoPartidaFinalizada`, `ResumoPartida`) são processadas uniformemente pelo barramento.
- **Interface Segregation Principle (ISP):** O servidor de jogo depende apenas de `IProcessadorGameplay`; o chat depende apenas de `IPoliticaRetencaoLGPD`.
- **Dependency Inversion Principle (DIP):** `SalaTorneio`, `ServicoAutenticacao` e `AgregadorCanaisGlobais` dependem da abstração `IBarramentoEventos` (em `Abstracoes_Interfaces`), e não de um broker concreto.
- **Diagnóstico de Acoplamento (24/09/2026):** `Plano_Central` ($D=0,00$), `Processadores_Eventos` ($D=0,00$) e `Abstracoes_Interfaces` ($D=0,20$) estão saudáveis; `Shard_Realm` ($D=0,40$) está em zona de atenção (rotear 100% via `IRepositorioRealm`); **`Eventos_Dominio` ($D=0,86$, $A=0,14$, $Ca=8$, $I=0,00$)** está na Zona de Dor — prioridade de refatoração via extração da interface `IEvento` e adoção do padrão Adapter.

### 5.2. Catálogo de Padrões GoF Utilizados (3 Conjuntos)

| Padrão GoF | Categoria | Aplicação na Arquitetura (`./docs/ddd/analise-acoplamento-gof.md`) | Problema de Engenharia Resolvido |
| :--- | :--- | :--- | :--- |
| **Builder** | Criacional | `RealmBuilder` (criação e configuração dos Realms) | Montagem passo a passo de Realms complexos validando invariantes e evitando configurações espalhadas. |
| **Adapter** | Estrutural | Consumidores de `Eventos_Dominio` e ACLs de Pagamento/OAuth | Redução de acoplamento lógico nos eventos ($D=0,86$) e tradução de modelos externos/upstream para o domínio local. |
| **Strategy** | Comportamental | Roteamento do Serviço de Chat (~33 mil msg/s no pico) | Substituição de condicionais por algoritmos intercambiáveis para evoluir chat global e local independentemente. |
| **Composite** | Estrutural | `EntidadeJogo`, `JogadorLeaf`, `EsquadraoComposite` (`aplicarEfeito()`) | Ações em lote uniformes sobre jogadores, esquadrões e guildas, e distribuição hierárquica de permissões em canais de chat. |
| **Bridge** | Estrutural | `BarramentoEventosAbstrato` e `ProvedorTransporte` | Desacopla serviços de aplicação dos drivers físicos de rede, permitindo alternar entre gRPC local, WebSocket e AMQP sem alterar regras de domínio. |
| **Flyweight** | Estrutural | `TipoProjetilFlyweight`, `FabricaProjetil`, `ProjetilEmJogo` | Separa estado intrínseco (modelo 3D, dano base) do extrínseco (posição X, Y) nos picos de 8M eventos/s de projéteis (estimativa >90% de economia de RAM). |
| **Facade** | Estrutural | Gateway Global (`SolicitarSalaPrivada`) | Ponto único de entrada (Cenário 2 QAW / R1) ocultando validação de sessão, rate limiting e alocação (DIP). |
| **Factory Method** | Criacional | `AlocadorSalas` (`CriarPartida`) | Admissão de novos formatos de torneio (ex.: eliminatória, pontos corridos) sem modificar o alocador (OCP / R2). |
| **Observer** | Comportamental | Barramento de Eventos (`ChatSalaObserver`) | Chat assina `SalaCriada` e `JogadorEntrouNaSala` para abrir canais sem acoplamento ao Gameplay (OCP e DIP / R3). |

---

## 6. Resiliência, Integração e IA-Native

### 6.1. Padrões de Resiliência e Contratos de Dados
- **Contrato Crítico (`SessaoIniciada` — `./docs/contracts/sessao-iniciada.schema.json`):** Formalizado em JSON Schema com 5 campos obrigatórios (`eventId` UUID para idempotência, `playerId` pseudônimo LGPD, `realmId`, `timestamp` date-time e `sessionToken` assinado).
- **Circuit Breaker & Retry no Serviço de Autenticação (ADR 004):** Identificado como elo mais fraco no caminho do login. Configurado com **timeout de 1,5 s** (teto p95), **Retry de 2 tentativas** com backoff exponencial (200 ms e 600 ms), **abertura do circuito** após 5 falhas seguidas ou taxa de erro > 50% em 10 s, **Half-open** com 1 requisição de teste a cada 10 s e **Fallback gracioso** (erro amigável para novos logins, mantendo reconexões via cache local para jogadores já autenticados).
- **Nivelamento de Carga e Failover de Chat (ADRs 003 e 007):** Remoção automática de nós falhos com reconexão idempotente no mesmo realm ($\text{RTO} \le 10\text{ s}$); filas locais AMQP absorvem rajadas no pipeline de moderação com **Dead Letter Queue (DLQ)** para auditoria assíncrona.

### 6.2. Componente de Inteligência Artificial Generativa (ADR 007)
- **Arquitetura AI-Native (Motor AI-Native e Autonomia):** Contexto isolado do loop autoritativo de 60 Hz em Go (WebRTC/UDP), estruturado em **Defesa em Profundidade (6 Tiers)** e **Model Tiering**:
  - **Tier 0 — Ingress Gateway (Realm):** Validação de JSON Schema e Rate Limiting Token Bucket (> 5 req/s descartado com HTTP 429; SLA $\le 1\text{ ms}$).
  - **Tier 1 — Input Guardrails:** Sanitização XSS, **PII Masking** obrigatório (regex/autômatos substituem CPF, cartões, e-mails e telefones por `[DADO_PESSOAL_REMOVIDO]` antes de vetorizar), **Bloom Filter** (>50.000 termos maliciosos em $O(1)$ < 1 ms) e filtros anti-jailbreak/prompt injection (SLA $\le 2\text{ ms}$).
  - **Tier 2 — Cache Semântico:** Embeddings locais ultraleves (`MiniLM-L6-v2` quantizado em ONNX, 384 dimensões, < 2 ms) armazenados em **Redis Stack / Valkey** com índice **HNSW** na mesma subnet dos Gateways em Go. Similaridade de cosseno $\ge 0,93$ retorna **HIT** em $\le 5\text{ ms}$ (absorve 70–85% do tráfego; TTL móvel de 48 h para mensagens comuns e 7 dias para ofensas catalogadas).
  - **Tier 3 — Buffer e Fila Prioritária:** Fila local AMQP desacoplando rajadas (5–15 ms).
  - **Tier 4 — Inferência Probabilística (Model Tiering):** **SLM Tier 1** (modelos quantizados de 1B–7B como `Llama-3.2-1B`/`MiniLM`, SLA $\le 40\text{ ms}$, atende ~85% dos misses) e **LLM Tier 2** (200–500 ms sob **Circuit Breaker de 250 ms**, acionado apenas para confiança ambígua entre 0,40 e 0,70 ou disputas complexas de torneio; abre se erros/timeouts > 20% em 10 s com fallback heurístico local + DLQ).
  - **Tier 5 — Output Guardrails e Egress:** Validação anti-alucinação em **JSON Schema estrito** (`status`: `FLAGGED`/`CLEAN`, `confidence_score`: `0.0–1.0`, `violation_category`: enum), filtragem residual, gravação no cache e broadcast (SLA $\le 2\text{ ms}$). Fluxos assíncronos via Barramento Central alimentam o pipeline RAG e auditorias sem concorrer com o chat em tempo real.

---

## 7. Registros de Decisões Arquiteturais (ADRs)

Todas as decisões estratégicas e seus trade-offs estão registradas em `./docs/adrs/`:

| ADR | Título da Decisão Arquitetural | Status | Data |
| :--- | :--- | :--- | :--- |
| **ADR-000** | Estilo Arquitetural Orientado a Eventos com Sharding por Realm | Aceito | Sprint 1–4* |
| **ADR-001** | Autenticação Centralizada e Isolamento de Bancos de Dados por Shard de Realm | Proposto | Sprint 1–4* |
| **ADR-002** | Alocação Local de Salas e Processamento Local de Gameplay por Afinidade | Proposto | Sprint 1–4* |
| **ADR-003** | Gerenciamento de Canais de Chat por Shard Local e Agregação de Canais Globais | Proposto | Sprint 1–4* |
| **ADR-004** | Protocolos de Comunicação, Contratos de Dados e Táticas de Resiliência Ativa | Proposto | Sprint 2–4* |
| **ADR-005** | Mapeamento de Contextos (DDD) e Estratégia de Integração entre Bounded Contexts | Proposto | Sprint 3–4* |
| **ADR-006** | Seleção da Linguagem Principal do Back-end: Go | Proposto | 2026-11-12 |
| **ADR-007** | Componentes Probabilísticos para Moderação de Chat via Arquitetura Defensiva *(renumerada da antiga 006 da Sprint 4.1)* | Aprovado | 2026-11-20 |

*\*As ADRs 000 a 005 não possuem data individual fixada no documento consolidado (consolidado em 2026-11-26).*

---

## 8. Testes & Fitness Functions (Governança Automatizada)

### 8.1. Execução das Fitness Functions (ArchUnit, CI/CD, Carga e Chaos)
Para assegurar a aderência contínua às ADRs e à Topologia de Dados, o repositório consolida as seguintes regras automatizadas em `./tests/fitness/`:
- **Regra Validada 1 — Isolamento Estrito de Shards (ArchUnit / CI — ADRs 000, 001, 005 e 006):** Pacotes de um Realm não importam clientes de banco de dados de outro Realm (meta: zero conexões cross-shard); acesso direto a bancos de outros Bounded Contexts e chamadas síncronas entre contextos são bloqueados na esteira.
- **Regra Validada 2 — Imunidade do Loop de Gameplay (CI — ADR 002):** O build falha automaticamente caso haja tentativa de publicação de frames de movimento/disparo no Barramento de Eventos Central; monitoramento contínuo bloqueia chamadas cross-realm e alerta se $p95 > 20\text{ ms}$.
- **Regra Validada 3 — Contratos de Domínio (Contract Tests — ADR 005):** Testes automatizados validam compatibilidade entre produtores OHS e consumidores ACL antes do consumo, exigindo schemas versionados.
- **Regra Validada 4 — Performance e Capacidade em Go (Testes de Carga — ADRs 001 e 006):** Validação contínua de 4.000 logins/s (sucesso $\ge 99,9\%$, $p95 \le 1,5\text{ s}$) e carga de 8.000 jogadores por realm ($p95 \le 20\text{ ms}$ gateway-partida, $p95\text{ RTT} \le 120\text{ ms}$, piso de 5.000 pacotes/s por vCPU e monitoramento de pausas de GC).
- **Regra Validada 5 — Privacidade LGPD e Retenção (Auditorias Diárias — ADRs 003 e 007):** Scripts diários verificam 100% de exclusão no TTL de 90 dias do chat; teste diário com 1.000 payloads sintéticos (CPFs, cartões, e-mails) exige 100% de anonimização no PII Masking (qualquer falha interrompe a esteira).
- **Regra Validada 6 — Resiliência, Cache Semântico e Chaos Engineering (ADRs 003 e 007):** Injeção de falhas no chat ($\text{RTO} \le 10\text{ s}$, $p95 \le 200\text{ ms}$) e indisponibilidade total do provedor de IA (abertura do Circuit Breaker em $< 10\text{ s}$ sem queda de throughput); monitoramento Prometheus de hit ratio $\ge 70\%$ no cache semântico (alerta severidade 1 se $< 60\%$ por $> 15\text{ min}$); teste de latência de cauda no CI com 15.000 mensagens simultâneas ($p95 \le 40\text{ ms}$ cacheadas e $p99 \le 300\text{ ms}$ sob fallback).
- **Regra Validada 7 — Licenciamento, Segurança e TCO (CI — ADR 006):** Auditoria automatizada permitindo apenas bibliotecas MIT, Apache 2.0 ou BSD (bloqueando GPL/AGPL e SSPL/BSL), varredura contínua de CVEs e alertas de orçamento de egresso de rede.
