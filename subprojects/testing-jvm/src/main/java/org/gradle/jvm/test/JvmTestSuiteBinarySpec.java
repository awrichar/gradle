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

package org.gradle.jvm.test;

import org.gradle.api.Incubating;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.JvmBinarySpec;
import org.gradle.platform.base.BinaryTasksCollection;
import org.gradle.platform.base.test.TestSuiteBinarySpec;

/**
 * Base type of JVM test suite binaries.
 *
 * @since 2.11
 */
@Incubating
public interface JvmTestSuiteBinarySpec extends TestSuiteBinarySpec, JvmBinarySpec {

    /**
     * Provides direct access to important build tasks of JVM test suites.
     */
    interface JvmTestSuiteTasks extends BinaryTasksCollection {
        Test getTest();
    }

    @Override
    JvmTestSuiteSpec getTestSuite();

    JvmBinarySpec getTestedBinary();

    @Override
    JvmTestSuiteTasks getTasks();
}
