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

    protected BrooklynProperties brooklynProperties;
    private TerraformConfiguration terraformConfiguration;
    private Map<String, Object> env;

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
        env = ImmutableMap.of(
                "AWS_ACCESS_KEY_ID", getRequiredProperty("brooklyn.location.jclouds.aws-ec2.identity"),
                "AWS_SECRET_ACCESS_KEY", getRequiredProperty("brooklyn.location.jclouds.aws-ec2.credential"),
                "AWS_REGION", REGION_NAME);
    }

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
    }

    private Object getRequiredProperty(String property) {
        return checkNotNull(brooklynProperties.get(property), "test requires mgmt context property: " + property);
    }

    private static class SensorSupplier<T> implements Supplier<T> {
        private final Entity entity;
        private final AttributeSensor<T> sensor;

        private SensorSupplier(Entity entity, AttributeSensor<T> sensor) {
            this.entity = entity;
            this.sensor = sensor;
        }

        @Override
        public T get() {
            return entity.sensors().get(sensor);
        }
    }

}
