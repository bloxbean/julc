package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.scaffold.GradleProjectScaffolder;
import com.bloxbean.julc.cli.scaffold.MavenProjectScaffolder;
import com.bloxbean.julc.cli.scaffold.PackageNameUtils;
import com.bloxbean.julc.cli.scaffold.ProjectScaffolder;
import com.bloxbean.julc.cli.scaffold.ProjectTemplate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

@Command(name = "new", description = "Create a new julc project")
public class NewCommand implements Runnable {

    @Parameters(index = "0", description = "Project name")
    private String name;

    @Option(names = {"-t", "--template"},
            description = "Project template: basic, gradle, maven (default: basic)",
            defaultValue = "basic")
    private ProjectTemplate template;

    @Option(names = {"-g", "--group"}, description = "Maven groupId (default: com.example)")
    private String group;

    @Option(names = {"-a", "--artifact"}, description = "Maven artifactId (default: project name)")
    private String artifact;

    @Option(names = {"-p", "--package"}, description = "Java package name (default: <group>.<name>)")
    private String pkg;

    @Override
    public void run() {
        try {
            Path projectRoot = Path.of(name);
            if (Files.exists(projectRoot)) {
                System.err.println(AnsiColors.red("Directory '" + name + "' already exists."));
                System.exit(1);
            }

            System.out.println("Creating project " + AnsiColors.bold(name) + " ...");

            switch (template) {
                case BASIC -> {
                    ProjectScaffolder.scaffold(projectRoot, name);
                    System.out.println(AnsiColors.green("Project created at ./" + name));
                    System.out.println();
                    System.out.println("  cd " + name);
                    System.out.println("  julc build    # compile validators");
                    System.out.println("  julc check    # run tests");
                }
                case GRADLE -> {
                    resolveDefaults();
                    GradleProjectScaffolder.scaffold(projectRoot, name, group, artifact, pkg);
                    printJavaVersionWarning();
                    System.out.println(AnsiColors.green("Gradle project created at ./" + name));
                    System.out.println();
                    System.out.println("  cd " + name);
                    System.out.println("  ./gradlew build");
                }
                case MAVEN -> {
                    resolveDefaults();
                    MavenProjectScaffolder.scaffold(projectRoot, name, group, artifact, pkg);
                    printJavaVersionWarning();
                    System.out.println(AnsiColors.green("Maven project created at ./" + name));
                    System.out.println();
                    System.out.println("  cd " + name);
                    System.out.println("  ./mvnw compile test");
                }
            }
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Failed to create project: " + e.getMessage()));
            System.exit(1);
        }
    }

    private void resolveDefaults() {
        if (group == null) {
            group = promptWithDefault("Group ID", "com.example");
        }
        if (artifact == null) {
            artifact = promptWithDefault("Artifact ID", name);
        }
        if (pkg == null) {
            String defaultPkg = group + "." + PackageNameUtils.sanitize(name);
            pkg = promptWithDefault("Package name", defaultPkg);
        }

        // Validate package name
        String error = PackageNameUtils.validate(pkg);
        if (error != null) {
            System.err.println(AnsiColors.red("Invalid package name: " + error));
            System.exit(1);
        }
    }

    private String promptWithDefault(String label, String defaultValue) {
        var console = System.console();
        if (console != null) {
            String input = console.readLine("  %s [%s]: ", label, defaultValue);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }
        return defaultValue;
    }

    private void printJavaVersionWarning() {
        int javaVersion = Runtime.version().feature();
        if (javaVersion < 24) {
            System.out.println();
            System.out.println(AnsiColors.yellow("Warning: Java 24+ is required. Detected: Java " + javaVersion));
            System.out.println(AnsiColors.yellow("  Install GraalVM JDK 24:"));
            System.out.println(AnsiColors.yellow("    macOS/Linux: sdk install java 24.0.1-graal"));
            System.out.println(AnsiColors.yellow("    Windows:     Download from https://www.graalvm.org/downloads/"));
        }
    }
}
