# ADR 003: Gerenciamento de Canais de Chat por Shard Local e Agregação de Canais Globais

- **Status:** Proposto
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Disponibilidade, Performance de Rede (Cenário QAW 3) e Privacidade/LGPD

---

## Contexto
Durante o pico, **500.000 usuários ativos** (25% dos 2 milhões simultâneos) geram **16.667 msg/s** em média e **33.334 msg/s** em rajada 2x (~333 msg/s por realm). O serviço precisa manter **p95 de entrega <= 200 ms**, recuperar falhas em **RTO <= 10 s** com disponibilidade mensal **>= 99,95%**, e atender aos princípios da **LGPD** (minimização e eliminação), evitando reter `2,365 PB` de histórico de chat ao longo de 5 anos.

---

## Decisão
1. **Modelar canais por chave composta `realmId + channelId`.**
2. **Processar localmente** o chat de sala e o chat de realm dentro do respectivo shard.
3. **Utilizar tópicos particionados no barramento** e um **Agregador de Canais Globais dedicado** para o fan-out hierárquico entre realms.
4. **Failover resiliente:** Remover automaticamente o nó falho do balanceamento e reconectar clientes ao mesmo realm com retomada **idempotente** das mensagens confirmadas.
5. **Retenção máxima de 90 dias (janela móvel)** para chat identificável, implementada via **TTL por categoria** (~116,64 TB com 3 cópias).

---

## Alternativas Rejeitadas
- **Persistência vitalícia do chat:** Viola o princípio de minimização da LGPD, amplia superfície de incidente e impõe custo de armazenamento superior a **2 PB** (`2,365 PB` em 5 anos com 3 cópias).
- **Ordenação global síncrona entre realms:** Eleva latência e acoplamento, comprometendo disponibilidade e o teto de entrega p95 <= 200 ms.

---

## Consequências
### Positivas (+)
- Atende às metas de **RTO <= 10 s**, **perda <= 0,1%** e **duplicação <= 0,1%**.
- Reduz significativamente a superfície de vazamento de dados pessoais e o custo de armazenamento.
- Isola falhas de chat dentro do shard sem impactar o gameplay autoritativo.

### Negativas (-)
- Pequenas inconsistências temporárias de ordenação de mensagens podem ocorrer durante eventos de failover.
- A janela de 90 dias é uma hipótese arquitetural que depende de validação jurídica/privacidade formal.

---

## Governança e Fitness Functions
- **Auditoria Diária de TTL (LGPD):** Scripts diários automatizados validam o TTL de 90 dias com meta de **100% de exclusão verificável**.
- **Chaos Engineering:** Injeção periódica de falha em instâncias de chat e partições do broker validando **p95 <= 200 ms** e **RTO <= 10 s**.
