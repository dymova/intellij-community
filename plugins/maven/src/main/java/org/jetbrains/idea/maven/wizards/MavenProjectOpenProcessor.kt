// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.maven.wizards

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import javax.swing.Icon

class MavenProjectOpenProcessor : ProjectOpenProcessor() {
  private val importProvider = MavenExternalSystemImportProvider()

  override fun getName(): String =
    importProvider.builder.name

  override fun getIcon(): Icon? =
    importProvider.builder.icon

  override fun canOpenProject(file: VirtualFile): Boolean =
    importProvider.canSetupProjectFrom(file)

  override fun doOpenProject(projectFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? =
    importProvider.openProject(projectFile, projectToClose, forceOpenInNewFrame)
}