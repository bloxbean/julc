package com.bloxbean.julc.cli.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates IntelliJ IDEA project files for julcc projects.
 */
public final class IdeaConfigGenerator {

    private IdeaConfigGenerator() {}

    public static void generate(Path projectRoot, String projectName) throws IOException {
        Path ideaDir = projectRoot.resolve(".idea");
        Files.createDirectories(ideaDir);

        // misc.xml — language level
        Files.writeString(ideaDir.resolve("misc.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectRootManager" version="2" languageLevel="JDK_21" default="true" project-jdk-name="21" project-jdk-type="JavaSDK">
                    <output url="file://$PROJECT_DIR$/build" />
                  </component>
                </project>
                """);

        // modules.xml — reference .iml file
        Files.writeString(ideaDir.resolve("modules.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectModuleManager">
                    <modules>
                      <module fileurl="file://$PROJECT_DIR$/%s.iml" filepath="$PROJECT_DIR$/%s.iml" />
                    </modules>
                  </component>
                </project>
                """.formatted(projectName, projectName));

        // <name>.iml — source roots
        Files.writeString(projectRoot.resolve(projectName + ".iml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <module type="JAVA_MODULE" version="4">
                  <component name="NewModuleRootManager" inherit-compiler-output="true">
                    <exclude-output />
                    <content url="file://$MODULE_DIR$">
                      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
                      <sourceFolder url="file://$MODULE_DIR$/test" isTestSource="true" />
                      <sourceFolder url="file://$MODULE_DIR$/.julc/stdlib" isTestSource="false" />
                      <excludeFolder url="file://$MODULE_DIR$/build" />
                    </content>
                    <orderEntry type="inheritedJdk" />
                    <orderEntry type="sourceFolder" forTests="false" />
                  </component>
                </module>
                """);
    }
}
