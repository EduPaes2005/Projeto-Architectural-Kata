package br.com.aurora.worldofwebcraft.fitness;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fitness Functions Estruturais (ArchUnit) — World of Webcraft (Equipe Aurora).
 * Valida automaticamente as restrições de dependência definidas nas ADRs 000, 001, 002, 005, 006 e 007
 * e a arquitetura dos 5 pacotes lógicos (Seções 8, 9 e 14.3 do Documento Consolidado).
 */
@AnalyzeClasses(
    packages = "br.com.aurora.worldofwebcraft",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
public class WorldOfWebcraftArchitectureTest {

    // Regra 1 (ADR 000, ADR 001, ADR 006): Isolamento estrito de bancos de dados por Shard/Realm
    // Nenhum componente do Plano Central ou de um Shard deve acessar diretamente clientes de banco de outro Realm.
    @ArchTest
    static final ArchRule planoCentralNaoDeveAcessarBancoDeShard =
        noClasses()
            .that().resideInAPackage("..plano_central..")
            .should().dependOnClassesThat().resideInAPackage("..shard_realm.db..")
            .because("ADR 001: Autenticação e Plano Central publicam SessaoIniciada no barramento, sem acesso direto ao banco do shard.");

    // Regra 2 (ADR 000, ADR 005): Zero acoplamento direto entre Gameplay e Chat (apenas via Eventos / Barramento)
    @ArchTest
    static final ArchRule gameplayNaoDeveDependerDiretamenteDoChat =
        noClasses()
            .that().resideInAPackage("..shard_realm.gameplay..")
            .should().dependOnClassesThat().resideInAPackage("..shard_realm.chat..")
            .because("ADR 005: Gameplay (Core) e Chat (Suporte/LGPD) integram-se exclusivamente por eventos assíncronos (OHS + ACL).");

    // Regra 3 (ADR 002): Imunidade do Barramento de Eventos contra frames de gameplay
    @ArchTest
    static final ArchRule framesDeGameplayNaoPodemSerPublicadosNoBarramento =
        noClasses()
            .that().resideInAPackage("..shard_realm.gameplay.frames..")
            .should().dependOnClassesThat().haveSimpleName("IBarramentoEventos")
            .because("ADR 002: Frames de movimento e disparo ficam restritos ao loop local de 60 Hz; o barramento recebe apenas eventos discretos e resumos.");

    // Regra 4 (ADR 007): Imunidade do loop autoritativo de gameplay contra chamadas síncronas ao Motor AI-Native
    @ArchTest
    static final ArchRule gameplayNaoDeveChamarMotorAiNative =
        noClasses()
            .that().resideInAPackage("..shard_realm.gameplay..")
            .should().dependOnClassesThat().resideInAPackage("..ai_native..")
            .because("ADR 007: Proibido acoplamento síncrono do Motor AI-Native aos servidores autoritativos de gameplay em Go (60 Hz).");

    // Regra 5 (Seções 8 e 9 — DIP e redução do D=0,40 em Shard_Realm):
    // Processadores de eventos e alocação devem depender de abstrações (Abstracoes_Interfaces), nunca de implementações concretas de infraestrutura.
    @ArchTest
    static final ArchRule processadoresDevemImplementarInterfaceDeEvento =
        classes()
            .that().resideInAPackage("..processadores_eventos..")
            .should().implement("br.com.aurora.worldofwebcraft.abstracoes_interfaces.IProcessadorEvento")
            .because("Seção 8 (OCP/DIP): Todos os processadores de eventos devem implementar IProcessadorEvento.");

    @ArchTest
    static final ArchRule abstracoesNaoDevemDependerDeImplementacoesDeInfraestrutura =
        noClasses()
            .that().resideInAPackage("..abstracoes_interfaces..")
            .should().dependOnClassesThat().resideInAnyPackage("..infraestrutura..", "..processadores_eventos..", "..plano_central..")
            .because("Seção 8 (DIP): O pacote Abstracoes_Interfaces (A=1,00) define contratos de domínio e não pode depender de infraestrutura concreta.");
}
