# ADR 005: Mapeamento de Contextos (DDD) e Estratégia de Integração entre Bounded Contexts

- **Status:** Proposto
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)
- **Drivers Relacionados:** Modulabilidade, Escalabilidade, Performance de Rede e Privacidade/LGPD

---

## Contexto
O sistema possuía separações físicas entre componentes, mas carecia de limites claros de modelo (Linguagem Ubíqua):
- **Gameplay** é o Core Domain (efêmero, baseado em ticks).
- **Chat** é domínio de suporte (retenção de 90 dias, moderação, LGPD).
- **Identidade** concentra dados pessoais (PII).
- **Provedor OAuth** e **Provedor de Pagamento** são externos e fora do nosso controle.

---

## Decisão
1. **Formalizar três Bounded Contexts** (cada contêiner pertence a exatamente um; `Global` e `Realm` são apenas fronteiras de implantação):
   - **Identidade & Conta (genérico/suporte, global):** Serviço de Autenticação Centralizado, absorvendo temporariamente cobrança e pagamento nesta fase.
   - **Gameplay (core, distribuído):** Alocador de Salas Global, Gateway do Realm, Servidor de Gameplay FPS, Serviço de Salas do Realm e Banco do Realm.
   - **Chat (suporte, local por Realm e global):** Serviço de Chat Local, Banco de Dados do Chat e Agregador de Canais Globais.
2. **Integração exclusivamente por eventos assíncronos** utilizando **Open Host Service (OHS) + Published Language** no *upstream* e **Anti-Corruption Layer (ACL)** padronizada em todo cenário *downstream*:
   - `Identidade -> Gameplay`: OHS publica `SessaoIniciada`; ACL traduz sessão em jogador pseudônimo.
   - `Identidade -> Chat`: OHS publica `SessaoIniciada`; ACL converte sessão em participante de canal.
   - `Gameplay -> Chat`: OHS publica `SalaCriada` e `JogadorEntrouNaSala`; ACL mapeia sala (`realmId + roomId`) em canal (`realmId + channelId`).
   - `Provedor OAuth -> Identidade`: ACL isola modelo externo.
   - `Provedor de Pagamento -> Identidade`: ACL protege modelo interno.
3. **Adiar** a extração de um Bounded Context exclusivo de **Pagamento** (moeda, carteira, loja) e a relação `Pagamento -> Gameplay` via evento `CompraConfirmada`.

---

## Alternativas Rejeitadas
- **Shared Kernel:** Reacopla domínios e cria dependência de evolução conjunta.
- **Conformist:** Contamina o modelo interno com contratos externos ou de outros domínios.
- **Chat dentro de Gameplay:** Mistura o Core Domain efêmero de tempo real com um domínio sujeito à retenção e exclusão LGPD.
- **Integração síncrona ou banco compartilhado:** Viola o isolamento de topologia de dados e propaga indisponibilidade.

---

## Consequências
### Positivas (+)
- Evolução, deploy e escalabilidade independentes entre Gameplay, Chat e Identidade.
- Sistemas externos (OAuth e Pagamento) totalmente isolados por ACL.
- Conformidade LGPD facilitada pelo isolamento estrito de PII.

### Negativas (-)
- Overhead de manutenção de contratos e schemas versionados.
- Duplicação traduzida do conceito de "jogador" entre contextos.
- Identidade acumula autenticação e cobrança nesta fase, elevando o risco de ponto único de falha.

---

## Governança e Fitness Functions
- **Contract Tests:** Testes automatizados de contrato entre produtor OHS e consumidor ACL.
- **Versionamento Prévio:** Schemas versionados e publicados antes de qualquer consumo.
- **Testes de Arquitetura (CI):** Proibição automatizada de cruzamento de domínios em acesso a bancos de dados.
- **Code Review:** Bloqueio rigoroso de chamadas síncronas entre Bounded Contexts.
