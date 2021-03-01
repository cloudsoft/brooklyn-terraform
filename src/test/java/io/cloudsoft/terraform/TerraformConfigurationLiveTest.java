package io.cloudsoft.terraform;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEqualsEventually;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEventuallyNonNull;
import static org.testng.Assert.assertNotNull;

import java.util.Map;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.test.Asserts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class TerraformConfigurationLiveTest extends TerraformConfigurationLiveTestFixture {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationLiveTest.class);

    @Test(groups="Live")
    public void testCreateSecurityGroup() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformConfiguration.CONFIGURATION_URL, "classpath://plans/create-security-group.tf")
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
        app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        // Previously this failed because the environment was not set on the SSH sensor feeds.
        Asserts.continually(new SensorSupplier<>(terraformConfiguration, TerraformConfiguration.STATE), new Predicate<Map<String, Object>>() {
            @Override
            public boolean apply(@Nullable Map<String, Object> input) {
                return input == null || !input.containsKey("ERROR");
            }
        });
    }

    @Test(groups="Live")
    public void testCreateInstance() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformConfiguration.CONFIGURATION_URL, "classpath://plans/create-instance.tf")
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
        app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));

        assertNotNull(terraformConfiguration.getAttribute(TerraformConfiguration.HOSTNAME));
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_UP, true);
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        assertAttributeEventuallyNonNull(terraformConfiguration, TerraformConfiguration.OUTPUT);

        Entities.dumpInfo(app);

        // Terraform can take more than thirty seconds to destroy the instance which
        // trips tearDown's timeout. Stop the application here instead.
        app.stop();
    }

}
