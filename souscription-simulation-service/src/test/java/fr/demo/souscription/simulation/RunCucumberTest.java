package fr.demo.souscription.simulation;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectDirectories;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectDirectories("../features/simulation")
@ConfigurationParameter(key = "cucumber.glue", value = "features.step_definitions")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty,html:target/cucumber-report.html")
public class RunCucumberTest {
}
