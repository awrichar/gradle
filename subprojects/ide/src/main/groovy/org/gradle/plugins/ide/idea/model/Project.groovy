/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.plugins.ide.idea.model

import org.gradle.api.Incubating
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.internal.xml.XmlTransformer
import org.gradle.plugins.ide.internal.generator.XmlPersistableConfigurationObject

/**
 * Represents the customizable elements of an ipr (via XML hooks everything of the ipr is customizable).
 */
class Project extends XmlPersistableConfigurationObject {
    /**
     * A set of {@link Path} instances pointing to the modules contained in the ipr.
     */
    Set<Path> modulePaths = []

    /**
     * A set of wildcard string to be included/excluded from the resources.
     */
    Set<String> wildcards = []

    /**
     * Represent the jdk information of the project java sdk.
     */
    Jdk jdk

    /**
     * The vcs used by the project.
     */
    @Incubating
    String vcs

    /**
     * The project-level libraries of the IDEA project.
     */
    @Incubating
    Set<ProjectLibrary> projectLibraries = [] as LinkedHashSet

    private final PathFactory pathFactory
    private List<IdeaModule> modules

    Project(XmlTransformer xmlTransformer, pathFactory) {
        super(xmlTransformer)
        this.pathFactory = pathFactory
    }

    void configure(List<IdeaModule> modules, String jdkName, IdeaLanguageLevel languageLevel,
                   Collection<String> wildcards, Collection<ProjectLibrary> projectLibraries, String vcs) {
        if (jdkName) {
            jdk = new Jdk(jdkName, languageLevel)
        }


        def modulePaths = modules.collect { pathFactory.relativePath('PROJECT_DIR', it.outputFile) }
        this.modulePaths.addAll(modulePaths)
        this.wildcards.addAll(wildcards)
        this.modules = modules
        // overwrite rather than append libraries
        this.projectLibraries = projectLibraries
        this.vcs = vcs
    }

    @Override
    protected void load(Node xml) {
        findModules().module.each { module ->
            this.modulePaths.add(pathFactory.path(module.@fileurl, module.@filepath))
        }

        findCompilerConfiguration().wildcardResourcePatterns.entry.each { entry ->
            this.wildcards.add(entry.@name)
        }
        def jdkValues = findProjectRootManager().attributes()

        jdk = new Jdk(Boolean.parseBoolean(jdkValues.'assert-keyword'), Boolean.parseBoolean(jdkValues.'jdk-15'),
            jdkValues.languageLevel, jdkValues.'project-jdk-name')

        loadProjectLibraries()
    }

    @Override
    protected String getDefaultResourceName() {
        return "defaultProject.xml"
    }

    @Override
    protected void store(Node xml) {
        findModules().replaceNode {
            modules {
                modulePaths.each { Path modulePath ->
                    module(fileurl: modulePath.url, filepath: modulePath.relPath)
                }
            }
        }
        findCompilerConfiguration().wildcardResourcePatterns.replaceNode {
            wildcardResourcePatterns {
                this.wildcards.each { wildcard ->
                    entry(name: wildcard)
                }
            }
        }
        findProjectRootManager().@'assert-keyword' = jdk.assertKeyword
        findProjectRootManager().@'assert-jdk-15' = jdk.jdk15
        findProjectRootManager().@languageLevel = jdk.languageLevel
        findProjectRootManager().@'project-jdk-name' = jdk.projectJdkName

        configureBytecodeLevel()

        if (vcs) {
            findVcsDirectoryMappings().@vcs = vcs
        }

        storeProjectLibraries()
    }

    private void configureBytecodeLevel() {
        def moduleBytecodeLevels = modules.findAll { it.project.plugins.hasPlugin(JavaBasePlugin) }
        def groupedModuleBytecodeLevels = moduleBytecodeLevels.groupBy { it.project.targetCompatibility }
        if (groupedModuleBytecodeLevels.size() == 1 && !groupedModuleBytecodeLevels.containsKey(JavaVersion.toVersion(jdk.projectJdkName))) {
            findBytecodeLevelConfiguration().@'target' = groupedModuleBytecodeLevels.iterator().next().key
        } else if (groupedModuleBytecodeLevels.size() > 1) {
            def bytecodeTargetLevelNode = findBytecodeLevelConfiguration()
            groupedModuleBytecodeLevels.each { JavaVersion moduleTargetCompatibility, modules ->
                if (!JavaVersion.toVersion(jdk.projectJdkName).equals(moduleTargetCompatibility)) {
                    for (IdeaModule module : modules) {
                        def moduleNode = bytecodeTargetLevelNode.appendNode('module')
                        moduleNode.@'name' = module.name
                        moduleNode.@'target' = moduleTargetCompatibility.toString()
                    }
                }
            }
        }
    }

    private findProjectRootManager() {
        xml.component.find { it.@name == 'ProjectRootManager' }
    }

    private findCompilerConfiguration() {
        xml.component.find { it.@name == 'CompilerConfiguration' }
    }

    private findBytecodeLevelConfiguration() {
        def compilerConfiguration = findCompilerConfiguration()
        if (!compilerConfiguration.bytecodeTargetLevel) {
            compilerConfiguration.appendNode('bytecodeTargetLevel')
        }
        compilerConfiguration.find { it.name() == 'bytecodeTargetLevel' }
    }

    private findVcsDirectoryMappings() {
        xml.component.find { it.@name == 'VcsDirectoryMappings' }.mapping
    }

    private findModules() {
        def moduleManager = xml.component.find { it.@name == 'ProjectModuleManager' }
        if (!moduleManager.modules) {
            moduleManager.appendNode('modules')
        }
        moduleManager.modules
    }

    private Node findLibraryTable() {
        def libraryTable = xml.component.find { it.@name == 'libraryTable' }
        if (!libraryTable) {
            libraryTable = xml.appendNode('component', [name: 'libraryTable'])
        }
        libraryTable
    }

    private void loadProjectLibraries() {
        def libraryTable = findLibraryTable()
        for (library in libraryTable.library) {
            def name = library.@name
            def classes = library.CLASSES.root.@url.collect { new File(it) }
            def javadoc = library.JAVADOC.root.@url.collect { new File(it) }
            def sources = library.SOURCES.root.@url.collect { new File(it) }
            projectLibraries << new ProjectLibrary(name: name, classes: classes, javadoc: javadoc, sources: sources)
        }
    }

    private void storeProjectLibraries() {
        Node libraryTable = findLibraryTable()
        if (projectLibraries.empty) {
            xml.remove(libraryTable)
            return
        }
        libraryTable.value = new NodeList()
        for (library in projectLibraries) {
            library.addToNode(libraryTable, pathFactory)
        }
    }

    boolean equals(o) {
        if (this.is(o)) {
            return true
        }

        if (getClass() != o.class) {
            return false
        }

        Project project = (Project) o;

        if (jdk != project.jdk) {
            return false
        }
        if (modulePaths != project.modulePaths) {
            return false
        }
        if (wildcards != project.wildcards) {
            return false
        }
        if (vcs != project.vcs) {
            return false
        }

        return true;
    }

    int hashCode() {
        int result;

        result = (modulePaths != null ? modulePaths.hashCode() : 0);
        result = 31 * result + (wildcards != null ? wildcards.hashCode() : 0);
        result = 31 * result + (jdk != null ? jdk.hashCode() : 0);
        result = 31 * result + (vcs != null ? vcs.hashCode() : 0);
        return result;
    }
}
