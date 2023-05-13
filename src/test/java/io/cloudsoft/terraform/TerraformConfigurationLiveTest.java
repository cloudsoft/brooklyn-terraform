package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableList;
import io.cloudsoft.terraform.predicates.ResourceType;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.persist.PersistMode;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.launcher.BrooklynViewerLauncher;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.Collection;
import java.util.List;

import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEqualsEventually;
import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEventuallyNonNull;
import static org.testng.Assert.assertNotNull;

public class TerraformConfigurationLiveTest extends TerraformConfigurationLiveTestFixture {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationLiveTest.class);

    private TerraformConfiguration terraformConfiguration;

    List<BrooklynViewerLauncher> viewers = MutableList.of();

    @AfterMethod(alwaysRun = true, timeOut = 30000L)
    @Override
    public void tearDown() throws Exception {
        tearDownViewers(mgmt, viewers);
        super.tearDown();
    }

    // method can be used in a test to add a REST API endpoint to which a UI can be attached
    protected void attachViewerForNonRebind() {
        attachViewerForNonRebind(mgmt, viewers);
    }

    public static void attachViewerForNonRebind(ManagementContext mgmt, Collection<BrooklynViewerLauncher> viewers) {
        // required for REST access - otherwise it is viewed as not yet ready
        mgmt.getHighAvailabilityManager().disabled(true);
        //mgmt.getHighAvailabilityManager().getNodeState();
        ((RebindManagerImpl)mgmt.getRebindManager()).setAwaitingInitialRebind(false);

        BrooklynViewerLauncher viewer = BrooklynViewerLauncher.newInstance();
        viewers.add(viewer);
        viewer.managementContext(mgmt);
        viewer.persistMode(PersistMode.DISABLED);
        viewer.start();
    }

    public static void tearDownViewers(ManagementContext mgmt, Collection<BrooklynViewerLauncher> viewers) {
        viewers.forEach(BrooklynViewerLauncher::terminate);
        viewers.clear();
    }

    @Test(groups="Live")
    public void testCreateSecurityGroupSsh() throws Exception {
        doTestCreateSecurityGroup(TerraformCommons.SSH_MODE, true);
    }

    @Test(groups="Live")
    public void testCreateSecurityGroupLocal() throws Exception {
        doTestCreateSecurityGroup(TerraformCommons.LOCAL_MODE, false);
    }

    void doTestCreateSecurityGroup(String mode, boolean needsLocation) throws Exception {
        attachViewerForNonRebind();

        // TODO why local does not show isRunning
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformCommons.CONFIGURATION_URL, "classpath://plans/create-security-group.tf")
                .configure(TerraformCommons.TF_EXECUTION_MODE, mode)
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));

        if (needsLocation) app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));
        else terraformConfiguration.reinstallConfig(null);

        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        Asserts.continually(new SensorSupplier<>(terraformConfiguration, TerraformConfiguration.STATE),
                input -> input == null || !input.containsKey("ERROR"));

        Dumper.dumpInfo(app);
        LOG.debug("Stopping application ...");
        app.stop();
    }

    @Test(groups="Live")
    public void testCreateInstance() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformCommons.CONFIGURATION_URL, "classpath://plans/create-instance.tf")
                .configure(TerraformCommons.TF_EXECUTION_MODE, TerraformCommons.SSH_MODE)
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env));
        app.start(ImmutableList.<Location>of(app.newLocalhostProvisioningLocation()));

        assertNotNull(terraformConfiguration.getAttribute(TerraformConfiguration.HOSTNAME));
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_UP, true);
        assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        assertAttributeEventuallyNonNull(terraformConfiguration, TerraformConfiguration.OUTPUT);
        Dumper.dumpInfo(app);

        // Terraform can take more than thirty seconds to destroy the instance which
        // trips tearDown's timeout. Stop the application here instead.
        LOG.debug("Stopping application ...");
        app.stop();
    }

    @Test(groups="Live")
    public void testCreateInstanceWithDynamicGroups() throws Exception {
        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformCommons.CONFIGURATION_URL, "classpath://plans/create-instance.tf")
                .configure(TerraformCommons.TF_EXECUTION_MODE, TerraformCommons.SSH_MODE)
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
        Dumper.dumpInfo(app);

        // Terraform can take more than thirty seconds to destroy the instance which
        // trips tearDown's timeout. Stop the application here instead.
        LOG.debug("Stopping application ...");
        app.stop();
    }
}
