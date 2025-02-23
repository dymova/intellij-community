// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.application.options.editor

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationBundle.message
import com.intellij.ui.layout.*

// @formatter:off
const val ID = "editor.preferences.tabs"

internal val ui = UISettings.instance.state

internal val showDirectoryForNonUniqueFilenames  = CheckboxDescriptor(message("checkbox.show.directory.for.non.unique.files"), ui::showDirectoryForNonUniqueFilenames)
internal val markModifiedTabsWithAsterisk        = CheckboxDescriptor(message("checkbox.mark.modified.tabs.with.asterisk"), ui::markModifiedTabsWithAsterisk)
internal val showTabsTooltips                    = CheckboxDescriptor(message("checkbox.show.tabs.tooltips"), ui::showTabsTooltips)
internal val showFileException                   = CheckboxDescriptor(message("checkbox.show.file.extension.in.editor.tabs"), PropertyBinding({ !ui.hideKnownExtensionInTabs }, { ui.hideKnownExtensionInTabs = !it }))
internal val hideTabsIfNeeded                    = CheckboxDescriptor(message("checkbox.editor.scroll.if.need"), ui::hideTabsIfNeeded)
internal val sortTabsAlphabetically              = CheckboxDescriptor(message("checkbox.sort.tabs.alphabetically"), ui::sortTabsAlphabetically)
internal val openTabsAtTheEnd                    = CheckboxDescriptor(message("checkbox.open.new.tabs.at.the.end"), ui::openTabsAtTheEnd)
internal val reuseNotModifiedTabs                = CheckboxDescriptor(message("checkbox.smart.tab.reuse"), ui::reuseNotModifiedTabs, message("checkbox.smart.tab.reuse.inline.help"))
internal val scrollTabLayoutInEditor             = CheckboxDescriptor(message("checkbox.editor.tabs.in.single.row"), ui::scrollTabLayoutInEditor)