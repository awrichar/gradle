/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.jvm.test.internal;

import com.google.common.collect.Lists;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.JvmBinarySpec;
import org.gradle.jvm.internal.DefaultJvmBinarySpec;
import org.gradle.jvm.internal.WithDependencies;
import org.gradle.jvm.internal.WithJvmAssembly;
import org.gradle.jvm.test.JUnitTestSuiteSpec;
import org.gradle.jvm.test.JvmTestSuiteBinarySpec;
import org.gradle.platform.base.BinaryTasksCollection;
import org.gradle.platform.base.DependencySpec;
import org.gradle.platform.base.Variant;
import org.gradle.platform.base.internal.BinaryTasksCollectionWrapper;

import java.util.Collection;

public class DefaultJUnitTestSuiteBinarySpec extends DefaultJvmBinarySpec implements JUnitTestSuiteBinarySpecInternal, WithJvmAssembly, WithDependencies {
    private String junitVersion;
    private Collection<DependencySpec> componentLevelDependencies = Lists.newLinkedList();
    private JvmBinarySpec testedBinary;
    private final DefaultTasksCollection tasks = new DefaultTasksCollection(super.getTasks());

    @Override
    public JvmTestSuiteBinarySpec.JvmTestSuiteTasks getTasks() {
        return tasks;
    }

    @Override
    public JUnitTestSuiteSpec getTestSuite() {
        return getComponentAs(JUnitTestSuiteSpec.class);
    }

    @Override
    public JvmBinarySpec getTestedBinary() {
        return testedBinary;
    }

    @Override
    protected String getTypeName() {
        return "Test suite";
    }

    @Override
    @Variant
    public String getJUnitVersion() {
        return junitVersion;
    }

    @Override
    public void setJUnitVersion(String version) {
        this.junitVersion = version;
    }

    @Override
    public void setDependencies(Collection<DependencySpec> dependencies) {
        this.componentLevelDependencies = dependencies;
    }

    @Override
    public Collection<DependencySpec> getDependencies() {
        return componentLevelDependencies;
    }

    @Override
    public void setTestedBinary(JvmBinarySpec testedBinary) {
        this.testedBinary = testedBinary;
    }

    static class DefaultTasksCollection extends BinaryTasksCollectionWrapper implements JvmTestSuiteBinarySpec.JvmTestSuiteTasks {
        public DefaultTasksCollection(BinaryTasksCollection delegate) {
            super(delegate);
        }

        @Override
        public Test getTest() {
            return findSingleTaskWithType(Test.class);
        }

    }
}
