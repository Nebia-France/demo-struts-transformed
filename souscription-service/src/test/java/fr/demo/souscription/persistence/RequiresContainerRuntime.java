package fr.demo.souscription.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Precondition d'environnement : le test n'est execute que lorsqu'un daemon de conteneurs
 * (Docker / Podman) est joignable, c'est-a-dire lorsque Dev Services peut demarrer la base
 * PostgreSQL. Sans daemon, JUnit le rapporte comme ignore avec la raison de
 * {@link ContainerRuntimeCondition} ; ce n'est jamais une desactivation inconditionnelle.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(ContainerRuntimeCondition.class)
public @interface RequiresContainerRuntime {
}
