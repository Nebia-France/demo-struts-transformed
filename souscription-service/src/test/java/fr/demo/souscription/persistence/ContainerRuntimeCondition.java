package fr.demo.souscription.persistence;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Detecte la presence d'un daemon de conteneurs en interrogeant la CLI docker puis podman.
 * Aucune dependance supplementaire n'est requise.
 */
final class ContainerRuntimeCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (runtimeAvailable("docker") || runtimeAvailable("podman")) {
            return ConditionEvaluationResult.enabled("daemon de conteneurs disponible");
        }
        return ConditionEvaluationResult.disabled(
                "aucun daemon de conteneurs joignable : Dev Services PostgreSQL ne peut pas demarrer");
    }

    private static boolean runtimeAvailable(String command) {
        try {
            Process process = new ProcessBuilder(List.of(command, "info"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
