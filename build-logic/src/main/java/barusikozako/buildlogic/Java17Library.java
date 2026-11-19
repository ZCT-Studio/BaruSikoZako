// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public final class Java17Library implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        project.getExtensions().getByType(JavaPluginExtension.class)
            .getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(17));
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(17);
            task.getOptions().setEncoding("UTF-8");
        });
        project.getConfigurations().configureEach(configuration -> {
            if (configuration.isCanBeResolved()) {
                configuration.getResolutionStrategy().activateDependencyLocking();
                configuration.getResolutionStrategy().eachDependency(dependency -> {
                    String version = dependency.getRequested().getVersion();
                    if (version != null && (version.endsWith("+") || version.startsWith("latest.") ||
                        version.indexOf('[') >= 0 || version.indexOf(']') >= 0 ||
                        version.indexOf('(') >= 0 || version.indexOf(')') >= 0 ||
                        version.toUpperCase(java.util.Locale.ROOT).endsWith("-SNAPSHOT")))
                        throw new GradleException("Expected a fixed dependency version: " + dependency.getRequested());
                });
            }
        });
    }
}
