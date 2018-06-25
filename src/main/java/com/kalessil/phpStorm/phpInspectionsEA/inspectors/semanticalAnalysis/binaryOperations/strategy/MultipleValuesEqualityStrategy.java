package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis.binaryOperations.strategy;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpeanapiEquivalenceUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

final public class MultipleValuesEqualityStrategy {
    private static final String messageAlwaysTrue  = "'%s' seems to be always true.";
    private static final String messageAlwaysFalse = "'%s' seems to be always false.";

    private static final Map<IElementType, String> equalOperators    = new HashMap<>();
    private static final Map<IElementType, String> notEqualOperators = new HashMap<>();
    static {
        equalOperators.put(PhpTokenTypes.opEQUAL, "%s == %s && %s == %s");
        equalOperators.put(PhpTokenTypes.opIDENTICAL, "%s === %s && %s === %s");

        notEqualOperators.put(PhpTokenTypes.opNOT_EQUAL, "%s != %s || %s != %s");
        notEqualOperators.put(PhpTokenTypes.opNOT_IDENTICAL, "%s !== %s || %s !== %s");
    }

    public static boolean apply(@NotNull BinaryExpression expression, @NotNull ProblemsHolder holder) {
        boolean result              = false;
        final IElementType operator = expression.getOperationType();
        if (operator != null && (operator == PhpTokenTypes.opAND || operator == PhpTokenTypes.opOR)) {
            /* false-positives: part of another condition */
            final PsiElement parent  = expression.getParent();
            final PsiElement context = parent instanceof ParenthesizedExpression ? parent.getParent() : parent;
            if (!(context instanceof BinaryExpression) || ((BinaryExpression) context).getOperationType() != operator) {
                final List<PsiElement> fragments = extractFragments(expression, operator);
                if (fragments.size() > 1) {
                    final Map<IElementType, String> operators = operator == PhpTokenTypes.opAND ? equalOperators : notEqualOperators;
                    final List<BinaryExpression> filtered     = fragments.stream()
                            .filter(fragment -> fragment instanceof BinaryExpression)
                            .map(fragment    -> (BinaryExpression) fragment)
                            .filter(fragment -> operators.containsKey(fragment.getOperationType()))
                            .collect(Collectors.toList());
                    if (filtered.size() > 1) {
                        result = analyze(filtered, operator, holder);
                    }
                    filtered.clear();
                }
                fragments.clear();
            }
        }
        return result;
    }

    private static boolean analyze(
            @NotNull List<BinaryExpression> filtered,
            @NotNull IElementType operator,
            @NotNull ProblemsHolder holder
    ) {
        for (final BinaryExpression current : filtered) {
            final PsiElement currentLeft  = current.getLeftOperand();
            final PsiElement currentRight = current.getRightOperand();
            if (currentLeft != null && currentRight != null) {
                final IElementType operation  = current.getOperationType();
                boolean reached               = false;
                for (final BinaryExpression following : filtered) {
                    if (following.getOperationType() == operation) {
                        if (reached) {
                            final PsiElement followingLeft  = following.getLeftOperand();
                            final PsiElement followingRight = following.getRightOperand();
                            if (followingLeft != null && followingRight != null) {
                                PsiElement source             = null;
                                final List<PsiElement> values = new ArrayList<>();
                                if (OpeanapiEquivalenceUtil.areEqual(currentLeft, followingLeft)) {
                                    source = followingLeft;
                                    values.add(currentRight);
                                    values.add(followingRight);
                                } else if (OpeanapiEquivalenceUtil.areEqual(currentRight, followingRight)) {
                                    source = followingRight;
                                    values.add(currentLeft);
                                    values.add(followingLeft);
                                }
                                if (
                                    source != null &&
                                    !(source instanceof StringLiteralExpression) &&
                                    !(source instanceof ConstantReference) &&
                                    !(source instanceof ClassConstantReference) &&
                                    !OpenapiTypesUtil.isNumber(source)
                                ) {
                                    /* TODO: values only of those filtered types */

                                    final boolean isAndOperator = operator == PhpTokenTypes.opAND;
                                    final String fragment       = String.format(
                                            (isAndOperator ? equalOperators : notEqualOperators).get(operation),
                                            source.getText(),
                                            values.get(0).getText(),
                                            source.getText(),
                                            values.get(1).getText()
                                    );
                                    values.clear();

                                    holder.registerProblem(
                                            following,
                                            String.format(isAndOperator ? messageAlwaysFalse : messageAlwaysTrue, fragment)
                                    );
                                    return true;
                                }
                                values.clear();
                            }
                        }
                        reached = reached || following == current;
                    }
                }
            }
        }

        return false;
    }

    @NotNull
    private static List<PsiElement> extractFragments(@NotNull BinaryExpression binary, @Nullable IElementType operator) {
        final List<PsiElement> result = new ArrayList<>();
        if (binary.getOperationType() == operator) {
            Stream.of(binary.getLeftOperand(), binary.getRightOperand())
                    .filter(Objects::nonNull).map(ExpressionSemanticUtil::getExpressionTroughParenthesis)
                    .forEach(expression -> {
                        if (expression instanceof BinaryExpression) {
                            result.addAll(extractFragments((BinaryExpression) expression, operator));
                        } else {
                            result.add(expression);
                        }
                    });
        } else {
            result.add(binary);
        }
        return result;
    }
}
