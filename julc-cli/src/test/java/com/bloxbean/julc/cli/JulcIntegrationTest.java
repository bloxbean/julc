package com.bloxbean.julc.cli;

import com.bloxbean.julc.cli.check.TestDiscovery;
import com.bloxbean.julc.cli.check.TestRunner;
import com.bloxbean.julc.cli.project.*;
import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.julc.cli.scaffold.GradleProjectScaffolder;
import com.bloxbean.julc.cli.scaffold.MavenProjectScaffolder;
import com.bloxbean.julc.cli.scaffold.ProjectScaffolder;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class JulcIntegrationTest {

    @Test
    void scaffoldBuildCheck(@TempDir Path tempDir) throws IOException {
        Path projectRoot = tempDir.resolve("myproject");

        // 1. Scaffold
        ProjectScaffolder.scaffold(projectRoot, "myproject");
        assertTrue(Files.exists(ProjectLayout.tomlFile(projectRoot)));
        assertTrue(Files.exists(ProjectLayout.srcDir(projectRoot).resolve("AlwaysSucceeds.java")));
        assertTrue(Files.exists(ProjectLayout.testDir(projectRoot).resolve("AlwaysSucceedsTest.java")));
        assertTrue(Files.isDirectory(ProjectLayout.stdlibDir(projectRoot)));
        assertTrue(Files.exists(ProjectLayout.lockFile(projectRoot)));

        // 2. Build
        var config = TomlParser.parse(ProjectLayout.tomlFile(projectRoot));
        assertEquals("myproject", config.name());

        var scanResult = ProjectScanner.scan(ProjectLayout.srcDir(projectRoot));
        assertEquals(1, scanResult.validators().size());
        assertTrue(scanResult.validators().containsKey("AlwaysSucceeds"));

        var pool = ProjectSourceResolver.buildPool(scanResult.libraries());
        var validatorSource = scanResult.validators().get("AlwaysSucceeds");
        var resolvedLibs = ProjectSourceResolver.resolve(validatorSource, pool);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions());
        var result = compiler.compile(validatorSource, resolvedLibs);

        assertFalse(result.hasErrors());
        assertNotNull(result.program());
        assertTrue(result.scriptSizeBytes() > 0);

        // Verify script hash
        String hash = JulcScriptAdapter.scriptHash(result.program());
        assertNotNull(hash);
        assertEquals(56, hash.length()); // 28 bytes hex

        // 3. Blueprint
        var compiled = new ArrayList<BlueprintGenerator.CompiledValidator>();
        compiled.add(new BlueprintGenerator.CompiledValidator("AlwaysSucceeds", validatorSource, result));
        var blueprint = BlueprintGenerator.generate(new BlueprintConfig(config.name(), config.version()), compiled);
        assertEquals(1, blueprint.validators().size());
        assertEquals(hash, blueprint.validators().getFirst().hash());

        // 4. Check (tests)
        var tests = TestDiscovery.discover(ProjectLayout.testDir(projectRoot));
        assertEquals(2, tests.size());

        var testPool = ProjectSourceResolver.buildPool(scanResult.libraries());
        testPool.putAll(scanResult.validators());
        var runner = new TestRunner(testPool);

        for (var test : tests) {
            testPool.put(test.className(), test.source());
            var testResult = runner.run(test);
            assertTrue(testResult.passed(), "Test " + test.methodName() + " should pass: " + testResult.error());
            assertTrue(testResult.budget().cpuSteps() > 0);
        }
    }

    @Test
    void testContextLibThroughCheck(@TempDir Path tempDir) throws IOException {
        Path projectRoot = tempDir.resolve("testctx");
        ProjectScaffolder.scaffold(projectRoot, "testctx");

        // Write a test using TestContextLib
        Files.writeString(ProjectLayout.testDir(projectRoot).resolve("ContextTest.java"), """
                import com.bloxbean.cardano.julc.stdlib.test.Test;
                import com.bloxbean.cardano.julc.stdlib.test.TestContextLib;
                import com.bloxbean.cardano.julc.ledger.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import com.bloxbean.cardano.julc.core.PlutusData;

                public class ContextTest {
                    @Test
                    public static boolean test_emptyTxInfo() {
                        var txInfo = TestContextLib.emptyTxInfo();
                        // Verify it's a valid ConstrData (tag 0, 16 fields)
                        return Builtins.constrTag((PlutusData)(Object) txInfo) == 0;
                    }
                }
                """);

        var scanResult = ProjectScanner.scan(ProjectLayout.srcDir(projectRoot));
        var tests = TestDiscovery.discover(ProjectLayout.testDir(projectRoot));
        // Should find at least the ContextTest
        assertTrue(tests.stream().anyMatch(t -> t.className().equals("ContextTest")));

        var testPool = ProjectSourceResolver.buildPool(scanResult.libraries());
        testPool.putAll(scanResult.validators());
        var runner = new TestRunner(testPool);

        for (var test : tests) {
            if (test.className().equals("ContextTest")) {
                testPool.put(test.className(), test.source());
                var result = runner.run(test);
                assertTrue(result.passed(), "ContextTest should pass: " + result.error());
                assertTrue(result.budget().cpuSteps() > 0);
            }
        }
    }

    @Test
    void buildWithUserLibrary(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        // User library
        Files.writeString(srcDir.resolve("MyLib.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class MyLib {
                    public static boolean always() { return true; }
                }
                """);

        // Validator using the library
        Files.writeString(srcDir.resolve("MyValidator.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;

                @Validator
                public class MyValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return MyLib.always();
                    }
                }
                """);

        var scanResult = ProjectScanner.scan(srcDir);
        assertEquals(1, scanResult.validators().size());
        assertEquals(1, scanResult.libraries().size());

        var pool = ProjectSourceResolver.buildPool(scanResult.libraries());
        assertTrue(pool.containsKey("MyLib"));

        var validatorSource = scanResult.validators().get("MyValidator");
        var resolvedLibs = ProjectSourceResolver.resolve(validatorSource, pool);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var result = compiler.compile(validatorSource, resolvedLibs);

        assertFalse(result.hasErrors());
        assertNotNull(result.program());
    }

    @Test
    void scaffoldGradleProject(@TempDir Path tempDir) throws IOException {
        Path projectRoot = tempDir.resolve("test-gradle");
        GradleProjectScaffolder.scaffold(projectRoot, "test-gradle",
                "com.myorg", "test-gradle", "com.myorg.testgradle");

        // Verify directory structure
        assertTrue(Files.isDirectory(projectRoot.resolve("src/main/java/com/myorg/testgradle")));
        assertTrue(Files.isDirectory(projectRoot.resolve("src/test/java/com/myorg/testgradle")));

        // Verify build files
        assertTrue(Files.exists(projectRoot.resolve("build.gradle")));
        assertTrue(Files.exists(projectRoot.resolve("settings.gradle")));
        assertTrue(Files.exists(projectRoot.resolve(".gitignore")));

        // Verify Gradle wrapper
        assertTrue(Files.exists(projectRoot.resolve("gradlew")));
        assertTrue(Files.exists(projectRoot.resolve("gradlew.bat")));
        assertTrue(Files.exists(projectRoot.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(Files.exists(projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties")));
        assertTrue(projectRoot.resolve("gradlew").toFile().canExecute());

        // Verify build.gradle content
        String buildGradle = Files.readString(projectRoot.resolve("build.gradle"));
        assertTrue(buildGradle.contains("group = 'com.myorg'"));
        assertTrue(buildGradle.contains("julcVersion = '" + JulcVersionProvider.VERSION + "'"));
        assertTrue(buildGradle.contains("cardanoClientLibVersion = '" + JulcVersionProvider.CARDANO_CLIENT_LIB_VERSION + "'"));
        assertTrue(buildGradle.contains("julc-stdlib"));
        assertTrue(buildGradle.contains("julc-annotation-processor"));
        assertTrue(buildGradle.contains("julc-testkit"));

        // Verify settings.gradle
        String settingsGradle = Files.readString(projectRoot.resolve("settings.gradle"));
        assertTrue(settingsGradle.contains("rootProject.name = 'test-gradle'"));

        // Verify starter files have package declaration
        String validator = Files.readString(projectRoot.resolve("src/main/java/com/myorg/testgradle/AlwaysSucceeds.java"));
        assertTrue(validator.contains("package com.myorg.testgradle;"));
        assertTrue(validator.contains("@Validator"));

        String test = Files.readString(projectRoot.resolve("src/test/java/com/myorg/testgradle/AlwaysSucceedsTest.java"));
        assertTrue(test.contains("package com.myorg.testgradle;"));
        assertTrue(test.contains("ContractTest"));
        assertTrue(test.contains("compileValidatorWithSourceMap"));
        assertTrue(test.contains("TestDataBuilder"));
        assertTrue(test.contains("evaluateWithTrace"));

        // No julc.toml or .julc/ directory
        assertFalse(Files.exists(projectRoot.resolve("julc.toml")));
        assertFalse(Files.exists(projectRoot.resolve(".julc")));
    }

    @Test
    void scaffoldMavenProject(@TempDir Path tempDir) throws IOException {
        Path projectRoot = tempDir.resolve("test-maven");
        MavenProjectScaffolder.scaffold(projectRoot, "test-maven",
                "com.myorg", "test-maven", "com.myorg.testmaven");

        // Verify directory structure
        assertTrue(Files.isDirectory(projectRoot.resolve("src/main/java/com/myorg/testmaven")));
        assertTrue(Files.isDirectory(projectRoot.resolve("src/test/java/com/myorg/testmaven")));

        // Verify build files
        assertTrue(Files.exists(projectRoot.resolve("pom.xml")));
        assertTrue(Files.exists(projectRoot.resolve(".gitignore")));

        // Verify Maven wrapper
        assertTrue(Files.exists(projectRoot.resolve("mvnw")));
        assertTrue(Files.exists(projectRoot.resolve("mvnw.cmd")));
        assertTrue(Files.exists(projectRoot.resolve(".mvn/wrapper/maven-wrapper.jar")));
        assertTrue(Files.exists(projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties")));
        assertTrue(projectRoot.resolve("mvnw").toFile().canExecute());

        // Verify pom.xml content
        String pom = Files.readString(projectRoot.resolve("pom.xml"));
        assertTrue(pom.contains("<groupId>com.myorg</groupId>"));
        assertTrue(pom.contains("<artifactId>test-maven</artifactId>"));
        assertTrue(pom.contains("<julc.version>" + JulcVersionProvider.VERSION + "</julc.version>"));
        assertTrue(pom.contains("<cardano-client-lib.version>" + JulcVersionProvider.CARDANO_CLIENT_LIB_VERSION + "</cardano-client-lib.version>"));
        assertTrue(pom.contains("julc-stdlib"));
        assertTrue(pom.contains("julc-annotation-processor"));
        assertTrue(pom.contains("julc-testkit"));
        assertTrue(pom.contains("maven-compiler-plugin"));
        assertTrue(pom.contains("annotationProcessorPaths"));

        // Verify starter files have package declaration
        String validator = Files.readString(projectRoot.resolve("src/main/java/com/myorg/testmaven/AlwaysSucceeds.java"));
        assertTrue(validator.contains("package com.myorg.testmaven;"));

        String test = Files.readString(projectRoot.resolve("src/test/java/com/myorg/testmaven/AlwaysSucceedsTest.java"));
        assertTrue(test.contains("package com.myorg.testmaven;"));
        assertTrue(test.contains("compileValidatorWithSourceMap"));
        assertTrue(test.contains("TestDataBuilder"));
        assertTrue(test.contains("evaluateWithTrace"));

        // No julc.toml or .julc/ directory
        assertFalse(Files.exists(projectRoot.resolve("julc.toml")));
        assertFalse(Files.exists(projectRoot.resolve(".julc")));
    }
}
