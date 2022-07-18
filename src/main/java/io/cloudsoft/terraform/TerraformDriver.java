package io.cloudsoft.terraform;

import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.json.ShellEnvironmentSerializer;

import static java.lang.String.format;

public interface TerraformDriver extends SoftwareProcessDriver {
    String EMPTY_TF_CFG_WARN = "Terraform initialized in an empty directory!";

    String PLAN_STATUS = "tf.plan.status";
    String PLAN_PROVIDER = "tf.plan.provider";
    String RESOURCE_CHANGES = "tf.resource.changes";
    String PLAN_MESSAGE = "tf.plan.message";
    String PLAN_ERRORS = "tf.errors";

    void customize();
    void launch();
    void postLaunch();
    int destroy();

    Map<String, Object> runJsonPlanTask();
    String runPlanTask();
    void runApplyTask();
    String runOutputTask();
    String runShowTask();
    int runDestroyTask();
    int runRemoveLockFileTask();

    // added these methods to underline the terraform possible commands
    default String initCommand() {
        return makeTerraformCommand("init -input=false"); // Prepare your working directory for other commands
    }
    default String jsonPlanCommand() {
        return makeTerraformCommand("plan -lock=false -input=false -no-color -json"); // Show changes required by the current configuration
    }
    default String planCommand() {
        return makeTerraformCommand("plan -lock=false -input=false -no-color"); // Show changes required by the current in the normal TF style, provides more info than the json version
    }
    default String applyCommand() {
        return makeTerraformCommand("apply -no-color -input=false -auto-approve"); // Create or update infrastructure
    }
    default String refreshCommand() {
        return makeTerraformCommand(" apply -refresh-only -auto-approve -no-color -input=false"); // Create or update infrastructure
    }
    default String showCommand() {
        return makeTerraformCommand("show -no-color -json"); // Show the current state or a saved plan
    }
    default String outputCommand() {
        return makeTerraformCommand("output -no-color -json"); // Show output values from your root module
    }
    default String destroyCommand() {
        return makeTerraformCommand("apply -destroy -auto-approve -no-color");
    }

    default String makeTerraformCommand(String argument) {
        return format("cd %s && %s/terraform %s", getRunDir(), getInstallDir(), argument);
    }

    default String getConfigurationFilePath() {
        return getRunDir() + "/configuration.tf";
    }

    default String getTfVarsFilePath() {
        return getRunDir() + "/terraform.tfvars";
    }

    default String getStateFilePath() {
        return getRunDir() + "/terraform.tfstate";
    }

    // these are just here to allow the terraform commands building methods to be default too :)
    String getRunDir();
    String getInstallDir();


    /**
     * This method converts any brooklyn configuration starting with tf_var. into TERRAFORM environment variables.
     * Declared here so it can be reused in all drivers.
     */
    default Map<String, String> getShellEnvironment(final EntityInternal entity) {
        Map<String, Object> env = MutableMap.copyOf(entity.getConfig(SoftwareProcess.SHELL_ENVIRONMENT));

        // extend the parent to read vars whenever the shell environment is fetched, so if a var changes we will flag that as drift
        Set<ConfigKey<?>> terraformVars =  entity.config().findKeysPresent(k -> k.getName().startsWith("tf_var"));
        terraformVars.forEach(c -> {
            final String bcName = c.getName();
            final String tfName = bcName.replace("tf_var.", "TF_VAR_");
            final Object value = entity.getConfig(ConfigKeys.newConfigKey(Object.class, bcName));
            env.put(tfName, value);
        });

        ShellEnvironmentSerializer envSerializer = new ShellEnvironmentSerializer((entity).getManagementContext());
        return envSerializer.serialize(env);
    }

}
