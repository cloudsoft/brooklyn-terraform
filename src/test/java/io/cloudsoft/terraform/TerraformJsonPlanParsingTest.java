package io.cloudsoft.terraform;

import io.cloudsoft.terraform.parser.PlanLogEntry;
import io.cloudsoft.terraform.parser.StateParser;

import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static io.cloudsoft.terraform.TerraformDriver.*;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertEquals;

public class TerraformJsonPlanParsingTest {

    @Test
    public void readManagedResources() throws IOException {
        final String state = loadTestData("state/state.json");

        Map<String,Object> resources = StateParser.parseResources(state);
        assertEquals(resources.size(), 1);
        assertTrue(resources.containsKey("aws_instance.example1"));
        //assertTrue(((Map<String,Object>)(resources.get("aws_instance.example1"))).containsKey("resource.status"));
    }

    @Test
    public void readVSManagedResources() throws IOException {
        final String state = loadTestData("state/vs-state.json");

        Map<String,Object> resources = StateParser.parseResources(state);
        assertEquals(resources.size(), 9);
        long dataCount = resources.entrySet().stream().filter(e -> e.getKey().startsWith("data")).count();
        assertEquals(dataCount,6);
        assertTrue(resources.containsKey("vsphere_tag.tag"));
        assertTrue(resources.containsKey("vsphere_tag_category.category"));
        assertTrue(resources.containsKey("vsphere_virtual_machine.vm01"));
    }


    /**
     * 0. Deploy Terraform config -> TF plan status = SYNC, Resources are created, AMP all green - pass
     * @throws IOException
     */
    @Test
    public void parseNoChanges() throws IOException {
        final String logs = loadTestData("state/plan-nothing.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(result.size(), 3);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.SYNC);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "No changes. Your infrastructure matches the configuration.");
    }

    /**
     * The log processed by this test is returned when 'terraform plan' is executed before 'terraform apply'
     * @throws IOException
     */
    @Test
    public void parseCreate() throws IOException {
        final String logs = loadTestData("state/plan-create.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "Configuration and infrastructure do not match.Plan: 2 to add, 0 to change, 0 to destroy.");
        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals(outputs.size(), 4);

        assertEquals(outputs.stream().filter(m -> m.containsValue("create")).count(), 4);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals(resources.size(), 2);
        assertEquals(resources.stream().filter(m -> m.containsValue("create")).count(), 2);
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
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DRIFT);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.size(), 4);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals( resources.size(), 2);
        assertEquals(resources.stream().filter(m -> m.containsValue("update")).count(), 2);
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
        assertEquals( result.size(), 5);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "Configuration and infrastructure do not match.Plan: 1 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals(outputs.size(), 2);
        assertEquals(outputs.stream().filter(m -> m.containsValue("create")).count(), 2);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals(resources.size(), 1);
        assertEquals(resources.stream().filter(m -> m.containsValue("create")).count(), 1);
    }

    /**
     * 3. Editing the configuration (2)
     * Remove a new resource and/or output from the configuration.tf
     * TF plan status = DESYNCHRONIZED, TF configuration is ON FIRE, Application is ON_FIRE , unaffected entities are OK (green)
     * compliance.drift sensor is added.
     * Action: invoke apply effector on configuration node to create the new resources and outputs
     * -> expect TF plan status = SYNC, broken children removed, AMP all  green + compliance.drift sensor is removed - pass
     * @throws IOException
     */
    @Test // apparently deleting a resource is not actually a drift
    public void parseRemoveDrift() throws IOException {
        final String logs = loadTestData("state/plan-drift-remove.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals( result.size(), 5);
        assertEquals(result.get(PLAN_MESSAGE), "Configuration and infrastructure do not match.Plan: 0 to add, 0 to change, 1 to destroy.");
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals( outputs.size(), 2);
        assertEquals(outputs.stream().filter(m -> m.containsValue("delete")).count(), 2);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals( resources.size(), 1);
        assertEquals(resources.stream().filter(m -> m.containsValue("delete")).count(), 1);
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
        assertEquals( result.size(), 4);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DRIFT);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 0 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals( resources.size(), 1);
        assertEquals(resources.stream().filter(m -> m.containsValue("update")).count(), 1);
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
        assertEquals( result.size(), 5);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DRIFT);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 1 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals( outputs.size(), 1); // output can no longer be populated, since the VM is terminated
        assertEquals(outputs.stream().filter(m -> m.containsValue("update")).count(), 1);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals( resources.size(), 1);
        assertEquals(resources.stream().filter(m -> m.containsValue("delete")).count(), 1);
    }

    /**
     * 7. Removing an output ( works the same for adding outputs)
     * @throws IOException
     */
    @Test // removing or adding outputs is not seen as a infrastructure change by terraform, but we need to be aware of it.
    public void parseRemoveOutputConfig() throws IOException {
        final String logs = loadTestData("state/plan-remove-output.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(result.size(),4);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.AWS);
        assertEquals(result.get(PLAN_MESSAGE), "Outputs configuration was changed.Plan: 0 to add, 0 to change, 0 to destroy.");

        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals( outputs.size(), 1); // output can no longer be populated, since the VM is terminated
        assertEquals(outputs.stream().filter(m -> m.containsValue("delete")).count(), 1);
    }

    /**
     * 8. Deleting a tag declared in the plan configuration and used to tag another resource, causes terraform to be in an (unrecoverable) error state.
     * @throws IOException
     */
    @Test
    public void parseFatalError() throws IOException {
        final String logs = loadTestData("state/deleted-tag.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(result.size(), 5);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.ERROR);
        assertEquals(result.get(PLAN_PROVIDER),PlanLogEntry.Provider.VSPHERE );
        assertEquals(result.get(PLAN_MESSAGE), "Terraform in UNRECOVERABLE error state.");
    }

    /**
     * A configuration syntax error makes the plan go into Recoverable state.
     * @throws IOException
     */
    @Test // a bad configuration is a serious error
    public void parseBadConfig() throws IOException {
        final String logs = loadTestData("state/plan-bad-config.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(3, result.size());
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.ERROR);
        assertEquals(result.get(PLAN_PROVIDER), null);
        assertEquals(result.get(PLAN_MESSAGE), "Terraform in RECOVERABLE error state. Check configuration syntax.");
        assertTrue(result.containsKey("tf.errors"));
    }

    private String loadTestData(final String filePathAsStr) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource(filePathAsStr).getFile());

        return new String(Files.readAllBytes(file.toPath()));
    }


    /**
     * The log processed by this test is returned when 'terraform plan' is executed before 'terraform apply'
     * @throws IOException
     */
    @Test
    public void parseVsCreate() throws IOException {
        final String logs = loadTestData("state/vs-plan-create.json");

        Map<String, Object> result = StateParser.parsePlanLogEntries(logs);
        assertEquals(result.get(PLAN_STATUS), TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
        assertEquals(result.get(PLAN_PROVIDER), PlanLogEntry.Provider.VSPHERE);
        assertEquals(result.get(PLAN_MESSAGE), "Configuration and infrastructure do not match.Plan: 3 to add, 0 to change, 0 to destroy.");
        List<Map<String, Object>> outputs = ((List<Map<String, Object>>)result.get("tf.output.changes"));
        assertEquals(outputs.size(), 1);

        assertEquals(outputs.stream().filter(m -> m.containsValue("create")).count(), 1);

        List<Map<String, Object>> resources = ((List<Map<String, Object>>)result.get(RESOURCE_CHANGES));
        assertEquals(resources.size(), 3);
        assertEquals(resources.stream().filter(m -> m.containsValue("create")).count(), 3);
    }
}