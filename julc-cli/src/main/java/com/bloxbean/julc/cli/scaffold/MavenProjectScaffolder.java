package com.bloxbean.julc.cli.scaffold;

import com.bloxbean.julc.cli.JulcVersionProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a complete Maven project with julc dependencies,
 * annotation processor, starter validator, and Maven wrapper.
 */
public final class MavenProjectScaffolder {

    private MavenProjectScaffolder() {}

    public static void scaffold(Path root, String projectName, String group,
                                String artifact, String pkg) throws IOException {
        String pkgPath = PackageNameUtils.toPath(pkg);

        // Create directory structure
        Path mainJava = root.resolve("src/main/java").resolve(pkgPath);
        Path testJava = root.resolve("src/test/java").resolve(pkgPath);
        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        String julcVersion = JulcVersionProvider.VERSION;
        String cclVersion = JulcVersionProvider.CARDANO_CLIENT_LIB_VERSION;

        // pom.xml
        Files.writeString(root.resolve("pom.xml"), pomXml(group, artifact, julcVersion, cclVersion));

        // .gitignore
        Files.writeString(root.resolve(".gitignore"), gitignore());

        // Starter validator (same as Gradle)
        Files.writeString(mainJava.resolve("AlwaysSucceeds.java"), validatorTemplate(pkg));

        // Starter test (same as Gradle)
        Files.writeString(testJava.resolve("AlwaysSucceedsTest.java"), testTemplate(pkg));

        // Extract Maven wrapper
        WrapperExtractor.extractMavenWrapper(root);
    }

    private static String pomXml(String group, String artifact,
                                 String julcVersion, String cclVersion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.1.0</version>

                    <properties>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <maven.compiler.source>25</maven.compiler.source>
                        <maven.compiler.target>25</maven.compiler.target>
                        <julc.version>%s</julc.version>
                        <cardano-client-lib.version>%s</cardano-client-lib.version>
                        <junit.version>5.11.4</junit.version>
                    </properties>

                    <repositories>
                        <repository>
                            <id>sonatype-snapshots</id>
                            <url>https://central.sonatype.com/repository/maven-snapshots</url>
                            <snapshots><enabled>true</enabled></snapshots>
                            <releases><enabled>false</enabled></releases>
                        </repository>
                    </repositories>

                    <dependencies>
                        <!-- Core: stdlib + ledger types + annotations -->
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>julc-stdlib</artifactId>
                            <version>${julc.version}</version>
                        </dependency>

                        <!-- Offchain libraries -->
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>julc-cardano-client-lib</artifactId>
                            <version>${julc.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>cardano-client-lib</artifactId>
                            <version>${cardano-client-lib.version}</version>
                        </dependency>

                        <!-- Test: VM for local evaluation -->
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>julc-testkit</artifactId>
                            <version>${julc.version}</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>julc-compiler</artifactId>
                            <version>${julc.version}</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.bloxbean.cardano</groupId>
                            <artifactId>julc-vm-java</artifactId>
                            <version>${julc.version}</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                                <configuration>
                                    <source>25</source>
                                    <target>25</target>
                                    <compilerArgs>
                                        <arg>-Ajulc.projectName=${project.name}</arg>
                                        <arg>-Ajulc.projectVersion=${project.version}</arg>
                                    </compilerArgs>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>com.bloxbean.cardano</groupId>
                                            <artifactId>julc-annotation-processor</artifactId>
                                            <version>${julc.version}</version>
                                        </path>
                                    </annotationProcessorPaths>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.5.2</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(group, artifact, julcVersion, cclVersion);
    }

    private static String gitignore() {
        return """
                target/
                *.class
                .idea/
                *.iml
                .DS_Store
                """;
    }

    private static String validatorTemplate(String pkg) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;

                @Validator
                public class AlwaysSucceeds {

                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """.formatted(pkg);
    }

    private static String testTemplate(String pkg) {
        return """
                package %s;

                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.testkit.ContractTest;
                import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.*;

                class AlwaysSucceedsTest extends ContractTest {

                    @Test
                    void testAlwaysSucceeds() {
                        var program = compileValidatorWithSourceMap(AlwaysSucceeds.class);

                        var ref = TestDataBuilder.randomTxOutRef_typed();
                        var ctx = spendingContext(ref, PlutusData.UNIT)
                                .redeemer(PlutusData.UNIT)
                                .buildPlutusData();

                        var result = evaluateWithTrace(program, ctx);

                        //Assert if successful
                        assertTrue(result.isSuccess());

                        System.out.print(formatExecutionTrace());
                        System.out.print(formatBudgetSummary());
                    }
                }
                """.formatted(pkg);
    }
}
