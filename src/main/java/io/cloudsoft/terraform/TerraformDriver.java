package io.cloudsoft.terraform;

import java.util.Map;

import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;

import static java.lang.String.format;

public interface TerraformDriver extends SoftwareProcessDriver {

    int destroy();

    Map<String, Object> runJsonPlanTask();
    String runPlanTask();
    void runApplyTask();
    String runOutputTask();
    String runShowTask();
    int runDestroyTask();
    int runDestroyTargetTask(String target);

    // added these methods to underline the terraform possible commands
    default String initCommand() {
        return makeTerraformCommand("init -input=false"); // Prepare your working directory for other commands
    }
    default String jsonPlanCommand() {
        return makeTerraformCommand("plan -out=tfplan -lock=false -no-color -json"); // Show changes required by the current configuration
    }
    default String planCommand() {
        return makeTerraformCommand("plan -lock=false -no-color"); // Show changes required by the current in the normal TF style, provides more info than the json version
    }
    default String applyCommand() {
        return makeTerraformCommand("apply -no-color -input=false tfplan"); // Create or update infrastructure
    }
    default String showCommand() {
        return makeTerraformCommand("show -no-color -json"); // Show the current state or a saved plan
    }

    default String outputCommand() {
        return makeTerraformCommand("output -no-color -json"); // Show output values from your root module
    }
    default String destroyCommand() {
        return makeTerraformCommand("destroy -auto-approve -no-color");
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

}
