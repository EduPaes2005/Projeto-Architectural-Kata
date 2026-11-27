# ADR 001: Autenticação Centralizada e Isolamento de Bancos de Dados por Shard de Realm

- **Status:** Proposto
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Escalabilidade (Cenário QAW 1), Privacidade/LGPD e Segurança

---

## Contexto
Eventos como lançamentos de temporada ou abertura de torneios geram rajadas de **1.200.000 logins em 10 minutos** (2.000 req/s médios, **4.000 req/s de pico de projeto**). Além disso, dados pessoais de contas de jogadores (50.000.000 de contas em 5 anos) estão sujeitos à LGPD e não devem ser replicados nos 100 shards de gameplay, sob pena de ampliar a superfície de vazamento e inviabilizar auditoria e eliminação.

---

## Decisão
1. **Validar credenciais exclusivamente no Serviço de Autenticação Centralizado**, mantido fora dos shards de realm.
2. **Utilizar o Gateway Global** para aplicar rate limiting justo e autoscaling visando sustentar **4.000 logins/s**.
3. **Publicar o evento `SessaoIniciada` de forma assíncrona** no barramento central, proibindo acesso direto do serviço de autenticação ao banco de dados dos shards.
4. **Isolar o armazenamento por realm** (chave de particionamento `realmId`), utilizando exclusivamente **identificadores pseudônimos** (`playerId`) dentro dos shards.

---

## Alternativas Rejeitadas
- **Banco global único:** Gera gargalo severo de concorrência, falhas em cascata e inviabiliza o isolamento de topologia de dados.
- **Acesso direto cross-realm:** Quebra o sharding, aumenta latência e acopla o ciclo de vida dos realms entre si.

---

## Consequências
### Positivas (+)
- O fluxo de login escala isoladamente em relação às salas de jogo.
- Dados pessoais (PII) permanecem concentrados no serviço central, facilitando a conformidade com a LGPD (minimização e eliminação).
- Proteção contra sobrecarga por meio de rate limiting na borda.

### Negativas (-)
- Consistência eventual na propagação do estado de login para o realm de destino.
- Maior complexidade de operação no Gateway Global e no gerenciamento de filas do barramento.

---

## Governança e Fitness Functions
- **ArchUnit (CI):** Validação arquitetural automatizada garantindo **zero conexões cross-shard**.
- **Testes de Carga Contínuos:** Execução recorrente validando **4.000 logins/s**, taxa de **sucesso >= 99,9%**, latência **p95 <= 1,5 s**, nenhum realm acima de 30.000 sessões e estabilização da fila em até 5 minutos.
