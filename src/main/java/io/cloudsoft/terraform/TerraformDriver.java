package io.cloudsoft.terraform;

import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.mgmt.TaskFactory;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.json.ShellEnvironmentSerializer;
import org.apache.brooklyn.util.core.task.*;
import org.apache.brooklyn.util.core.task.system.SimpleProcessTaskFactory;
import org.apache.brooklyn.util.core.text.TemplateProcessor;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import static io.cloudsoft.terraform.TerraformCommons.*;

public interface TerraformDriver extends SoftwareProcessDriver {
    static final Logger LOG = LoggerFactory.getLogger(TerraformDriver.class);

    String EMPTY_TF_CFG_WARN = "Terraform initialized in an empty directory!";

    String PLAN_STATUS = "tf.plan.status";
    String PLAN_PROVIDER = "tf.plan.provider";
    String RESOURCE_CHANGES = "tf.resource.changes";
    String PLAN_MESSAGE = "tf.plan.message";
    String PLAN_ERRORS = "tf.errors";

    public void postLaunch();

    /**
     * This method converts any brooklyn configuration starting with tf_var. into TERRAFORM environment variables.
     * Declared here so it can be reused in all drivers.
     */
    default Map<String, String> getShellEnvironment(final Entity entity) {
        Map<String, Object> env = MutableMap.copyOf(entity.getConfig(SoftwareProcess.SHELL_ENVIRONMENT));

        // extend the parent to read vars whenever the shell environment is fetched, so if a var changes we will flag that as drift
        Set<ConfigKey<?>> terraformVars =  entity.config().findKeysPresent(k -> k.getName().startsWith("tf_var"));
        terraformVars.forEach(c -> {
            final String bcName = c.getName();
            final String tfName = bcName.replace("tf_var.", "TF_VAR_");
            final Object value = entity.getConfig(ConfigKeys.newConfigKey(Object.class, bcName));
            env.put(tfName, value);
        });

        ShellEnvironmentSerializer envSerializer = new ShellEnvironmentSerializer(((EntityInternal)entity).getManagementContext());
        return envSerializer.serialize(env);
    }

    /**
     * This method converts any brooklyn configuration starting with tf_var. into TERRAFORM environment variables
     */
    default Map<String, String> getShellEnvironment() {
        return getShellEnvironment(getEntity());
    }

    SimpleProcessTaskFactory<?,?,String,?> newCommandTaskFactory(boolean withEnvVars, String command);

    void copyTo(InputStream tfStream, String target);

    String makeTerraformCommand(String argument);

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


    default <T> T runQueued(TaskFactory<? extends TaskAdaptable<T>> task) {
        return DynamicTasks.queue(task).asTask().getUnchecked();
    }

    default <T> T retryUntilLockAvailable(String summary, Callable<T> job) {
        return ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).retryUntilLockAvailable(summary, job);
    }

    default void clearCurrentTagInessential() {
        ((BasicTask<?>) Tasks.current()).applyTagModifier(input -> {
            input.remove(TaskTags.INESSENTIAL_TASK);
            return null;
        });
    }

    /** path to active dir, with trailing slash */
    String getTerraformActiveDir();
    String computeHomeDir(boolean clearCache);

    default String getLogFileLocation() {
        return getStateFilePath();
    }
    default String getConfigurationFilePath() {
        return Os.mergePathsUnix(getTerraformActiveDir(), "configuration.tf");
    }
    default String getTfVarsFilePath() {
        return Os.mergePathsUnix(getTerraformActiveDir(), "terraform.tfvars");
    }
    default String getStateFilePath() {
        return Os.mergePathsUnix(getTerraformActiveDir(), "terraform.tfstate");
    }
    default String getLockFilePath() {
        return Os.mergePathsUnix(getTerraformActiveDir(), ".terraform.tfstate.lock.info");
    }

    default void runTerraformInitAndVerifyTask() {
        String initialized = runQueued(newCommandTaskFactory(true, initCommand())
                .summary("Initializing terraform infrastructure"));
        if (initialized.contains(EMPTY_TF_CFG_WARN)) {
            throw new IllegalStateException("Invalid or missing Terraform configuration: " + initialized);
        }
    }

    /**
     * @return {@code String} containing json state of the infrastructure
     */
    default String runShowTask() {
        return runQueued( newCommandTaskFactory(true, showCommand())
                .summary("Show (retrieve) the most recent state snapshot") );
    }

    // Needed for extracting pure Terraform output for the tf.plan sensor
    default String runPlanTask() {
        return runQueued( newCommandTaskFactory(true, planCommand())
                .summary("Analyse and create terraform plan (human readable change report)") );
    }

    default Map<String, Object> runJsonPlanTask() {
        DynamicTasks.queue(refreshTaskWithName("Refresh Terraform state", false));
        Task<String> planTask = DynamicTasks.queue(jsonPlanTaskWithName("Analyse and create terraform plan"));
        DynamicTasks.waitForLast();
        String result;
        try {
            result = planTask.get();
            return StateParser.parsePlanLogEntries(result);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Error running terraform plan (json)", e);
        }
    }

    default String runOutputTask() {
        DynamicTasks.queue(refreshTaskWithName("Gather terraform output", false));
        return runQueued( newCommandTaskFactory(true, outputCommand())
                .summary("Retrieving terraform outputs") );
    }

    default void runApplyTask() {
        DynamicTasks.queue(applyTaskWithName("Applying terraform plan"));
        DynamicTasks.waitForLast();
        getEntity().sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, Instant.now());
        // previously removed children here, but (1) there might be children we shouldn't remove; and (2) the synch should take care of that
        // now _caller_ should force a new plan instead
    }


    default Task<String> jsonPlanTaskWithName(final String name){
        return newCommandTaskFactory(true, jsonPlanCommand())
                .summary(name)
                .newTask().asTask();
    }

    default Task<String> applyTaskWithName(final String name) {
        return newCommandTaskFactory(true, applyCommand())
                .summary(name)
                .newTask().asTask();
    }

    default Task<String> refreshTaskWithName(final String name, boolean required) {
        Task<String> t = newCommandTaskFactory(true, refreshCommand()).summary(name).newTask().asTask();
        if (!required) TaskTags.markInessential(t);
        return t;
    }

    default int runRemoveLockFileTask() {
        int result = runQueued( newCommandTaskFactory(false, "rm "+getLockFilePath())
                .summary("Remove lock file")
                .returningExitCodeAllowingNonZero() );
        if (result==0) {
            try {
                // empirically, if lock file was just deleted, seems to need a second to recover (TBC)
                Tasks.withBlockingDetails("Waiting after forcibly deleting lock file", () -> {
                    Time.sleep(Duration.ONE_SECOND);
                    return null;
                });
            } catch (Exception e) {
                throw Exceptions.propagate(e);
            }
        }
        return result;
    }

    /**
     * Converts text into configuration.tf file and wrap it in a {@code KnownSizeInputStream}.
     * Or convert URL into  {@code InputStream}.
     * @return
     */
    default InputStream getConfiguration() {
        final String configurationUrl = getEntity().getConfig(CONFIGURATION_URL);
        if (Strings.isNonBlank(configurationUrl)) {
            return new ResourceUtils(getEntity()).getResourceFromUrl(configurationUrl);
        }
        final String configurationContents = getEntity().getConfig(CONFIGURATION_CONTENTS);
        if (Strings.isNonBlank(configurationContents)) {
            return KnownSizeInputStream.of(configurationContents);
        }
        throw new IllegalStateException("Could not resolve Terraform configuration from " +
                CONFIGURATION_URL.getName() + " or " + CONFIGURATION_CONTENTS.getName());
    }

    default void moveConfigurationFilesToBackupDir() {
        final String activePath = getTerraformActiveDir();
        final String backupPath = activePath + "../backup/";

        runQueued( newCommandTaskFactory(false, String.join(" ; ",
                "mkdir -p "+activePath,
                "rm -rf "+backupPath,
                "mkdir -p "+backupPath,
                "cd "+activePath,
                "mv * "+backupPath+" || echo nothing to backup",
                "mv "+backupPath+"*.tfstate . || echo no tfstate to restore"))
            .summary("Move existing configuration files to backup folder")
            .allowingNonZeroExitCode()  // working directory may be empty
        );
    }


    /**
     * If a `terraform.tfvars` file is present in the bundle is copied in the terraform workspace
     */
    default void copyTfVars(){
        final String varsURL = getEntity().getConfig(TFVARS_FILE_URL);
        if (Strings.isNonBlank(varsURL)) {
            InputStream tfStream =  new ResourceUtils(getEntity()).getResourceFromUrl(varsURL);
            copyTo(tfStream, getTfVarsFilePath());
        }
    }
    /**
     * If extra templates contents is specified, create those
     */
    default void copyTemplatesContents(){
        final Map<String, String> templates = getEntity().getConfig(EXTRA_TEMPLATES_CONTENTS);
        if (templates!=null && !templates.isEmpty()) {
            DynamicTasks.queue("Install extra TF files", () -> {
                templates.forEach((targetPath, template) -> {
                    DynamicTasks.queue("Install "+targetPath, () -> {
                        String remotePath;
                        if (Os.isAbsolutish(targetPath)) {
                            if (targetPath.startsWith("~/")) {
                                remotePath = Os.mergePathsUnix(computeHomeDir(false), targetPath.substring(2));
                            } else {
                                remotePath = targetPath;
                            }
                        } else {
                            remotePath = Os.mergePathsUnix(getTerraformActiveDir(), targetPath);
                        }

                        String templateResolved = TemplateProcessor.processTemplateContents(template, this, null);
                        LOG.debug("Installing template to "+remotePath+":\n"+templateResolved);

                        InputStream tfStream = new ByteArrayInputStream(templateResolved.getBytes());
                        copyTo(tfStream, remotePath);
                    });
                });
            });
        }
    }

    default void customize() {
        final String cfgPath = getConfigurationFilePath();

        DynamicTasks.queue(Tasks.create("Copy configuration file(s)", () -> {
            moveConfigurationFilesToBackupDir();
            // copy terraform configuration file or zip
            copyTo(getConfiguration(), cfgPath);
            copyTfVars();
            copyTemplatesContents();
        }));

        DynamicTasks.queue(newCommandTaskFactory(true,
                "if grep -q \"No errors detected\" <<< $(unzip -t "+ cfgPath +" ); then "
                        + "mv " + cfgPath + " " + cfgPath + ".zip && cd " + getTerraformActiveDir() + " &&"
                        + "unzip " + cfgPath + ".zip ; fi")
                .summary("Preparing configuration (unzip if necessary)..."));

        runTerraformInitAndVerifyTask();
    }

    default void launch() {
        ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.PRE_APPLY_WORKFLOW);
        ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.PRE_PLAN_WORKFLOW);

        final Map<String,Object> planLog =  retryUntilLockAvailable("terraform plan -json", () -> runJsonPlanTask());
        Task<Object> verifyPlanTask = Tasks.create("Verify plan", () -> {
            if (planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.ERROR) {
                throw new IllegalStateException(planLog.get(PLAN_MESSAGE) + ": " + planLog.get(PLAN_ERRORS));
            }
        }).asTask();
        Task<Object> checkAndApply =Tasks.create("Apply (if no existing deployment is found)", () -> {
            boolean deploymentExists = planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.SYNC;
            if (deploymentExists) {
                LOG.debug("Terraform plan exists!!");
            } else {
                runApplyTask();
            }
        }).asTask();

        retryUntilLockAvailable("launch, various terraform commands", () -> {
            TaskBuilder<Object> tb = Tasks.builder()
                    .displayName("Verify and apply terraform")
                    .add(verifyPlanTask)
                    .add(checkAndApply)
                    .add(refreshTaskWithName("Refresh Terraform state", false));
            ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).workflowTask(TerraformConfiguration.POST_APPLY_WORKFLOW)
                            .apply(t -> tb.add(t));
            DynamicTasks.queue(tb.build());
            DynamicTasks.waitForLast();

            return null;
        });
        // do a plan just after launch, to populate everything
        ((TerraformConfiguration)getEntity()).plan();
    }



    default void stop() {
        clearCurrentTagInessential();

        // see comments on clearLockFile effector (restarting Brooklyn might interrupt terraform, leaving lock files present)
        retryUntilLockAvailable("destroying", () -> {
            this.runRemoveLockFileTask();
            this.destroy(null);
            return null;
        });
    }

    default void destroy(Boolean alsoDestroyFiles) {
        Exception error = null;
        try {
            ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.PRE_DESTROY_WORKFLOW);
            DynamicTasks.queue( newCommandTaskFactory(true, destroyCommand())
                    .summary("Destroying terraform deployment.") );
            ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.POST_DESTROY_WORKFLOW);

            DynamicTasks.waitForLast();

            ((TerraformConfiguration) getEntity()).removeDiscoveredResources();
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            error = e;
        }
        if (Boolean.TRUE.equals(alsoDestroyFiles) || (alsoDestroyFiles==null && error==null)) {
            // delete files on stop if no error
            deleteFilesOnDestroy();
        }
        if (error!=null) Exceptions.propagate(error);
    }

    default void deleteFilesOnDestroy() {}

}
