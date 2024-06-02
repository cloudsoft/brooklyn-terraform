package io.cloudsoft.terraform;

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.text.StringEscapes.JavaStringEscapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEqualsEventually;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEventuallyNonNull;
import static org.testng.Assert.assertNotNull;

/** tests which run terraform, but using eg the random provider, so not actually creating cloud resources */
public class TerraformConfigurationIntegrationTest extends TerraformConfigurationLiveTestFixture {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationIntegrationTest.class);

    private TerraformConfiguration tc;

    protected void setUpBrooklynProperties() {
        brooklynProperties = BrooklynProperties.Factory.newEmpty();
    }
    @Override
    protected Map<String, Object> setUpEnv() {
        return ImmutableMap.of();
    }

    @Test(groups="Integration")
    public void testRandomWithState() throws Exception {
        tc = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformCommons.CONFIGURATION_URL, "classpath://plans/random_with_state.zip")
                .configure(TerraformCommons.TF_EXECUTION_MODE, TerraformCommons.LOCAL_MODE)
                .configure(TerraformCommons.TF_STATE_CONFLICTS_IN_ZIP_IGNORED, true)
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
        app.start(null);

        assertAttributeEventuallyNonNull(tc, TerraformConfiguration.OUTPUT);
        String output = tc.sensors().get(TerraformConfiguration.OUTPUT);
        Asserts.assertStringContains(output, JavaStringEscapes.wrapJavaString("sgemgq"));

        tc.reinstallConfig("classpath://plans/random_longer_without_state.zip");
        tc.apply();
        output = tc.sensors().get(TerraformConfiguration.OUTPUT);
        Asserts.assertStringDoesNotContain(output, JavaStringEscapes.wrapJavaString("sgemgq"));

        tc.reinstallConfig("classpath://plans/random_with_state.zip");
        tc.apply();
        output = tc.sensors().get(TerraformConfiguration.OUTPUT);
        // state from file will not overwrite local state
        Asserts.assertStringDoesNotContain(output, JavaStringEscapes.wrapJavaString("sgemgq"));

        LOG.debug("Stopping application ...");
        app.stop();
    }

}
