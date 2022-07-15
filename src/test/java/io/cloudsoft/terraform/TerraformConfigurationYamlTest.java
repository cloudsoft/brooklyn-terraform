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

import java.util.Collection;

import static org.apache.brooklyn.core.entity.EntityAsserts.assertAttributeEqualsEventually;

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

    @Test(groups="Live") // TF already installed on localhost
    public void testDeployFromLocalhostAndFromCfgInBundle() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-in-bundle.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.applied"), v -> v.equals("true"));

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
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.applied"), v -> v.equals("true"));

        Assert.assertTrue(((Integer) entity.getChildren().size()).equals(1));
        Entity resource = Iterables.getOnlyElement(entity.getChildren());
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.address"), v -> v.equals("aws_instance.web"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.mode"), v -> v.equals("managed"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.type"), v -> v.equals("aws_instance"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.name"), v -> v.equals("web"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.provider"), v -> v.equals("registry.terraform.io/hashicorp/aws"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.status"), v -> v.equals("running"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.ami"), v -> v.equals("\"ami-02df9ea15c1778c9c\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.instance_type"), v -> v.equals("\"t2.micro\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.tags"), v -> v.equals("{\"Name\":\"terraform-test-cfg-in-bundle\"}"));

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
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.applied"), v -> v.equals("true"));

        Assert.assertTrue(((Integer) entity.getChildren().size()).equals(1));
        Entity resource = Iterables.getOnlyElement(entity.getChildren());
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.address"), v -> v.equals("aws_instance.example1"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.mode"), v -> v.equals("managed"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.type"), v -> v.equals("aws_instance"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.name"), v -> v.equals("example1"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.provider"), v -> v.equals("registry.terraform.io/hashicorp/aws"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.status"), v -> v.equals("running"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.ami"), v -> v.equals("\"ami-02df9ea15c1778c9c\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.instance_type"), v -> v.equals("\"t1.micro\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.tags"), v -> v.equals("{\"Name\":\"terraform-test-cfg-in-blueprint\"}"));

        // gracefully shutdown and test children are stopped
        ((BasicApplication)app).stop();
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test(groups="Live") // TF on localhost (AMP machine)
    // TODO - Note: looks like this test case requires vault set up as per tf-cfg-in-artifactory.bom - do we need/want it?
    public void testDeployFromLocalhostFromCfgFileInArtifactory() throws Exception {
        Application app = deploy("localhost_location", "classpath://blueprints/tf-cfg-in-artifactory.bom");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Assert.assertTrue(entity instanceof TerraformConfiguration);
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.applied"), v -> v.equals("true"));

        Assert.assertTrue(((Integer) entity.getChildren().size()).equals(1));
        Entity resource = Iterables.getOnlyElement(entity.getChildren());
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.address"), v -> v.equals("aws_instance.web"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.mode"), v -> v.equals("managed"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.type"), v -> v.equals("aws_instance"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.name"), v -> v.equals("web"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.provider"), v -> v.equals("registry.terraform.io/hashicorp/aws"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.status"), v -> v.equals("running"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.ami"), v -> v.equals("\"ami-02df9ea15c1778c9c\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.instance_type"), v -> v.equals("\"t2.micro\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.tags"), v -> v.equals("{\"Name\":\"terraform-test-cfg-with-input-vars\"}"));

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
        EntityAsserts.assertAttributeEventually(entity, Sensors.newStringSensor("tf.configuration.applied"), v -> v.equals("true"));

        Assert.assertTrue(((Integer) entity.getChildren().size()).equals(1));
        Entity resource = Iterables.getOnlyElement(entity.getChildren());
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.address"), v -> v.equals("aws_instance.example"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.mode"), v -> v.equals("managed"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.type"), v -> v.equals("aws_instance"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.name"), v -> v.equals("example"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.provider"), v -> v.equals("registry.terraform.io/hashicorp/aws"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.resource.status"), v -> v.equals("running"));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.ami"), v -> v.equals("\"ami-02df9ea15c1778c9c\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.instance_type"), v -> v.equals("\"t1.micro\""));
        EntityAsserts.assertAttributeEventually(resource, Sensors.newStringSensor("tf.value.tags"), v -> v.equals("{\"Name\":\"terraform-test-cfg-in-zip-in-bucket\"}"));

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
