package io.cloudsoft.terraform;

import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEqualsEventually;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEventuallyNonNull;
import static org.testng.Assert.assertNotNull;

import com.google.common.collect.ImmutableList;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.test.Asserts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import io.cloudsoft.terraform.predicates.ResourceType;

public class TerraformConfigurationLiveTest extends TerraformConfigurationLiveTestFixture {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationLiveTest.class);

    private TerraformConfiguration terraformConfiguration;

    @Test(groups="Live")
    public void testCreateSecurityGroup() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformConfiguration.CONFIGURATION_URL, "classpath://plans/create-security-group.tf")
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
        app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        // Previously this failed because the environment was not set on the SSH sensor feeds.
        Asserts.continually(new SensorSupplier<>(terraformConfiguration, TerraformConfiguration.STATE),
                input -> input == null || !input.containsKey("ERROR"));

        Entities.dumpInfo(app);
        LOG.debug("Stopping application ...");
        app.stop();
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
        LOG.debug("Stopping application ...");
        app.stop();
    }

    @Test(groups="Live")
    public void testCreateInstanceWithDynamicGroups() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformConfiguration.CONFIGURATION_URL, "classpath://plans/create-instance.tf")
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
                terraformConfiguration
                        .addChild(EntitySpec.create(DynamicGroup.class)
                                .configure(DynamicGroup.ENTITY_FILTER,
                                        ResourceType.resourceType("aws_instance")));
        app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));

        assertNotNull(terraformConfiguration.getAttribute(TerraformConfiguration.HOSTNAME));
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_UP, true);
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        assertAttributeEventuallyNonNull(terraformConfiguration, TerraformConfiguration.OUTPUT);

        for (Entity child : terraformConfiguration.getChildren()) {
            System.out.println(child.getDisplayName());
        }

        Entities.dumpInfo(app);

        // Terraform can take more than thirty seconds to destroy the instance which
        // trips tearDown's timeout. Stop the application here instead.
        LOG.debug("Stopping application ...");
        app.stop();
    }
}
