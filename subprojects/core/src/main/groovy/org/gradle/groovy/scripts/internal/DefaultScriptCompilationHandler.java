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

package org.gradle.groovy.scripts.internal;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovyResourceLoader;
import groovy.lang.Script;
import groovyjarjarasm.asm.ClassWriter;
import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderId;
import org.gradle.configuration.ImportsReader;
import org.gradle.groovy.scripts.ScriptCompilationException;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.groovy.scripts.Transformer;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.serialize.Serializer;
import org.gradle.internal.serialize.kryo.KryoBackedDecoder;
import org.gradle.internal.serialize.kryo.KryoBackedEncoder;
import org.gradle.util.Clock;
import org.gradle.util.GFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;

public class DefaultScriptCompilationHandler implements ScriptCompilationHandler {
    private Logger logger = LoggerFactory.getLogger(DefaultScriptCompilationHandler.class);
    private static final NoOpGroovyResourceLoader NO_OP_GROOVY_RESOURCE_LOADER = new NoOpGroovyResourceLoader();
    private static final String METADATA_FILE_NAME = "metadata.bin";
    private static final int EMPTY_FLAG = 1;
    private static final int HAS_METHODS_FLAG = 2;
    private final ClassLoaderCache classLoaderCache;
    private final String[] defaultImportPackages;

    public DefaultScriptCompilationHandler(ClassLoaderCache classLoaderCache, ImportsReader importsReader) {
        this.classLoaderCache = classLoaderCache;
        defaultImportPackages = importsReader.getImportPackages();
    }

    @Override
    public void compileToDir(ScriptSource source, ClassLoader classLoader, File classesDir, File metadataDir, CompileOperation<?> extractingTransformer,
                             Class<? extends Script> scriptBaseClass, Action<? super ClassNode> verifier) {
        Clock clock = new Clock();
        GFileUtils.deleteDirectory(classesDir);
        GFileUtils.mkdirs(classesDir);
        CompilerConfiguration configuration = createBaseCompilerConfiguration(scriptBaseClass);
        configuration.setTargetDirectory(classesDir);
        try {
            compileScript(source, classLoader, configuration, classesDir, metadataDir, extractingTransformer, verifier);
        } catch (GradleException e) {
            GFileUtils.deleteDirectory(classesDir);
            GFileUtils.deleteDirectory(metadataDir);
            throw e;
        }

        logger.debug("Timing: Writing script to cache at {} took: {}", classesDir.getAbsolutePath(),
                clock.getTime());
    }

    private void compileScript(final ScriptSource source, ClassLoader classLoader, CompilerConfiguration configuration, File classesDir, File metadataDir,
                               final CompileOperation<?> extractingTransformer, final Action<? super ClassNode> customVerifier) {
        final Transformer transformer = extractingTransformer != null ? extractingTransformer.getTransformer() : null;
        logger.info("Compiling {} using {}.", source.getDisplayName(), transformer != null ? transformer.getClass().getSimpleName() : "no transformer");

        final EmptyScriptDetector emptyScriptDetector = new EmptyScriptDetector();
        final PackageStatementDetector packageDetector = new PackageStatementDetector();
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader(classLoader, configuration, false) {
            @Override
            protected CompilationUnit createCompilationUnit(CompilerConfiguration compilerConfiguration,
                                                            CodeSource codeSource) {
                ImportCustomizer customizer = new ImportCustomizer();
                customizer.addStarImports(defaultImportPackages);
                compilerConfiguration.addCompilationCustomizers(customizer);

                CompilationUnit compilationUnit = new CustomCompilationUnit(compilerConfiguration, codeSource, customVerifier, source, this);

                if (transformer != null) {
                    transformer.register(compilationUnit);
                }

                compilationUnit.addPhaseOperation(packageDetector, Phases.CANONICALIZATION);
                compilationUnit.addPhaseOperation(emptyScriptDetector, Phases.CANONICALIZATION);
                return compilationUnit;
            }
        };

        groovyClassLoader.setResourceLoader(NO_OP_GROOVY_RESOURCE_LOADER);
        String scriptText = source.getResource().getText();
        String scriptName = source.getClassName();
        GroovyCodeSource codeSource = new GroovyCodeSource(scriptText == null ? "" : scriptText, scriptName, "/groovy/script");
        try {
            groovyClassLoader.parseClass(codeSource, false);
        } catch (MultipleCompilationErrorsException e) {
            wrapCompilationFailure(source, e);
        } catch (CompilationFailedException e) {
            throw new GradleException(String.format("Could not compile %s.", source.getDisplayName()), e);
        }

        if (packageDetector.hasPackageStatement) {
            throw new UnsupportedOperationException(String.format("%s should not contain a package statement.",
                    StringUtils.capitalize(source.getDisplayName())));
        }
        serializeMetadata(source, extractingTransformer, metadataDir, emptyScriptDetector.isEmptyScript(), emptyScriptDetector.getHasMethods());
    }

    private <M> void serializeMetadata(ScriptSource scriptSource, CompileOperation<M> extractingTransformer, File metadataDir, boolean emptyScript, boolean hasMethods) {
        File metadataFile = new File(metadataDir, METADATA_FILE_NAME);
        try {
            GFileUtils.mkdirs(metadataDir);
            KryoBackedEncoder encoder = new KryoBackedEncoder(new FileOutputStream(metadataFile));
            try {
                byte flags = (byte) ((emptyScript ? EMPTY_FLAG : 0) | (hasMethods ? HAS_METHODS_FLAG : 0));
                encoder.writeByte(flags);
                if (extractingTransformer != null && extractingTransformer.getDataSerializer() != null) {
                    Serializer<M> serializer = extractingTransformer.getDataSerializer();
                    serializer.write(encoder, extractingTransformer.getExtractedData());
                }
            } finally {
                encoder.close();
            }
        } catch (Exception e) {
            throw new GradleException(String.format("Failed to serialize script metadata extracted for %s", scriptSource.getDisplayName()), e);
        }
    }

    private void wrapCompilationFailure(ScriptSource source, MultipleCompilationErrorsException e) {
        // Fix the source file name displayed in the error messages
        for (Object message : e.getErrorCollector().getErrors()) {
            if (message instanceof SyntaxErrorMessage) {
                try {
                    SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
                    Field sourceField = SyntaxErrorMessage.class.getDeclaredField("source");
                    sourceField.setAccessible(true);
                    SourceUnit sourceUnit = (SourceUnit) sourceField.get(syntaxErrorMessage);
                    Field nameField = SourceUnit.class.getDeclaredField("name");
                    nameField.setAccessible(true);
                    nameField.set(sourceUnit, source.getDisplayName());
                } catch (Exception failure) {
                    throw UncheckedException.throwAsUncheckedException(failure);
                }
            }
        }

        SyntaxException syntaxError = e.getErrorCollector().getSyntaxError(0);
        Integer lineNumber = syntaxError == null ? null : syntaxError.getLine();
        throw new ScriptCompilationException(String.format("Could not compile %s.", source.getDisplayName()), e, source,
                lineNumber);
    }

    private CompilerConfiguration createBaseCompilerConfiguration(Class<? extends Script> scriptBaseClass) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(scriptBaseClass.getName());
        return configuration;
    }

    public <T extends Script, M> CompiledScript<T, M> loadFromDir(ScriptSource source, ClassLoader classLoader, File scriptCacheDir,
                                                                  File metadataCacheDir, CompileOperation<M> transformer, Class<T> scriptBaseClass,
                                                                  ClassLoaderId classLoaderId) {
        File metadataFile = new File(metadataCacheDir, METADATA_FILE_NAME);
        try {
            KryoBackedDecoder decoder = new KryoBackedDecoder(new FileInputStream(metadataFile));
            try {
                byte flags = decoder.readByte();
                boolean isEmpty = (flags & EMPTY_FLAG) != 0;
                boolean hasMethods = (flags & HAS_METHODS_FLAG) != 0;
                if (isEmpty) {
                    classLoaderCache.remove(classLoaderId);
                }
                M data;
                if (transformer != null && transformer.getDataSerializer() != null) {
                    data = transformer.getDataSerializer().read(decoder);
                } else {
                    data = null;
                }
                return new ClassesDirCompiledScript<T, M>(isEmpty, hasMethods, classLoaderId, scriptBaseClass, scriptCacheDir, classLoader, source, data);
            } finally {
                decoder.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to deserialize script metadata extracted for %s", source.getDisplayName()), e);
        }
    }

    private static class PackageStatementDetector extends CompilationUnit.SourceUnitOperation {
        private boolean hasPackageStatement;

        @Override
        public void call(SourceUnit source) throws CompilationFailedException {
            hasPackageStatement = source.getAST().getPackageName() != null;
        }
    }

    private static class EmptyScriptDetector extends CompilationUnit.SourceUnitOperation {
        private boolean emptyScript;
        private boolean hasMethods;

        @Override
        public void call(SourceUnit source) {
            if (!source.getAST().getMethods().isEmpty()) {
                hasMethods = true;
            }
            emptyScript = isEmpty(source);
        }

        private boolean isEmpty(SourceUnit source) {
            List<Statement> statements = source.getAST().getStatementBlock().getStatements();
            for (Statement statement : statements) {
                if (AstUtils.mayHaveAnEffect(statement)) {
                    return false;
                }
            }

            // No statements, or no statements that have an effect
            return true;
        }

        public boolean getHasMethods() {
            return hasMethods;
        }

        public boolean isEmptyScript() {
            return emptyScript;
        }
    }

    private static class NoOpGroovyResourceLoader implements GroovyResourceLoader {
        @Override
        public URL loadGroovySource(String filename) throws MalformedURLException {
            return null;
        }
    }

    private class CustomCompilationUnit extends CompilationUnit {

        private final ScriptSource source;

        public CustomCompilationUnit(CompilerConfiguration compilerConfiguration, CodeSource codeSource, final Action<? super ClassNode> customVerifier, ScriptSource source, GroovyClassLoader groovyClassLoader) {
            super(compilerConfiguration, codeSource, groovyClassLoader);
            this.source = source;
            this.verifier = new Verifier() {
                public void visitClass(ClassNode node) {
                    customVerifier.execute(node);
                    super.visitClass(node);
                }

            };
            this.classNodeResolver = new ShortcutClassNodeResolver();
        }

        // This creepy bit of code is here to put the full source path of the script into the debug info for
        // the class.  This makes it possible for a debugger to find the source file for the class.  By default
        // Groovy will only put the filename into the class, but that does not help a debugger for Gradle
        // because it does not know where Gradle scripts might live.
        @Override
        protected groovyjarjarasm.asm.ClassVisitor createClassVisitor() {
            return new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                @Override
                public byte[] toByteArray() {
                    // ignore the sourcePath that is given by Groovy (this is only the filename) and instead
                    // insert the full path if our script source has a source file
                    visitSource(source.getFileName(), null);
                    return super.toByteArray();
                }
            };
        }
    }

    /**
     * This custom class node resolver is responsible for quickly discarding class lookups
     * based on the class name, in order to increase the performance of compilation of Groovy scripts:
     * the Groovy compiler, for each thing that may be a class reference in a script, performs *lots*
     * of attempts to load a class using different fully qualified class names. In some situations,
     * we are able to tell that this will fail, for example when we see something that starts with
     * java.lang$java$lang$.
     * This shortcut makes dramatic differences in compilation times.
     */
    private static class ShortcutClassNodeResolver extends ClassNodeResolver {
        @Override
        public LookupResult findClassNode(String name, CompilationUnit compilationUnit) {
            // todo: ideally, we should use a precompiled DFA here, in order to choose whether to perform a lookup
            // in linear time
            if (name.startsWith("org$gradle$")) {
                return null;
            }
            if (name.indexOf("$org$gradle") > 0 || name.indexOf("$java$lang") > 0 || name.indexOf("$groovy$lang") > 0) {
                return null;
            }
            if (name.startsWith("java.") || name.startsWith("groovy.") || name.startsWith("org.gradle")) {
                int idx = 6;
                char c;
                while ((c = name.charAt(idx)) == '.' || Character.isLowerCase(c)) {
                    if (++idx == name.length()) {
                        break;
                    }
                }
                if (c == '$') {
                    return null;
                }
                if (name.indexOf("org.gradle", 1) > 0) {
                    // ex: org.gradle.api.org.gradle....
                    return null;
                }
            }
            return super.findClassNode(name, compilationUnit);
        }
    }

    private class ClassesDirCompiledScript<T extends Script, M> implements CompiledScript<T, M> {
        private final boolean isEmpty;
        private final boolean hasMethods;
        private final ClassLoaderId classLoaderId;
        private final Class<T> scriptBaseClass;
        private final File scriptCacheDir;
        private final ClassLoader classLoader;
        private final ScriptSource source;
        private final M metadata;
        private Class<? extends T> scriptClass;

        public ClassesDirCompiledScript(boolean isEmpty, boolean hasMethods, ClassLoaderId classLoaderId, Class<T> scriptBaseClass, File scriptCacheDir, ClassLoader classLoader, ScriptSource source, M metadata) {
            this.isEmpty = isEmpty;
            this.hasMethods = hasMethods;
            this.classLoaderId = classLoaderId;
            this.scriptBaseClass = scriptBaseClass;
            this.scriptCacheDir = scriptCacheDir;
            this.classLoader = classLoader;
            this.source = source;
            this.metadata = metadata;
        }

        @Override
        public boolean getRunDoesSomething() {
            return !isEmpty;
        }

        @Override
        public boolean getHasMethods() {
            return hasMethods;
        }

        @Override
        public M getData() {
            return metadata;
        }

        @Override
        public Class<? extends T> loadClass() {
            if (scriptClass == null) {
                if (isEmpty && !hasMethods) {
                    throw new UnsupportedOperationException("Cannot load script that does nothing.");
                }
                try {
                    ClassLoader loader = classLoaderCache.get(classLoaderId, new DefaultClassPath(scriptCacheDir), classLoader, null);
                    scriptClass = loader.loadClass(source.getClassName()).asSubclass(scriptBaseClass);
                } catch (Exception e) {
                    File expectedClassFile = new File(scriptCacheDir, source.getClassName() + ".class");
                    if (!expectedClassFile.exists()) {
                        throw new GradleException(String.format("Could not load compiled classes for %s from cache. Expected class file %s does not exist.", source.getDisplayName(), expectedClassFile.getAbsolutePath()), e);
                    }
                    throw new GradleException(String.format("Could not load compiled classes for %s from cache.", source.getDisplayName()), e);
                }
            }
            return scriptClass;
        }
    }
}
