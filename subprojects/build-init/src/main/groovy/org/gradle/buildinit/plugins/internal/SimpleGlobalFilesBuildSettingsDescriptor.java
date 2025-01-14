/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.buildinit.plugins.internal;

import org.gradle.internal.file.PathToFileResolver;
import org.gradle.util.GUtil;

public class SimpleGlobalFilesBuildSettingsDescriptor implements ProjectInitDescriptor {

    private final TemplateOperationFactory templateOperationBuilder;
    private final PathToFileResolver fileResolver;

    public SimpleGlobalFilesBuildSettingsDescriptor(TemplateOperationFactory templateOperationBuilder, PathToFileResolver fileResolver) {
        this.templateOperationBuilder = templateOperationBuilder;
        this.fileResolver = fileResolver;
    }

    @Override
    public void generate(BuildInitModifier modifier) {
        templateOperationBuilder.newTemplateOperation()
            .withTemplate("settings.gradle.template")
            .withTarget("settings.gradle")
            .withDocumentationBindings(GUtil.map("ref_userguide_multiproject", "multi_project_builds"))
            .withBindings(GUtil.map("rootProjectName", fileResolver.resolve(".").getName()))
            .create().generate();
    }

    @Override
    public boolean supports(BuildInitModifier modifier) {
        return false;
    }
}
