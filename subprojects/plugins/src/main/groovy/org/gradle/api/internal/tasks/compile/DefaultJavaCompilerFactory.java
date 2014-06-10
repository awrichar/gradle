/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.tasks.compile;

import org.gradle.api.internal.tasks.compile.daemon.CompilerDaemonFactory;
import org.gradle.api.internal.tasks.compile.daemon.DaemonJavaCompiler;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.language.base.internal.compile.Compiler;

import java.io.File;

public class DefaultJavaCompilerFactory implements JavaCompilerFactory {
    private final File daemonWorkingDir;
    private final JavaCompilerFactory inProcessCompilerFactory;
    private final CompilerDaemonFactory compilerDaemonFactory;
    private boolean jointCompilation;

    public DefaultJavaCompilerFactory(File daemonWorkingDir, JavaCompilerFactory inProcessCompilerFactory, CompilerDaemonFactory compilerDaemonFactory){
        this.daemonWorkingDir = daemonWorkingDir;
        this.inProcessCompilerFactory = inProcessCompilerFactory;
        this.compilerDaemonFactory = compilerDaemonFactory;
    }

    /**
     * If true, the Java compiler to be created is used for joint compilation
     * together with another language's compiler in the compiler daemon.
     * In that case, the other language's normalizing and daemon compilers should be used.
     */
    public void setJointCompilation(boolean flag) {
        jointCompilation = flag;
    }

    public org.gradle.language.base.internal.compile.Compiler<JavaCompileSpec> create(CompileOptions options) {
        Compiler<JavaCompileSpec> result = createTargetCompiler(options);
        if (!jointCompilation) {
            result = new NormalizingJavaCompiler(result);
        }
        return result;
    }

    private Compiler<JavaCompileSpec> createTargetCompiler(CompileOptions options) {
        if (options.isFork() && options.getForkOptions().getExecutable() != null) {
            return new CommandLineJavaCompiler();
        }

        Compiler<JavaCompileSpec> compiler = inProcessCompilerFactory.create(options);
        if (options.isFork() && !jointCompilation) {
            return new DaemonJavaCompiler(daemonWorkingDir, compiler, compilerDaemonFactory);
        }

        return compiler;
    }
}
