package fr.demo.souscription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModuleBuildTest {

    @Test
    void buildsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
