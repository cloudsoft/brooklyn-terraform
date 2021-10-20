package io.cloudsoft.terraform;

import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.util.text.Strings;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
public class TerraformSimpleTest {


    @Test
    public void xorTest() throws IOException {
        // nulls
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(null));
        assertTrue(Strings.isNonBlank(null) ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank(null) ^ Strings.isNonBlank(null));

        // empty strings
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(""));
        assertTrue(Strings.isNonBlank("") ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank("") ^ Strings.isNonBlank(""));
    }

    @Test
    public void readManagedResources() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/state.json").getFile());

        final String state =  new String(Files.readAllBytes(file.toPath()));

        Map<String,Object> resources = StateParser.parseResources(state);
        Assert.assertTrue(1 == resources.size());
        Assert.assertTrue(resources.containsKey("aws_instance.example1"));
        Assert.assertTrue(((Map<String,Object>)(resources.get("aws_instance.example1"))).containsKey("resource.status"));
    }

    @Test
    public void parseNoChanges() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-nothing.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.SYNC);
        Assert.assertEquals(result.get("tf.plan.message"), "No changes. Your infrastructure matches the configuration.");
    }

    @Test
    public void parseCreate() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-create.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 2 to add, 0 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.output.group_name"), "create");
        Assert.assertEquals(result.get("tf.output.group_id"), "create");
        Assert.assertEquals(result.get("tf.output.group_id2"), "create");
        Assert.assertEquals(result.get("tf.output.group_name2"), "create");
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all2"), "create");
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all"), "create");
    }

    @Test
    public void parseUpdateDrift() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-drift-update.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(4, result.size());
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DRIFT);
        Assert.assertEquals(result.get("tf.plan.message"), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 0 to add, 2 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all2"), "update");
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all"), "update");
    }

    @Test // apparently adding a new resource is not actually a drift
    public void parseAddDrift() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-drift-create.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(5, result.size());
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 1 to add, 0 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all2"), "create");
        Assert.assertEquals(result.get("tf.output.group_id2"), "create");
        Assert.assertEquals(result.get("tf.output.group_name2"), "create");
    }

    @Test // apparently deleting a resource is not actually a drift
    public void parseRemoveDrift() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-drift-remove.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(5, result.size());
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 0 to add, 0 to change, 1 to destroy.");
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        Assert.assertEquals(result.get("tf.resource.aws_security_group.allow_all2"), "delete");
        Assert.assertEquals(result.get("tf.output.group_id2"), "delete");
        Assert.assertEquals(result.get("tf.output.group_name2"), "delete");
    }

    @Test // changing state of an instance to stopping is a drift
    public void parseShutdownDrift() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-drift-shutdown.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(3, result.size());
        Assert.assertEquals(result.get("tf.plan.message"), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 0 to add, 0 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DRIFT);
        Assert.assertEquals(result.get("tf.resource.aws_instance.example1"), "update");
    }

    @Test // terminating an instance is a drift
    public void parseTerminateDrift() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-drift-terminate.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(4, result.size());
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.DRIFT);
        Assert.assertEquals(result.get("tf.plan.message"), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 1 to add, 0 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.resource.aws_instance.example1"), "delete");
        Assert.assertEquals(result.get("tf.output.address1"), "update"); // output can no longer be populated, since the VM is terminated
    }

    @Test // a bad configuration is a serious error
    public void parseBlowConfig() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("state/plan-bad-config.json").getFile());
        final String logs = new String(Files.readAllBytes(file.toPath()));

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(3, result.size());
        Assert.assertEquals(result.get("tf.plan"), TerraformConfiguration.TerraformStatus.ERROR);
        Assert.assertEquals(result.get("tf.plan.message"), "Something went wrong. Check your configuration.");
        Assert.assertTrue(result.containsKey("tf.errors"));
    }
}