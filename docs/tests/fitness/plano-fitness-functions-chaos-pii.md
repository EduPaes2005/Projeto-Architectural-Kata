# Plano Consolidado de Fitness Functions, QA e Governança Automatizada

Este documento centraliza todas as **Fitness Functions** definidas nas ADRs **000 a 007** e na Topologia de Dados do World of Webcraft, garantindo rastreabilidade contínua entre drivers arquiteturais, cenários QAW e métricas executáveis na esteira de CI/CD e em produção.

---

## 1. Matriz Completa de Fitness Functions

| Origem | Tipo | Regra / Métrica Verificada | Limiar / Critério de Falha | Frequência |
| :--- | :--- | :--- | :--- | :--- |
| **ADR 000 / 001 / 005 / 006** | ArchUnit / CI | Isolamento cross-shard e entre Bounded Contexts (proibição de importar clientes de banco de outro realm ou cruzar domínios em acesso a banco) | Meta: **zero conexões cross-shard**; qualquer violação falha o merge/build | A cada PR / Merge |
| **ADR 001** | Teste de Carga | Login em massa no Gateway Global e Serviço de Autenticação | `4.000 logins/s`; sucesso `>= 99,9%`; `p95 <= 1,5 s`; teto de `30.000` sessões/realm; fila estabilizada em `<= 5 min` | Contínuo |
| **ADR 002** | Teste Arquitetural (CI) + Monitoramento | Bloqueio de publicação de frames de gameplay no Barramento Central; monitoramento de chamadas cross-realm e latência | Falha build se houver publicação de frames; alerta para qualquer chamada cross-realm ou `p95 > 20 ms` | A cada build / Tempo real |
| **ADR 003** | Script LGPD + Chaos Engineering | Verificação do TTL de 90 dias no chat e resiliência a queda de instância/partição do broker | **100% de exclusão verificável** após 90 dias; Chaos com `p95 <= 200 ms`, `RTO <= 10 s`, perda `<= 0,1%` e duplicação `<= 0,1%` | Diário (TTL) / Periódico (Chaos) |
| **ADR 004** | Validação de Schema + Teste de Resiliência | Contrato JSON Schema de `SessaoIniciada` (`eventId`, `playerId`, `realmId`, `timestamp`, `sessionToken`) e Circuit Breaker na autenticação | Rejeição de payload inválido; abertura após 5 falhas ou erro `> 50%` em 10 s (timeout `1,5 s`); reconexão via cache mantida | A cada build / Contínuo |
| **ADR 005** | Contract Tests + Code Review | Compatibilidade entre produtores OHS e consumidores ACL; schemas versionados; ausência de chamadas síncronas entre contextos | Quebra de contrato ou chamada síncrona cross-context bloqueia o merge | A cada PR / Build |
| **ADR 006** | Carga (Go) + GC + Licenças/CVEs | Capacidade de 1 realm com `8.000` jogadores; pausas de GC; auditoria de licenças e egresso de rede | `p95 <= 20 ms` (gateway-partida) e `RTT p95 <= 120 ms`; `< 5.000 pacotes/s/vCPU` dispara revisão da ADR; pausas recorrentes de GC acionam avaliação de Rust; somente licenças **MIT, Apache 2.0 ou BSD** (proibidas GPL/AGPL, SSPL/BSL) | Por release / CI / Contínuo |
| **ADR 007** | Prometheus + CI + Auditoria PII + Chaos | Cache semântico, latência de cauda da IA, anonimização LGPD e Circuit Breaker de `250 ms` | Hit ratio `>= 70%` (alerta Sev. 1 se `< 60%` por `> 15 min`); CI com `15.000` msg simultâneas (`p95 <= 40 ms` cacheadas / `p99 <= 300 ms` fallback); **100% de anonimização** em `1.000` payloads sintéticos diários (qualquer falha para a esteira); Circuit Breaker abre em `< 10 s` sem queda de throughput | Tempo real / CI / Diário |

---

## 2. Mitigações de Topologia de Dados Monitoradas

| Risco de Topologia | Mecanismo de Detecção e Controle |
| :--- | :--- |
| **Acesso cross-shard a banco** | Bloqueio em rede/IAM + regra ArchUnit (`WorldOfWebcraftArchitectureTest.java`) |
| **Partição quente (Hot Shard)** | Alertas preventivos em **70%** e **85%** da capacidade; bloqueio automático de novas sessões em **30.000/realm**; split/migração apenas entre partidas |
| **Evento duplicado / fora de ordem** | Validação obrigatória de `eventId`, `idempotencyKey` e sequenciamento por agregado |
| **Vazamento de identidade nos shards** | Pseudonimização (`playerId`), minimização de dados e catálogo de retenção auditado |
| **Barramento no loop de tick** | Regra de CI bloqueando frames no barramento (`ADR 002`) |

---

## 3. Riscos Abertos Acompanhados pelo QA (Seção 16)
1. **Premissas do BOTE (2M usuários / 100 realms):** Hipóteses a calibrar com telemetria real ou benchmarks.
2. **Capacidade do Go (`10.000 pacotes/s` e `20.000 eventos/s` por vCPU):** Pendente de validação no protótipo de 1 realm.
3. **Ganho do Flyweight (> 90% de RAM):** Estimativa arquitetural pendente de medição em benchmark.
4. **Retenção de 90 dias do chat:** Dependente de aprovação jurídica/LGPD definitiva.
5. **Migração de salas "quentes" entre partidas:** Procedimento técnico a detalhar antes de produção.
6. **Acoplamento em `Eventos_Dominio` (`D = 0,86`):** Permanece na Zona de Dor até a extração de `IEvento` e implementação completa dos Adapters.
