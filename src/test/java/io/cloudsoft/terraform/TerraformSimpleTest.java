package io.cloudsoft.terraform;

import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.util.text.Strings;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
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
        final String state = loadTestData("state/state.json");

        Map<String,Object> resources = StateParser.parseResources(state);
        Assert.assertTrue(1 == resources.size());
        Assert.assertTrue(resources.containsKey("aws_instance.example1"));
        Assert.assertTrue(((Map<String,Object>)(resources.get("aws_instance.example1"))).containsKey("resource.status"));
    }

    /**
     * 0. Deploy Terraform config -> TF plan status = SYNC, Resources are created, AMP all green - pass
     * @throws IOException
     */
    @Test
    public void parseNoChanges() throws IOException {
        final String logs = loadTestData("state/plan-nothing.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(result.size(), 2);
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.SYNC);
        Assert.assertEquals(result.get("tf.plan.message"), "No changes. Your infrastructure matches the configuration.");
    }

    /**
     * The log processed by this test is returned when 'terraform plan' is executed before 'terraform apply'
     * @throws IOException
     */
    @Test
    public void parseCreate() throws IOException {
        final String logs = loadTestData("state/plan-create.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 2 to add, 0 to change, 0 to destroy.");
        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        Assert.assertEquals(outputs.size(), 4);

        Assert.assertEquals(outputs.stream().filter(m -> m.containsValue("create")).count(), 4);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals(resources.size(), 2);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("create")).count(), 2);
    }

    /**
     * 1. Drift detected (update):
     * Resource update outside terraform
     * TF plan status = DRIFT
     * resource entity is ON_FIRE , TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * Action: invoke apply effector on configuration node to reset back to configuration
     * -> expect resource in initial state + AMP all green + compliance.drift sensor is removed - pass  (Obs: status returns back to green in 30 seconds after apply - is this acceptable?)
     * @throws IOException
     */
    @Test // resource is changes outside terraform -> AWS example: renaming a tag
    public void parseUpdateDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-update.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(result.size(), 3);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals( resources.size(), 2);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("update")).count(), 2);
    }

    /**
     * 2. Editing the configuration (1)
     * Adding a new resource and/or output to the configuration.tf
     * TF plan status = DESYNCHRONIZED, TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * Action: invoke apply effector on configuration node to create the new reources and outputs
     * -> expect TF plan status = SYNC, extra children added, AMP all  green + compliance.drift sensor is removed - pass
     * @throws IOException
     */
    @Test // apparently adding a new resource to the configuration file is not actually a drift
    public void parseAddDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-create.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals( result.size(), 4);
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 1 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        Assert.assertEquals(outputs.size(), 2);
        Assert.assertEquals(outputs.stream().filter(m -> m.containsValue("create")).count(), 2);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals(resources.size(), 1);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("create")).count(), 1);
    }

    /**
     * 3. Editing the configuration (2)
     * Remove a new resource and/or output from the configuration.tf
     * TF plan status = DESYNCHRONIZED, TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * Action: invoke apply effector on configuration node to create the new reources and outputs
     * -> expect TF plan status = SYNC, broken children removed, AMP all  green + compliance.drift sensor is removed - pass
     * @throws IOException
     */
    @Test // apparently deleting a resource is not actually a drift
    public void parseRemoveDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-remove.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals( result.size(), 4);
        Assert.assertEquals(result.get("tf.plan.message"), "Configuration and infrastructure do not match.Plan: 0 to add, 0 to change, 1 to destroy.");
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        Assert.assertEquals( outputs.size(), 2);
        Assert.assertEquals(outputs.stream().filter(m -> m.containsValue("delete")).count(), 2);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals( resources.size(), 1);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("delete")).count(), 1);
    }

    /**
     * 4. Resource state is not as expected (1)
     * VM is stopped outside terraform, terraform expects it to be up, thus drift happens
     * TF plan status = DRIFT,  TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * 2 Actions possible:
     * -> apply -> accepts the state as normal and terrform goes back to expect TF plan status = SYNC, child is marked as STOPPED,  TF configuration is RUNNING, Application is RUNNING
     * -> manually start the instance + invoke apply (Here is where AMP could be a great help - I think-  if we could get access to the location, because it could save the user the hassle to go to the cloud console to start it)
     * @throws IOException
     */
    @Test // changing state of an instance to stopping is a drift
    public void parseShutdownDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-shutdown.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals( result.size(), 3);
        Assert.assertEquals(result.get("tf.plan.message"), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 0 to add, 0 to change, 0 to destroy.");
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.DRIFT);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals( resources.size(), 1);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("update")).count(), 1);
    }

    /**
     * 5. Resource state is not as expected (2) - well, resource is missing
     * VM is terminated outside terraform, terraform expects it to be up, thus drift happens
     * TF plan status = DRIFT,  TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * Action: invoke apply -> creates the resource, TF plan status = SYNC, running child is added, the deffective one is removed,  TF configuration is RUNNING, Application is RUNNING
     * @throws IOException
     */
    @Test // terminating an instance is a drift
    public void parseTerminateDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-terminate.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals( result.size(), 4);
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.DRIFT);
        Assert.assertEquals(result.get("tf.plan.message"), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 1 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        Assert.assertEquals( outputs.size(), 1); // output can no longer be populated, since the VM is terminated
        Assert.assertEquals(outputs.stream().filter(m -> m.containsValue("update")).count(), 1);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get("tf.resource.changes"));
        Assert.assertEquals( resources.size(), 1);
        Assert.assertEquals(resources.stream().filter(m -> m.containsValue("delete")).count(), 1);
    }

    /**
     * 6. Manually breaking the configuration
     * TF plan status = ERROR, TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green), service problems show TF-ERROR, compliance.drift sensor is added.
     * Action: fix configuration file, in the next 30 AMP refreshes and all is green again. also service problems are gone.
     * @throws IOException
     */
    @Test // a bad configuration is a serious error
    public void parseBlowConfig() throws IOException {
        final String logs = loadTestData("state/plan-bad-config.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        Assert.assertEquals(3, result.size());
        Assert.assertEquals(result.get("tf.plan.status"), TerraformConfiguration.TerraformStatus.ERROR);
        Assert.assertEquals(result.get("tf.plan.message"), "Something went wrong. Check your configuration.");
        Assert.assertTrue(result.containsKey("tf.errors"));
    }

    private String loadTestData(final String filePathAsStr) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource(filePathAsStr).getFile());

        return new String(Files.readAllBytes(file.toPath()));
    }
}