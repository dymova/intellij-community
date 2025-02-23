// Copyright 2000-2017 JetBrains s.r.o.
// Use of this source code is governed by the Apache 2.0 license that can be
// found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightControlFlowUtil;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.controlFlow.AnalysisCanceledException;
import com.intellij.psi.controlFlow.ControlFlow;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor;
import com.intellij.refactoring.changeSignature.OverriderUsageInfo;
import com.intellij.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.refactoring.typeMigration.TypeMigrationProcessor;
import com.intellij.refactoring.typeMigration.TypeMigrationRules;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MethodReturnTypeFix extends LocalQuickFixAndIntentionActionOnPsiElement implements HighPriorityAction {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.quickfix.MethodReturnBooleanFix");

  private final SmartTypePointer myReturnTypePointer;
  private final boolean myFixWholeHierarchy;
  private final String myName;
  private final String myCanonicalText;

  public MethodReturnTypeFix(@NotNull PsiMethod method, @NotNull PsiType returnType, boolean fixWholeHierarchy) {
    super(method);
    myReturnTypePointer = SmartTypePointerManager.getInstance(method.getProject()).createSmartTypePointer(returnType);
    myFixWholeHierarchy = fixWholeHierarchy;
    myName = method.getName();
    if (TypeConversionUtil.isNullType(returnType)) {
      returnType = PsiType.getJavaLangObject(method.getManager(), method.getResolveScope());
    }
    if (fixWholeHierarchy) {
      PsiType type = getHierarchyAdjustedReturnType(method, returnType);
      myCanonicalText = (type != null ? type : returnType).getCanonicalText();
    }
    else {
      myCanonicalText = returnType.getCanonicalText();
    }
  }


  @NotNull
  @Override
  public String getText() {
    return QuickFixBundle.message("fix.return.type.text", myName, myCanonicalText);
  }

  @Override
  @NotNull
  public String getFamilyName() {
    return QuickFixBundle.message("fix.return.type.family");
  }

  @Override
  public boolean isAvailable(@NotNull Project project,
                             @NotNull PsiFile file,
                             @NotNull PsiElement startElement,
                             @NotNull PsiElement endElement) {
    final PsiMethod myMethod = (PsiMethod)startElement;

    final PsiType myReturnType = myReturnTypePointer.getType();
    if (BaseIntentionAction.canModify(myMethod) &&
        myReturnType != null &&
        myReturnType.isValid()) {
      final PsiType returnType = myMethod.getReturnType();
      if (returnType != null && returnType.isValid() && !Comparing.equal(myReturnType, returnType)) {
        return PsiTypesUtil.allTypeParametersResolved(myMethod, myReturnType);
      }
    }
    return false;
  }

  @Override
  public void invoke(@NotNull Project project,
                     @NotNull PsiFile file,
                     Editor editor,
                     @NotNull PsiElement startElement,
                     @NotNull PsiElement endElement) {
    final PsiMethod myMethod = (PsiMethod)startElement;

    if (!FileModificationService.getInstance().prepareFileForWrite(myMethod.getContainingFile())) return;
    PsiType myReturnType = myReturnTypePointer.getType();
    if (myReturnType == null) return;
    boolean isNullType = TypeConversionUtil.isNullType(myReturnType);
    if (isNullType) myReturnType = PsiType.getJavaLangObject(myMethod.getManager(), myMethod.getResolveScope());
    if (myFixWholeHierarchy) {
      final PsiMethod superMethod = myMethod.findDeepestSuperMethod();
      final PsiType superReturnType = superMethod == null ? null : superMethod.getReturnType();
      if (superReturnType != null &&
          !Comparing.equal(myReturnType, superReturnType) &&
          !changeClassTypeArgument(myMethod, project, superReturnType, superMethod.getContainingClass(), editor, myReturnType)) {
        return;
      }
    }

    final List<PsiMethod> affectedMethods = changeReturnType(myMethod, myReturnType);

    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    PsiReturnStatement statementToSelect = null;
    if (!PsiType.VOID.equals(myReturnType)) {
      final ReturnStatementAdder adder = new ReturnStatementAdder(factory, myReturnType);

      for (PsiMethod affectedMethod : affectedMethods) {
        PsiReturnStatement statement = adder.addReturnForMethod(file, affectedMethod);
        if (statement != null && affectedMethod == myMethod) {
          statementToSelect = statement;
        }
      }
    }

    if (isNullType) {
      Editor editorForMethod = getEditorForMethod(myMethod, project, editor, file);
      if (editorForMethod != null) selectInEditor(myMethod.getReturnTypeElement(), editorForMethod);
      return;
    }

    if (statementToSelect != null) {
      Editor editorForMethod = getEditorForMethod(myMethod, project, editor, file);
      if (editorForMethod != null) {
        selectInEditor(statementToSelect.getReturnValue(), editorForMethod);
      }
    }
  }

  // to clearly separate data
  private static class ReturnStatementAdder {
    @NotNull private final PsiElementFactory factory;
    @NotNull private final PsiType myTargetType;

    private ReturnStatementAdder(@NotNull final PsiElementFactory factory, @NotNull final PsiType targetType) {
      this.factory = factory;
      myTargetType = targetType;
    }

    private PsiReturnStatement addReturnForMethod(final PsiFile file, final PsiMethod method) {
      final PsiModifierList modifiers = method.getModifierList();
      if (modifiers.hasModifierProperty(PsiModifier.ABSTRACT) || method.getBody() == null) {
        return null;
      }

      try {
        final ConvertReturnStatementsVisitor visitor = new ConvertReturnStatementsVisitor(factory, method, myTargetType);

        ControlFlow controlFlow;
        try {
          controlFlow = HighlightControlFlowUtil.getControlFlowNoConstantEvaluate(method.getBody());
        }
        catch (AnalysisCanceledException e) {
          return null; //must be an error
        }
        PsiReturnStatement returnStatement;
        if (ControlFlowUtil.processReturns(controlFlow, visitor)) {
          // extra return statement not needed
          // get latest modified return statement and select...
          returnStatement = visitor.getLatestReturn();
        }
        else {
          returnStatement = visitor.createReturnInLastStatement();
        }
        if (method.getContainingFile() != file) {
          UndoUtil.markPsiFileForUndo(file);
        }
        return returnStatement;
      }
      catch (IncorrectOperationException e) {
        LOG.error(e);
      }

      return null;
    }
  }

  private static Editor getEditorForMethod(PsiMethod myMethod, @NotNull final Project project, final Editor editor, final PsiFile file) {

    PsiFile containingFile = myMethod.getContainingFile();
    if (containingFile != file) {
      OpenFileDescriptor descriptor = new OpenFileDescriptor(project, containingFile.getVirtualFile());
      return FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
    }
    return editor;
  }

  private static PsiType getHierarchyAdjustedReturnType(final PsiMethod method, @NotNull PsiType returnType) {
    for (PsiMethod superMethod : method.findDeepestSuperMethods()) {
      PsiType superMethodReturnType = superMethod.getReturnType();
      if (superMethodReturnType != null && superMethodReturnType.isAssignableFrom(returnType)) {
        if (superMethodReturnType instanceof PsiClassType && returnType instanceof PsiPrimitiveType) {
          return ((PsiPrimitiveType)returnType).getBoxedType(method);
        }
        return returnType;
      }
    }
    return null;
  }
  
  @NotNull
  private List<PsiMethod> changeReturnType(final PsiMethod method, @NotNull PsiType returnType) {
    PsiMethod[] methods = new PsiMethod[] {method};
    if (myFixWholeHierarchy) {
      PsiType type = getHierarchyAdjustedReturnType(method, returnType);
      if (type != null) {
        returnType = type;
      }
      else {
        final PsiMethod[] superMethods = method.findDeepestSuperMethods();
        if (superMethods.length > 0) {
          methods = superMethods;
        }
      }
    }

    final MethodSignatureChangeVisitor methodSignatureChangeVisitor = new MethodSignatureChangeVisitor();
    for (PsiMethod targetMethod : methods) {
      methodSignatureChangeVisitor.addBase(targetMethod);
      ChangeSignatureProcessor processor = new UsagesAwareChangeSignatureProcessor(method.getProject(), targetMethod,
                                                                                   false, null,
                                                                                   myName,
                                                                                   returnType,
                                                                                   ParameterInfoImpl.fromMethod(targetMethod),
                                                                                   methodSignatureChangeVisitor);
      processor.run();
    }

    return methodSignatureChangeVisitor.getAffectedMethods();
  }

  private static class MethodSignatureChangeVisitor implements UsageVisitor {
    private final List<PsiMethod> myAffectedMethods;

    private MethodSignatureChangeVisitor() {
      myAffectedMethods = new ArrayList<>();
    }

    public void addBase(final PsiMethod baseMethod) {
      myAffectedMethods.add(baseMethod);
    }

    @Override
    public void visit(final UsageInfo usage) {
      if (usage instanceof OverriderUsageInfo) {
        myAffectedMethods.add(((OverriderUsageInfo) usage).getOverridingMethod());
      }
    }

    public List<PsiMethod> getAffectedMethods() {
      return myAffectedMethods;
    }

    @Override
    public void preprocessCovariantOverriders(final List<UsageInfo> covariantOverriderInfos) {
      for (Iterator<UsageInfo> usageInfoIterator = covariantOverriderInfos.iterator(); usageInfoIterator.hasNext();) {
        final UsageInfo info = usageInfoIterator.next();
        if (info instanceof OverriderUsageInfo) {
          final OverriderUsageInfo overrideUsage = (OverriderUsageInfo) info;
          if (myAffectedMethods.contains(overrideUsage.getOverridingMethod())) {
            usageInfoIterator.remove();
          }
        }
      }
    }
  }

  private interface UsageVisitor {
    void visit(final UsageInfo usage);
    void preprocessCovariantOverriders(final List<UsageInfo> covariantOverriderInfos);
  }

  private static class UsagesAwareChangeSignatureProcessor extends ChangeSignatureProcessor {
    private final UsageVisitor myUsageVisitor;

    private UsagesAwareChangeSignatureProcessor(final Project project, final PsiMethod method, final boolean generateDelegate,
                                                @PsiModifier.ModifierConstant final String newVisibility, final String newName, final PsiType newType,
                                                @NotNull final ParameterInfoImpl[] parameterInfo, final UsageVisitor usageVisitor) {
      super(project, method, generateDelegate, newVisibility, newName, newType, parameterInfo);
      myUsageVisitor = usageVisitor;
    }

    @Override
    protected void preprocessCovariantOverriders(final List<UsageInfo> covariantOverriderInfos) {
      myUsageVisitor.preprocessCovariantOverriders(covariantOverriderInfos);
    }

    @Override
    protected void performRefactoring(@NotNull final UsageInfo[] usages) {
      super.performRefactoring(usages);

      for (UsageInfo usage : usages) {
        myUsageVisitor.visit(usage);
      }
    }
  }

  static void selectInEditor(@Nullable PsiElement element, Editor editor) {
    LOG.assertTrue(element != null);
    TextRange range = element.getTextRange();
    int offset = range.getStartOffset();

    editor.getCaretModel().moveToOffset(offset);
    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
    editor.getSelectionModel().setSelection(range.getEndOffset(), range.getStartOffset());
  }

  private static boolean changeClassTypeArgument(PsiMethod myMethod,
                                                 Project project,
                                                 PsiType superReturnType,
                                                 PsiClass superClass,
                                                 Editor editor, PsiType returnType) {
    if (superClass == null || !superClass.hasTypeParameters()) return true;
    final PsiClass superReturnTypeClass = PsiUtil.resolveClassInType(superReturnType);
    if (superReturnTypeClass == null || !(superReturnTypeClass instanceof PsiTypeParameter || superReturnTypeClass.hasTypeParameters())) return true;

    final PsiClass derivedClass = myMethod.getContainingClass();
    if (derivedClass == null) return true;

    final PsiReferenceParameterList referenceParameterList = findTypeArgumentsList(superClass, derivedClass);
    if (referenceParameterList == null) return true;

    final PsiElement resolve = ((PsiJavaCodeReferenceElement)referenceParameterList.getParent()).resolve();
    if (!(resolve instanceof PsiClass)) return true;
    final PsiClass baseClass = (PsiClass)resolve;

    if (returnType instanceof PsiPrimitiveType) {
      returnType = ((PsiPrimitiveType)returnType).getBoxedType(derivedClass);
    }

    final PsiSubstitutor superClassSubstitutor =
      TypeConversionUtil.getSuperClassSubstitutor(superClass, baseClass, PsiSubstitutor.EMPTY);
    final PsiType superReturnTypeInBaseClassType = superClassSubstitutor.substitute(superReturnType);
    if (superReturnTypeInBaseClassType == null) return true;
    final PsiResolveHelper resolveHelper = JavaPsiFacade.getInstance(project).getResolveHelper();
    final PsiSubstitutor psiSubstitutor =
      resolveHelper.inferTypeArguments(PsiTypesUtil.filterUnusedTypeParameters(superReturnTypeInBaseClassType, baseClass.getTypeParameters()),
                                       new PsiType[]{superReturnTypeInBaseClassType},
                                       new PsiType[]{returnType},
                                       PsiUtil.getLanguageLevel(superClass));

    final TypeMigrationRules rules = new TypeMigrationRules(project);
    final PsiSubstitutor compoundSubstitutor =
      TypeConversionUtil.getSuperClassSubstitutor(superClass, derivedClass, PsiSubstitutor.EMPTY).putAll(psiSubstitutor);
    rules.setBoundScope(new LocalSearchScope(derivedClass));
    TypeMigrationProcessor.runHighlightingTypeMigration(project, editor, rules, referenceParameterList,
                                                        JavaPsiFacade.getElementFactory(project).createType(baseClass, compoundSubstitutor));

    return false;
  }

  @Nullable
  private static PsiReferenceParameterList findTypeArgumentsList(final PsiClass superClass, final PsiClass derivedClass) {
    PsiReferenceParameterList referenceParameterList = null;
    if (derivedClass instanceof PsiAnonymousClass) {
      referenceParameterList = ((PsiAnonymousClass)derivedClass).getBaseClassReference().getParameterList();
    } else {
      final PsiReferenceList implementsList = derivedClass.getImplementsList();
      if (implementsList != null) {
        referenceParameterList = extractReferenceParameterList(superClass, implementsList);
      }
      if (referenceParameterList == null) {
        final PsiReferenceList extendsList = derivedClass.getExtendsList();
        if (extendsList != null) {
          referenceParameterList = extractReferenceParameterList(superClass, extendsList);
        }
      }
    }
    return referenceParameterList;
  }

  @Nullable
  private static PsiReferenceParameterList extractReferenceParameterList(final PsiClass superClass,
                                                                         final PsiReferenceList extendsList) {
    for (PsiJavaCodeReferenceElement referenceElement : extendsList.getReferenceElements()) {
      final PsiElement element = referenceElement.resolve();
      if (element instanceof PsiClass && InheritanceUtil.isInheritorOrSelf((PsiClass)element, superClass, true)) {
        return referenceElement.getParameterList();
      }
    }
    return null;
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }
}
