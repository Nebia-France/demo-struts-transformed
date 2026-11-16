package fr.demo.souscription;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Suite d'execution des 88 scenarios Gherkin. Le suffixe IT la maintient hors du run
 * Surefire par defaut : elle n'est executee que via mvn verify -Pcucumber.
 * Les scenarios sont localises par cucumber.features=../features
 * (junit-platform.properties), qui pointe sur le repertoire features/ a la racine du depot.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue", value = "features.step_definitions")
public class CucumberSuiteIT {
}
