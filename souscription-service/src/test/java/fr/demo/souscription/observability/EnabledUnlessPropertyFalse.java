package fr.demo.souscription.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Precondition d'environnement du chemin de retour arriere de la vague 5 : le test est execute
 * sauf lorsque la propriete systeme {@link #named()} vaut explicitement {@code false}
 * (instrumentation desarmee). Meme semantique que la condition JUnit equivalente, sous un nom qui
 * exprime une precondition et non une desactivation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(EnabledUnlessPropertyFalse.List.class)
@ExtendWith(EnabledUnlessPropertyFalseCondition.class)
public @interface EnabledUnlessPropertyFalse {

    /** Nom de la propriete systeme. */
    String named();

    /** Raison rapportee lorsque la propriete vaut {@code false}. */
    String reason();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @ExtendWith(EnabledUnlessPropertyFalseCondition.class)
    @interface List {
        EnabledUnlessPropertyFalse[] value();
    }
}
