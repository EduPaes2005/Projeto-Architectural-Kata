# Princípios SOLID, Análise Quantitativa de Acoplamento e Padrões GoF

## 1. Aplicação dos Princípios SOLID
A modelagem orientada a objetos foi estruturada em cinco namespaces lógicos (`Plano_Central`, `Processadores_Eventos`, `Abstracoes_Interfaces`, `Eventos_Dominio` e `Shard_Realm`):
- **SRP (Single Responsibility Principle):** `ContaUsuario` gerencia dados e perfil; `ServicoAutenticacao` cuida apenas da validação e emissão de tokens (`TokenJWT`); a retenção LGPD fica isolada em `IPoliticaRetencaoLGPD`.
- **OCP (Open/Closed Principle):** `IProcessadorEvento` permite adicionar novos eventos (ex.: `EventoCompraItem`, `EventoFimTorneio`) ao barramento sem modificá-lo; subclasses de `AlocadorSalas` permitem novos formatos de torneio sem alterar o alocador base.
- **LSP (Liskov Substitution Principle):** Qualquer classe derivada de `EventoDominio` (`EventoSessaoIniciada`, `EventoPartidaFinalizada`, `ResumoPartida`) é processada uniformemente pelo barramento.
- **ISP (Interface Segregation Principle):** Interfaces coesas e específicas — o servidor autoritativo de jogo depende apenas de `IProcessadorGameplay`; o serviço de chat depende apenas de `IPoliticaRetencaoLGPD`.
- **DIP (Dependency Inversion Principle):** `SalaTorneio`, `ServicoAutenticacao` e `AgregadorCanaisGlobais` dependem de `IBarramentoEventos` (abstração), e não de um broker concreto (RabbitMQ/Kafka). O `Realm` atua como container/shard agregador.

---

## 2. Análise Quantitativa de Acoplamento (Revisão de 24/09/2026)

### Metodologia
Os cinco namespaces foram avaliados como pacotes lógicos utilizando as métricas de Robert C. Martin:
- **Na:** número de classes abstratas e interfaces no pacote; **Nc:** total de classes no pacote.
- **A (Abstração):** `Na / Nc`.
- **Ca (Acoplamento Aferente):** classes externas que dependem do pacote.
- **Ce (Acoplamento Eferente):** classes internas que dependem de elementos externos.
- **I (Instabilidade):** `Ce / (Ca + Ce)`.
- **D (Distância da Sequência Principal):** `|A + I - 1|`.
*(Relações dentro do mesmo namespace são tratadas como intra-pacote).*

| Pacote | Na | Nc | A | Ca | Ce | I | D = \|A + I - 1\| | Diagnóstico |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `Abstracoes_Interfaces` | 5 | 5 | 1,00 | 8 | 2 | 0,20 | **0,20** | Aceitável (100% abstrato; alto Ca é saudável) |
| `Plano_Central` | 0 | 9 | 0,00 | 0 | 3 | 1,00 | **0,00** | Ideal (posição ótima na sequência principal) |
| `Eventos_Dominio` | 1 | 7 | 0,14 | 8 | 0 | 0,00 | **0,86** | **Zona de Dor (problema crítico)** |
| `Processadores_Eventos` | 0 | 2 | 0,00 | 0 | 2 | 1,00 | **0,00** | Ideal (posição ótima na sequência principal) |
| `Shard_Realm` | 0 | 8 | 0,00 | 2 | 3 | 0,60 | **0,40** | Zona de atenção |

### Diagnóstico e Plano de Melhoria
- **Diagnóstico geral:** Arquitetura **parcialmente saudável**.
- **`Eventos_Dominio` (D = 0,86 — Prioridade 1):** É altamente estável (8 dependentes externos, `I = 0,00`) e quase totalmente concreto (`A = 0,14`). Alterar `EventoMensagemGlobal` ou `EventoPartidaFinalizada` pode quebrar `SalaTorneio`, `ServicoChatLocal` e os processadores simultaneamente.
  - *Ação:* Extrair uma interface `IEvento` com os contratos mínimos utilizados pelos consumidores (ou expandir o uso da abstração `EventoDominio`) e isolar consumidores via padrão **Adapter**.
- **`Shard_Realm` (D = 0,40 — Prioridade 2):** Se novos pacotes passarem a depender dele, migrará para a Zona de Dor. `AlocadorSalas` e `ProcessadorSessaoIniciada` ainda apontam para o `Realm` concreto em alguns pontos.
  - *Ação:* Rotear 100% das dependências por meio da interface `IRepositorioRealm`.
- **`Abstracoes_Interfaces` (D = 0,20 — Monitoramento):** Monitorar continuamente, pois o crescimento concreto de `EventoDominio` sem abstração "puxa" instabilidade para o pacote de interfaces.

---

## 3. Catálogo de Padrões de Projeto GoF (3 Conjuntos)

### Fundamentação Teórica
O custo dominante de sistemas longevos é a manutenção, moldada por decisões iniciais. Padrões são pares problema-solução com consequências explícitas; aplicá-los sem um problema real gera complexidade acidental (Khomh e Guéhéneuc, 2008, 2018).
- **Instanciação desgovernada:** o operador `new` acopla clientes a classes concretas, dificulta testes isolados e propaga alterações em cascata.
- **Acoplamento físico vs. lógico:** interfaces e fábricas mitigam acoplamento físico (compilação/implantação); adapters e DTOs mitigam acoplamento lógico (suposições sobre formato, ordem e significado).
- **DIP (Martin, 2003) e OCP (Meyer, 1988):** fechamento estratégico antecipando apenas variações reais da arquitetura.

### Quadro-Resumo dos 9 Padrões Aplicados

| Conjunto | Padrão GoF | Categoria | Onde se Aplica |
| :--- | :--- | :--- | :--- |
| 1 (Seleção) | **Builder** | Criação | `RealmBuilder` — criação e configuração passo a passo dos Realms |
| 1 (Seleção) | **Adapter** | Estrutural | Comunicação entre `Eventos_Dominio` e consumidores; ACL de APIs externas |
| 1 (Seleção) | **Strategy** | Comportamental | Formas de comunicação e roteamento do Chat (~33 mil msg/s no pico) |
| 2 (Apresentação) | **Composite** | Estrutural | Ações em lote sobre jogadores, esquadrões e guildas (`EntidadeJogo`, `JogadorLeaf`, `EsquadraoComposite`) |
| 2 (Apresentação) | **Bridge** | Estrutural | Desacoplamento entre serviços de aplicação e transporte (`BarramentoEventosAbstrato`, `ProvedorTransporte`) |
| 2 (Apresentação) | **Flyweight** | Estrutural | Projéteis em picos de 8M eventos/s (`TipoProjetilFlyweight`, `FabricaProjetil`, `ProjetilEmJogo`) |
| 3 (Estudo de Caso) | **Facade** | Estrutural | Gateway Global (`SolicitarSalaPrivada`) |
| 3 (Estudo de Caso) | **Factory Method** | Criação | Alocador de Salas Global (`AlocadorSalas.CriarPartida`) |
| 3 (Estudo de Caso) | **Observer** | Comportamental | Barramento de Eventos Central (`ChatSalaObserver`) |

---

### 3.1. Conjunto 1: Builder, Adapter e Strategy
- **Builder:** Separa a construção de um `Realm` complexo de sua representação, montando componentes interdependentes passo a passo e validando invariantes ao final. Evita configurações espalhadas e facilita escalar novos realms.
- **Adapter:** Converte interfaces de eventos/APIs no contrato esperado pelos consumidores, atacando o acoplamento lógico de `Eventos_Dominio` (`D = 0,86`) e atuando como camada anticorrupção contra APIs de pagamento/OAuth. Requer que os componentes dependam também de contratos estáveis (DIP).
- **Strategy:** Encapsula algoritmos de roteamento de chat (~33.334 msg/s no pico), permitindo evoluir chat global e local de forma independente, sem blocos `if/else` complexos, e facilitando testes unitários.

### 3.2. Conjunto 2: Composite, Bridge e Flyweight
- **Composite:** Trata uniformemente indivíduos e grupos em árvore parte-todo (`EntidadeJogo` -> `JogadorLeaf` e `EsquadraoComposite` via `aplicarEfeito()`), resolvendo buffs de área, notificações em lote e permissões hierárquicas de canais de chat (ver `docs/c4/diagrama-composite-gof.puml`).
- **Bridge:** Desacopla a abstração (`BarramentoEventosAbstrato`) do driver de transporte (`ProvedorTransporte`), permitindo ao chat e aos serviços de eventos alternar entre gRPC local, WebSocket e AMQP sem alterar regras de domínio.
- **Flyweight:** Separa estado intrínseco compartilhado (`TipoProjetilFlyweight`: modelo 3D, dano base, instância única via `FabricaProjetil`) do estado extrínseco (`ProjetilEmJogo`: posições X e Y) para sustentar picos de 8 milhões de eventos/s em 80.000 salas. *Estimativa da equipe: redução > 90% de RAM (hipótese ainda pendente de medição empírica).*

**Integração dos 6 primeiros padrões:** `RealmBuilder` instancia os servidores de forma modular e injeta `FabricaProjetil` (Flyweight) no motor de gameplay; `Adapter` isola APIs externas (ACL); `Bridge` desacopla motores internos dos drivers de rede; `Strategy` ajusta dinamicamente o roteamento de chat sobre hierarquias de canais unificadas pelo `Composite`.

---

### 3.3. Conjunto 3: Estudo de Caso — Solicitação de Sala Privada (Cenário QAW 2)

**Requisitos Derivados:**
- **R1:** Ponto único de entrada validando sessão e aplicando rate limiting antes da alocação (ADR 001).
- **R2:** Criar sala em realm com capacidade, admitindo novos formatos de torneio sem alterar o alocador *(hipótese de variação da equipe: eliminatória e pontos corridos)*.
- **R3:** Abrir canal de chat da sala sem que o Gameplay conheça o Chat (ADRs 000 e 005).

| Padrão | Elemento da Arquitetura | Princípio | Justificativa |
| :--- | :--- | :---: | :--- |
| **Facade** | Gateway Global | DIP | Oculta validação de sessão, rate limiting e alocação atrás de `SolicitarSalaPrivada` (R1). |
| **Factory Method** | Alocador de Salas Global | OCP | Cada formato de torneio é uma subclasse de `AlocadorSalas` sobrescrevendo `CriarPartida` (R2). |
| **Observer** | Barramento de Eventos | OCP e DIP | O Chat assina `SalaCriada`; novos consumidores (anticheat, métricas) entram sem modificar o Gameplay (R3). |

**Sequência do Fluxo:**
1. O jogador aciona a `Facade` (`SolicitarSalaPrivada`) no Gateway Global;
2. A Facade delega ao `AlocadorSalas` Global;
3. O alocador invoca `CriarPartida` (Factory Method) e publica `SalaCriada` e `JogadorEntrouNaSala`;
4. O Barramento de Eventos notifica os assinantes (Observer);
5. `ChatSalaObserver` traduz sala (`realmId + roomId`) em canal (`realmId + channelId`), efetivando a ACL da ADR 005.

**Trade-offs Explícitos:**
- **Facade:** Risco de acumular regras e virar ponto único de falha (mitigado com autoscaling e Circuit Breaker — ADR 001); salto extra desprezível frente à meta de entrada `p95 <= 2 s`.
- **Factory Method:** Adequado para poucos formatos; se proliferarem, migrar para `Strategy` ou fábrica parametrizada.
- **Observer:** Introduz consistência eventual (canal abre milissegundos após a sala, exigindo buffer/retentativa para mensagens iniciais) e demanda idempotência por `eventId`; em produção é generalizado por pub/sub (AMQP/Kafka/NATS), com atenção a vazamento de recursos por assinantes órfãos.
