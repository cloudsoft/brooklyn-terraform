package io.cloudsoft.terraform.sample.app;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.beust.jcommander.internal.Maps;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.cloudsoft.terraform.TerraformConfiguration;

public class TerraformConfigurationLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Add more tests
    // - other protocols, such as HTTPS, and TCP using `nc`
    // - removing nodes cleanly, to ensure they are removed from the pool
    // - health checker, to ensure

    // Note we need to pass an explicit list of AZs - if it tries to use all of them, then get an error
    // that us-east-1a and us-east-1d are not compatible.

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationLiveTest.class);

    public static final String PROVIDER = "aws-ec2";
    public static final String REGION_NAME = "us-east-1";
    public static final String TINY_HARDWARE_ID = "t1.micro";
    public static final String SMALL_HARDWARE_ID = "m1.small";
    public static final String LOCATION_SPEC = PROVIDER + (REGION_NAME == null ? "" : ":" + REGION_NAME);

    protected BrooklynProperties brooklynProperties;

    protected Location loc;
    protected List<Location> locs;

    private TerraformConfiguration terraformConfiguration;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");

        mgmt = new LocalManagementContext(brooklynProperties);

        super.setUp();

        loc = mgmt.getLocationRegistry().getLocationManaged("localhost", Maps.newHashMap());
        locs = ImmutableList.of(loc);
    }

    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            if (terraformConfiguration != null) {
                terraformConfiguration.destroy();
            }
            if (app != null) {
                app.stop();
            }
        } catch (Exception e) {
            LOG.error("error deleting/stopping ELB app; continuing with shutdown...", e);
        } finally {
            super.tearDown();
        }
    }

    @Test(groups="Live")
    public void testCreateInstance() throws Exception {
        Map<String, Object> env = ImmutableMap.of(
                "AWS_ACCESS_KEY_ID", brooklynProperties.get("brooklyn.location.jclouds.aws-ec2.identity"),
                "AWS_SECRET_ACCESS_KEY", brooklynProperties.get("brooklyn.location.jclouds.aws-ec2.credential")
        );

        terraformConfiguration = app.createAndManageChild(EntitySpec.create(TerraformConfiguration.class)
                .configure(TerraformConfiguration.CONFIGURATION_URL, "classpath://instance.tf")
                .configure(SoftwareProcess.SHELL_ENVIRONMENT, env)
        );

        app.start(locs);

        checkNotNull(terraformConfiguration.getAttribute(TerraformConfiguration.HOSTNAME));
        EntityAsserts.assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(terraformConfiguration, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEventuallyNonNull(terraformConfiguration, TerraformConfiguration.OUTPUT);

        Entities.dumpInfo(app);

    }

}
