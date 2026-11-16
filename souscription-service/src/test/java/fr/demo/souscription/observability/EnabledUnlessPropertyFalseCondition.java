package fr.demo.souscription.observability;

import java.lang.reflect.AnnotatedElement;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Evalue {@link EnabledUnlessPropertyFalse} sur la classe ou la methode de test courante. */
final class EnabledUnlessPropertyFalseCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        AnnotatedElement element = context.getElement().orElse(null);
        for (EnabledUnlessPropertyFalse condition : AnnotationSupport.findRepeatableAnnotations(
                element, EnabledUnlessPropertyFalse.class)) {
            if ("false".equals(System.getProperty(condition.named()))) {
                return ConditionEvaluationResult.disabled(condition.reason());
            }
        }
        return ConditionEvaluationResult.enabled("aucune instrumentation desarmee");
    }
}
