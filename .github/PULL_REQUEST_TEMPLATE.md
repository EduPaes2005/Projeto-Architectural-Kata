# Pull Request — Architectural Kata (World of Webcraft)

## 1. Identificação da Entrega
- **Autor(a):** [Nome Completo]
- **Papel na Sprint 4:** [Líder & Arquiteto Principal / Pesquisador & Modelagem DDD / Pesquisador & Resiliência & Integração / Executor IA-Native & QA/Fitness Functions]
- **Branch de Origem:** `feature/[nome-aluno]-[escopo]`
- **Eixos da Rubrica Endereçados:**
  - [ ] Eixo 1: Visão Sistêmica & Modelo C4
  - [ ] Eixo 2: Governança & Qualidade das ADRs
  - [ ] Eixo 3: Aplicação de SOLID, GoF & DDD
  - [ ] Eixo 4: Resiliência, Integração & IA-Native
  - [ ] Eixo 5: Organização do Repositório & Fitness Functions

---

## 2. Resumo das Alterações e Artefatos Adicionados
Descreva objetivamente os arquivos `.md`, `.puml`, `.dsl`, `.d2`, `.json` ou testes adicionados/modificados neste Pull Request:
- `caminho/do/arquivo`: descrição breve da contribuição.

---

## 3. Defesa Técnica das Decisões Arquiteturais
Explique **por que** as escolhas modeladas/documentadas atendem aos drivers priorizados do World of Webcraft (Escalabilidade, Performance de Rede, Disponibilidade, Modulabilidade, Elasticidade e Privacidade/LGPD):

### 3.1. Rastreabilidade aos Requisitos (QAW / BOTE / ADRs)
- **Cenário(s) QAW atendido(s):** [ex.: Cenário 1 (4.000 logins/s, p95 <= 1,5 s) / Cenário 2 (80.000 salas, p95 RTT <= 120 ms) / Cenário 3 (chat RTO <= 10 s, p95 <= 200 ms)]
- **ADRs relacionadas:** [ex.: ADR-000, ADR-001, ADR-002, ADR-006]

### 3.2. Trade-offs Assumidos e Alternativas Rejeitadas
- **Alternativa(s) descartada(s):** [Explique o que foi rejeitado e o motivo técnico]
- **Trade-off(s) aceito(s) e mitigação:** [Explique o impacto negativo conhecido e qual mecanismo ou fitness function o controla]

---

## 4. Checklist Obrigatório do Autor
- [ ] Não há commits diretos na `main`; o PR parte da `main` atualizada.
- [ ] Todos os números e decisões respeitam estritamente o Documento Consolidado da Equipe Aurora.
- [ ] Os diagramas Diagrams-as-Code (DaC) / Models-as-Code (MaC) especificam tecnologias, protocolos e natureza (síncrono/assíncrono).
- [ ] As regras de isolamento de Topologia de Dados (zero acesso cross-shard, sem frames no barramento, conformidade LGPD) foram preservadas.

---

## 5. Checklist do Revisor (Peer-Review)
> **Instrução ao Revisor:** Inspecione a aba **Files changed**, deixe comentários técnicos diretamente nas linhas dos arquivos e aprove apenas quando o modelo estiver consistente.

- [ ] Consistência verificada entre diagramas C4, Bounded Contexts, Diagrama de Classes e ADRs.
- [ ] Trade-offs e fitness functions mensuráveis estão explícitos.
- [ ] Comentários técnicos registrados na revisão de código/documentação.
