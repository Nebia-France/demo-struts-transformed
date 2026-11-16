package fr.demo.souscription.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Ignore un test lorsque aucun daemon de conteneurs (Docker / Podman) n'est joignable,
 * c'est-a-dire lorsque Dev Services ne peut pas demarrer la base PostgreSQL.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(ContainerRuntimeCondition.class)
public @interface DisabledIfContainerRuntimeUnavailable {
}
