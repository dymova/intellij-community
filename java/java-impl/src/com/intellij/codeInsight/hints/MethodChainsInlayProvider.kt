// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints

import com.intellij.codeInsight.hints.presentation.InsetPresentation
import com.intellij.codeInsight.hints.presentation.MenuOnClickPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.*
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.layout.*
import com.intellij.util.ui.JBUI
import com.siyeh.ig.psiutils.ExpressionUtils
import org.intellij.lang.annotations.Language
import javax.swing.JPanel
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MethodChainsInlayProvider : InlayHintsProvider<MethodChainsInlayProvider.Settings> {
  override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink) =
    object : FactoryInlayHintsCollector(editor) {
      override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink) : Boolean {
        if (file.project.service<DumbService>().isDumb) return true
        val call = element as? PsiMethodCallExpression ?: return true
        if (!isFirstCall(call, editor)) return true
        val next = call.nextSibling
        if (!(next is PsiWhiteSpace && next.textContains('\n'))) return true
        val chain = collectChain(call)
          .filter {
            val nextSibling = it.nextSibling as? PsiWhiteSpace ?: return@filter false
            nextSibling.textContains('\n')
          }
        if (chain.isEmpty()) return true
        val types = chain.mapNotNull { it.type }
        if (types.size != chain.size) return true // some type unknown

        val uniqueTypes = mutableSetOf<PsiType>()
        for (i in (0 until types.size - 1)) { // Except last to avoid builder.build() which has obvious type
          uniqueTypes.add(types[i])
        }
        if (uniqueTypes.size < settings.uniqueTypeCount) return true // to hide hints for builders, where type is obvious
        val javaFactory = JavaTypeHintsPresentationFactory(factory, 3)
        for ((index, currentCall) in chain.withIndex()) {
          val type = types[index]
          val presentation = javaFactory.typeHint(type)
          val project = file.project
          val finalPresentation = InsetPresentation(MenuOnClickPresentation(presentation, project) {
            val provider = this@MethodChainsInlayProvider
            listOf(InlayProviderDisablingAction(provider.name, file.language, project, provider.key))
          }, left = 1)
          sink.addInlineElement(currentCall.textRange.endOffset, true, finalPresentation)
        }
        return true
      }
    }

  override val key: SettingsKey<Settings>
    get() = ourKey

  override fun createConfigurable(settings: Settings) = object : ImmediateConfigurable {
    val uniqueTypeCountName = "Unique type count"

    private val uniqueTypeCount = JBIntSpinner(1, 1, 10)

    override fun createComponent(listener: ChangeListener): JPanel {
      uniqueTypeCount.value = settings.uniqueTypeCount
      uniqueTypeCount.addChangeListener {
        handleChange(listener)
      }
      val panel = panel {
        row {
          label(uniqueTypeCountName)
          uniqueTypeCount(pushX)
        }
      }
      panel.border = JBUI.Borders.empty(5)
      return panel
    }

    private fun handleChange(listener: ChangeListener) {
      settings.uniqueTypeCount = uniqueTypeCount.number
      listener.settingsChanged()
    }
  }

  override fun createSettings() = Settings()

  override val name: String
    get() = "Method chains"

  override val previewText: String?
    @Language("JAVA")
    get() = """class Main {
  void layout(A a) {
    a
     .b()
     .a()
     .b()
     .c();
  }
  interface A {
    B b();
    C c();
  }
  interface B {
    A a();
    C c();
  }
  interface C {
    B b();
    A a();
  }
}
"""


  private fun isFirstCall(call: PsiMethodCallExpression, editor: Editor): Boolean {
    val document = editor.document

    val textOffset = call.argumentList.textOffset
    if (document.textLength - 1 < textOffset) return false
    val callLine = document.getLineNumber(textOffset)

    val callForQualifier = ExpressionUtils.getCallForQualifier(call)
    if (callForQualifier == null ||
        document.getLineNumber(callForQualifier.argumentList.textOffset) == callLine) return false

    val firstQualifierCall = call.methodExpression.qualifier as? PsiMethodCallExpression
    if (firstQualifierCall != null) {
      if (document.getLineNumber(firstQualifierCall.argumentList.textOffset) != callLine) return false
      var currentQualifierCall: PsiMethodCallExpression = firstQualifierCall
      while (true) {
        val qualifier = currentQualifierCall.methodExpression.qualifier
        if (qualifier == null) return false
        if (qualifier !is PsiMethodCallExpression) return true
        if (document.getLineNumber(qualifier.argumentList.textOffset) != callLine) return false
        currentQualifierCall = qualifier
      }
    }
    return true
  }

  private fun collectChain(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
    val chain = mutableListOf(call)
    var current = call
    while (true) {
      val nextCall = ExpressionUtils.getCallForQualifier(current)
      if (nextCall == null) break
      chain.add(nextCall)
      current = nextCall
    }
    return chain
  }

  companion object {
    val ourKey: SettingsKey<Settings> = SettingsKey("chain.hints")
  }

  data class Settings(var uniqueTypeCount: Int = 2)
}