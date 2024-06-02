package io.cloudsoft.terraform;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.persist.PersistMode;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.launcher.BrooklynViewerLauncher;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class TerraformConfigurationLiveTestFixture extends BrooklynAppLiveTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationLiveTestFixture.class);

    public static final String PROVIDER = "aws-ec2";
    public static final String REGION_NAME = "eu-west-1";

    protected BrooklynProperties brooklynProperties;
    protected Map<String, Object> env;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        setUpBrooklynProperties();
        mgmt = new LocalManagementContext(brooklynProperties);
        super.setUp();
        env = setUpEnv();
    }

    protected Map<String, Object> setUpEnv() {
        return ImmutableMap.of(
                "AWS_ACCESS_KEY_ID", getRequiredProperty("brooklyn.location.jclouds.aws-ec2.identity", "brooklyn.jclouds.aws-ec2.identity"),
                "AWS_SECRET_ACCESS_KEY", getRequiredProperty("brooklyn.location.jclouds.aws-ec2.credential", "brooklyn.jclouds.aws-ec2.credential"),
                "AWS_REGION", REGION_NAME);
    }

    protected void setUpBrooklynProperties() {
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");
    }


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

    protected Object getRequiredProperty(String ...properties) {
        return checkNotNull(Strings.firstNonNull(Arrays.asList(properties).stream().map(brooklynProperties::getConfig).collect(Collectors.toList()).toArray(new Object[0])), "test requires mgmt context property, brooklyn.properties must have at least one of: " + Arrays.asList(properties));
    }

    protected static class SensorSupplier<T> implements Supplier<T> {
        private final Entity entity;
        private final AttributeSensor<T> sensor;

        protected SensorSupplier(Entity entity, AttributeSensor<T> sensor) {
            this.entity = entity;
            this.sensor = sensor;
        }

        @Override
        public T get() {
            return entity.sensors().get(sensor);
        }
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

}
