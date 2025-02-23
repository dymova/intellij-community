// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl.analysis;

import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInsight.ContainerProvider;
import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.codeInsight.JavaModuleSystemEx;
import com.intellij.codeInsight.JavaModuleSystemEx.ErrorWithFixes;
import com.intellij.codeInsight.daemon.JavaErrorMessages;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.quickfix.*;
import com.intellij.codeInsight.highlighting.HighlightUsagesDescriptionLocation;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.codeInsight.quickfix.UnresolvedReferenceQuickFixProvider;
import com.intellij.codeInspection.LocalQuickFixOnPsiElementAsIntentionAdapter;
import com.intellij.lang.findUsages.LanguageFindUsages;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.EffectiveLanguageLevelUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.roots.impl.FilePropertyPusher;
import com.intellij.openapi.roots.impl.JavaLanguageLevelPusher;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import com.intellij.psi.impl.source.resolve.graphInference.InferenceSession;
import com.intellij.psi.impl.source.resolve.graphInference.PsiPolyExpressionUtil;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.scope.processor.VariablesNotProcessor;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.templateLanguages.OuterLanguageElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.*;
import com.intellij.refactoring.util.RefactoringChangeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.JavaPsiConstructorUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.hash.HashSet;
import com.intellij.util.ui.UIUtil;
import com.intellij.xml.util.XmlStringUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import gnu.trove.THashMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HighlightUtil extends HighlightUtilBase {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.analysis.HighlightUtil");

  private static final QuickFixFactory QUICK_FIX_FACTORY = QuickFixFactory.getInstance();

  private static final Map<String, Set<String>> ourInterfaceIncompatibleModifiers = new THashMap<>(7);
  private static final Map<String, Set<String>> ourMethodIncompatibleModifiers = new THashMap<>(11);
  private static final Map<String, Set<String>> ourFieldIncompatibleModifiers = new THashMap<>(8);
  private static final Map<String, Set<String>> ourClassIncompatibleModifiers = new THashMap<>(8);
  private static final Map<String, Set<String>> ourClassInitializerIncompatibleModifiers = new THashMap<>(1);
  private static final Map<String, Set<String>> ourModuleIncompatibleModifiers = new THashMap<>(1);
  private static final Map<String, Set<String>> ourRequiresIncompatibleModifiers = new THashMap<>(2);

  private static final Set<String> ourConstructorNotAllowedModifiers =
    ContainerUtil.newTroveSet(PsiModifier.ABSTRACT, PsiModifier.STATIC, PsiModifier.NATIVE, PsiModifier.FINAL, PsiModifier.STRICTFP, PsiModifier.SYNCHRONIZED);

  private static final String SERIAL_PERSISTENT_FIELDS_FIELD_NAME = "serialPersistentFields";

  static {
    ourClassIncompatibleModifiers.put(PsiModifier.ABSTRACT, ContainerUtil.newTroveSet(PsiModifier.FINAL));
    ourClassIncompatibleModifiers.put(PsiModifier.FINAL, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT));
    ourClassIncompatibleModifiers.put(PsiModifier.PACKAGE_LOCAL, ContainerUtil.newTroveSet(PsiModifier.PRIVATE, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourClassIncompatibleModifiers.put(PsiModifier.PRIVATE, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourClassIncompatibleModifiers.put(PsiModifier.PUBLIC, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PRIVATE, PsiModifier.PROTECTED));
    ourClassIncompatibleModifiers.put(PsiModifier.PROTECTED, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PRIVATE));
    ourClassIncompatibleModifiers.put(PsiModifier.STRICTFP, Collections.emptySet());
    ourClassIncompatibleModifiers.put(PsiModifier.STATIC, Collections.emptySet());

    ourInterfaceIncompatibleModifiers.put(PsiModifier.ABSTRACT, Collections.emptySet());
    ourInterfaceIncompatibleModifiers.put(PsiModifier.PACKAGE_LOCAL, ContainerUtil.newTroveSet(PsiModifier.PRIVATE, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourInterfaceIncompatibleModifiers.put(PsiModifier.PRIVATE, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourInterfaceIncompatibleModifiers.put(PsiModifier.PUBLIC, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PRIVATE, PsiModifier.PROTECTED));
    ourInterfaceIncompatibleModifiers.put(PsiModifier.PROTECTED, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PRIVATE));
    ourInterfaceIncompatibleModifiers.put(PsiModifier.STRICTFP, Collections.emptySet());
    ourInterfaceIncompatibleModifiers.put(PsiModifier.STATIC, Collections.emptySet());

    ourMethodIncompatibleModifiers.put(PsiModifier.ABSTRACT, ContainerUtil.newTroveSet(
      PsiModifier.NATIVE, PsiModifier.STATIC, PsiModifier.FINAL, PsiModifier.PRIVATE, PsiModifier.STRICTFP, PsiModifier.SYNCHRONIZED, PsiModifier.DEFAULT));
    ourMethodIncompatibleModifiers.put(PsiModifier.NATIVE, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT, PsiModifier.STRICTFP));
    ourMethodIncompatibleModifiers.put(PsiModifier.PACKAGE_LOCAL, ContainerUtil.newTroveSet(PsiModifier.PRIVATE, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourMethodIncompatibleModifiers.put(PsiModifier.PRIVATE, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourMethodIncompatibleModifiers.put(PsiModifier.PUBLIC, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PRIVATE, PsiModifier.PROTECTED));
    ourMethodIncompatibleModifiers.put(PsiModifier.PROTECTED, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PRIVATE));
    ourMethodIncompatibleModifiers.put(PsiModifier.STATIC, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT, PsiModifier.DEFAULT, PsiModifier.FINAL));
    ourMethodIncompatibleModifiers.put(PsiModifier.DEFAULT, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT, PsiModifier.STATIC, PsiModifier.FINAL, PsiModifier.PRIVATE));
    ourMethodIncompatibleModifiers.put(PsiModifier.SYNCHRONIZED, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT));
    ourMethodIncompatibleModifiers.put(PsiModifier.STRICTFP, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT));
    ourMethodIncompatibleModifiers.put(PsiModifier.FINAL, ContainerUtil.newTroveSet(PsiModifier.ABSTRACT));

    ourFieldIncompatibleModifiers.put(PsiModifier.FINAL, ContainerUtil.newTroveSet(PsiModifier.VOLATILE));
    ourFieldIncompatibleModifiers.put(PsiModifier.PACKAGE_LOCAL, ContainerUtil.newTroveSet(PsiModifier.PRIVATE, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourFieldIncompatibleModifiers.put(PsiModifier.PRIVATE, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PROTECTED));
    ourFieldIncompatibleModifiers.put(PsiModifier.PUBLIC, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PRIVATE, PsiModifier.PROTECTED));
    ourFieldIncompatibleModifiers.put(PsiModifier.PROTECTED, ContainerUtil.newTroveSet(PsiModifier.PACKAGE_LOCAL, PsiModifier.PUBLIC, PsiModifier.PRIVATE));
    ourFieldIncompatibleModifiers.put(PsiModifier.STATIC, Collections.emptySet());
    ourFieldIncompatibleModifiers.put(PsiModifier.TRANSIENT, Collections.emptySet());
    ourFieldIncompatibleModifiers.put(PsiModifier.VOLATILE, ContainerUtil.newTroveSet(PsiModifier.FINAL));

    ourClassInitializerIncompatibleModifiers.put(PsiModifier.STATIC, Collections.emptySet());

    ourModuleIncompatibleModifiers.put(PsiModifier.OPEN, Collections.emptySet());

    ourRequiresIncompatibleModifiers.put(PsiModifier.STATIC, Collections.emptySet());
    ourRequiresIncompatibleModifiers.put(PsiModifier.TRANSITIVE, Collections.emptySet());
  }

  private HighlightUtil() { }

  @Nullable
  private static String getIncompatibleModifier(@NotNull String modifier,
                                                @NotNull PsiModifierList modifierList,
                                                @NotNull Map<String, Set<String>> incompatibleModifiersHash) {
    // modifier is always incompatible with itself
    PsiElement[] modifiers = modifierList.getChildren();
    int modifierCount = 0;
    for (PsiElement otherModifier : modifiers) {
      if (Comparing.equal(modifier, otherModifier.getText(), true)) modifierCount++;
    }
    if (modifierCount > 1) {
      return modifier;
    }

    Set<String> incompatibles = incompatibleModifiersHash.get(modifier);
    if (incompatibles == null) return null;
    final PsiElement parent = modifierList.getParent();
    final boolean level8OrHigher = PsiUtil.isLanguageLevel8OrHigher(modifierList);
    final boolean level9OrHigher = PsiUtil.isLanguageLevel9OrHigher(modifierList);
    for (@PsiModifier.ModifierConstant String incompatible : incompatibles) {
      if (level8OrHigher) {
        if (modifier.equals(PsiModifier.STATIC) && incompatible.equals(PsiModifier.ABSTRACT)){
          continue;
        }
      }
      if (parent instanceof PsiMethod) {
        if (level9OrHigher && modifier.equals(PsiModifier.PRIVATE) && incompatible.equals(PsiModifier.PUBLIC)) {
          continue;
        }

        if (modifier.equals(PsiModifier.STATIC) && incompatible.equals(PsiModifier.FINAL)) {
          final PsiClass containingClass = ((PsiMethod)parent).getContainingClass();
          if (containingClass == null || !containingClass.isInterface()) {
            continue;
          }
        }
      }
      if (modifierList.hasModifierProperty(incompatible)) {
        return incompatible;
      }
      if (PsiModifier.ABSTRACT.equals(incompatible) && modifierList.hasExplicitModifier(incompatible)) {
        return incompatible;
      }
    }

    return null;
  }


  @Nullable
  static HighlightInfo checkInstanceOfApplicable(@NotNull PsiInstanceOfExpression expression) {
    PsiExpression operand = expression.getOperand();
    PsiTypeElement typeElement = expression.getCheckType();
    if (typeElement == null) return null;
    PsiType checkType = typeElement.getType();
    PsiType operandType = operand.getType();
    if (operandType == null) return null;
    if (TypeConversionUtil.isPrimitiveAndNotNull(operandType)
        || TypeConversionUtil.isPrimitiveAndNotNull(checkType)
        || !TypeConversionUtil.areTypesConvertible(operandType, checkType)) {
      String message = JavaErrorMessages.message("inconvertible.type.cast", JavaHighlightUtil.formatType(operandType), JavaHighlightUtil
        .formatType(checkType));
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
    }
    return null;
  }


  /**
   * 15.16 Cast Expressions
   * ( ReferenceType {AdditionalBound} ) expression, where AdditionalBound: & InterfaceType then all must be true
   *  - ReferenceType must denote a class or interface type.
   *  - The erasures of all the listed types must be pairwise different.
   *  - No two listed types may be subtypes of different parameterization of the same generic interface.
   */
  @Nullable
  static HighlightInfo checkIntersectionInTypeCast(@NotNull PsiTypeCastExpression expression,
                                                   @NotNull LanguageLevel languageLevel,
                                                   @NotNull PsiFile file) {
    PsiTypeElement castTypeElement = expression.getCastType();
    if (castTypeElement != null && isIntersection(castTypeElement, castTypeElement.getType())) {
      HighlightInfo info = checkFeature(expression, Feature.INTERSECTION_CASTS, languageLevel, file);
      if (info != null) return info;

      final PsiTypeElement[] conjuncts = PsiTreeUtil.getChildrenOfType(castTypeElement, PsiTypeElement.class);
      if (conjuncts != null) {
        final Set<PsiType> erasures = new HashSet<>(conjuncts.length);
        erasures.add(TypeConversionUtil.erasure(conjuncts[0].getType()));
        final List<PsiTypeElement> conjList = new ArrayList<>(Arrays.asList(conjuncts));
        for (int i = 1; i < conjuncts.length; i++) {
          final PsiTypeElement conjunct = conjuncts[i];
          final PsiType conjType = conjunct.getType();
          if (conjType instanceof PsiClassType) {
            final PsiClass aClass = ((PsiClassType)conjType).resolve();
            if (aClass != null && !aClass.isInterface()) {
              final HighlightInfo errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                .range(conjunct)
                .descriptionAndTooltip(JavaErrorMessages.message("interface.expected")).create();
              QuickFixAction.registerQuickFixAction(errorResult, new FlipIntersectionSidesFix(aClass.getName(), conjList, conjunct, castTypeElement), null);
              return errorResult;
            }
          }
          else {
            return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
              .range(conjunct)
              .descriptionAndTooltip("Unexpected type: class is expected").create();
          }
          if (!erasures.add(TypeConversionUtil.erasure(conjType))) {
            final HighlightInfo highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
              .range(conjunct)
              .descriptionAndTooltip("Repeated interface").create();
            QuickFixAction.registerQuickFixAction(highlightInfo, new DeleteRepeatedInterfaceFix(conjunct, conjList), null);
            return highlightInfo;
          }
        }

        final List<PsiType> typeList = ContainerUtil.map(conjList, PsiTypeElement::getType);
        final Ref<String> differentArgumentsMessage = new Ref<>();
        final PsiClass sameGenericParameterization =
          InferenceSession.findParameterizationOfTheSameGenericClass(typeList, pair -> {
            if (!TypesDistinctProver.provablyDistinct(pair.first, pair.second)) {
              return true;
            }
            differentArgumentsMessage.set(pair.first.getPresentableText() + " and " + pair.second.getPresentableText());
            return false;
          });
        if (sameGenericParameterization != null) {
          final String message = formatClass(sameGenericParameterization) + " cannot be inherited with different arguments: " +
                                 differentArgumentsMessage.get();
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(expression)
            .descriptionAndTooltip(message).create();
        }
      }
    }

    return null;
  }

  private static boolean isIntersection(@NotNull PsiTypeElement castTypeElement, @NotNull PsiType castType) {
    if (castType instanceof PsiIntersectionType) return true;
    return castType instanceof PsiClassType && PsiTreeUtil.getChildrenOfType(castTypeElement, PsiTypeElement.class) != null;
  }

  @Nullable
  static HighlightInfo checkInconvertibleTypeCast(@NotNull PsiTypeCastExpression expression) {
    final PsiTypeElement castTypeElement = expression.getCastType();
    if (castTypeElement == null) return null;
    PsiType castType = castTypeElement.getType();

    PsiExpression operand = expression.getOperand();
    if (operand == null) return null;
    PsiType operandType = operand.getType();

    if (operandType != null &&
        !TypeConversionUtil.areTypesConvertible(operandType, castType, PsiUtil.getLanguageLevel(expression)) &&
        !RedundantCastUtil.isInPolymorphicCall(expression)) {
      String message = JavaErrorMessages.message("inconvertible.type.cast", JavaHighlightUtil.formatType(operandType), JavaHighlightUtil
        .formatType(castType));
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
    }


    return null;
  }

  @Nullable
  static HighlightInfo checkVariableExpected(@NotNull PsiExpression expression) {
    PsiExpression lValue;
    if (expression instanceof PsiAssignmentExpression) {
      PsiAssignmentExpression assignment = (PsiAssignmentExpression)expression;
      lValue = assignment.getLExpression();
    }
    else if (PsiUtil.isIncrementDecrementOperation(expression)) {
      lValue = ((PsiUnaryExpression)expression).getOperand();
    }
    else {
      lValue = null;
    }
    HighlightInfo errorResult = null;
    if (lValue != null && !TypeConversionUtil.isLValue(lValue)) {
      String description = JavaErrorMessages.message("variable.expected");
      errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(lValue).descriptionAndTooltip(description).create();
    }

    return errorResult;
  }


  @Nullable
  static HighlightInfo checkAssignmentOperatorApplicable(@NotNull PsiAssignmentExpression assignment) {
    PsiJavaToken operationSign = assignment.getOperationSign();
    IElementType eqOpSign = operationSign.getTokenType();
    IElementType opSign = TypeConversionUtil.convertEQtoOperation(eqOpSign);
    if (opSign == null) return null;
    final PsiType lType = assignment.getLExpression().getType();
    final PsiExpression rExpression = assignment.getRExpression();
    if (rExpression == null) return null;
    final PsiType rType = rExpression.getType();
    HighlightInfo errorResult = null;
    if (!TypeConversionUtil.isBinaryOperatorApplicable(opSign, lType, rType, true)) {
      String operatorText = operationSign.getText().substring(0, operationSign.getText().length() - 1);
      String message = JavaErrorMessages.message("binary.operator.not.applicable", operatorText,
                                                 JavaHighlightUtil.formatType(lType),
                                                 JavaHighlightUtil.formatType(rType));

      errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(assignment).descriptionAndTooltip(message).create();
    }
    return errorResult;
  }


  @Nullable
  static HighlightInfo checkAssignmentCompatibleTypes(@NotNull PsiAssignmentExpression assignment) {
    PsiExpression lExpr = assignment.getLExpression();
    PsiExpression rExpr = assignment.getRExpression();
    if (rExpr == null) return null;
    PsiType lType = lExpr.getType();
    PsiType rType = rExpr.getType();
    if (rType == null) return null;

    final IElementType sign = assignment.getOperationTokenType();
    HighlightInfo highlightInfo;
    if (JavaTokenType.EQ.equals(sign)) {
      highlightInfo = checkAssignability(lType, rType, rExpr, assignment);
    }
    else {
      // 15.26.2. Compound Assignment Operators
      final IElementType opSign = TypeConversionUtil.convertEQtoOperation(sign);
      final PsiType type = TypeConversionUtil.calcTypeForBinaryExpression(lType, rType, opSign, true);
      if (type == null || lType == null || TypeConversionUtil.areTypesConvertible(type, lType)) {
        return null;
      }
      highlightInfo = createIncompatibleTypeHighlightInfo(lType, type, assignment.getTextRange(), 0);
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createChangeToAppendFix(sign, lType, assignment));
    }
    if (highlightInfo == null) {
      return null;
    }
    HighlightFixUtil.registerChangeVariableTypeFixes(lExpr, rType, rExpr, highlightInfo);
    if (lType != null) {
      HighlightFixUtil.registerChangeVariableTypeFixes(rExpr, lType, lExpr, highlightInfo);
    }
    return highlightInfo;
  }

  private static boolean isCastIntentionApplicable(@NotNull PsiExpression expression, @Nullable PsiType toType) {
    while (expression instanceof PsiTypeCastExpression || expression instanceof PsiParenthesizedExpression) {
      if (expression instanceof PsiTypeCastExpression) {
        expression = ((PsiTypeCastExpression)expression).getOperand();
      }
      if (expression instanceof PsiParenthesizedExpression) {
        expression = ((PsiParenthesizedExpression)expression).getExpression();
      }
    }
    if (expression == null) return false;
    PsiType rType = expression.getType();
    PsiType castType = GenericsUtil.getVariableTypeByExpressionType(toType);
    return rType != null && toType != null && TypeConversionUtil.areTypesConvertible(rType, toType) && toType.isAssignableFrom(castType);
  }


  @Nullable
  static HighlightInfo checkVariableInitializerType(@NotNull PsiVariable variable) {
    PsiExpression initializer = variable.getInitializer();
    // array initializer checked in checkArrayInitializerApplicable
    if (initializer == null || initializer instanceof PsiArrayInitializerExpression) return null;
    PsiType lType = variable.getType();
    PsiType rType = initializer.getType();
    PsiTypeElement typeElement = variable.getTypeElement();
    int start = typeElement != null ? typeElement.getTextRange().getStartOffset() : variable.getTextRange().getStartOffset();
    int end = variable.getTextRange().getEndOffset();
    HighlightInfo highlightInfo = checkAssignability(lType, rType, initializer, new TextRange(start, end), 0);
    if (highlightInfo != null) {
      HighlightFixUtil.registerChangeVariableTypeFixes(variable, rType, variable.getInitializer(), highlightInfo);
      HighlightFixUtil.registerChangeVariableTypeFixes(initializer, lType, null, highlightInfo);
    }
    return highlightInfo;
  }

  static HighlightInfo checkLegalVarReference(@NotNull PsiJavaCodeReferenceElement ref, @NotNull PsiClass resolved) {
    if (PsiKeyword.VAR.equals(resolved.getName()) && PsiUtil.getLanguageLevel(ref).isAtLeast(LanguageLevel.JDK_10)) {
      String message = JavaErrorMessages.message("lvti.illegal");
      PsiElement range = ObjectUtils.notNull(ref.getReferenceNameElement(), ref);
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(range).create();
    }
    return null;
  }

  static HighlightInfo checkVarTypeApplicability(@NotNull PsiVariable variable) {
    PsiTypeElement typeElement = variable.getTypeElement();
    if (typeElement != null && typeElement.isInferredType()) {
      if (variable instanceof PsiLocalVariable) {
        PsiExpression initializer = variable.getInitializer();
        if (initializer == null) {
          String message = JavaErrorMessages.message("lvti.no.initializer");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
        }
        if (initializer instanceof PsiFunctionalExpression) {
          boolean lambda = initializer instanceof PsiLambdaExpression;
          String message = JavaErrorMessages.message(lambda ? "lvti.lambda" : "lvti.method.ref");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
        }

        PsiElement parent = variable.getParent();
        if (parent instanceof PsiDeclarationStatement && ((PsiDeclarationStatement)parent).getDeclaredElements().length > 1) {
          String message = JavaErrorMessages.message("lvti.compound");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(variable).create();
        }

        if (isArray(variable)) {
          String message = JavaErrorMessages.message("lvti.array");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
        }

        PsiType lType = variable.getType();
        if (PsiType.NULL.equals(lType)) {
          String message = JavaErrorMessages.message("lvti.null");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
        }
        if (PsiType.VOID.equals(lType)) {
          String message = JavaErrorMessages.message("lvti.void");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
        }
      }
      else if (variable instanceof PsiParameter && variable.getParent() instanceof PsiParameterList && isArray(variable)) {
        String message = JavaErrorMessages.message("lvti.array");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).descriptionAndTooltip(message).range(typeElement).create();
      }
    }

    return null;
  }

  private static boolean isArray(PsiVariable variable) {
    // Java-style 'var' arrays are prohibited by the parser; for C-style ones, looking for a bracket is enough
    return Stream.of(variable.getChildren()).anyMatch(e -> PsiUtil.isJavaToken(e, JavaTokenType.LBRACKET));
  }

  @Nullable
  static HighlightInfo checkAssignability(@Nullable PsiType lType,
                                          @Nullable PsiType rType,
                                          @Nullable PsiExpression expression,
                                          @NotNull PsiElement elementToHighlight) {
    TextRange textRange = elementToHighlight.getTextRange();
    return checkAssignability(lType, rType, expression, textRange, 0);
  }

  @Nullable
  private static HighlightInfo checkAssignability(@Nullable PsiType lType,
                                                  @Nullable PsiType rType,
                                                  @Nullable PsiExpression expression,
                                                  @NotNull TextRange textRange,
                                                  int navigationShift) {
    if (lType == rType) return null;
    if (expression == null) {
      if (rType == null || lType == null || TypeConversionUtil.isAssignable(lType, rType)) return null;
    }
    else if (TypeConversionUtil.areTypesAssignmentCompatible(lType, expression)) {
      return null;
    }
    if (rType == null) {
      rType = expression.getType();
    }
    HighlightInfo highlightInfo = createIncompatibleTypeHighlightInfo(lType, rType, textRange, navigationShift);
    if (rType != null && expression != null && isCastIntentionApplicable(expression, lType)) {
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createAddTypeCastFix(lType, expression));
    }
    if (expression != null) {
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createWrapWithOptionalFix(lType, expression));
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createWrapExpressionFix(lType, expression));
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createWrapWithAdapterFix(lType, expression));
      AddTypeArgumentsConditionalFix.register(highlightInfo, expression, lType);
      HighlightFixUtil.registerCollectionToArrayFixAction(highlightInfo, rType, lType, expression);
      HighlightFixUtil.registerChangeReturnTypeFix(highlightInfo, expression, lType);
    }
    ChangeNewOperatorTypeFix.register(highlightInfo, expression, lType);
    return highlightInfo;
  }

  @Nullable
  static HighlightInfo checkReturnFromSwitchExpr(@NotNull PsiStatement statement) {
    if (PsiImplUtil.findEnclosingSwitchExpression(statement) != null) {
      String message = JavaErrorMessages.message("return.outside.switch.expr");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(message).create();
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkReturnStatementType(@NotNull PsiReturnStatement statement) {
    PsiMethod method = null;
    PsiLambdaExpression lambda = null;
    PsiElement parent = statement.getParent();
    while (true) {
      if (parent instanceof PsiFile) break;
      if (parent instanceof PsiClassInitializer) break;
      if (parent instanceof PsiLambdaExpression){
        lambda = (PsiLambdaExpression)parent;
        break;
      }
      if (parent instanceof PsiMethod) {
        method = (PsiMethod)parent;
        break;
      }
      parent = parent.getParent();
    }
    if (parent instanceof PsiCodeFragment) {
      return null;
    }
    String description;
    HighlightInfo errorResult = null;
    if (method == null && lambda != null) {
      //todo check return statements type inside lambda
    }
    else if (method == null && !(parent instanceof ServerPageFile)) {
      description = JavaErrorMessages.message("return.outside.method");
      errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
    }
    else {
      PsiType returnType = method != null ? method.getReturnType() : null/*JSP page returns void*/;
      boolean isMethodVoid = returnType == null || PsiType.VOID.equals(returnType);
      final PsiExpression returnValue = statement.getReturnValue();
      if (returnValue != null) {
        PsiType valueType = RefactoringChangeUtil.getTypeByExpression(returnValue);
        if (isMethodVoid) {
          description = JavaErrorMessages.message("return.from.void.method");
          errorResult =
            HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
          registerReturnValueFixes(errorResult, method, statement, valueType);
        }
        else {
          TextRange textRange = statement.getTextRange();
          errorResult = checkAssignability(returnType, valueType, returnValue, textRange, returnValue.getStartOffsetInParent());
          if (errorResult != null && valueType != null) {
            if (!PsiType.VOID.equals(valueType)) {
              QuickFixAction.registerQuickFixAction(errorResult, QUICK_FIX_FACTORY.createMethodReturnFix(method, valueType, true));
            }
            HighlightFixUtil.registerChangeParameterClassFix(returnType, valueType, errorResult);
            if (returnType instanceof PsiArrayType) {
              final PsiType erasedValueType = TypeConversionUtil.erasure(valueType);
              if (erasedValueType != null && TypeConversionUtil.isAssignable(((PsiArrayType)returnType).getComponentType(), erasedValueType)) {
                QuickFixAction.registerQuickFixAction(errorResult, QUICK_FIX_FACTORY.createSurroundWithArrayFix(null, returnValue));
              }
            }
            HighlightFixUtil.registerCollectionToArrayFixAction(errorResult, valueType, returnType, returnValue);
          }
        }
      }
      else if (!isMethodVoid) {
        description = JavaErrorMessages.message("missing.return.value");
        errorResult = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description)
          .navigationShift(PsiKeyword.RETURN.length()).create();
        QuickFixAction.registerQuickFixAction(errorResult, QUICK_FIX_FACTORY.createMethodReturnFix(method, PsiType.VOID, true));
      }
    }
    return errorResult;
  }

  private static boolean isValidConstructor(@NotNull PsiMethod method) {
    PsiClass aClass = method.getContainingClass();
    if (aClass == null) return false;
    return method.getName().equals(aClass.getName());
  }

  private static void registerReturnValueFixes(@Nullable HighlightInfo info,
                                               @Nullable PsiMethod method,
                                               @NotNull PsiReturnStatement returnStatement,
                                               @Nullable PsiType returnValueType) {
    boolean canCreateFixes = method != null && returnValueType != null && (!method.isConstructor() || isValidConstructor(method));
    if (!canCreateFixes) return;

    PsiCodeBlock codeBlock = method.getBody();
    if (codeBlock == null) return;
    PsiExpression returnValue = returnStatement.getReturnValue();
    if (returnValue != null) {
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createDeleteReturnFix(method, returnStatement, returnValue));
    }
    if (method.isConstructor()) return;
    QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createMethodReturnFix(method, returnValueType, true));
  }

  @NotNull
  public static String getUnhandledExceptionsDescriptor(@NotNull final Collection<? extends PsiClassType> unhandled) {
    return getUnhandledExceptionsDescriptor(unhandled, null);
  }

  @NotNull
  private static String getUnhandledExceptionsDescriptor(@NotNull final Collection<? extends PsiClassType> unhandled, @Nullable final String source) {
    final String exceptions = formatTypes(unhandled);
    return source == null
           ? JavaErrorMessages.message("unhandled.exceptions", exceptions, unhandled.size())
           : JavaErrorMessages.message("unhandled.close.exceptions", exceptions, unhandled.size(), source);
  }

  @NotNull
  private static String formatTypes(@NotNull Collection<? extends PsiClassType> unhandled) {
    return StringUtil.join(unhandled, JavaHighlightUtil::formatType, ", ");
  }

  @Nullable
  static HighlightInfo checkVariableAlreadyDefined(@NotNull PsiVariable variable) {
    if (variable instanceof ExternallyDefinedPsiElement) return null;
    PsiVariable oldVariable = null;
    PsiElement declarationScope = null;
    if (variable instanceof PsiLocalVariable ||
        variable instanceof PsiParameter &&
        ((declarationScope = ((PsiParameter)variable).getDeclarationScope()) instanceof PsiCatchSection ||
         declarationScope instanceof PsiForeachStatement ||
         declarationScope instanceof PsiLambdaExpression)) {
      PsiElement scope = PsiTreeUtil.getParentOfType(variable, PsiFile.class, PsiMethod.class, PsiClassInitializer.class, PsiResourceList.class);
      VariablesNotProcessor proc = new VariablesNotProcessor(variable, false) {
        @Override
        protected boolean check(final PsiVariable var, final ResolveState state) {
          return (var instanceof PsiLocalVariable || var instanceof PsiParameter) && super.check(var, state);
        }
      };
      PsiIdentifier identifier = variable.getNameIdentifier();
      assert identifier != null : variable;
      PsiScopesUtil.treeWalkUp(proc, identifier, scope);
      if (scope instanceof PsiResourceList && proc.size() == 0) {
        scope = PsiTreeUtil.getParentOfType(variable, PsiFile.class, PsiMethod.class, PsiClassInitializer.class);
        PsiScopesUtil.treeWalkUp(proc, identifier, scope);
      }
      if (proc.size() > 0) {
        oldVariable = proc.getResult(0);
      }
      else if (declarationScope instanceof PsiLambdaExpression) {
        oldVariable = checkSameNames(variable);
      }
    }
    else if (variable instanceof PsiField) {
      PsiField field = (PsiField)variable;
      PsiClass aClass = field.getContainingClass();
      if (aClass == null) return null;
      PsiField fieldByName = aClass.findFieldByName(variable.getName(), false);
      if (fieldByName != null && fieldByName != field) {
        oldVariable = fieldByName;
      }
    }
    else {
      oldVariable = checkSameNames(variable);
    }

    if (oldVariable != null) {
      String description = JavaErrorMessages.message("variable.already.defined", variable.getName());
      PsiIdentifier identifier = variable.getNameIdentifier();
      assert identifier != null : variable;
      VirtualFile vFile = PsiUtilCore.getVirtualFile(identifier);
      HighlightInfo.Builder builder = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(identifier);
      if (vFile != null) {
        final String path = FileUtil.toSystemIndependentName(vFile.getPath());
        String linkText = "<a href=\"#navigation/" + path + ":" + oldVariable.getTextOffset() + "\">" + variable.getName() + "</a>";
        builder = builder.description(description)
          .escapedToolTip("<html>" + JavaErrorMessages.message("variable.already.defined", linkText) + "</html>");
      }
      else {
        builder = builder.descriptionAndTooltip(description);
      }
      HighlightInfo highlightInfo = builder.create();
      if (variable instanceof PsiLocalVariable) {
        QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createReuseVariableDeclarationFix((PsiLocalVariable)variable));
      }
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createNavigateToAlreadyDeclaredVariableFix(oldVariable));
      return highlightInfo;
    }
    return null;
  }

  private static PsiVariable checkSameNames(@NotNull PsiVariable variable) {
    PsiElement scope = variable.getParent();
    PsiElement[] children = scope.getChildren();
    for (PsiElement child : children) {
      if (child instanceof PsiVariable) {
        if (child.equals(variable)) continue;
        if (Objects.equals(variable.getName(), ((PsiVariable)child).getName())) {
          return (PsiVariable)child;
        }
      }
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkUnderscore(@NotNull PsiIdentifier identifier, @NotNull LanguageLevel languageLevel) {
    if ("_".equals(identifier.getText())) {
      if (languageLevel.isAtLeast(LanguageLevel.JDK_1_9)) {
        String text = JavaErrorMessages.message("underscore.identifier.error");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(identifier).descriptionAndTooltip(text).create();
      }
      else if (languageLevel.isAtLeast(LanguageLevel.JDK_1_8)) {
        PsiElement parent = identifier.getParent();
        if (parent instanceof PsiParameter && ((PsiParameter)parent).getDeclarationScope() instanceof PsiLambdaExpression) {
          String text = JavaErrorMessages.message("underscore.lambda.identifier");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(identifier).descriptionAndTooltip(text).create();
        }
      }
    }

    return null;
  }

  @NotNull
  public static String formatClass(@NotNull PsiClass aClass) {
    return formatClass(aClass, true);
  }

  @NotNull
  public static String formatClass(@NotNull PsiClass aClass, boolean fqn) {
    return PsiFormatUtil.formatClass(aClass, PsiFormatUtilBase.SHOW_NAME |
                                             PsiFormatUtilBase.SHOW_ANONYMOUS_CLASS_VERBOSE | (fqn ? PsiFormatUtilBase.SHOW_FQ_NAME : 0));
  }

  @NotNull
  private static String formatField(@NotNull PsiField field) {
    return PsiFormatUtil.formatVariable(field, PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME, PsiSubstitutor.EMPTY);
  }

  @Nullable
  static HighlightInfo checkUnhandledExceptions(@NotNull PsiElement element, @Nullable TextRange textRange) {
    List<PsiClassType> unhandled = ExceptionUtil.getOwnUnhandledExceptions(element);
    if (unhandled.isEmpty()) return null;

    HighlightInfoType highlightType = getUnhandledExceptionHighlightType(element);
    if (highlightType == null) return null;

    if (textRange == null) textRange = element.getTextRange();
    String description = getUnhandledExceptionsDescriptor(unhandled);
    HighlightInfo errorResult = HighlightInfo.newHighlightInfo(highlightType).range(textRange).descriptionAndTooltip(description).create();
    HighlightFixUtil.registerUnhandledExceptionFixes(element, errorResult);
    return errorResult;
  }

  @Nullable
  static HighlightInfo checkUnhandledCloserExceptions(@NotNull PsiResourceListElement resource) {
    List<PsiClassType> unhandled = ExceptionUtil.getUnhandledCloserExceptions(resource, null);
    if (unhandled.isEmpty()) return null;

    HighlightInfoType highlightType = getUnhandledExceptionHighlightType(resource);
    if (highlightType == null) return null;

    String description = getUnhandledExceptionsDescriptor(unhandled, "auto-closeable resource");
    HighlightInfo highlight = HighlightInfo.newHighlightInfo(highlightType).range(resource).descriptionAndTooltip(description).create();
    HighlightFixUtil.registerUnhandledExceptionFixes(resource, highlight);
    return highlight;
  }

  @Nullable
  private static HighlightInfoType getUnhandledExceptionHighlightType(@NotNull PsiElement element) {
    // JSP top-level errors are handled by UnhandledExceptionInJSP inspection
    if (FileTypeUtils.isInServerPageFile(element)) {
      PsiMethod targetMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class, true, PsiLambdaExpression.class);
      if (targetMethod instanceof SyntheticElement) {
        return null;
      }
    }

    return HighlightInfoType.UNHANDLED_EXCEPTION;
  }

  @Nullable
  static HighlightInfo checkBreakTarget(@NotNull PsiBreakStatement statement, @NotNull LanguageLevel languageLevel) {
    return checkBreakOrContinueTarget(statement, statement.getLabelIdentifier(), statement.findExitedStatement(), languageLevel,
                                      "break.outside.switch.or.loop",
                                      "break.outside.switch.expr");
  }

  @Nullable
  static HighlightInfo checkYieldOutsideSwitchExpression(@NotNull PsiYieldStatement statement) {
    if (statement.findEnclosingExpression() == null) {
      String message = JavaErrorMessages.message("yield.unexpected");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(message).create();
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkYieldExpressionType(@NotNull PsiYieldStatement statement) {
    PsiExpression expression = statement.getExpression();
    if (expression != null && PsiType.VOID.equals(expression.getType())) {
      String message = JavaErrorMessages.message("yield.void");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkContinueTarget(@NotNull PsiContinueStatement statement, @NotNull LanguageLevel languageLevel) {
    PsiStatement continuedStatement = statement.findContinuedStatement();
    PsiIdentifier label = statement.getLabelIdentifier();

    if (label != null && continuedStatement != null && !(continuedStatement instanceof PsiLoopStatement)) {
      String message = JavaErrorMessages.message("not.loop.label", label.getText());
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(message).create();
    }

    return checkBreakOrContinueTarget(statement, label, continuedStatement, languageLevel,
                                      "continue.outside.loop",
                                      "continue.outside.switch.expr");
  }

  @Nullable
  private static HighlightInfo checkBreakOrContinueTarget(PsiStatement statement,
                                                          @Nullable PsiIdentifier label,
                                                          @Nullable PsiStatement target,
                                                          LanguageLevel level,
                                                          @PropertyKey(resourceBundle = JavaErrorMessages.BUNDLE) String misplacedKey,
                                                          @PropertyKey(resourceBundle = JavaErrorMessages.BUNDLE) String crossingKey) {
    if (target == null && label != null) {
      String message = JavaErrorMessages.message("unresolved.label", label.getText());
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(label).descriptionAndTooltip(message).create();
    }

    if (Feature.ENHANCED_SWITCH.isSufficient(level)) {
      PsiSwitchExpression expression = PsiImplUtil.findEnclosingSwitchExpression(statement);
      if (expression != null && (target == null || PsiTreeUtil.isAncestor(target, expression, true))) {
        String message = JavaErrorMessages.message(crossingKey);
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(message).create();
      }
    }

    if (target == null) {
      String message = JavaErrorMessages.message(misplacedKey);
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(message).create();
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkIllegalModifierCombination(@NotNull PsiKeyword keyword, @NotNull PsiModifierList modifierList) {
    @PsiModifier.ModifierConstant String modifier = keyword.getText();
    String incompatible = getIncompatibleModifier(modifier, modifierList);
    if (incompatible != null) {
      String message = JavaErrorMessages.message("incompatible.modifiers", modifier, incompatible);
      HighlightInfo highlightInfo =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(keyword).descriptionAndTooltip(message).create();
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createModifierListFix(modifierList, modifier, false, false));
      return highlightInfo;
    }

    return null;
  }

  /**
   * Checks if the supplied modifier list contains incompatible modifiers (e.g. public and private).
   *
   * @param modifierList a {@link PsiModifierList} to check
   * @return true if the supplied modifier list contains incompatible modifiers
   */
  public static boolean isIllegalModifierCombination(@NotNull PsiModifierList modifierList) {
    for (PsiElement child : modifierList.getChildren()) {
      if (child instanceof PsiKeyword && getIncompatibleModifier(child.getText(), modifierList) != null) {
        return true;
      }
    }
    return false;
  }

  @Contract("null -> null")
  private static Map<String, Set<String>> getIncompatibleModifierMap(@NotNull PsiElement modifierListOwner) {
    if (PsiUtilCore.hasErrorElementChild(modifierListOwner)) return null;
    if (modifierListOwner instanceof PsiClass) {
      return ((PsiClass)modifierListOwner).isInterface() ? ourInterfaceIncompatibleModifiers : ourClassIncompatibleModifiers;
    }
    if (modifierListOwner instanceof PsiMethod) return ourMethodIncompatibleModifiers;
    if (modifierListOwner instanceof PsiVariable) return ourFieldIncompatibleModifiers;
    if (modifierListOwner instanceof PsiClassInitializer) return ourClassInitializerIncompatibleModifiers;
    if (modifierListOwner instanceof PsiJavaModule) return ourModuleIncompatibleModifiers;
    if (modifierListOwner instanceof PsiRequiresStatement) return ourRequiresIncompatibleModifiers;
    return null;
  }

  @Nullable
  static String getIncompatibleModifier(@NotNull String modifier, @NotNull PsiModifierList modifierList) {
    PsiElement parent = modifierList.getParent();
    Map<String, Set<String>> incompatibleModifierMap = null;
    if (parent != null) {
      incompatibleModifierMap = getIncompatibleModifierMap(parent);
    }
    return incompatibleModifierMap == null ? null : getIncompatibleModifier(modifier, modifierList, incompatibleModifierMap);
  }

  @Nullable
  static HighlightInfo checkNotAllowedModifier(@NotNull PsiKeyword keyword, @NotNull PsiModifierList modifierList) {
    PsiElement modifierOwner = modifierList.getParent();
    if (modifierOwner == null) return null;
    Map<String, Set<String>> incompatibleModifierMap = getIncompatibleModifierMap(modifierOwner);
    if (incompatibleModifierMap == null) return null;

    @PsiModifier.ModifierConstant String modifier = keyword.getText();
    Set<String> incompatibles = incompatibleModifierMap.get(modifier);
    PsiElement modifierOwnerParent = modifierOwner instanceof PsiMember ? ((PsiMember)modifierOwner).getContainingClass() : modifierOwner.getParent();
    if (modifierOwnerParent == null) modifierOwnerParent = modifierOwner.getParent();
    boolean isAllowed = true;
    IntentionAction fix = null;
    if (modifierOwner instanceof PsiClass) {
      PsiClass aClass = (PsiClass)modifierOwner;
      boolean privateOrProtected = PsiModifier.PRIVATE.equals(modifier) || PsiModifier.PROTECTED.equals(modifier);
      if (aClass.isInterface()) {
        //noinspection DuplicateExpressions
        if (PsiModifier.STATIC.equals(modifier) || privateOrProtected || PsiModifier.PACKAGE_LOCAL.equals(modifier)) {
          isAllowed = modifierOwnerParent instanceof PsiClass;
        }
      }
      else {
        if (PsiModifier.PUBLIC.equals(modifier)) {
          isAllowed = modifierOwnerParent instanceof PsiImportHolder ||
                      // PsiJavaFile or JavaDummyHolder
                      modifierOwnerParent instanceof PsiClass &&
                      (modifierOwnerParent instanceof PsiSyntheticClass || ((PsiClass)modifierOwnerParent).getQualifiedName() != null || !modifierOwnerParent.isPhysical());
        }
        else {
          //noinspection DuplicateExpressions
          if (PsiModifier.STATIC.equals(modifier) || privateOrProtected || PsiModifier.PACKAGE_LOCAL.equals(modifier)) {
            isAllowed = modifierOwnerParent instanceof PsiClass &&
                        // non-physical dummy holder might not have FQN
                        ((PsiClass)modifierOwnerParent).getQualifiedName() != null || FileTypeUtils.isInServerPageFile(modifierOwnerParent) || !modifierOwnerParent.isPhysical();
          }
          if (privateOrProtected && !isAllowed) {
            fix = QUICK_FIX_FACTORY.createChangeModifierFix();
          }
        }

        if (aClass.isEnum()) {
          isAllowed &= !(PsiModifier.FINAL.equals(modifier) || PsiModifier.ABSTRACT.equals(modifier));
        }

        if (aClass.getContainingClass() instanceof PsiAnonymousClass) {
          isAllowed &= !privateOrProtected;
        }
      }
    }
    else if (modifierOwner instanceof PsiMethod) {
      PsiMethod method = (PsiMethod)modifierOwner;
      isAllowed = !(method.isConstructor() && ourConstructorNotAllowedModifiers.contains(modifier));
      PsiClass containingClass = method.getContainingClass();
      if ((method.hasModifierProperty(PsiModifier.PUBLIC) || method.hasModifierProperty(PsiModifier.PROTECTED)) && method.isConstructor() &&
          containingClass != null && containingClass.isEnum()) {
        isAllowed = false;
      }

      boolean isInterface = modifierOwnerParent instanceof PsiClass && ((PsiClass)modifierOwnerParent).isInterface();
      if (PsiModifier.PRIVATE.equals(modifier) && modifierOwnerParent instanceof PsiClass) {
        isAllowed &= !isInterface || PsiUtil.isLanguageLevel9OrHigher(modifierOwner) && !((PsiClass)modifierOwnerParent).isAnnotationType();
      }
      else if (PsiModifier.STRICTFP.equals(modifier)) {
        isAllowed &= !isInterface || PsiUtil.isLanguageLevel8OrHigher(modifierOwner);
      }
      else if (PsiModifier.PROTECTED.equals(modifier) || PsiModifier.TRANSIENT.equals(modifier) || PsiModifier.SYNCHRONIZED.equals(modifier)) {
        isAllowed &= !isInterface;
      }

      if (containingClass != null && containingClass.isInterface()) {
        isAllowed &= !PsiModifier.NATIVE.equals(modifier);
      }

      if (containingClass != null && containingClass.isAnnotationType()) {
        isAllowed &= !PsiModifier.STATIC.equals(modifier);
        isAllowed &= !PsiModifier.DEFAULT.equals(modifier);
      }
    }
    else if (modifierOwner instanceof PsiField) {
      if (PsiModifier.PRIVATE.equals(modifier) || PsiModifier.PROTECTED.equals(modifier) || PsiModifier.TRANSIENT.equals(modifier) ||
          PsiModifier.STRICTFP.equals(modifier) || PsiModifier.SYNCHRONIZED.equals(modifier)) {
        isAllowed = modifierOwnerParent instanceof PsiClass && !((PsiClass)modifierOwnerParent).isInterface();
      }
    }
    else if (modifierOwner instanceof PsiClassInitializer) {
      isAllowed = PsiModifier.STATIC.equals(modifier);
    }
    else if (modifierOwner instanceof PsiLocalVariable || modifierOwner instanceof PsiParameter) {
      isAllowed = PsiModifier.FINAL.equals(modifier);
    }
    else if (modifierOwner instanceof PsiReceiverParameter) {
      isAllowed = false;
    }

    isAllowed &= incompatibles != null;
    if (!isAllowed) {
      String message = JavaErrorMessages.message("modifier.not.allowed", modifier);
      HighlightInfo highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(keyword).descriptionAndTooltip(message).create();
      QuickFixAction.registerQuickFixAction(highlightInfo, fix != null ? fix : QUICK_FIX_FACTORY.createModifierListFix(modifierList, modifier, false, false));
      return highlightInfo;
    }

    return null;
  }

  @Nullable
  public static HighlightInfo checkLiteralExpressionParsingError(@NotNull PsiLiteralExpression expression,
                                                                 @NotNull LanguageLevel level,
                                                                 @Nullable PsiFile file) {
    PsiElement literal = expression.getFirstChild();
    assert literal instanceof PsiJavaToken : literal;
    IElementType type = ((PsiJavaToken)literal).getTokenType();
    if (type == JavaTokenType.TRUE_KEYWORD || type == JavaTokenType.FALSE_KEYWORD || type == JavaTokenType.NULL_KEYWORD) {
      return null;
    }

    boolean isInt = ElementType.INTEGER_LITERALS.contains(type);
    boolean isFP = ElementType.REAL_LITERALS.contains(type);
    String text = isInt || isFP ? StringUtil.toLowerCase(literal.getText()) : literal.getText();
    Object value = expression.getValue();

    if (file != null) {
      if (isFP) {
        if (text.startsWith(PsiLiteralUtil.HEX_PREFIX)) {
          final HighlightInfo info = checkFeature(expression, Feature.HEX_FP_LITERALS, level, file);
          if (info != null) return info;
        }
      }
      if (isInt) {
        if (text.startsWith(PsiLiteralUtil.BIN_PREFIX)) {
          final HighlightInfo info = checkFeature(expression, Feature.BIN_LITERALS, level, file);
          if (info != null) return info;
        }
      }
      if (isInt || isFP) {
        if (text.contains("_")) {
          HighlightInfo info = checkFeature(expression, Feature.UNDERSCORES, level, file);
          if (info != null) return info;
          info = checkUnderscores(expression, text, isInt);
          if (info != null) return info;
        }
      }
    }

    final PsiElement parent = expression.getParent();
    if (type == JavaTokenType.INTEGER_LITERAL) {
      String cleanText = StringUtil.replace(text, "_", "");
      //literal 2147483648 may appear only as the operand of the unary negation operator -.
      if (!(cleanText.equals(PsiLiteralUtil._2_IN_31) &&
            parent instanceof PsiPrefixExpression &&
            ((PsiPrefixExpression)parent).getOperationTokenType() == JavaTokenType.MINUS)) {
        if (cleanText.equals(PsiLiteralUtil.HEX_PREFIX)) {
          String message = JavaErrorMessages.message("hexadecimal.numbers.must.contain.at.least.one.hexadecimal.digit");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        if (cleanText.equals(PsiLiteralUtil.BIN_PREFIX)) {
          String message = JavaErrorMessages.message("binary.numbers.must.contain.at.least.one.hexadecimal.digit");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        if (value == null || cleanText.equals(PsiLiteralUtil._2_IN_31)) {
          String message = JavaErrorMessages.message("integer.number.too.large");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
      }
    }
    else if (type == JavaTokenType.LONG_LITERAL) {
      String cleanText = StringUtil.replace(StringUtil.trimEnd(text, 'l'), "_", "");
      //literal 9223372036854775808L may appear only as the operand of the unary negation operator -.
      if (!(cleanText.equals(PsiLiteralUtil._2_IN_63) &&
            parent instanceof PsiPrefixExpression &&
            ((PsiPrefixExpression)parent).getOperationTokenType() == JavaTokenType.MINUS)) {
        if (cleanText.equals(PsiLiteralUtil.HEX_PREFIX)) {
          String message = JavaErrorMessages.message("hexadecimal.numbers.must.contain.at.least.one.hexadecimal.digit");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        if (cleanText.equals(PsiLiteralUtil.BIN_PREFIX)) {
          String message = JavaErrorMessages.message("binary.numbers.must.contain.at.least.one.hexadecimal.digit");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        if (value == null || cleanText.equals(PsiLiteralUtil._2_IN_63)) {
          String message = JavaErrorMessages.message("long.number.too.large");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
      }
    }
    else if (isFP) {
      if (value == null) {
        String message = JavaErrorMessages.message("malformed.floating.point.literal");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
    }
    else if (type == JavaTokenType.CHARACTER_LITERAL) {
      if (value == null) {
        if (!StringUtil.startsWithChar(text, '\'')) {
          return null;
        }
        if (!StringUtil.endsWithChar(text, '\'') || text.length() == 1) {
          String message = JavaErrorMessages.message("unclosed.char.literal");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        text = text.substring(1, text.length() - 1);

        StringBuilder chars = new StringBuilder();
        boolean success = PsiLiteralExpressionImpl.parseStringCharacters(text, chars, null);
        if (!success) {
          String message = JavaErrorMessages.message("illegal.escape.character.in.character.literal");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
        int length = chars.length();
        if (length > 1) {
          String message = JavaErrorMessages.message("too.many.characters.in.character.literal");
          HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
          QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createConvertToStringLiteralAction());
          return info;
        }
        else if (length == 0) {
          String message = JavaErrorMessages.message("empty.character.literal");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
      }
    }
    else if (type == JavaTokenType.STRING_LITERAL) {
      if (value == null) {
        for (PsiElement element : expression.getChildren()) {
          if (element instanceof OuterLanguageElement) {
            return null;
          }
        }

        if (!StringUtil.startsWithChar(text, '\"')) return null;
        if (StringUtil.endsWithChar(text, '\"')) {
          if (text.length() == 1) {
            String message = JavaErrorMessages.message("illegal.line.end.in.string.literal");
            return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
          }
          text = text.substring(1, text.length() - 1);
        }
        else {
          String message = JavaErrorMessages.message("illegal.line.end.in.string.literal");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }

        StringBuilder chars = new StringBuilder();
        boolean success = PsiLiteralExpressionImpl.parseStringCharacters(text, chars, null);
        if (!success) {
          String message = JavaErrorMessages.message("illegal.escape.character.in.string.literal");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
      }
    }
    else if (type == JavaTokenType.TEXT_BLOCK_LITERAL) {
      if (value == null) {
        if (!text.endsWith("\"\"\"")) {
          String message = JavaErrorMessages.message("text.block.unclosed");
          int p = expression.getTextRange().getEndOffset();
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(p, p).endOfLine().descriptionAndTooltip(message).create();
        }
        else {
          String message = JavaErrorMessages.message("text.block.new.line");
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
        }
      }
      StringBuilder chars = new StringBuilder();
      boolean success = CodeInsightUtilCore.parseStringCharacters(text, chars, null);
      if (!success) {
        String message = JavaErrorMessages.message("illegal.escape.character.in.string.literal");
        TextRange textRange = chars.length() < text.length() - 1 ? new TextRange(chars.length(), chars.length() + 1) 
                                                                 : expression.getTextRange();
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
          .range(expression, textRange)
          .descriptionAndTooltip(message).create();
      }
    }

    if (value instanceof Float) {
      Float number = (Float)value;
      if (number.isInfinite()) {
        String message = JavaErrorMessages.message("floating.point.number.too.large");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
      if (number.floatValue() == 0 && !TypeConversionUtil.isFPZero(text)) {
        String message = JavaErrorMessages.message("floating.point.number.too.small");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
    }
    else if (value instanceof Double) {
      Double number = (Double)value;
      if (number.isInfinite()) {
        String message = JavaErrorMessages.message("floating.point.number.too.large");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
      if (number.doubleValue() == 0 && !TypeConversionUtil.isFPZero(text)) {
        String message = JavaErrorMessages.message("floating.point.number.too.small");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
    }

    return null;
  }

  private static final Pattern FP_LITERAL_PARTS =
    Pattern.compile("(?:" +
                    "(?:0x([_\\p{XDigit}]*)\\.?([_\\p{XDigit}]*)p[+-]?([_\\d]*))" +
                    "|" +
                    "(?:([_\\d]*)\\.?([_\\d]*)e?[+-]?([_\\d]*))" +
                    ")[fd]?");

  @Nullable
  private static HighlightInfo checkUnderscores(@NotNull PsiElement expression, @NotNull String text, boolean isInt) {
    String[] parts = ArrayUtilRt.EMPTY_STRING_ARRAY;

    if (isInt) {
      int start = 0;
      if (text.startsWith(PsiLiteralUtil.HEX_PREFIX) || text.startsWith(PsiLiteralUtil.BIN_PREFIX)) start += 2;
      int end = text.length();
      if (StringUtil.endsWithChar(text, 'l')) --end;
      parts = new String[]{text.substring(start, end)};
    }
    else {
      Matcher matcher = FP_LITERAL_PARTS.matcher(text);
      if (matcher.matches()) {
        parts = new String[matcher.groupCount()];
        for (int i = 0; i < matcher.groupCount(); i++) {
          parts[i] = matcher.group(i + 1);
        }
      }
    }

    for (String part : parts) {
      if (part != null && (StringUtil.startsWithChar(part, '_') || StringUtil.endsWithChar(part, '_'))) {
        String message = JavaErrorMessages.message("illegal.underscore");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkMustBeBoolean(@NotNull PsiExpression expr, @Nullable PsiType type) {
    PsiElement parent = expr.getParent();
    if (parent instanceof PsiIfStatement || parent instanceof PsiWhileStatement ||
        parent instanceof PsiForStatement && expr.equals(((PsiForStatement)parent).getCondition()) ||
        parent instanceof PsiDoWhileStatement && expr.equals(((PsiDoWhileStatement)parent).getCondition())) {
      if (expr.getNextSibling() instanceof PsiErrorElement) return null;

      if (!TypeConversionUtil.isBooleanType(type)) {
        return createMustBeBooleanInfo(expr, type);
      }
    }
    return null;
  }

  private static HighlightInfo createMustBeBooleanInfo(@NotNull PsiExpression expr, @Nullable PsiType type) {
    final HighlightInfo info = createIncompatibleTypeHighlightInfo(PsiType.BOOLEAN, type, expr.getTextRange(), 0);
    if (expr instanceof PsiMethodCallExpression) {
      final PsiMethodCallExpression methodCall = (PsiMethodCallExpression)expr;
      final PsiMethod method = methodCall.resolveMethod();
      if (method != null) {
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createMethodReturnFix(method, PsiType.BOOLEAN, true));
      }
    }
    else if (expr instanceof PsiAssignmentExpression && ((PsiAssignmentExpression)expr).getOperationTokenType() == JavaTokenType.EQ) {
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAssignmentToComparisonFix((PsiAssignmentExpression)expr));
    }
    return info;
  }


  @NotNull
  static Set<PsiClassType> collectUnhandledExceptions(@NotNull final PsiTryStatement statement) {
    final Set<PsiClassType> thrownTypes = new java.util.HashSet<>();

    final PsiCodeBlock tryBlock = statement.getTryBlock();
    if (tryBlock != null) {
      thrownTypes.addAll(ExceptionUtil.collectUnhandledExceptions(tryBlock, tryBlock));
    }

    final PsiResourceList resources = statement.getResourceList();
    if (resources != null) {
      thrownTypes.addAll(ExceptionUtil.collectUnhandledExceptions(resources, resources));
    }

    return thrownTypes;
  }

  @Nullable
  static List<HighlightInfo> checkExceptionThrownInTry(@NotNull final PsiParameter parameter,
                                                       @NotNull final Set<? extends PsiClassType> thrownTypes) {
    final PsiElement declarationScope = parameter.getDeclarationScope();
    if (!(declarationScope instanceof PsiCatchSection)) return null;

    final PsiType caughtType = parameter.getType();
    if (caughtType instanceof PsiClassType) {
      HighlightInfo info = checkSimpleCatchParameter(parameter, thrownTypes, (PsiClassType)caughtType);
      return info == null ? null : Collections.singletonList(info);
    }
    if (caughtType instanceof PsiDisjunctionType) {
      return checkMultiCatchParameter(parameter, thrownTypes);
    }

    return null;
  }

  @Nullable
  private static HighlightInfo checkSimpleCatchParameter(@NotNull final PsiParameter parameter,
                                                         @NotNull final Collection<? extends PsiClassType> thrownTypes,
                                                         @NotNull final PsiClassType caughtType) {
    if (ExceptionUtil.isUncheckedExceptionOrSuperclass(caughtType)) return null;

    for (PsiClassType exceptionType : thrownTypes) {
      if (exceptionType.isAssignableFrom(caughtType) || caughtType.isAssignableFrom(exceptionType)) return null;
    }

    final String description = JavaErrorMessages.message("exception.never.thrown.try", JavaHighlightUtil.formatType(caughtType));
    final HighlightInfo errorResult =
      HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(parameter).descriptionAndTooltip(description).create();
    QuickFixAction.registerQuickFixAction(errorResult, QUICK_FIX_FACTORY.createDeleteCatchFix(parameter));
    return errorResult;
  }

  @NotNull
  private static List<HighlightInfo> checkMultiCatchParameter(@NotNull final PsiParameter parameter,
                                                              @NotNull final Collection<? extends PsiClassType> thrownTypes) {
    final List<PsiTypeElement> typeElements = PsiUtil.getParameterTypeElements(parameter);
    final List<HighlightInfo> highlights = new ArrayList<>(typeElements.size());

    for (final PsiTypeElement typeElement : typeElements) {
      final PsiType catchType = typeElement.getType();
      if (catchType instanceof PsiClassType && ExceptionUtil.isUncheckedExceptionOrSuperclass((PsiClassType)catchType)) continue;

      boolean used = false;
      for (PsiClassType exceptionType : thrownTypes) {
        if (exceptionType.isAssignableFrom(catchType) || catchType.isAssignableFrom(exceptionType)) {
          used = true;
          break;
        }
      }
      if (!used) {
        final String description = JavaErrorMessages.message("exception.never.thrown.try", JavaHighlightUtil.formatType(catchType));
        final HighlightInfo highlight =
          HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(typeElement).descriptionAndTooltip(description).create();
        QuickFixAction.registerQuickFixAction(highlight, QUICK_FIX_FACTORY.createDeleteMultiCatchFix(typeElement));
        highlights.add(highlight);
      }
    }

    return highlights;
  }


  @Nullable
  static Collection<HighlightInfo> checkWithImprovedCatchAnalysis(@NotNull PsiParameter parameter,
                                                                  @NotNull Collection<? extends PsiClassType> thrownInTryStatement,
                                                                  @NotNull PsiFile containingFile) {
    final PsiElement scope = parameter.getDeclarationScope();
    if (!(scope instanceof PsiCatchSection)) return null;

    final PsiCatchSection catchSection = (PsiCatchSection)scope;
    final PsiCatchSection[] allCatchSections = catchSection.getTryStatement().getCatchSections();
    final int idx = ArrayUtilRt.find(allCatchSections, catchSection);
    if (idx <= 0) return null;

    final Collection<PsiClassType> thrownTypes = new java.util.HashSet<>(thrownInTryStatement);
    final PsiManager manager = containingFile.getManager();
    final GlobalSearchScope parameterResolveScope = parameter.getResolveScope();
    thrownTypes.add(PsiType.getJavaLangError(manager, parameterResolveScope));
    thrownTypes.add(PsiType.getJavaLangRuntimeException(manager, parameterResolveScope));
    final Collection<HighlightInfo> result = new ArrayList<>();

    final List<PsiTypeElement> parameterTypeElements = PsiUtil.getParameterTypeElements(parameter);
    final boolean isMultiCatch = parameterTypeElements.size() > 1;
    for (PsiTypeElement catchTypeElement : parameterTypeElements) {
      final PsiType catchType = catchTypeElement.getType();
      if (ExceptionUtil.isGeneralExceptionType(catchType)) continue;

      // collect exceptions caught by this type
      final Collection<PsiClassType> caught =
        ContainerUtil.findAll(thrownTypes, type -> catchType.isAssignableFrom(type) || type.isAssignableFrom(catchType));
      if (caught.isEmpty()) continue;
      final Collection<PsiClassType> caughtCopy = new java.util.HashSet<>(caught);

      // exclude all caught by previous catch sections
      for (int i = 0; i < idx; i++) {
        final PsiParameter prevCatchParameter = allCatchSections[i].getParameter();
        if (prevCatchParameter == null) continue;
        for (PsiTypeElement prevCatchTypeElement : PsiUtil.getParameterTypeElements(prevCatchParameter)) {
          final PsiType prevCatchType = prevCatchTypeElement.getType();
          caught.removeIf(prevCatchType::isAssignableFrom);
          if (caught.isEmpty()) break;
        }
      }

      // check & warn
      if (caught.isEmpty()) {
        final String message = JavaErrorMessages.message("exception.already.caught.warn", formatTypes(caughtCopy), caughtCopy.size());
        final HighlightInfo highlightInfo =
          HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING).range(catchSection).descriptionAndTooltip(message).create();
        if (isMultiCatch) {
          QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createDeleteMultiCatchFix(catchTypeElement));
        }
        else {
          QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createDeleteCatchFix(parameter));
        }
        result.add(highlightInfo);
      }
    }

    return result;
  }


  @Nullable
  static HighlightInfo checkNotAStatement(@NotNull PsiStatement statement) {
    if (!PsiUtil.isStatement(statement) && !PsiUtilCore.hasErrorElementChild(statement)) {
      boolean isDeclarationNotAllowed = false;
      if (statement instanceof PsiDeclarationStatement) {
        PsiElement parent = statement.getParent();
        isDeclarationNotAllowed = parent instanceof PsiIfStatement || parent instanceof PsiLoopStatement;
      }
      String description = JavaErrorMessages.message(isDeclarationNotAllowed ? "declaration.not.allowed" : "not.a.statement");
      HighlightInfo error =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
      if (statement instanceof PsiExpressionStatement) {
        QuickFixAction.registerQuickFixAction(error, QuickFixFactory.getInstance().createDeleteSideEffectAwareFix((PsiExpressionStatement)statement));
      }
      return error;
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkSwitchSelectorType(@NotNull PsiSwitchBlock switchBlock, @NotNull LanguageLevel level) {
    PsiExpression expression = switchBlock.getExpression();
    if (expression == null) return null;
    PsiType type = expression.getType();
    if (type == null) return null;

    SelectorKind kind = getSwitchSelectorKind(type);
    if (kind == SelectorKind.INT) return null;

    LanguageLevel requiredLevel = null;
    if (kind == SelectorKind.ENUM) requiredLevel = LanguageLevel.JDK_1_5;
    if (kind == SelectorKind.STRING) requiredLevel = LanguageLevel.JDK_1_7;

    if (kind == null || requiredLevel != null && !level.isAtLeast(requiredLevel)) {
      boolean is7 = level.isAtLeast(LanguageLevel.JDK_1_7);
      String expected = JavaErrorMessages.message(is7 ? "valid.switch.17.selector.types" : "valid.switch.selector.types");
      String message = JavaErrorMessages.message("incompatible.types", expected, JavaHighlightUtil.formatType(type));
      HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      if (switchBlock instanceof PsiSwitchStatement) {
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createConvertSwitchToIfIntention((PsiSwitchStatement)switchBlock));
      }
      if (PsiType.LONG.equals(type) || PsiType.FLOAT.equals(type) || PsiType.DOUBLE.equals(type)) {
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAddTypeCastFix(PsiType.INT, expression));
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createWrapWithAdapterFix(PsiType.INT, expression));
      }
      if (requiredLevel != null) {
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createIncreaseLanguageLevelFix(requiredLevel));
      }
      return info;
    }

    PsiClass member = PsiUtil.resolveClassInClassTypeOnly(type);
    if (member != null && !PsiUtil.isAccessible(member.getProject(), member, expression, null)) {
      String className = PsiFormatUtil.formatClass(member, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_FQ_NAME);
      String message = JavaErrorMessages.message("inaccessible.type", className);
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
    }

    return null;
  }

  static Collection<HighlightInfo> checkSwitchExpressionReturnTypeCompatible(PsiSwitchExpression switchExpression) {
    if (!PsiPolyExpressionUtil.isPolyExpression(switchExpression)) return null;
    List<HighlightInfo> infos = new ArrayList<>();
    PsiType switchExpressionType = switchExpression.getType();
    if (switchExpressionType != null) {
      for (PsiExpression expression : PsiUtil.getSwitchResultExpressions(switchExpression)) {
        final PsiType expressionType = PsiResolveHelper.ourGraphGuard.doPreventingRecursion(expression, true, expression::getType);
        if (expressionType != null && !switchExpressionType.isAssignableFrom(expressionType)) {
          String text = "Bad type in switch expression: " + expressionType.getCanonicalText() + " cannot be converted to " + switchExpressionType.getCanonicalText();
          infos.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(text).create());
        }
      }

      if (PsiType.VOID.equals(switchExpressionType)) {
        String text = "Target type for switch expression cannot be void";
        infos.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(switchExpression.getFirstChild()).descriptionAndTooltip(text).create());
      }
    }

    return infos;
  }

  private enum SelectorKind { INT, ENUM, STRING }

  private static SelectorKind getSwitchSelectorKind(@NotNull PsiType type) {
    if (TypeConversionUtil.getTypeRank(type) <= TypeConversionUtil.INT_RANK) {
      return SelectorKind.INT;
    }

    PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);
    if (psiClass != null) {
      if (psiClass.isEnum()) {
        return SelectorKind.ENUM;
      }
      if (Comparing.strEqual(psiClass.getQualifiedName(), CommonClassNames.JAVA_LANG_STRING)) {
        return SelectorKind.STRING;
      }
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkPolyadicOperatorApplicable(@NotNull PsiPolyadicExpression expression) {
    PsiExpression[] operands = expression.getOperands();

    PsiType lType = operands[0].getType();
    IElementType operationSign = expression.getOperationTokenType();
    for (int i = 1; i < operands.length; i++) {
      PsiExpression operand = operands[i];
      PsiType rType = operand.getType();
      if (!TypeConversionUtil.isBinaryOperatorApplicable(operationSign, lType, rType, false)) {
        PsiJavaToken token = expression.getTokenBeforeOperand(operand);
        assert token != null : expression;
        String message = JavaErrorMessages.message("binary.operator.not.applicable", token.getText(),
                                                   JavaHighlightUtil.formatType(lType),
                                                   JavaHighlightUtil.formatType(rType));
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(message).create();
      }
      lType = TypeConversionUtil.calcTypeForBinaryExpression(lType, rType, operationSign, true);
    }

    return null;
  }


  @Nullable
  static HighlightInfo checkUnaryOperatorApplicable(@NotNull PsiJavaToken token, @Nullable PsiExpression expression) {
    if (expression != null && !TypeConversionUtil.isUnaryOperatorApplicable(token, expression)) {
      PsiType type = expression.getType();
      if (type == null) return null;
      String message = JavaErrorMessages.message("unary.operator.not.applicable", token.getText(), JavaHighlightUtil.formatType(type));

      PsiElement parentExpr = token.getParent();
      HighlightInfo highlightInfo =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(parentExpr).descriptionAndTooltip(message).create();
      if (parentExpr instanceof PsiPrefixExpression && token.getTokenType() == JavaTokenType.EXCL) {
        QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createNegationBroadScopeFix((PsiPrefixExpression)parentExpr));
        if (expression instanceof PsiMethodCallExpression) {
          PsiMethod method = ((PsiMethodCallExpression)expression).resolveMethod();
          if (method != null) {
            QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createMethodReturnFix(method, PsiType.BOOLEAN, true));
          }
        }
      }
      return highlightInfo;
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkThisOrSuperExpressionInIllegalContext(@NotNull PsiExpression expr,
                                                                  @Nullable PsiJavaCodeReferenceElement qualifier,
                                                                  @NotNull LanguageLevel languageLevel) {
    if (expr instanceof PsiSuperExpression) {
      final PsiElement parent = expr.getParent();
      if (!(parent instanceof PsiReferenceExpression)) {
        // like in 'Object o = super;'
        final int o = expr.getTextRange().getEndOffset();
        String description = JavaErrorMessages.message("dot.expected.after.super.or.this");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(o, o + 1).descriptionAndTooltip(description).create();
      }
    }

    PsiClass aClass;
    if (qualifier != null) {
      PsiElement resolved = qualifier.advancedResolve(true).getElement();
      if (resolved != null && !(resolved instanceof PsiClass)) {
        String description = JavaErrorMessages.message("class.expected");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(qualifier).descriptionAndTooltip(description).create();
      }
      aClass = (PsiClass)resolved;
    }
    else {
      aClass = PsiTreeUtil.getParentOfType(expr, PsiClass.class);
      if (aClass instanceof PsiAnonymousClass && PsiTreeUtil.isAncestor(((PsiAnonymousClass)aClass).getArgumentList(), expr, false)) {
        aClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class, true);
      }
    }
    if (aClass == null) return null;

    if (!InheritanceUtil.hasEnclosingInstanceInScope(aClass, expr, false, false)) {
      if (!resolvesToImmediateSuperInterface(expr, qualifier, aClass, languageLevel)) {
        return HighlightClassUtil.reportIllegalEnclosingUsage(expr, null, aClass, expr);
      }
      if (expr instanceof PsiSuperExpression) {
        final PsiElement resolved = ((PsiReferenceExpression)expr.getParent()).resolve();
        //15.11.2
        //The form T.super.Identifier refers to the field named Identifier of the lexically enclosing instance corresponding to T,
        //but with that instance viewed as an instance of the superclass of T.
        if (resolved instanceof PsiField) {
          String description = JavaErrorMessages.message("is.not.an.enclosing.class", formatClass(aClass));
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expr).descriptionAndTooltip(description).create();
        }
      }
    }

    if (qualifier != null && aClass.isInterface() && expr instanceof PsiSuperExpression && languageLevel.isAtLeast(LanguageLevel.JDK_1_8)) {
      //15.12.1 for method invocation expressions; 15.13 for method references
      //If TypeName denotes an interface, I, then let T be the type declaration immediately enclosing the method reference expression.
      //It is a compile-time error if I is not a direct superinterface of T,
      //or if there exists some other direct superclass or direct superinterface of T, J, such that J is a subtype of I.
      final PsiClass classT = PsiTreeUtil.getParentOfType(expr, PsiClass.class);
      if (classT != null) {
        final PsiElement parent = expr.getParent();
        final PsiElement resolved = parent instanceof PsiReferenceExpression ? ((PsiReferenceExpression)parent).resolve() : null;

        PsiClass containingClass =
          ObjectUtils.notNull(resolved instanceof PsiMethod ? ((PsiMethod)resolved).getContainingClass() : null, aClass);
        for (PsiClass superClass : classT.getSupers()) {
          if (superClass.isInheritor(containingClass, true)) {
            String cause = null;
            if (superClass.isInheritor(aClass, true) && superClass.isInterface()) {
              cause = "redundant interface " + format(containingClass) + " is extended by ";
            }
            else if (resolved instanceof PsiMethod &&
                     MethodSignatureUtil.findMethodBySuperMethod(superClass, (PsiMethod)resolved, true) != resolved) {
              cause = "method " + ((PsiMethod)resolved).getName() + " is overridden in ";
            }

            if (cause != null) {
              return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                .range(qualifier)
                .descriptionAndTooltip(JavaErrorMessages.message("bad.qualifier.in.super.method.reference", cause + formatClass(superClass))).create();
            }
          }
        }

        if (!classT.isInheritor(aClass, false)) {
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(qualifier)
            .descriptionAndTooltip(JavaErrorMessages.message("no.enclosing.instance.in.scope", format(aClass))).create();
        }
      }
    }

    if (expr instanceof PsiThisExpression) {
      final PsiMethod psiMethod = PsiTreeUtil.getParentOfType(expr, PsiMethod.class);
      if (psiMethod == null || psiMethod.getContainingClass() != aClass && !isInsideDefaultMethod(psiMethod, aClass)) {
        if (aClass.isInterface()) {
          return thisNotFoundInInterfaceInfo(expr);
        }

        if (aClass instanceof PsiAnonymousClass && PsiTreeUtil.isAncestor(((PsiAnonymousClass)aClass).getArgumentList(), expr, true)) {
          final PsiClass parentClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class, true);
          if (parentClass != null && parentClass.isInterface()) {
            return thisNotFoundInInterfaceInfo(expr);
          }
        }
      }
    }
    return null;
  }

  static HighlightInfo checkUnqualifiedSuperInDefaultMethod(@NotNull LanguageLevel languageLevel,
                                                            @NotNull PsiReferenceExpression expr,
                                                            @Nullable PsiExpression qualifier) {
    if (languageLevel.isAtLeast(LanguageLevel.JDK_1_8) && qualifier instanceof PsiSuperExpression) {
      final PsiMethod method = PsiTreeUtil.getParentOfType(expr, PsiMethod.class);
      if (method != null && method.hasModifierProperty(PsiModifier.DEFAULT) && ((PsiSuperExpression)qualifier).getQualifier() == null) {
        String description = JavaErrorMessages.message("unqualified.super.disallowed");
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expr).descriptionAndTooltip(description).create();
        QualifySuperArgumentFix.registerQuickFixAction((PsiSuperExpression)qualifier, info);
        return info;
      }
    }
    return null;
  }

  private static boolean isInsideDefaultMethod(@NotNull PsiMethod method, @NotNull PsiClass aClass) {
    while (method != null && method.getContainingClass() != aClass) {
      method = PsiTreeUtil.getParentOfType(method, PsiMethod.class, true);
    }
    return method != null && method.hasModifierProperty(PsiModifier.DEFAULT);
  }

  private static HighlightInfo thisNotFoundInInterfaceInfo(@NotNull PsiExpression expr) {
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expr).descriptionAndTooltip("Cannot find symbol variable this").create();
  }

  private static boolean resolvesToImmediateSuperInterface(@NotNull PsiExpression expr,
                                                           @Nullable PsiJavaCodeReferenceElement qualifier,
                                                           @NotNull PsiClass aClass,
                                                           @NotNull LanguageLevel languageLevel) {
    if (!(expr instanceof PsiSuperExpression) || qualifier == null || !languageLevel.isAtLeast(LanguageLevel.JDK_1_8)) return false;
    final PsiType superType = expr.getType();
    if (!(superType instanceof PsiClassType)) return false;
    final PsiClass superClass = ((PsiClassType)superType).resolve();
    return aClass.equals(superClass) && PsiUtil.getEnclosingStaticElement(expr, PsiTreeUtil.getParentOfType(expr, PsiClass.class)) == null;
  }

  @NotNull
  static String staticContextProblemDescription(@NotNull PsiElement refElement) {
    String type = LanguageFindUsages.getType(refElement);
    String name = HighlightMessageUtil.getSymbolName(refElement, PsiSubstitutor.EMPTY);
    return JavaErrorMessages.message("non.static.symbol.referenced.from.static.context", type, name);
  }

  @NotNull
  static String accessProblemDescription(@NotNull PsiElement ref, @NotNull PsiElement resolved, @NotNull JavaResolveResult result) {
    return accessProblemDescriptionAndFixes(ref, resolved, result).first;
  }

  @NotNull
  static Pair<String, List<IntentionAction>> accessProblemDescriptionAndFixes(@NotNull PsiElement ref,
                                                                              @NotNull PsiElement resolved,
                                                                              @NotNull JavaResolveResult result) {
    assert resolved instanceof PsiModifierListOwner : resolved;
    PsiModifierListOwner refElement = (PsiModifierListOwner)resolved;
    String symbolName = HighlightMessageUtil.getSymbolName(refElement, result.getSubstitutor());

    if (refElement.hasModifierProperty(PsiModifier.PRIVATE)) {
      String containerName = getContainerName(refElement, result.getSubstitutor());
      return Pair.pair(JavaErrorMessages.message("private.symbol", symbolName, containerName), null);
    }

    if (refElement.hasModifierProperty(PsiModifier.PROTECTED)) {
      String containerName = getContainerName(refElement, result.getSubstitutor());
      return Pair.pair(JavaErrorMessages.message("protected.symbol", symbolName, containerName), null);
    }

    PsiClass packageLocalClass = HighlightFixUtil.getPackageLocalClassInTheMiddle(ref);
    if (packageLocalClass != null) {
      refElement = packageLocalClass;
      symbolName = HighlightMessageUtil.getSymbolName(refElement, result.getSubstitutor());
    }

    if (refElement.hasModifierProperty(PsiModifier.PACKAGE_LOCAL) || packageLocalClass != null) {
      String containerName = getContainerName(refElement, result.getSubstitutor());
      return Pair.pair(JavaErrorMessages.message("package.local.symbol", symbolName, containerName), null);
    }

    String containerName = getContainerName(refElement, result.getSubstitutor());
    ErrorWithFixes problem = checkModuleAccess(resolved, ref, symbolName, containerName);
    if (problem != null) return Pair.pair(problem.message, problem.fixes);
    return Pair.pair(JavaErrorMessages.message("visibility.access.problem", symbolName, containerName), null);
  }

  private static ErrorWithFixes checkModuleAccess(@NotNull PsiElement target,
                                                  @NotNull PsiElement place,
                                                  @Nullable String symbolName,
                                                  @Nullable String containerName) {
    ErrorWithFixes error = null;
    for (JavaModuleSystem moduleSystem : JavaModuleSystem.EP_NAME.getExtensions()) {
      if (moduleSystem instanceof JavaModuleSystemEx) {
        error = checkAccess((JavaModuleSystemEx)moduleSystem, target, place);
      }
      else if (!isAccessible(moduleSystem, target, place)) {
        String message = JavaErrorMessages.message("visibility.module.access.problem", symbolName, containerName, moduleSystem.getName());
        error = new ErrorWithFixes(message);
      }
      if (error != null) {
        return error;
      }
    }

    return null;
  }

  private static ErrorWithFixes checkAccess(@NotNull JavaModuleSystemEx system, @NotNull PsiElement target, @NotNull PsiElement place) {
    if (target instanceof PsiClass) return system.checkAccess((PsiClass)target, place);
    if (target instanceof PsiPackage) return system.checkAccess(((PsiPackage)target).getQualifiedName(), null, place);
    return null;
  }

  private static boolean isAccessible(@NotNull JavaModuleSystem system, @NotNull PsiElement target, @NotNull PsiElement place) {
    if (target instanceof PsiClass) return system.isAccessible((PsiClass)target, place);
    if (target instanceof PsiPackage) return system.isAccessible(((PsiPackage)target).getQualifiedName(), null, place);
    return true;
  }

  private static PsiElement getContainer(@NotNull PsiModifierListOwner refElement) {
    for (ContainerProvider provider : ContainerProvider.EP_NAME.getExtensions()) {
      final PsiElement container = provider.getContainer(refElement);
      if (container != null) return container;
    }
    return refElement.getParent();
  }

  private static String getContainerName(@NotNull PsiModifierListOwner refElement, @NotNull PsiSubstitutor substitutor) {
    final PsiElement container = getContainer(refElement);
    return container == null ? "?" : HighlightMessageUtil.getSymbolName(container, substitutor);
  }

  @Nullable
  static HighlightInfo checkValidArrayAccessExpression(@NotNull PsiArrayAccessExpression arrayAccessExpression) {
    final PsiExpression arrayExpression = arrayAccessExpression.getArrayExpression();
    final PsiType arrayExpressionType = arrayExpression.getType();

    if (arrayExpressionType != null && !(arrayExpressionType instanceof PsiArrayType)) {
      final String description = JavaErrorMessages.message("array.type.expected", JavaHighlightUtil.formatType(arrayExpressionType));
      final HighlightInfo info =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(arrayExpression).descriptionAndTooltip(description).create();
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createReplaceWithListAccessFix(arrayAccessExpression));
      return info;
    }

    final PsiExpression indexExpression = arrayAccessExpression.getIndexExpression();
    return indexExpression != null ? checkAssignability(PsiType.INT, indexExpression.getType(), indexExpression, indexExpression) : null;
  }


  @Nullable
  static HighlightInfo checkCatchParameterIsThrowable(@NotNull final PsiParameter parameter) {
    if (parameter.getDeclarationScope() instanceof PsiCatchSection) {
      final PsiType type = parameter.getType();
      return checkMustBeThrowable(type, parameter, true);
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkTryResourceIsAutoCloseable(@NotNull PsiResourceListElement resource) {
    PsiType type = resource.getType();
    if (type == null) return null;

    PsiElementFactory factory = JavaPsiFacade.getElementFactory(resource.getProject());
    PsiClassType autoCloseable = factory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_AUTO_CLOSEABLE, resource.getResolveScope());
    if (TypeConversionUtil.isAssignable(autoCloseable, type)) return null;

    return createIncompatibleTypeHighlightInfo(autoCloseable, type, resource.getTextRange(), 0);
  }

  @Nullable
  static HighlightInfo checkResourceVariableIsFinal(@NotNull PsiResourceExpression resource) {
    PsiExpression expression = resource.getExpression();

    if (expression instanceof PsiThisExpression) return null;

    if (expression instanceof PsiReferenceExpression) {
      PsiElement target = ((PsiReferenceExpression)expression).resolve();
      if (target == null) return null;

      if (target instanceof PsiVariable) {
        PsiVariable variable = (PsiVariable)target;

        PsiModifierList modifierList = variable.getModifierList();
        if (modifierList != null && modifierList.hasModifierProperty(PsiModifier.FINAL)) return null;

        if (!(variable instanceof PsiField) && HighlightControlFlowUtil.isEffectivelyFinal(variable, resource, (PsiJavaCodeReferenceElement)expression)) return null;
      }

      String text = JavaErrorMessages.message("resource.variable.must.be.final");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(text).create();
    }

    String text = JavaErrorMessages.message("declaration.or.variable.expected");
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(text).create();
  }

  @Nullable
  static Collection<HighlightInfo> checkArrayInitializer(@NotNull PsiExpression initializer, @Nullable PsiType type) {
    if (!(initializer instanceof PsiArrayInitializerExpression)) return null;
    if (!(type instanceof PsiArrayType)) return null;

    final PsiType componentType = ((PsiArrayType)type).getComponentType();
    final PsiArrayInitializerExpression arrayInitializer = (PsiArrayInitializerExpression)initializer;

    boolean arrayTypeFixChecked = false;
    VariableArrayTypeFix fix = null;

    final Collection<HighlightInfo> result = new ArrayList<>();
    final PsiExpression[] initializers = arrayInitializer.getInitializers();
    for (PsiExpression expression : initializers) {
      final HighlightInfo info = checkArrayInitializerCompatibleTypes(expression, componentType);
      if (info != null) {
        result.add(info);

        if (!arrayTypeFixChecked) {
          final PsiType checkResult = JavaHighlightUtil.sameType(initializers);
          fix = checkResult != null ? VariableArrayTypeFix.createFix(arrayInitializer, checkResult) : null;
          arrayTypeFixChecked = true;
        }
        if (fix != null) {
          QuickFixAction.registerQuickFixAction(info, new LocalQuickFixOnPsiElementAsIntentionAdapter(fix));
        }
      }
    }
    return result;
  }

  @Nullable
  private static HighlightInfo checkArrayInitializerCompatibleTypes(@NotNull PsiExpression initializer, @NotNull PsiType componentType) {
    PsiType initializerType = initializer.getType();
    if (initializerType == null) {
      String description = JavaErrorMessages.message("illegal.initializer", JavaHighlightUtil.formatType(componentType));
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(initializer).descriptionAndTooltip(description).create();
    }
    PsiExpression expression = initializer instanceof PsiArrayInitializerExpression ? null : initializer;
    return checkAssignability(componentType, initializerType, expression, initializer);
  }

  @Nullable
  static HighlightInfo checkExpressionRequired(@NotNull PsiReferenceExpression expression, @NotNull JavaResolveResult resultForIncompleteCode) {
    if (expression.getNextSibling() instanceof PsiErrorElement) return null;

    PsiElement resolved = resultForIncompleteCode.getElement();
    if (resolved == null || resolved instanceof PsiVariable) return null;

    PsiElement parent = expression.getParent();
    if (parent instanceof PsiReferenceExpression || parent instanceof PsiMethodCallExpression || parent instanceof PsiBreakStatement) return null;

    String description = JavaErrorMessages.message("expression.expected");
    HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description).create();
    UnresolvedReferenceQuickFixProvider.registerReferenceFixes(expression, new QuickFixActionRegistrarImpl(info));
    return info;
  }

  @Nullable
  static HighlightInfo checkArrayInitializerApplicable(@NotNull PsiArrayInitializerExpression expression) {
    /*
    JLS 10.6 Array Initializers
    An array initializer may be specified in a declaration, or as part of an array creation expression
    */
    PsiElement parent = expression.getParent();
    if (parent instanceof PsiVariable) {
      PsiVariable variable = (PsiVariable)parent;
      PsiTypeElement typeElement = variable.getTypeElement();
      boolean isInferredType = typeElement != null && typeElement.isInferredType();
      if (!isInferredType && variable.getType() instanceof PsiArrayType) return null;
    }
    else if (parent instanceof PsiNewExpression || parent instanceof PsiArrayInitializerExpression) {
      return null;
    }

    String description = JavaErrorMessages.message("array.initializer.not.allowed");
    HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description).create();
    QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAddNewArrayExpressionFix(expression));
    return info;
  }


  @Nullable
  static HighlightInfo checkCaseStatement(@NotNull PsiSwitchLabelStatementBase statement) {
    PsiSwitchBlock switchBlock = statement.getEnclosingSwitchBlock();
    if (switchBlock == null) {
      String description = JavaErrorMessages.message("case.statement.outside.switch");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
    }

    return null;
  }

  @Nullable
  static Collection<HighlightInfo> checkSwitchLabelValues(@NotNull PsiSwitchBlock switchBlock) {
    PsiCodeBlock body = switchBlock.getBody();
    if (body == null) return null;

    PsiExpression selectorExpression = switchBlock.getExpression();
    PsiType selectorType = selectorExpression == null ? PsiType.INT : selectorExpression.getType();
    MultiMap<Object, PsiElement> values = new MultiMap<>();
    Object defaultValue = new Object();
    Collection<HighlightInfo> results = new ArrayList<>();
    boolean hasDefaultCase = false;

    for (PsiStatement st : body.getStatements()) {
      if (!(st instanceof PsiSwitchLabelStatementBase)) continue;
      PsiSwitchLabelStatementBase labelStatement = (PsiSwitchLabelStatementBase)st;
      boolean defaultCase = labelStatement.isDefaultCase();

      if (defaultCase) {
        values.putValue(defaultValue, ObjectUtils.notNull(labelStatement.getFirstChild(), labelStatement));
        hasDefaultCase = true;
      }
      else {
        PsiExpressionList expressionList = labelStatement.getCaseValues();
        if (expressionList != null) {
          for (PsiExpression expr : expressionList.getExpressions()) {
            if (selectorExpression != null) {
              HighlightInfo result = checkAssignability(selectorType, expr.getType(), expr, expr);
              if (result != null) {
                results.add(result);
                continue;
              }
            }

            Object value = null;
            if (expr instanceof PsiReferenceExpression) {
              PsiElement element = ((PsiReferenceExpression)expr).resolve();
              if (element instanceof PsiEnumConstant) {
                value = ((PsiEnumConstant)element).getName();
                if (((PsiReferenceExpression)expr).getQualifier() != null) {
                  String message = JavaErrorMessages.message("qualified.enum.constant.in.switch");
                  results.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expr).descriptionAndTooltip(message).create());
                  continue;
                }
              }
            }
            if (value == null) {
              value = ConstantExpressionUtil.computeCastTo(expr, selectorType);
            }
            if (value == null) {
              String description = JavaErrorMessages.message("constant.expression.required");
              results.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expr).descriptionAndTooltip(description).create());
              continue;
            }

            values.putValue(value, expr);
          }
        }
      }
    }

    for (Map.Entry<Object, Collection<PsiElement>> entry : values.entrySet()) {
      if (entry.getValue().size() > 1) {
        Object value = entry.getKey();
        String description = value == defaultValue ? JavaErrorMessages.message("duplicate.default.switch.label") : JavaErrorMessages.message("duplicate.switch.label", value);
        for (PsiElement element : entry.getValue()) {
          results.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(description).create());
        }
      }
    }

    if (results.isEmpty() && switchBlock instanceof PsiSwitchExpression) {
      Set<String> missingConstants = new HashSet<>();
      boolean exhaustive = hasDefaultCase;
      if (!exhaustive) {
        if (!values.isEmpty() && selectorType instanceof PsiClassType) {
          PsiClass type = ((PsiClassType)selectorType).resolve();
          if (type != null && type.isEnum()) {
            for (PsiField field : type.getFields()) {
              if (field instanceof PsiEnumConstant && !values.containsKey(field.getName())) {
                missingConstants.add(field.getName());
              }
            }
            exhaustive = missingConstants.isEmpty();
          }
        }
      }
      if (!exhaustive) {
        PsiElement range = ObjectUtils.notNull(selectorExpression, switchBlock);
        String message = JavaErrorMessages.message(values.isEmpty() ? "switch.expr.empty" : "switch.expr.incomplete");
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(range).descriptionAndTooltip(message).create();
        if (!missingConstants.isEmpty()) {
          QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAddMissingEnumBranchesFix(switchBlock, missingConstants));
        }
        QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createAddSwitchDefaultFix(switchBlock, null));
        results.add(info);
      }
    }

    return results;
  }

  @Nullable
  static Collection<HighlightInfo> checkSwitchExpressionHasResult(@NotNull PsiSwitchExpression switchExpression) {
    PsiCodeBlock switchBody = switchExpression.getBody();
    if (switchBody != null) {
      PsiStatement lastStatement = PsiTreeUtil.getPrevSiblingOfType(switchBody.getRBrace(), PsiStatement.class);
      if (lastStatement instanceof PsiSwitchLabeledRuleStatement) {
        Collection<HighlightInfo> results = new ArrayList<>();
        for (PsiSwitchLabeledRuleStatement rule = (PsiSwitchLabeledRuleStatement)lastStatement;
             rule != null;
             rule = PsiTreeUtil.getPrevSiblingOfType(rule, PsiSwitchLabeledRuleStatement.class)) {
          PsiStatement ruleBody = rule.getBody();
          // the expression and throw statements are fine, only the block statement could be an issue
          if (ruleBody instanceof PsiBlockStatement && ControlFlowUtils.statementMayCompleteNormally(ruleBody)) {
            PsiElement target = ObjectUtils.notNull(ObjectUtils.tryCast(rule.getFirstChild(), PsiKeyword.class), rule);
            String message = JavaErrorMessages.message("switch.expr.rule.should.produce.result");
            results.add(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(target).descriptionAndTooltip(message).create());
          }
        }
        return results;
      }
      // previous statements may have no result as well, but in that case they fall through to the last one, which needs to be checked anyway
      if (lastStatement != null && ControlFlowUtils.statementMayCompleteNormally(lastStatement)) {
        PsiElement target = ObjectUtils.notNull(ObjectUtils.tryCast(switchExpression.getFirstChild(), PsiKeyword.class), switchExpression);
        String message = JavaErrorMessages.message("switch.expr.should.produce.result");
        return Collections.singletonList(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(target).descriptionAndTooltip(message).create());
      }
    }
    return null;
  }

  /**
   * See JLS 8.3.2.3.
   */
  @Nullable
  static HighlightInfo checkIllegalForwardReferenceToField(@NotNull PsiReferenceExpression expression, @NotNull PsiField referencedField) {
    Boolean isIllegalForwardReference = isIllegalForwardReferenceToField(expression, referencedField, false);
    if (isIllegalForwardReference == null) return null;
    String description = JavaErrorMessages.message(isIllegalForwardReference ? "illegal.forward.reference" : "illegal.self.reference");
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description).create();
  }

  public static Boolean isIllegalForwardReferenceToField(@NotNull PsiReferenceExpression expression,
                                                         @NotNull PsiField referencedField,
                                                         boolean acceptQualified) {
    PsiClass containingClass = referencedField.getContainingClass();
    if (containingClass == null) return null;
    if (expression.getContainingFile() != referencedField.getContainingFile()) return null;
    TextRange fieldRange = referencedField.getTextRange();
    if (fieldRange == null || expression.getTextRange().getStartOffset() >= fieldRange.getEndOffset()) return null;
    // only simple reference can be illegal
    if (!acceptQualified && expression.getQualifierExpression() != null) return null;
    PsiField initField = findEnclosingFieldInitializer(expression);
    PsiClassInitializer classInitializer = findParentClassInitializer(expression);
    if (initField == null && classInitializer == null) return null;
    // instance initializers may access static fields
    boolean isStaticClassInitializer = classInitializer != null && classInitializer.hasModifierProperty(PsiModifier.STATIC);
    boolean isStaticInitField = initField != null && initField.hasModifierProperty(PsiModifier.STATIC);
    boolean inStaticContext = isStaticInitField || isStaticClassInitializer;
    if (!inStaticContext && referencedField.hasModifierProperty(PsiModifier.STATIC)) return null;
    if (PsiUtil.isOnAssignmentLeftHand(expression) && !PsiUtil.isAccessedForReading(expression)) return null;
    if (!containingClass.getManager().areElementsEquivalent(containingClass, PsiTreeUtil.getParentOfType(expression, PsiClass.class))) {
      return null;
    }
    return initField != referencedField;
  }

  /**
   * @return field that has initializer with this element as subexpression or null if not found
   */
  @Nullable
  static PsiField findEnclosingFieldInitializer(@NotNull PsiElement entry) {
    PsiElement element = entry;
    while (element != null) {
      PsiElement parent = element.getParent();
      if (parent instanceof PsiField) {
        PsiField field = (PsiField)parent;
        if (element == field.getInitializer()) return field;
        if (field instanceof PsiEnumConstant && element == ((PsiEnumConstant)field).getArgumentList()) return field;
      }
      if (element instanceof PsiClass || element instanceof PsiMethod) return null;
      element = parent;
    }
    return null;
  }

  @Nullable
  private static PsiClassInitializer findParentClassInitializer(@NotNull PsiElement root) {
    PsiElement element = root;
    while (element != null) {
      if (element instanceof PsiClassInitializer) return (PsiClassInitializer)element;
      if (element instanceof PsiClass || element instanceof PsiMethod) return null;
      element = element.getParent();
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkIllegalType(@NotNull PsiTypeElement typeElement) {
    if (typeElement.getParent() instanceof PsiTypeElement) return null;

    if (PsiUtil.isInsideJavadocComment(typeElement)) return null;

    PsiType type = typeElement.getType();
    PsiType componentType = type.getDeepComponentType();
    if (componentType instanceof PsiClassType) {
      PsiClass aClass = PsiUtil.resolveClassInType(componentType);
      if (aClass == null) {
        String canonicalText = type.getCanonicalText();
        String description = JavaErrorMessages.message("unknown.class", canonicalText);
        HighlightInfo info =
          HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(typeElement).descriptionAndTooltip(description).create();
        PsiJavaCodeReferenceElement referenceElement = typeElement.getInnermostComponentReferenceElement();
        if (referenceElement != null) {
          UnresolvedReferenceQuickFixProvider.registerReferenceFixes(referenceElement, new QuickFixActionRegistrarImpl(info));
        }
        return info;
      }
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkIllegalVoidType(@NotNull PsiKeyword type) {
    if (!PsiKeyword.VOID.equals(type.getText())) return null;

    PsiElement parent = type.getParent();
    if (parent instanceof PsiTypeElement) {
      PsiElement typeOwner = parent.getParent();
      if (typeOwner != null) {
        // do not highlight incomplete declarations
        if (PsiUtilCore.hasErrorElementChild(typeOwner)) return null;
      }

      if (typeOwner instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)typeOwner;
        if (method.getReturnTypeElement() == parent && PsiType.VOID.equals(method.getReturnType())) return null;
      }
      else if (typeOwner instanceof PsiClassObjectAccessExpression) {
        if (TypeConversionUtil.isVoidType(((PsiClassObjectAccessExpression)typeOwner).getOperand().getType())) return null;
      }
      else if (typeOwner instanceof JavaCodeFragment) {
        if (typeOwner.getUserData(PsiUtil.VALID_VOID_TYPE_IN_CODE_FRAGMENT) != null) return null;
      }
    }

    String description = JavaErrorMessages.message("illegal.type.void");
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(type).descriptionAndTooltip(description).create();
  }

  @Nullable
  static HighlightInfo checkMemberReferencedBeforeConstructorCalled(@NotNull PsiElement expression,
                                                                    @Nullable PsiElement resolved,
                                                                    @NotNull PsiFile containingFile) {
    PsiClass referencedClass;
    String resolvedName;
    PsiType type;
    if (expression instanceof PsiJavaCodeReferenceElement) {
      // redirected ctr
      if (PsiKeyword.THIS.equals(((PsiJavaCodeReferenceElement)expression).getReferenceName())
          && resolved instanceof PsiMethod
          && ((PsiMethod)resolved).isConstructor()) {
        return null;
      }
      PsiElement qualifier = ((PsiJavaCodeReferenceElement)expression).getQualifier();
      type = qualifier instanceof PsiExpression ? ((PsiExpression)qualifier).getType() : null;
      referencedClass = PsiUtil.resolveClassInType(type);

      boolean isSuperCall = JavaPsiConstructorUtil.isSuperConstructorCall(expression.getParent());
      if (resolved == null && isSuperCall) {
        if (qualifier instanceof PsiReferenceExpression) {
          resolved = ((PsiReferenceExpression)qualifier).resolve();
          expression = qualifier;
          type = ((PsiReferenceExpression)qualifier).getType();
          referencedClass = PsiUtil.resolveClassInType(type);
        }
        else if (qualifier == null) {
          resolved = PsiTreeUtil.getParentOfType(expression, PsiMethod.class, true, PsiMember.class);
          if (resolved != null) {
            referencedClass = ((PsiMethod)resolved).getContainingClass();
          }
        } else if (qualifier instanceof PsiThisExpression) {
          referencedClass = PsiUtil.resolveClassInType(((PsiThisExpression)qualifier).getType());
        }
      }
      if (resolved instanceof PsiField) {
        PsiField referencedField = (PsiField)resolved;
        if (referencedField.hasModifierProperty(PsiModifier.STATIC)) return null;
        resolvedName = PsiFormatUtil.formatVariable(referencedField, PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME, PsiSubstitutor.EMPTY);
        referencedClass = referencedField.getContainingClass();
      }
      else if (resolved instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)resolved;
        if (method.hasModifierProperty(PsiModifier.STATIC)) return null;
        PsiElement nameElement = expression instanceof PsiThisExpression ? expression : ((PsiJavaCodeReferenceElement)expression).getReferenceNameElement();
        String name = nameElement == null ? null : nameElement.getText();
        if (isSuperCall) {
          if (referencedClass == null) return null;
          if (qualifier == null) {
            PsiClass superClass = referencedClass.getSuperClass();
            if (superClass != null
                && PsiUtil.isInnerClass(superClass)
                && InheritanceUtil.isInheritorOrSelf(referencedClass, superClass.getContainingClass(), true)) {
              // by default super() is considered "this"-qualified
              resolvedName = PsiKeyword.THIS;
            }
            else {
              return null;
            }
          }
          else {
            resolvedName = qualifier.getText();
          }
        }
        else if (PsiKeyword.THIS.equals(name)) {
          resolvedName = PsiKeyword.THIS;
        }
        else {
          resolvedName = PsiFormatUtil.formatMethod(method, PsiSubstitutor.EMPTY, PsiFormatUtilBase.SHOW_CONTAINING_CLASS |
                                                                                  PsiFormatUtilBase.SHOW_NAME, 0);
          if (referencedClass == null) referencedClass = method.getContainingClass();
        }
      }
      else if (resolved instanceof PsiClass) {
        PsiClass aClass = (PsiClass)resolved;
        if (aClass.hasModifierProperty(PsiModifier.STATIC)) return null;
        referencedClass = aClass.getContainingClass();
        if (referencedClass == null) return null;
        resolvedName = PsiFormatUtil.formatClass(aClass, PsiFormatUtilBase.SHOW_NAME);
      }
      else {
        return null;
      }
    }
    else if (expression instanceof PsiThisExpression) {
      PsiThisExpression thisExpression = (PsiThisExpression)expression;
      type = thisExpression.getType();
      referencedClass = PsiUtil.resolveClassInType(type);
      if (thisExpression.getQualifier() != null) {
        resolvedName = referencedClass == null
                       ? null
                       : PsiFormatUtil.formatClass(referencedClass, PsiFormatUtilBase.SHOW_NAME) + ".this";
      }
      else {
        resolvedName = "this";
      }
    }
    else {
      return null;
    }

    if (referencedClass == null ||
        PsiTreeUtil.getParentOfType(expression, PsiReferenceParameterList.class, true, PsiExpression.class) != null) {
      return null;
    }

    PsiElement element = expression.getParent();
    while (element != null) {
      // check if expression inside super()/this() call
      if (JavaPsiConstructorUtil.isConstructorCall(element)) {
        PsiElement parentClass = new PsiMatcherImpl(element)
          .parent(PsiMatchers.hasClass(PsiExpressionStatement.class))
          .parent(PsiMatchers.hasClass(PsiCodeBlock.class))
          .parent(PsiMatchers.hasClass(PsiMethod.class))
          .dot(JavaMatchers.isConstructor(true))
          .parent(PsiMatchers.hasClass(PsiClass.class))
          .getElement();
        if (parentClass == null) {
          return null;
        }

        // only this class/superclasses instance methods are not allowed to call
        PsiClass aClass = (PsiClass)parentClass;
        if (PsiUtil.isInnerClass(aClass) && referencedClass == aClass.getContainingClass()) return null;
        // field or method should be declared in this class or super
        if (!InheritanceUtil.isInheritorOrSelf(aClass, referencedClass, true)) return null;
        // and point to our instance
        if (expression instanceof PsiReferenceExpression &&
            !thisOrSuperReference(((PsiReferenceExpression)expression).getQualifierExpression(), aClass)) {
          return null;
        }

        if (expression instanceof PsiJavaCodeReferenceElement &&
            !aClass.equals(PsiTreeUtil.getParentOfType(expression, PsiClass.class)) &&
            PsiTreeUtil.getParentOfType(expression, PsiTypeElement.class) != null) {
          return null;
        }

        if (expression instanceof PsiJavaCodeReferenceElement &&
            PsiTreeUtil.getParentOfType(expression, PsiClassObjectAccessExpression.class) != null) {
          return null;
        }

        final HighlightInfo highlightInfo = createMemberReferencedError(resolvedName, expression.getTextRange());
        if (expression instanceof PsiReferenceExpression && PsiUtil.isInnerClass(aClass)) {
          final String referenceName = ((PsiReferenceExpression)expression).getReferenceName();
          final PsiClass containingClass = aClass.getContainingClass();
          LOG.assertTrue(containingClass != null);
          final PsiField fieldInContainingClass = containingClass.findFieldByName(referenceName, true);
          if (fieldInContainingClass != null && ((PsiReferenceExpression)expression).getQualifierExpression() == null) {
            QuickFixAction.registerQuickFixAction(highlightInfo, new QualifyWithThisFix(containingClass, expression));
          }
        }

        return highlightInfo;
      }

      if (element instanceof PsiReferenceExpression) {
        final PsiElement resolve;
        if (element instanceof PsiReferenceExpressionImpl) {
          PsiReferenceExpressionImpl referenceExpression = (PsiReferenceExpressionImpl)element;
          JavaResolveResult[] results = JavaResolveUtil
            .resolveWithContainingFile(referenceExpression, PsiReferenceExpressionImpl.OurGenericsResolver.INSTANCE, true, false, containingFile);
          resolve = results.length == 1 ? results[0].getElement() : null;
        }
        else {
          resolve = ((PsiReferenceExpression)element).resolve();
        }
        if (resolve instanceof PsiField && ((PsiField)resolve).hasModifierProperty(PsiModifier.STATIC)) {
          return null;
        }
      }

      element = element.getParent();
      if (element instanceof PsiClass && InheritanceUtil.isInheritorOrSelf((PsiClass)element, referencedClass, true)) return null;
    }
    return null;
  }

  private static HighlightInfo createMemberReferencedError(@NotNull String resolvedName, @NotNull TextRange textRange) {
    String description = JavaErrorMessages.message("member.referenced.before.constructor.called", resolvedName);
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).descriptionAndTooltip(description).create();
  }

  @Nullable
  static HighlightInfo checkImplicitThisReferenceBeforeSuper(@NotNull PsiClass aClass, @NotNull JavaSdkVersion javaSdkVersion) {
    if (javaSdkVersion.isAtLeast(JavaSdkVersion.JDK_1_7)) return null;
    if (aClass instanceof PsiAnonymousClass || aClass instanceof PsiTypeParameter) return null;
    PsiClass superClass = aClass.getSuperClass();
    if (superClass == null || !PsiUtil.isInnerClass(superClass)) return null;
    PsiClass outerClass = superClass.getContainingClass();
    if (!InheritanceUtil.isInheritorOrSelf(aClass, outerClass, true)) {
      return null;
    }
    // 'this' can be used as an (implicit) super() qualifier
    PsiMethod[] constructors = aClass.getConstructors();
    if (constructors.length == 0) {
      TextRange range = HighlightNamesUtil.getClassDeclarationTextRange(aClass);
      return createMemberReferencedError(aClass.getName() + ".this", range);
    }
    for (PsiMethod constructor : constructors) {
      PsiMethodCallExpression call = JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(constructor);
      if (!JavaPsiConstructorUtil.isSuperConstructorCall(call)) {
        return createMemberReferencedError(aClass.getName() + ".this", HighlightNamesUtil.getMethodDeclarationTextRange(constructor));
      }
    }
    return null;
  }

  private static boolean thisOrSuperReference(@Nullable PsiExpression qualifierExpression, @NotNull PsiClass aClass) {
    if (qualifierExpression == null) return true;
    PsiJavaCodeReferenceElement qualifier;
    if (qualifierExpression instanceof PsiThisExpression) {
      qualifier = ((PsiThisExpression)qualifierExpression).getQualifier();
    }
    else if (qualifierExpression instanceof PsiSuperExpression) {
      qualifier = ((PsiSuperExpression)qualifierExpression).getQualifier();
    }
    else {
      return false;
    }
    if (qualifier == null) return true;
    PsiElement resolved = qualifier.resolve();
    return resolved instanceof PsiClass && InheritanceUtil.isInheritorOrSelf(aClass, (PsiClass)resolved, true);
  }


  @Nullable
  static HighlightInfo checkLabelWithoutStatement(@NotNull PsiLabeledStatement statement) {
    if (statement.getStatement() == null) {
      String description = JavaErrorMessages.message("label.without.statement");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkLabelAlreadyInUse(@NotNull PsiLabeledStatement statement) {
    PsiIdentifier identifier = statement.getLabelIdentifier();
    String text = identifier.getText();
    PsiElement element = statement;
    while (element != null) {
      if (element instanceof PsiMethod || element instanceof PsiClass) break;
      if (element instanceof PsiLabeledStatement && element != statement &&
          Comparing.equal(((PsiLabeledStatement)element).getLabelIdentifier().getText(), text)) {
        String description = JavaErrorMessages.message("duplicate.label", text);
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(identifier).descriptionAndTooltip(description).create();
      }
      element = element.getParent();
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkUnclosedComment(@NotNull PsiComment comment) {
    if (!(comment instanceof PsiDocComment) && comment.getTokenType() != JavaTokenType.C_STYLE_COMMENT) return null;
    if (!comment.getText().endsWith("*/")) {
      int start = comment.getTextRange().getEndOffset() - 1;
      int end = start + 1;
      String description = JavaErrorMessages.message("unclosed.comment");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(start, end).descriptionAndTooltip(description).create();
    }
    return null;
  }


  @Nullable
  static Collection<HighlightInfo> checkCatchTypeIsDisjoint(@NotNull final PsiParameter parameter) {
    if (!(parameter.getType() instanceof PsiDisjunctionType)) return null;

    final Collection<HighlightInfo> result = new ArrayList<>();
    final List<PsiTypeElement> typeElements = PsiUtil.getParameterTypeElements(parameter);
    for (int i = 0, size = typeElements.size(); i < size; i++) {
      final PsiClass class1 = PsiUtil.resolveClassInClassTypeOnly(typeElements.get(i).getType());
      if (class1 == null) continue;
      for (int j = i + 1; j < size; j++) {
        final PsiClass class2 = PsiUtil.resolveClassInClassTypeOnly(typeElements.get(j).getType());
        if (class2 == null) continue;
        final boolean sub = InheritanceUtil.isInheritorOrSelf(class1, class2, true);
        final boolean sup = InheritanceUtil.isInheritorOrSelf(class2, class1, true);
        if (sub || sup) {
          final String name1 = PsiFormatUtil.formatClass(class1, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_FQ_NAME);
          final String name2 = PsiFormatUtil.formatClass(class2, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_FQ_NAME);
          final String message = JavaErrorMessages.message("exception.must.be.disjoint", sub ? name1 : name2, sub ? name2 : name1);
          final PsiTypeElement element = typeElements.get(sub ? i : j);
          final HighlightInfo highlight =
            HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(message).create();
          QuickFixAction.registerQuickFixAction(highlight, QUICK_FIX_FACTORY.createDeleteMultiCatchFix(element));
          result.add(highlight);
          break;
        }
      }
    }

    return result;
  }


  @Nullable
  static Collection<HighlightInfo> checkExceptionAlreadyCaught(@NotNull final PsiParameter parameter) {
    final PsiElement scope = parameter.getDeclarationScope();
    if (!(scope instanceof PsiCatchSection)) return null;

    final PsiCatchSection catchSection = (PsiCatchSection)scope;
    final PsiCatchSection[] allCatchSections = catchSection.getTryStatement().getCatchSections();
    final int startFrom = ArrayUtilRt.find(allCatchSections, catchSection) - 1;
    if (startFrom < 0) return null;

    final List<PsiTypeElement> typeElements = PsiUtil.getParameterTypeElements(parameter);
    final boolean isInMultiCatch = typeElements.size() > 1;
    final Collection<HighlightInfo> result = new ArrayList<>();

    for (PsiTypeElement typeElement : typeElements) {
      final PsiClass catchClass = PsiUtil.resolveClassInClassTypeOnly(typeElement.getType());
      if (catchClass == null) continue;

      for (int i = startFrom; i >= 0; i--) {
        final PsiCatchSection upperCatchSection = allCatchSections[i];
        final PsiType upperCatchType = upperCatchSection.getCatchType();

        final boolean highlight = upperCatchType instanceof PsiDisjunctionType
                                  ? checkMultipleTypes(catchClass, ((PsiDisjunctionType)upperCatchType).getDisjunctions())
                                  : checkSingleType(catchClass, upperCatchType);
        if (highlight) {
          final String className = PsiFormatUtil.formatClass(catchClass, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_FQ_NAME);
          final String description = JavaErrorMessages.message("exception.already.caught", className);
          final HighlightInfo highlightInfo =
            HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(typeElement).descriptionAndTooltip(description).create();
          result.add(highlightInfo);

          QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createMoveCatchUpFix(catchSection, upperCatchSection));
          if (isInMultiCatch) {
            QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createDeleteMultiCatchFix(typeElement));
          }
          else {
            QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createDeleteCatchFix(parameter));
          }
        }
      }
    }

    return result.isEmpty() ? null : result;
  }

  private static boolean checkMultipleTypes(@NotNull PsiClass catchClass, @NotNull final List<? extends PsiType> upperCatchTypes) {
    for (int i = upperCatchTypes.size() - 1; i >= 0; i--) {
      if (checkSingleType(catchClass, upperCatchTypes.get(i))) return true;
    }
    return false;
  }

  private static boolean checkSingleType(@NotNull PsiClass catchClass, @Nullable PsiType upperCatchType) {
    final PsiClass upperCatchClass = PsiUtil.resolveClassInType(upperCatchType);
    return upperCatchClass != null && InheritanceUtil.isInheritorOrSelf(catchClass, upperCatchClass, true);
  }


  @Nullable
  static HighlightInfo checkTernaryOperatorConditionIsBoolean(@NotNull PsiExpression expression, @Nullable PsiType type) {
    if (expression.getParent() instanceof PsiConditionalExpression &&
        ((PsiConditionalExpression)expression.getParent()).getCondition() == expression && !TypeConversionUtil.isBooleanType(type)) {
      return createMustBeBooleanInfo(expression, type);
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkSwitchBlockStatements(@NotNull PsiSwitchBlock switchBlock,
                                                  @NotNull LanguageLevel languageLevel,
                                                  @NotNull PsiFile file) {
    PsiCodeBlock body = switchBlock.getBody();
    if (body != null) {
      PsiElement first = PsiTreeUtil.skipWhitespacesAndCommentsForward(body.getLBrace());
      if (first != null && !(first instanceof PsiSwitchLabelStatementBase) && !PsiUtil.isJavaToken(first, JavaTokenType.RBRACE)) {
        String description = JavaErrorMessages.message("statement.must.be.prepended.with.case.label");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(first).descriptionAndTooltip(description).create();
      }

      PsiElement element = first;
      PsiStatement alien = null;
      boolean classicLabels = false, enhancedLabels = false, levelChecked = false;
      while (element != null && !PsiUtil.isJavaToken(element, JavaTokenType.RBRACE)) {
        if (element instanceof PsiSwitchLabeledRuleStatement) {
          if (!levelChecked) {
            HighlightInfo info = checkFeature(element, Feature.ENHANCED_SWITCH, languageLevel, file);
            if (info != null) return info;
            levelChecked = true;
          }
          if (classicLabels) {
            alien = (PsiStatement)element;
            break;
          }
          enhancedLabels = true;
        }
        else if (element instanceof PsiStatement) {
          if (enhancedLabels) {
            alien = (PsiStatement)element;
            break;
          }
          classicLabels = true;
        }

        if (!levelChecked && element instanceof PsiSwitchLabelStatementBase) {
          PsiExpressionList values = ((PsiSwitchLabelStatementBase)element).getCaseValues();
          if (values != null && values.getExpressionCount() > 1) {
            HighlightInfo info = checkFeature(values, Feature.ENHANCED_SWITCH, languageLevel, file);
            if (info != null) return info;
            levelChecked = true;
          }
        }

        element = PsiTreeUtil.skipWhitespacesAndCommentsForward(element);
      }
      if (alien != null) {
        if (enhancedLabels && !(alien instanceof PsiSwitchLabelStatementBase)) {
          PsiSwitchLabeledRuleStatement previousRule = PsiTreeUtil.getPrevSiblingOfType(alien, PsiSwitchLabeledRuleStatement.class);
          String description = JavaErrorMessages.message("statement.must.be.prepended.with.case.label");
          HighlightInfo info =
            HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(alien).descriptionAndTooltip(description).create();
          if (previousRule != null) {
            QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createWrapSwitchRuleStatementsIntoBlockFix(previousRule));
          }
          return info;
        }
        String description = JavaErrorMessages.message("different.case.kinds.in.switch");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(alien).descriptionAndTooltip(description).create();
      }
    }

    return null;
  }


  @Nullable
  static HighlightInfo checkAssertOperatorTypes(@NotNull PsiExpression expression, @Nullable PsiType type) {
    if (type == null) return null;
    if (!(expression.getParent() instanceof PsiAssertStatement)) {
      return null;
    }
    PsiAssertStatement assertStatement = (PsiAssertStatement)expression.getParent();
    if (expression == assertStatement.getAssertCondition() && !TypeConversionUtil.isBooleanType(type)) {
      // addTypeCast quickfix is not applicable here since no type can be cast to boolean
      HighlightInfo highlightInfo = createIncompatibleTypeHighlightInfo(PsiType.BOOLEAN, type, expression.getTextRange(), 0);
      if (expression instanceof PsiAssignmentExpression && ((PsiAssignmentExpression)expression).getOperationTokenType() == JavaTokenType.EQ) {
        QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createAssignmentToComparisonFix((PsiAssignmentExpression)expression));
      }
      return highlightInfo;
    }
    if (expression == assertStatement.getAssertDescription() && TypeConversionUtil.isVoidType(type)) {
      String description = JavaErrorMessages.message("void.type.is.not.allowed");
      return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(expression).descriptionAndTooltip(description).create();
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkSynchronizedExpressionType(@NotNull PsiExpression expression,
                                                       @Nullable PsiType type,
                                                       @NotNull PsiFile containingFile) {
    if (type == null) return null;
    if (expression.getParent() instanceof PsiSynchronizedStatement) {
      PsiSynchronizedStatement synchronizedStatement = (PsiSynchronizedStatement)expression.getParent();
      if (expression == synchronizedStatement.getLockExpression() &&
          (type instanceof PsiPrimitiveType || TypeConversionUtil.isNullType(type))) {
        PsiClassType objectType = PsiType.getJavaLangObject(containingFile.getManager(), expression.getResolveScope());
        return createIncompatibleTypeHighlightInfo(objectType, type, expression.getTextRange(), 0);
      }
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkConditionalExpressionBranchTypesMatch(@NotNull final PsiExpression expression, @Nullable PsiType type) {
    PsiElement parent = expression.getParent();
    if (!(parent instanceof PsiConditionalExpression)) {
      return null;
    }
    PsiConditionalExpression conditionalExpression = (PsiConditionalExpression)parent;
    // check else branches only
    if (conditionalExpression.getElseExpression() != expression) return null;
    final PsiExpression thenExpression = conditionalExpression.getThenExpression();
    assert thenExpression != null;
    PsiType thenType = thenExpression.getType();
    if (thenType == null || type == null) return null;
    if (conditionalExpression.getType() == null) {
      if (PsiUtil.isLanguageLevel8OrHigher(conditionalExpression) && PsiPolyExpressionUtil.isPolyExpression(conditionalExpression)) {
        return null;
      }
      // cannot derive type of conditional expression
      // elseType will never be cast-able to thenType, so no quick fix here
      return createIncompatibleTypeHighlightInfo(thenType, type, expression.getTextRange(), 0);
    }
    return null;
  }

  @Nullable
  static HighlightInfo createIncompatibleTypeHighlightInfo(PsiType lType, PsiType rType, @NotNull TextRange textRange, int navigationShift) {
    Trinity<PsiType, PsiTypeParameter[], PsiSubstitutor> lTypeData = typeData(lType);
    Trinity<PsiType, PsiTypeParameter[], PsiSubstitutor> rTypeData = typeData(rType);
    lType = lTypeData.first;
    rType = rTypeData.first;
    PsiTypeParameter[] lTypeParams = lTypeData.second;
    PsiTypeParameter[] rTypeParams = rTypeData.second;

    int typeParamColumns = Math.max(lTypeParams.length, rTypeParams.length);
    StringBuilder requiredRow = new StringBuilder();
    StringBuilder foundRow = new StringBuilder();
    for (int i = 0; i < typeParamColumns; i++) {
      PsiTypeParameter lTypeParameter = i >= lTypeParams.length ? null : lTypeParams[i];
      PsiTypeParameter rTypeParameter = i >= rTypeParams.length ? null : rTypeParams[i];
      PsiType lSubstitutedType = lTypeParameter == null ? null : lTypeData.third.substitute(lTypeParameter);
      PsiType rSubstitutedType = rTypeParameter == null ? null : rTypeData.third.substitute(rTypeParameter);
      boolean matches = Comparing.equal(lSubstitutedType, rSubstitutedType);
      String openBrace = i == 0 ? "&lt;" : "";
      String closeBrace = i == typeParamColumns - 1 ? "&gt;" : ",";
      requiredRow.append("<td>").append(lTypeParams.length == 0 ? "" : openBrace).append(redIfNotMatch(lSubstitutedType, matches))
        .append(i < lTypeParams.length ? closeBrace : "").append("</td>");
      foundRow.append("<td>").append(rTypeParams.length == 0 ? "" : openBrace).append(redIfNotMatch(rSubstitutedType, matches))
        .append(i < rTypeParams.length ? closeBrace : "").append("</td>");
    }
    PsiType lRawType = lType instanceof PsiClassType ? ((PsiClassType)lType).rawType() : lType;
    PsiType rRawType = rType instanceof PsiClassType ? ((PsiClassType)rType).rawType() : rType;
    boolean assignable = lRawType == null || rRawType == null || TypeConversionUtil.isAssignable(lRawType, rRawType);
    String toolTip = JavaErrorMessages.message("incompatible.types.html.tooltip",
                                               redIfNotMatch(lRawType, assignable),
                                               requiredRow,
                                               redIfNotMatch(rRawType, assignable),
                                               foundRow,
                                               getReasonForIncompatibleTypes(rType));
    String description = JavaErrorMessages.message(
      "incompatible.types", JavaHighlightUtil.formatType(lType), JavaHighlightUtil.formatType(rType));
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(textRange).description(description).escapedToolTip(toolTip)
      .navigationShift(navigationShift).create();
  }

  private static String getReasonForIncompatibleTypes(PsiType rType) {
    if (rType instanceof PsiMethodReferenceType) {
      JavaResolveResult[] results = ((PsiMethodReferenceType)rType).getExpression().multiResolve(false);
      if (results.length > 1) {
        PsiElement element1 = results[0].getElement();
        PsiElement element2 = results[1].getElement();
        if (element1 instanceof PsiMethod && element2 instanceof PsiMethod) {
          String candidate1 = PsiFormatUtil.formatMethod((PsiMethod)element1, PsiSubstitutor.EMPTY,
                                                         PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME |
                                                         PsiFormatUtilBase.SHOW_PARAMETERS, PsiFormatUtilBase.SHOW_TYPE);
          String candidate2 = PsiFormatUtil.formatMethod((PsiMethod)element2, PsiSubstitutor.EMPTY,
                                                         PsiFormatUtilBase.SHOW_CONTAINING_CLASS | PsiFormatUtilBase.SHOW_NAME |
                                                         PsiFormatUtilBase.SHOW_PARAMETERS, PsiFormatUtilBase.SHOW_TYPE);
          return JavaErrorMessages.message("incompatible.types.reason.ambiguous.method.reference", candidate1, candidate2);
        }
      }
    }
    return "";
  }

  @NotNull
  private static Trinity<PsiType, PsiTypeParameter[], PsiSubstitutor> typeData(PsiType type) {
    PsiTypeParameter[] parameters = PsiTypeParameter.EMPTY_ARRAY;
    PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
    if (type instanceof PsiClassType) {
      PsiClassType.ClassResolveResult resolveResult = ((PsiClassType)type).resolveGenerics();
      substitutor = resolveResult.getSubstitutor();
      PsiClass psiClass = resolveResult.getElement();
      if (psiClass instanceof PsiAnonymousClass) {
        type = ((PsiAnonymousClass)psiClass).getBaseClassType();
        resolveResult = ((PsiClassType)type).resolveGenerics();
        substitutor = resolveResult.getSubstitutor();
        psiClass = resolveResult.getElement();
      }
      parameters = psiClass == null ? PsiTypeParameter.EMPTY_ARRAY : psiClass.getTypeParameters();
    }
    return Trinity.create(type, parameters, substitutor);
  }

  @NotNull
  private static String redIfNotMatch(@Nullable PsiType type, boolean matches) {
    if (matches) return getFQName(type, false);
    String color = UIUtil.isUnderDarcula() ? "FF6B68" : "red";
    return "<font color='" + color +"'><b>" + getFQName(type, true) + "</b></font>";
  }

  @NotNull
  private static String getFQName(@Nullable PsiType type, boolean longName) {
    return type == null ? "" : XmlStringUtil.escapeString(longName ? type.getInternalCanonicalText() : type.getPresentableText());
  }


  @Nullable
  static HighlightInfo checkSingleImportClassConflict(@NotNull PsiImportStatement statement,
                                                      @NotNull Map<String, Pair<PsiImportStaticReferenceElement, PsiClass>> importedClasses,
                                                      @NotNull PsiFile containingFile) {
    if (statement.isOnDemand()) return null;
    PsiElement element = statement.resolve();
    if (element instanceof PsiClass) {
      String name = ((PsiClass)element).getName();
      Pair<PsiImportStaticReferenceElement, PsiClass> imported = importedClasses.get(name);
      PsiClass importedClass = Pair.getSecond(imported);
      if (importedClass != null && !containingFile.getManager().areElementsEquivalent(importedClass, element)) {
        String description = JavaErrorMessages.message("single.import.class.conflict", formatClass(importedClass));
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(statement).descriptionAndTooltip(description).create();
      }
      importedClasses.put(name, Pair.pair(null, (PsiClass)element));
    }
    return null;
  }


  @Nullable
  static HighlightInfo checkMustBeThrowable(@NotNull PsiType type, @NotNull PsiElement context, boolean addCastIntention) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(context.getProject());
    PsiClassType throwable = factory.createTypeByFQClassName("java.lang.Throwable", context.getResolveScope());
    if (!TypeConversionUtil.isAssignable(throwable, type)) {
      HighlightInfo highlightInfo = createIncompatibleTypeHighlightInfo(throwable, type, context.getTextRange(), 0);
      if (addCastIntention && TypeConversionUtil.areTypesConvertible(type, throwable)) {
        if (context instanceof PsiExpression) {
          QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createAddTypeCastFix(throwable, (PsiExpression)context));
        }
      }

      final PsiClass aClass = PsiUtil.resolveClassInClassTypeOnly(type);
      if (aClass != null) {
        QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createExtendsListFix(aClass, throwable, true));
      }
      return highlightInfo;
    }
    return null;
  }


  @Nullable
  private static HighlightInfo checkMustBeThrowable(@NotNull PsiClass aClass, @NotNull PsiElement context) {
    PsiClassType type = JavaPsiFacade.getElementFactory(aClass.getProject()).createType(aClass);
    return checkMustBeThrowable(type, context, false);
  }

  @Nullable
  static HighlightInfo checkReference(@NotNull PsiJavaCodeReferenceElement ref,
                                      @NotNull JavaResolveResult result,
                                      @NotNull PsiFile containingFile,
                                      @NotNull LanguageLevel languageLevel) {
    PsiElement refName = ref.getReferenceNameElement();
    if (!(refName instanceof PsiIdentifier) && !(refName instanceof PsiKeyword)) return null;
    PsiElement resolved = result.getElement();

    PsiElement refParent = ref.getParent();
    PsiElement granny;
    if (refParent instanceof PsiReferenceExpression && (granny = refParent.getParent()) instanceof PsiMethodCallExpression) {
      PsiReferenceExpression referenceToMethod = ((PsiMethodCallExpression)granny).getMethodExpression();
      PsiExpression qualifierExpression = referenceToMethod.getQualifierExpression();
      if (qualifierExpression == ref && resolved != null && !(resolved instanceof PsiClass) && !(resolved instanceof PsiVariable)) {
        String message = JavaErrorMessages.message("qualifier.must.be.expression");
        return HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(qualifierExpression).descriptionAndTooltip(message).create();
      }
    }
    else if (refParent instanceof PsiMethodCallExpression) {
      return null;  // methods checked elsewhere
    }

    if (resolved == null) {
      // do not highlight unknown packages (javac does not care), Javadoc, and module references (checked elsewhere)
      PsiJavaCodeReferenceElement parent = getOuterReferenceParent(ref);
      PsiElement outerParent = parent.getParent();
      if (outerParent instanceof PsiPackageStatement ||
          result.isPackagePrefixPackageReference() ||
          PsiUtil.isInsideJavadocComment(ref) ||
          parent.resolve() instanceof PsiMember ||
          outerParent instanceof PsiPackageAccessibilityStatement) {
        return null;
      }

      JavaResolveResult[] results = ref.multiResolve(true);
      String description;
      if (results.length > 1) {
        String t1 = format(ObjectUtils.notNull(results[0].getElement()));
        String t2 = format(ObjectUtils.notNull(results[1].getElement()));
        description = JavaErrorMessages.message("ambiguous.reference", refName.getText(), t1, t2);
      }
      else {
        description = JavaErrorMessages.message("cannot.resolve.symbol", refName.getText());
      }

      HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(refName).descriptionAndTooltip(description).create();
      UnresolvedReferenceQuickFixProvider.registerReferenceFixes(ref, new QuickFixActionRegistrarImpl(info));
      return info;
    }

    boolean skipValidityChecks =
      PsiUtil.isInsideJavadocComment(ref) ||
      PsiTreeUtil.getParentOfType(ref, PsiPackageStatement.class, true) != null ||
      resolved instanceof PsiPackage && ref.getParent() instanceof PsiJavaCodeReferenceElement;
    if (!skipValidityChecks && !result.isValidResult()) {
      if (!result.isAccessible()) {
        Pair<String, List<IntentionAction>> problem = accessProblemDescriptionAndFixes(ref, resolved, result);
        boolean moduleAccessProblem = problem.second != null;
        PsiElement range = moduleAccessProblem ? findPackagePrefix(ref) : refName;
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(range).descriptionAndTooltip(problem.first).create();
        if (moduleAccessProblem) {
          problem.second.forEach(fix -> QuickFixAction.registerQuickFixAction(info, fix));
        }
        else if (result.isStaticsScopeCorrect() && resolved instanceof PsiJvmMember) {
          HighlightFixUtil.registerAccessQuickFixAction((PsiJvmMember)resolved, ref, info, result.getCurrentFileResolveScope());
          if (ref instanceof PsiReferenceExpression) {
            QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createRenameWrongRefFix((PsiReferenceExpression)ref));
          }
        }
        UnresolvedReferenceQuickFixProvider.registerReferenceFixes(ref, new QuickFixActionRegistrarImpl(info));
        return info;
      }

      if (!result.isStaticsScopeCorrect()) {
        String description = staticContextProblemDescription(resolved);
        HighlightInfo info =
          HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(refName).descriptionAndTooltip(description).create();
        HighlightFixUtil.registerStaticProblemQuickFixAction(resolved, info, ref);
        if (ref instanceof PsiReferenceExpression) {
          QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createRenameWrongRefFix((PsiReferenceExpression)ref));
        }
        return info;
      }
    }

    if ((resolved instanceof PsiLocalVariable || resolved instanceof PsiParameter) && !(resolved instanceof ImplicitVariable)) {
      return HighlightControlFlowUtil.checkVariableMustBeFinal((PsiVariable)resolved, ref, languageLevel);
    }

    if (resolved instanceof PsiClass &&
        ((PsiClass)resolved).getContainingClass() == null &&
        PsiUtil.isFromDefaultPackage(resolved) &&
        (PsiTreeUtil.getParentOfType(ref, PsiImportStatementBase.class) != null ||
         PsiUtil.isModuleFile(containingFile) ||
         !PsiUtil.isFromDefaultPackage(containingFile))) {
      String description = JavaErrorMessages.message("class.in.default.package", ((PsiClass)resolved).getName());
      return HighlightInfo.newHighlightInfo(HighlightInfoType.WRONG_REF).range(refName).descriptionAndTooltip(description).create();
    }

    return null;
  }

  @NotNull
  private static PsiElement findPackagePrefix(@NotNull PsiJavaCodeReferenceElement ref) {
    PsiElement candidate = ref;
    while (candidate instanceof PsiJavaCodeReferenceElement) {
      if (((PsiJavaCodeReferenceElement)candidate).resolve() instanceof PsiPackage) return candidate;
      candidate = ((PsiJavaCodeReferenceElement)candidate).getQualifier();
    }
    return ref;
  }

  @NotNull
  private static String format(@NotNull PsiElement element) {
    if (element instanceof PsiClass) return formatClass((PsiClass)element);
    if (element instanceof PsiMethod) return JavaHighlightUtil.formatMethod((PsiMethod)element);
    if (element instanceof PsiField) return formatField((PsiField)element);
    if (element instanceof PsiLabeledStatement) return ((PsiLabeledStatement)element).getName() + ':';
    return ElementDescriptionUtil.getElementDescription(element, HighlightUsagesDescriptionLocation.INSTANCE);
  }

  @NotNull
  private static PsiJavaCodeReferenceElement getOuterReferenceParent(@NotNull PsiJavaCodeReferenceElement ref) {
    PsiJavaCodeReferenceElement element = ref;
    while (true) {
      PsiElement parent = element.getParent();
      if (parent instanceof PsiJavaCodeReferenceElement) {
        element = (PsiJavaCodeReferenceElement)parent;
      }
      else {
        break;
      }
    }
    return element;
  }

  @Nullable
  static HighlightInfo checkPackageAndClassConflict(@NotNull PsiJavaCodeReferenceElement ref, @NotNull PsiFile containingFile) {
    if (ref.isQualified() && getOuterReferenceParent(ref).getParent() instanceof PsiPackageStatement) {
      Module module = ModuleUtilCore.findModuleForFile(containingFile);
      if (module != null) {
        GlobalSearchScope scope = module.getModuleWithDependenciesAndLibrariesScope(false);
        PsiClass aClass = JavaPsiFacade.getInstance(ref.getProject()).findClass(ref.getCanonicalText(), scope);
        if (aClass != null) {
          String message = JavaErrorMessages.message("package.clashes.with.class", ref.getText());
          return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(ref).descriptionAndTooltip(message).create();
        }
      }
    }

    return null;
  }

  @Nullable
  static HighlightInfo checkElementInReferenceList(@NotNull PsiJavaCodeReferenceElement ref,
                                                   @NotNull PsiReferenceList referenceList,
                                                   @NotNull JavaResolveResult resolveResult) {
    PsiElement resolved = resolveResult.getElement();
    HighlightInfo highlightInfo = null;
    PsiElement refGrandParent = referenceList.getParent();
    if (resolved instanceof PsiClass) {
      PsiClass aClass = (PsiClass)resolved;
      if (refGrandParent instanceof PsiClass) {
        if (refGrandParent instanceof PsiTypeParameter) {
          highlightInfo = GenericsHighlightUtil.checkElementInTypeParameterExtendsList(referenceList, (PsiClass)refGrandParent, resolveResult, ref);
        }
        else {
          highlightInfo = HighlightClassUtil.checkExtendsClassAndImplementsInterface(referenceList, resolveResult, ref);
          if (highlightInfo == null) {
            highlightInfo = HighlightClassUtil.checkCannotInheritFromFinal(aClass, ref);
          }
          if (highlightInfo == null) {
            highlightInfo = GenericsHighlightUtil.checkCannotInheritFromEnum(aClass, ref);
          }
          if (highlightInfo == null) {
            highlightInfo = GenericsHighlightUtil.checkCannotInheritFromTypeParameter(aClass, ref);
          }
        }
      }
      else if (refGrandParent instanceof PsiMethod && ((PsiMethod)refGrandParent).getThrowsList() == referenceList) {
        highlightInfo = checkMustBeThrowable(aClass, ref);
      }
    }
    else if (refGrandParent instanceof PsiMethod && referenceList == ((PsiMethod)refGrandParent).getThrowsList()) {
      String description = JavaErrorMessages.message("class.name.expected");
      highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(ref).descriptionAndTooltip(description).create();
    }
    return highlightInfo;
  }


  public static boolean isSerializationImplicitlyUsedField(@NotNull PsiField field) {
    final String name = field.getName();
    if (!SERIAL_VERSION_UID_FIELD_NAME.equals(name) && !SERIAL_PERSISTENT_FIELDS_FIELD_NAME.equals(name)) return false;
    if (!field.hasModifierProperty(PsiModifier.STATIC)) return false;
    PsiClass aClass = field.getContainingClass();
    return aClass == null || JavaHighlightUtil.isSerializable(aClass);
  }

  @Nullable
  static HighlightInfo checkClassReferenceAfterQualifier(@NotNull final PsiReferenceExpression expression, @Nullable PsiElement resolved) {
    if (!(resolved instanceof PsiClass)) return null;
    final PsiExpression qualifier = expression.getQualifierExpression();
    if (qualifier == null) return null;
    if (qualifier instanceof PsiReferenceExpression) {
      PsiElement qualifierResolved = ((PsiReferenceExpression)qualifier).resolve();
      if (qualifierResolved instanceof PsiClass || qualifierResolved instanceof PsiPackage) return null;

      if (qualifierResolved == null) {
        PsiReferenceExpression qExpression = (PsiReferenceExpression)qualifier;
        while (true) {
          PsiElement qResolve = qExpression.resolve();
          if (qResolve == null || qResolve instanceof PsiClass || qResolve instanceof PsiPackage) {
            PsiExpression qualifierExpression = qExpression.getQualifierExpression();
            if (qualifierExpression == null) return null;
            if (qualifierExpression instanceof PsiReferenceExpression) {
              qExpression = (PsiReferenceExpression)qualifierExpression;
              continue;
            }
          }
          break;
        }
      }
    }
    String description = JavaErrorMessages.message("expected.class.or.package");
    HighlightInfo info =
      HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(qualifier).descriptionAndTooltip(description).create();
    QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createRemoveQualifierFix(qualifier, expression, (PsiClass)resolved));
    return info;
  }

  @Nullable
  static HighlightInfo checkAnnotationMethodParameters(@NotNull PsiParameterList list) {
    final PsiElement parent = list.getParent();
    if (PsiUtil.isAnnotationMethod(parent) && !list.isEmpty()) {
      final String message = JavaErrorMessages.message("annotation.interface.members.may.not.have.parameters");
      final HighlightInfo highlightInfo =
        HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(list).descriptionAndTooltip(message).create();
      QuickFixAction.registerQuickFixAction(highlightInfo, QUICK_FIX_FACTORY.createRemoveParameterListFix((PsiMethod)parent));
      return highlightInfo;
    }
    return null;
  }

  @Nullable
  static HighlightInfo checkForStatement(@NotNull PsiForStatement statement) {
    PsiStatement init = statement.getInitialization();
    if (init == null ||
        init instanceof PsiEmptyStatement ||
        init instanceof PsiDeclarationStatement && ArrayUtil.getFirstElement(((PsiDeclarationStatement)init).getDeclaredElements()) instanceof PsiLocalVariable ||
        init instanceof PsiExpressionStatement ||
        init instanceof PsiExpressionListStatement) {
      return null;
    }

    String message = JavaErrorMessages.message("invalid.statement");
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(init).descriptionAndTooltip(message).create();
  }

  public enum Feature {
    GENERICS(LanguageLevel.JDK_1_5, "feature.generics"),
    ANNOTATIONS(LanguageLevel.JDK_1_5, "feature.annotations"),
    STATIC_IMPORTS(LanguageLevel.JDK_1_5, "feature.static.imports"),
    FOR_EACH(LanguageLevel.JDK_1_5, "feature.for.each"),
    VARARGS(LanguageLevel.JDK_1_5, "feature.varargs"),
    HEX_FP_LITERALS(LanguageLevel.JDK_1_5, "feature.hex.fp.literals"),
    DIAMOND_TYPES(LanguageLevel.JDK_1_7, "feature.diamond.types"),
    MULTI_CATCH(LanguageLevel.JDK_1_7, "feature.multi.catch"),
    TRY_WITH_RESOURCES(LanguageLevel.JDK_1_7, "feature.try.with.resources"),
    BIN_LITERALS(LanguageLevel.JDK_1_7, "feature.binary.literals"),
    UNDERSCORES(LanguageLevel.JDK_1_7, "feature.underscores.in.literals"),
    EXTENSION_METHODS(LanguageLevel.JDK_1_8, "feature.extension.methods"),
    METHOD_REFERENCES(LanguageLevel.JDK_1_8, "feature.method.references"),
    LAMBDA_EXPRESSIONS(LanguageLevel.JDK_1_8, "feature.lambda.expressions"),
    TYPE_ANNOTATIONS(LanguageLevel.JDK_1_8, "feature.type.annotations"),
    RECEIVERS(LanguageLevel.JDK_1_8, "feature.type.receivers"),
    INTERSECTION_CASTS(LanguageLevel.JDK_1_8, "feature.intersections.in.casts"),
    STATIC_INTERFACE_CALLS(LanguageLevel.JDK_1_8, "feature.static.interface.calls"),
    REFS_AS_RESOURCE(LanguageLevel.JDK_1_9, "feature.try.with.resources.refs"),
    MODULES(LanguageLevel.JDK_1_9, "feature.modules"),
    ENHANCED_SWITCH(LanguageLevel.JDK_12_PREVIEW, "feature.enhanced.switch"),
    SWITCH_EXPRESSION(LanguageLevel.JDK_12_PREVIEW, "feature.switch.expressions"),
    TEXT_BLOCKS(LanguageLevel.JDK_13_PREVIEW, "feature.text.blocks"),
    RAW_LITERALS(LanguageLevel.JDK_X, "feature.raw.literals");

    private final LanguageLevel level;
    private final String key;

    Feature(LanguageLevel level, @PropertyKey(resourceBundle = JavaErrorMessages.BUNDLE) String key) {
      this.level = level;
      this.key = key;
    }

    /**
     * @param element a valid PsiElement to check (it's better to supply PsiFile if already known; any element is accepted for convenience)
     * @return true if this feature is available in the PsiFile the supplied element belongs to
     */
    public boolean isAvailable(PsiElement element) {
      return isSufficient(PsiUtil.getLanguageLevel(element));
    }

    private boolean isSufficient(LanguageLevel useSiteLevel) {
      return useSiteLevel.isAtLeast(level) && (!level.isPreview() || useSiteLevel.isPreview());
    }
  }

  @Nullable
  static HighlightInfo checkFeature(@NotNull PsiElement element,
                                    @NotNull Feature feature,
                                    @NotNull LanguageLevel level,
                                    @NotNull PsiFile file) {
    if (file.getManager().isInProject(file) && !feature.isSufficient(level)) {
      String message = getUnsupportedFeatureMessage(element, feature, level, file);
      HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(element).descriptionAndTooltip(message).create();
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createIncreaseLanguageLevelFix(feature.level));
      QuickFixAction.registerQuickFixAction(info, QUICK_FIX_FACTORY.createShowModulePropertiesFix(element));
      return info;
    }

    return null;
  }

  private static String getUnsupportedFeatureMessage(@NotNull PsiElement element,
                                                     @NotNull Feature feature,
                                                     @NotNull LanguageLevel level,
                                                     @NotNull PsiFile file) {
    String name = JavaErrorMessages.message(feature.key);
    String version = JavaSdkVersion.fromLanguageLevel(level).getDescription();
    String message = JavaErrorMessages.message("insufficient.language.level", name, version);

    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    if (module != null) {
      LanguageLevel moduleLanguageLevel = EffectiveLanguageLevelUtil.getEffectiveLanguageLevel(module);
      if (moduleLanguageLevel.isAtLeast(feature.level)) {
        for (FilePropertyPusher pusher : FilePropertyPusher.EP_NAME.getExtensions()) {
          if (pusher instanceof JavaLanguageLevelPusher) {
            String newMessage = ((JavaLanguageLevelPusher)pusher).getInconsistencyLanguageLevelMessage(message, element, level, file);
            if (newMessage != null) {
              return newMessage;
            }
          }
        }
      }
    }

    return message;
  }
}