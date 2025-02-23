// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.refactoring

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.jetbrains.python.codeInsight.PyCodeInsightSettings
import com.jetbrains.python.fixtures.PyTestCase
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyElement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.pyi.PyiFile
import com.jetbrains.python.pyi.PyiUtil
import com.jetbrains.python.refactoring.inline.PyInlineFunctionHandler
import com.jetbrains.python.refactoring.inline.PyInlineFunctionProcessor
import junit.framework.TestCase

/**
 * @author Aleksei.Kniazev
 */
class PyInlineFunctionTest : PyTestCase() {

  override fun getTestDataPath(): String = super.getTestDataPath() + "/refactoring/inlineFunction"

  private fun doTest(inlineThis: Boolean = true, remove: Boolean = false) {
    val testName = getTestName(true)
    myFixture.copyDirectoryToProject(testName, "")
    myFixture.configureByFile("main.py")
    var element = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().referenceSearchFlags)
    if (element!!.containingFile is PyiFile) element = PyiUtil.getOriginalElement(element as PyElement)
    val reference = TargetElementUtil.findReference(myFixture.editor)
    TestCase.assertTrue(element is PyFunction)
    PyInlineFunctionProcessor(myFixture.project, myFixture. editor, element as PyFunction, reference, inlineThis, remove).run()
    myFixture.checkResultByFile("$testName/main.after.py")
  }

  private fun doTestError(expectedError: String, isReferenceError: Boolean = false) {
    myFixture.configureByFile("${getTestName(true)}.py")
    val element = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().referenceSearchFlags)
    try {
      if (isReferenceError) {
        val reference = TargetElementUtil.findReference(myFixture.editor)
        PyInlineFunctionProcessor(myFixture.project, myFixture. editor, element as PyFunction, reference, myInlineThis = true, removeDeclaration = false).run()
      }
      else {
        PyInlineFunctionHandler.getInstance().inlineElement(myFixture.project, myFixture.editor, element)
      }
      TestCase.fail("Expected error: $expectedError, but got none")
    }
    catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
      TestCase.assertEquals(expectedError, e.message)
    }
  }

  fun testSimple() = doTest()
  fun testNameClash() = doTest()
  fun testArgumentExtraction() = doTest()
  fun testMultipleReturns() = doTest()
  fun testImporting() = doTest()
  //fun testExistingImports() = doTest()
  fun testMethodInsideClass() = doTest()
  fun testMethodOutsideClass() = doTest()
  fun testNoReturnsAsExpressionStatement() = doTest()
  fun testNoReturnsAsCallExpression() = doTest()
  fun testInlineAll() = doTest(inlineThis = false)
  fun testRemoving() = doTest(inlineThis = false, remove = true)
  fun testDefaultValues() = doTest()
  fun testPositionalOnlyArgs() = doTest()
  fun testKeywordOnlyArgs() = doTest()
  fun testNestedCalls() = doTest(inlineThis = false, remove = true)
  fun testCallFromStaticMethod() = doTest()
  fun testCallFromClassMethod() = doTest()
  fun testComplexQualifier() = doTest()
  //fun testInlineImportedAs() = doTest(inlineThis = false)
  fun testRemoveFunctionWithStub() {
    doTest(inlineThis = false, remove = true)
    val testName = getTestName(true)
    myFixture.checkResultByFile("main.pyi", "$testName/main.after.pyi",true)
  }
  fun testGenerator() = doTestError("Cannot inline generators")
  fun testAsyncFunction()  {
    runWithLanguageLevel(LanguageLevel.PYTHON37) {
      doTestError("Cannot inline async functions")
    }
  }
  fun testConstructor() = doTestError("Cannot inline constructor calls")
  fun testBuiltin() = doTestError("Cannot inline builtin functions")
  fun testDecorator() = doTestError("Cannot inline functions with decorators")
  fun testRecursive() = doTestError("Cannot inline functions that reference themselves")
  fun testStar() = doTestError("Cannot inline functions with * arguments")
  fun testOverridden() = doTestError("Cannot inline overridden functions")
  fun testNested() = doTestError("Cannot inline functions with another function declaration")
  fun testInterruptedFlow() = doTestError("Cannot inline functions that interrupt control flow")
  fun testUsedAsDecorator() = doTestError("Function foo is used as a decorator and cannot be inlined. Function definition will not be removed", isReferenceError = true)
  fun testUsedAsReference() = doTestError("Function foo is used as a reference and cannot be inlined. Function definition will not be removed", isReferenceError = true)
  fun testUsesArgumentUnpacking() = doTestError("Function foo uses argument unpacking and cannot be inlined. Function definition will not be removed", isReferenceError = true)
}