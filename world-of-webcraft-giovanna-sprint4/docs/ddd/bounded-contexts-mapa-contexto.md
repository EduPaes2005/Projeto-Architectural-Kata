# Domain-Driven Design (DDD) — Bounded Contexts e Mapa de Contexto

## 1. Motivação e Separação de Modelos
Embora a arquitetura do **World of Webcraft** possuísse separações físicas iniciais por infraestrutura, era necessário formalizar os limites de modelo (Linguagem Ubíqua) para evitar contaminação conceitual entre domínios com ritmos de evolução, requisitos de latência e obrigações legais (LGPD) incompatíveis:
- **Gameplay** é efêmero e orientado a ticks (60 Hz, 8M eventos/s).
- **Chat** exige retenção controlada de 90 dias, moderação e conformidade LGPD.
- **Identidade & Conta** concentra dados pessoais (PII) sujeitos à eliminação e auditoria.
- **Provedor OAuth** e **Provedor de Pagamento** são sistemas externos fora do nosso controle.

> **Nota sobre Fronteiras de Implantação:** `Global` e `Realm` representam **fronteiras de implantação (deployment boundaries)**, e não Bounded Contexts isolados. Cada contêiner do C4 Nível 2 pertence a exatamente um Bounded Context.

---

## 2. Classificação dos Subdomínios e Contêineres

| Subdomínio | Classificação DDD | Escopo de Implantação | Contêineres Pertencentes | Responsabilidades Centrais |
| :--- | :--- | :--- | :--- | :--- |
| **Gameplay** | **Core Domain** *(Domínio Principal)* | Distribuído *(Global + Realm)* | Alocador de Salas Global, Gateway do Realm, Servidor de Gameplay FPS, Serviço de Salas do Realm e Banco de Dados do Realm | Alocação de salas por afinidade (`realmId + roomId`), loop autoritativo em tempo real e geração de resumos de partida. |
| **Chat** | **Supporting Subdomain** *(Suporte)* | Local por Realm + Global | Serviço de Chat Local, Banco de Dados do Chat e Agregador de Canais Globais | Canais locais (`realmId + channelId`), fan-out hierárquico global, moderação defensiva e retenção móvel de 90 dias (LGPD). |
| **Identidade & Conta** | **Generic / Supporting Subdomain** | Global | Serviço de Autenticação Centralizado | Validação de credenciais, emissão de `TokenJWT`, gestão de `ContaUsuario`, pseudonimização (`playerId`) e absorção temporária de cobrança/pagamento. |

### Decisão de Escopo Adiada
- A criação de um **Bounded Context exclusivo de Pagamento** (moeda, carteira, loja) e a relação **Pagamento -> Gameplay** via evento `CompraConfirmada` foram formalmente **adiadas** para fases futuras. Nesta fase, cobrança e pagamento permanecem em Identidade & Conta protegidos por ACL.

---

## 3. Mapa de Contexto e Integrações (OHS + Published Language + ACL)

A comunicação entre contextos ocorre **exclusivamente via eventos assíncronos** publicados por **Open Host Service (OHS) + Published Language** no *upstream* e traduzidos por **Anti-Corruption Layer (ACL)** no *downstream*, garantindo isolamento da Linguagem Ubíqua e zero acesso cruzado a bancos de dados.

| Relação (Upstream -> Downstream) | Padrão de Integração | Mecanismo e Tradução de Linguagem Ubíqua |
| :--- | :--- | :--- |
| **Identidade -> Gameplay** | OHS + Published Language -> ACL | OHS publica `SessaoIniciada` (AMQP); a ACL do Gameplay traduz os dados da sessão em um jogador operacional pseudônimo (`playerId`). |
| **Identidade -> Chat** | OHS + Published Language -> ACL | OHS publica `SessaoIniciada` (AMQP); a ACL do Chat converte a sessão em participante habilitado nos canais do realm. |
| **Gameplay -> Chat** | OHS + Published Language -> ACL | OHS publica `SalaCriada` e `JogadorEntrouNaSala` (AMQP); a ACL do Chat (`ChatSalaObserver`) mapeia a sala (`realmId + roomId`) em canal de chat (`realmId + channelId`). |
| **Provedor OAuth -> Identidade** | ACL *(no nosso lado)* | ACL isola o modelo externo de autenticação (OAuth/SSO), impedindo vazamento de formatos de terceiros para `ContaUsuario`. |
| **Provedor de Pagamento -> Identidade** | ACL *(no nosso lado)* | ACL protege o modelo interno de conta e faturamento contra mudanças nas APIs externas de pagamento. |

---

## 4. Alternativas Rejeitadas e Governança
- **Rejeitadas:**
  - *Shared Kernel:* reacopla domínios e cria dependências de compilação/deploy entre times.
  - *Conformist:* contamina o modelo interno com o vocabulário de sistemas externos ou de outros contextos.
  - *Chat dentro de Gameplay:* mistura o Core Domain efêmero de 60 Hz com um domínio sujeito à retenção e exclusão LGPD.
  - *Integração síncrona ou banco compartilhado:* viola a Topologia de Dados e provoca falhas em cascata.
- **Governança Automatizada:**
  - *Contract tests* entre OHS produtor e ACL consumidora.
  - Schemas versionados e publicados antes do consumo.
  - Testes de arquitetura proibindo cruzamento de domínios em acesso a banco de dados.
  - Revisão de código (Peer-Review) bloqueando chamadas síncronas entre contextos.
