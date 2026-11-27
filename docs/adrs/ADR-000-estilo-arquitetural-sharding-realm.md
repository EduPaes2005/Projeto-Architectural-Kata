# ADR 000: Estilo Arquitetural Orientado a Eventos com Sharding por Realm

- **Status:** Aceito
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Escalabilidade, Performance de Rede, Disponibilidade, Modulabilidade, Elasticidade e Privacidade/LGPD

---

## Contexto
O World of Webcraft precisa sustentar milhões de usuários simultâneos via navegador web (2.000.000 no pico), salas privadas isoladas de torneio (80.000 salas simultâneas) e comunicação em tempo real via chat com baixa latência. Um monólito centralizado ou uma arquitetura puramente síncrona não sustentam o volume de 8.000.000 de eventos/s de entrada e 72.000.000 de entregas/s (fan-out) sem saturar um núcleo central.

---

## Decisão
Adotar uma **arquitetura orientada a eventos, particionada por realm/shard**:
- Cada **realm** atua como uma unidade operacional quase independente, agrupando servidores de jogo autoritativos, serviço de chat local e salas de torneio (100 realms no pico planejado, 20.000 sessões/realm em operação normal e teto de 30.000 sessões/realm).
- A comunicação entre realms e com os serviços centrais (autenticação, conta e pagamento) ocorre exclusivamente por meio de um **barramento assíncrono pub/sub**.
- Serviços de pagamento permanecem isolados com consistência forte.

---

## Alternativas Rejeitadas
- **Monólito centralizado:** Cria ponto único de contenção para login, salas e fan-out, inviabilizando escala horizontal.
- **Microsserviços puramente síncronos (REST):** Propaga latência, bloqueia threads em picos e cria acoplamento temporal em cascata entre serviços.
- **Peer-to-Peer (P2P):** Perde autoridade sobre o estado da partida (vulnerável a trapaças) e dificulta governança e auditoria exigidas pela LGPD.

---

## Consequências
### Positivas (+)
- Escalabilidade horizontal real por meio do isolamento em shards/realms.
- Chat e gameplay desacoplados e resilientes a picos de tráfego.
- Base modular que permite evoluir login, chat e jogo separadamente.

### Negativas (-)
- Maior complexidade operacional (gestão de 100 shards, particionamento e mensageria).
- Chat global exige tratamento dedicado via agregador hierárquico.
- Consistência eventual entre serviços (exigindo manter pagamento em serviço separado com consistência forte).

---

## Fitness Function e Governança
- **Regra Automatizada:** A comunicação entre realms deve ocorrer exclusivamente via barramento de eventos.
- **Verificação:** Teste arquitetural automatizado executado antes de cada merge (CI/CD), com meta estrita de **zero conexões cross-shard**.
