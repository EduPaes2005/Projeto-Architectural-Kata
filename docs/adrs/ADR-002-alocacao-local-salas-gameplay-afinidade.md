# ADR 002: Alocação Local de Salas e Processamento Local de Gameplay por Afinidade

- **Status:** Proposto
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Performance de Rede e Escalabilidade (Cenário QAW 2)

---

## Contexto
Em torneios, 800.000 jogadores ingressam em **80.000 salas privadas simultâneas de 10 jogadores** em 5 minutos (2.667 req/s médios, **5.334 req/s de pico**), gerando 10 eventos/s por jogador — o equivalente a **8.000.000 de eventos/s de entrada** (80.000/s por realm) e **72.000.000 de entregas/s** em fan-out (720.000/s por realm). A experiência competitiva exige latência interna muito baixa (**p95 gateway -> servidor de partida <= 20 ms**) e **p95 RTT ao usuário <= 120 ms**.

---

## Decisão
1. **Modelar salas com chave composta `realmId + roomId`.**
2. **Alocação por afinidade:** O Alocador de Salas Global apenas fixa cada sala em um único realm durante toda a duração da partida.
3. **Execução local autoritativa:** O loop autoritativo de gameplay roda localmente no servidor de jogo do shard (60 Hz).
4. **Exclusão de frames do barramento:** Frames de movimento e disparo **nunca transitam pelo barramento central**; apenas eventos discretos de domínio (ex.: `SalaCriada`, `JogadorEntrouNaSala`) e o resumo final da partida (~66,67 resumos/s) são publicados no barramento.

---

## Alternativas Rejeitadas
- **Transporte centralizado de frames pelo barramento:** Introduziria latência, jitter e throughput inviáveis no loop de tick.
- **Live migration de salas ativas:** Eleva drasticamente a complexidade de sincronização de estado em tempo real; o rebalanceamento entre realms ocorre exclusivamente **entre partidas**.

---

## Consequências
### Positivas (+)
- Atende rigorosamente às metas de **p95 gateway -> partida <= 20 ms** e **RTT <= 120 ms**.
- **Zero chamadas síncronas cross-realm** durante a execução da partida.
- Redução drástica de tráfego e armazenamento histórico (eventos brutos de gameplay fora do histórico de 5 anos).

### Negativas (-)
- Risco de **"partições quentes"** (um realm saturar enquanto outros ficam ociosos).
- Necessidade de alertas preventivos (70% e 85%) e **bloqueio de novas sessões no limite de 30.000 por realm**, além de procedimento operacional para migração/split entre partidas.

---

## Governança e Fitness Functions
- **Teste Arquitetural no CI:** Falha automaticamente o build caso exista qualquer publicação de frames de gameplay no barramento de eventos central.
- **Monitoramento Contínuo:** Alertas automáticos para qualquer detecção de chamadas síncronas cross-realm e para violações de **p95 > 20 ms** entre gateway e servidor de partida.
