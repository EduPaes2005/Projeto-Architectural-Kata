# ADR 006: Seleção da Linguagem Principal do Back-end — Go

- **Status:** Proposto *(aguardando validação por protótipo de 1 realm)*
- **Data:** 12/11/2026 *(Encontro 14: Seleção Tecnológica)*
- **Decisores:** Equipe Aurora (Eduardo Paes, Giovanna Gonçalves, Nicole Assis e Nikolas)

---

## 1. Contexto e Escopo
O front-end do World of Webcraft executa em **JavaScript/TypeScript** (com WebAssembly pontual). Esta decisão abrange o **back-end**: servidores de realm (gameplay autoritativo, gateway do realm, chat local) e serviços globais (Gateway Global, Autenticação e Conta, Alocador de Salas e Agregador de Canais).

Premissas técnicas e restrições do projeto:
- **Escala:** 2.000.000 de sessões simultâneas em 100 realms (20.000/realm; teto de 30.000/realm).
- **Throughput por realm:** 80.000 eventos/s de entrada e 720.000 entregas/s em fan-out.
- **Metas de latência (ADR 002):** p95 gateway -> partida <= 20 ms e p95 RTT ao usuário <= 120 ms.
- **Restrição humana e de prazo:** A equipe atualmente domina apenas Node.js.

### Armadilhas Evitadas
- **Hype-Driven Development:** Evitar adoção por tendência sem vínculo quantitativo com os drivers.
- **Síndrome do Objeto Brilhante:** Rust entrega o maior desempenho bruto, mas possui curva íngreme frente à familiaridade atual da equipe.
- **Martelo Dourado:** Node.js é a única tecnologia dominada pela equipe, mas foi submetida rigorosamente aos pesos de latência e concorrência.

Candidatas avaliadas: **Go**, **Node.js (TypeScript)** e **Rust**. C++ foi excluído por segurança de memória e baixa produtividade; Java foi excluído por perfil próximo a C#/Go sem familiaridade da equipe.

---

## 2. Matriz Ponderada (Pesos de 1 a 5 | Pontuação Máxima: 125)

| # | Critério | Driver de Origem | Peso | Go | Node.js | Rust |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | Latência previsível | Performance de rede | 5 | 4 | 2 | 5 |
| 2 | Concorrência e conexões por nó | Escalabilidade | 5 | 5 | 3 | 5 |
| 3 | Eficiência de recursos e startup | Elasticidade e custo | 4 | 4 | 2 | 5 |
| 4 | Ecossistema de rede e mensageria | Escalabilidade e modularidade | 4 | 5 | 4 | 3 |
| 5 | Confiabilidade e manutenibilidade | Disponibilidade e modularidade | 4 | 4 | 3 | 4 |
| 6 | Familiaridade da equipe | Restrição de prazo (Nexo) | 3 | 3 | 5 | 1 |
| — | **Total Ponderado** | — | — | **106 (85%)** | **76 (61%)** | **101 (81%)** |

### Justificativa das Notas
- **Latência:** Go possui GC concorrente com pausas curtas; Node.js tem GC e modelo single-thread por processo, degradando p99; Rust não tem GC.
- **Concorrência:** Goroutines e multithreading nativo favorecem Go e Rust; o fan-out de 720.000 entregas/s por realm satura uma thread do Node.js.
- **Eficiência:** Go gera binário estático enxuto; V8 e replicação de processos elevam a RAM por conexão no Node.js; Rust possui a menor pegada.
- **Ecossistema:** Go possui gRPC nativo, clientes maduros de Kafka/NATS e WebRTC no servidor (Pion); Rust é menos maduro em WebRTC e mensageria.
- **Confiabilidade:** Go entrega tipagem estática e race detector (sujeito a panics por `nil`); TypeScript mitiga tipos mas traz riscos de supply chain no npm; Rust entrega segurança de memória superior, porém o ownership eleva custo de manutenção.
- **Familiaridade:** Node.js é a única linguagem dominada; Go tem curva curta; Rust tem curva íngreme (borrow checker).

### Análise de Sensibilidade
- Se o peso da **familiaridade** cair de 3 para 0, Rust ultrapassa Go por 1 ponto (`98 x 97`). Com peso 1, Go ainda vence (`100 x 99`).
- Se o **ecossistema de rede/mensageria do Rust** subir de 3 para 4, a vantagem do Go cai para 1 ponto (`106 x 105`).
- A decisão é sensível ao fator humano/prazo e à maturidade do ecossistema de rede.

---

## 3. TCO Simplificado (AWS `sa-east-1`, ARM64/Graviton, Preços de Lista)

### Composição Mensal (Ano 1, Sob Demanda)
| Componente | US$ mil/mês | % do Total |
| :--- | :---: | :---: |
| Saída de rede (~7.295 TB/mês) | 834,2 | 79% |
| Computação Go | 110,4 | 10,5% |
| Bancos de dados | 62,9 | 6,0% |
| Armazenamento | 13,7 | 1,3% |
| Mensageria | 2,5 | 0,2% |
| Outros (3%) | 30,7 | 2,9% |
| **Total** | **~1.054** | **100%** |

### Projeção Plurianual
| Cenário | 1 Ano | 2 Anos | 3 Anos |
| :--- | :---: | :---: | :---: |
| **A: Sob demanda** | US$ 12,65 mi | US$ 25,33 mi | US$ 38,02 mi |
| **B: Savings Plan (-30% na frota fixa, hipótese)** | US$ 12,05 mi | US$ 24,11 mi | US$ 36,20 mi |

### Premissas e Conclusões do TCO
- Carga de pico por 4 h/dia e 16% fora do pico (média de 30% do pico), em meses de 730 h.
- Capacidade assumida do Go: 10.000 pacotes/s e 20.000 eventos/s de lógica por vCPU (~26 vCPU por realm no pico = 16 rede + 4 lógica + 30% folga, ou 4 instâncias `c7g.2xlarge`: 2 fixas e 2 elásticas).
- Preços/hora considerados: `c7g.2xlarge` US$ 0,4443; `c7g.xlarge` US$ 0,2222; `r7g.large` US$ 0,1704; `m7g.xlarge` US$ 0,2601.
- Egresso de rede: US$ 0,15/GB (até 10 TB), US$ 0,138 (até 50 TB), US$ 0,126 (até 150 TB), US$ 0,114 acima (8,64 GB/s no pico, +5% cabeçalhos, x 30% média, +2% chat/API).
- Armazenamento: chat em EBS `gp3` (US$ 0,11/GB); resumos em S3 (US$ 0,0405/GB). Bancos (5x `r7g.large`/realm + 3x `r7g.xlarge` globais) e mensageria (6x `m7g.xlarge`) autogerenciados em EC2.
- **Principal achado:** A saída de rede domina o TCO (**79%**), enquanto a computação Go representa apenas **~10,5%**. Reduzir o egresso em 10% (compressão de deltas ou área de interesse) economiza cerca de **US$ 1 milhão/ano**. Mesmo se o Go render 5.000 pacotes/s por vCPU, o TCO sobe apenas ~7%. O TCO humano (ramp-up de Node.js para Go) é mitigado via protótipo, linters e code review.

---

## 4. Radar de Tecnologia e Licenciamento
| Tecnologia | Anel | Diretriz |
| :--- | :--- | :--- |
| **Go** | Trial -> Adopt | Iniciar em Trial com protótipo de 1 realm; promover a Adopt após validar metas de p95. |
| **Rust** | Assess / Trial (restrito) | Fora do núcleo inicial; PoCs pontuais apenas se o loop de física exigir ausência de GC. |
| **Node.js (TS)** | Adopt (front) / Hold (gameplay) | Mantido no cliente/web; em Hold para servidores autoritativos de tempo real. |

- **Licenciamento:** Nenhuma linguagem exige licença por núcleo de CPU. Bibliotecas de terceiros restritas a **MIT, Apache 2.0 ou BSD** (vedando GPL/AGPL e SSPL/BSL), com monitoramento automatizado de CVEs.
- **LGPD:** A adoção de Go preserva pseudonimização de `playerId`, isolamento de dados pessoais em Identidade & Conta e TTL de 90 dias do chat.

---

## 5. Decisão
Adotar **Go** como linguagem principal do back-end, compilado para **ARM64 (Graviton)** na região `sa-east-1`:
- **Protocolos:** **WebRTC DataChannel (UDP)** no gameplay, **WebSocket (TCP)** no chat e **gRPC** entre serviços internos.
- **Exceções** (ex.: reescrita do loop de física em Rust) exigem nova ADR fundamentada por evidência de teste de carga.
- Iniciar a validação com um **protótipo funcional de 1 realm**.

---

## 6. Alternativas Rejeitadas
- **Node.js (76 pts):** Baixo desempenho nos critérios de maior peso (latência e concorrência).
- **Rust (101 pts):** Curva íngreme de aprendizado e ecossistema WebRTC/mensageria menos maduro; mantido apenas como exceção avaliável para componentes críticos.
- **C++ e Java:** Fora da matriz por escopo.

---

## 7. Consequências
### Positivas (+)
- Atende aos drivers críticos de performance de rede e escalabilidade.
- Binário único com startup rápido e ecossistema maduro de rede/mensageria.
- Computação representa apenas ~10,5% do TCO.

### Negativas (-)
- Curva de aprendizado inicial da equipe (mitigada por protótipo, linters e code review).
- Pausas residuais do Garbage Collector.
- Custo de contratação de especialistas em Go.
- Saída de rede permanece como fator dominante de custo; premissas de capacidade por vCPU ainda aguardam medição empírica.

---

## 8. Fitness Functions
1. **Teste de Carga por Realm (8.000 jogadores):** Validar **p95 <= 20 ms** (gateway -> partida) e **RTT p95 <= 120 ms**. Rendimento abaixo de **5.000 pacotes/s por vCPU** dispara revisão obrigatória desta ADR.
2. **Monitoramento de Pausas de GC:** Meta calibrada no protótipo de 1 realm; violações recorrentes acionam PoC em Rust para o componente afetado.
3. **Teste de Arquitetura no CI:** Pacotes de um realm não podem importar clientes de banco de dados de outro realm.
4. **Auditoria de Licenças, CVEs e TCO:** Bloqueio automático de licenças incompatíveis e alertas de orçamento de egresso de rede com revisão do TCO quando o perfil de tráfego mudar.
