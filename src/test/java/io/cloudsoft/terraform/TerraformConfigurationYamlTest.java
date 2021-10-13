package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Requires in .brooklyn/brooklyn.properties:
 *
 * brooklyn.jclouds.aws-ec2.identity=AKIA....
 * brooklyn.jclouds.aws-ec2.credential=xX....
 *
 * @throws Exception
 */
public class TerraformConfigurationYamlTest extends AbstractYamlTest {

    public static ImmutableMap<String, String> locations = ImmutableMap.of(
            "localhost_location", "classpath://locations/localhost.location.bom",
            "aws_ubuntu20_location", "classpath://locations/aws.ubuntu20.location.bom"
    );

    @Test(groups="Live") // TF on localhost (AMP machine)
    public void testDeployFromLocalhostAndFromCfgInBundle() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-in-bundle.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.isApplied"), v -> v.equals("true"));

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    // this does not work, brooklyn.properties are not read
    @Test(groups="Live, Broken") // TF on AWS (AMP machine)
    public void testDeployFromAWSAndFromCfgInBundle() throws Exception {
        Application app = deploy("aws_ubuntu20_location", "classpath://blueprints/tf-cfg-in-bundle.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.isApplied"), v -> v.equals("true"));
        // TODO tests about sensors being populated with expected values here

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test(groups="Live") // TF on localhost (AMP machine)
    public void testDeployFromLocalhostAndFromCfgInBlueprint() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-in-blueprint.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.isApplied"), v -> v.equals("true"));
        // TODO tests about sensors being populated with expected values here

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test(groups="Live") // TF on localhost (AMP machine)
    public void testDeployFromLocalhostFromCfgFileInArtifactory() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-in-artifactory.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.isApplied"), v -> v.equals("true"));
        // TODO tests about sensors being populated with expected values here

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test(groups="Live") // TF on localhost (AMP machine)
    public void testDeployFromLocalhostFromCfgZipOverHttps() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-over-https.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.isApplied"), v -> v.equals("true"));
        // TODO tests about sensors being populated with expected values here

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    // --------------------------- core for test methods ---------------------------------
    private Application deploy(final String location, final String blueprint) throws Exception {
        addCatalogItems(loadYaml("classpath://catalog.bom"));  // adding terraform type to the catalog
        for (String v : locations.values()) addCatalogItems(loadYaml(v));

        Application app = createAndStartApplication("location: " + location + "\n" + loadYaml(blueprint));
        Dumper.dumpInfo(app);

        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_UP, true);

        return app;
    }

}
