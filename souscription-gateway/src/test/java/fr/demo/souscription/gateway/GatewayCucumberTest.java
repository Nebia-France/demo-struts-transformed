package fr.demo.souscription.gateway;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectDirectories;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectDirectories("../features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "fr.demo.souscription.gateway.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary")
// Les specifications Gherkin sont partagees par les cinq applications et la suite end-to-end.
// Chaque scenario a exactement un proprietaire (features/SCENARIO_OWNERSHIP.json), porte par la
// balise @<proprietaire> : la passerelle ne joue que les 24 scenarios balises
// @souscription-gateway. La coherence balises / matrice est verifiee par
// src/test/scripts/check-scenario-ownership.sh (stage gherkin-allocation).
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@souscription-gateway")
public class GatewayCucumberTest {
}
