package io.cloudsoft.terraform;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static io.cloudsoft.terraform.TerraformCommons.*;

public interface TerraformDriver extends SoftwareProcessDriver {
    static final Logger LOG = LoggerFactory.getLogger(TerraformDriver.class);

    String EMPTY_TF_CFG_WARN = "Terraform initialized in an empty directory!";

    String PLAN_STATUS = "tf.plan.status";
    String PLAN_PROVIDER = "tf.plan.provider";
    String PLAN_PROVIDERS = "tf.plan.providers";
    String RESOURCE_CHANGES = "tf.resource.changes";
    /** resources that terraform has found needs changes made to it to conform to the plan, mapped to the change object */
    String RESOURCES_CHANGES_PLANNED = "tf.resources.changes_planned";
    /** resources that terraform has found no longer matches the known state, mapped to the change object */
    String RESOURCES_DRIFT_DETECTED = "tf.resources.drift_detected";
    /** resources that terraform has found no longer matches the known state but does _not_ need changes made (e.g. simple refresh will update it), mapped to the change object;
     * this is not uncommon, e.g. where tags are null but then become {} later, or some aspect of state is allowed to change frequently */
    String RESOURCES_DRIFT_DETECTED_STATE_ONLY = "tf.resources.drift_detected.state_only";
    /** resources that terraform has found no longer matches the known state _and_ needs changes made, mapped to the change object */
    String RESOURCES_DRIFT_DETECTED_CHANGES_NEEDED = "tf.resources.drift_detected.changes_needed";

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

    default String prependTerraformExecutable(String argument) {
        return getTerraformExecutable() + " " + argument;
    }

    String getTerraformExecutable();

    String makeCommandInTerraformActiveDir(String command);

    // added these methods to underline the terraform possible commands
    default String initSubcommand() {
        return "init -input=false"; // Prepare your working directory for other commands
    }

    default String planSubcommand(boolean refresh, boolean json) {
        return "plan -lock=false -input=false -no-color"
                + (json ? " -json" : "")
                + (refresh ? "" : " -refresh=false");
    }
    default String applySubcommand() {
        // TODO use new config key, if set append here
        List<String> args = getEntity().config().get(TerraformConfiguration.EXTRA_APPLY_ARGS);
        return "apply -no-color -input=false -auto-approve" + (args==null ? "" : " "+Strings.join(args, " "));
    }

    default String applyRefreshOnly() {
        return applySubcommand("-refresh-only");
    }
    /** runs apply with the given subcommand; but note that -refresh-only is ignored if a plan is supplied */
    default String applySubcommand(String args) {
        return applySubcommand() + (Strings.isNonBlank(args) ? " "+args : "");
    }


    default <T> T runQueued(TaskFactory<? extends TaskAdaptable<T>> taskFactory) {
        return runQueued(taskFactory.newTask());
    }

    default <T> T runQueued(TaskAdaptable<T> task) {
        TaskAdaptable<T> t = DynamicTasks.queue(task);
        DynamicTasks.waitForLast();
        return t.asTask().getUnchecked();
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

    default void runTerraformInitAndVerifyResults() {
        String initialized = runQueued(taskForTerraformSubCommand(initSubcommand(), "terraform init"));
        if (initialized.contains(EMPTY_TF_CFG_WARN)) {
            throw new IllegalStateException("Invalid or missing Terraform configuration: " + initialized);
        }
        if (initialized.contains("calculate lock file checksums locally")) {
            runQueued(taskForTerraformSubCommand("providers lock", "terraform providers lock (detected as required)"));
        }
    }

    /**
     * @return {@code String} containing json state of the infrastructure
     */
    default String runShowTask() {
        return runQueued( taskForTerraformSubCommand("show -no-color -json", "terraform show"));
    }

    /**
     * @return {@code String} containing tf state
     */
    default String runStatePullTask() {
        return runQueued( taskForTerraformSubCommand("state pull") );
    }

    // Needed for extracting pure Terraform output for the tf.plan sensor
    default String runPlanTask() {
        return runQueued( taskForTerraformSubCommand(planSubcommand(true, false), "terraform plan (human-readable output)") );
    }

    default String runJsonPlanTask(boolean doRefresh) {
        return runJsonPlanTask(doRefresh, null, null);
    }
    default String runJsonPlanTask(boolean doRefresh, String filename, String args) {
        try {
            if (doRefresh) {
                // `plan` does not update tf state, it just makes a plan that will include that if needed;
                // but we can apply the plan as refresh-only to do the state-update only
                // thus the following seems the fastest way to do a refresh and get the plan output

                String planResult = runQueued(newCommandTaskFactory(true,
                        makeCommandInTerraformActiveDir(
                                prependTerraformExecutable(planSubcommand(doRefresh /* true */, true) +
                                        (filename!=null ? " -out=" + filename : "") +
                                        (args!=null ? " "+args : ""))))
                        .summary("terraform plan")
                        .newTask().asTask());

                return planResult;

            } else {
                // -refresh=false doesn't seem to speed up planning much at all (it still needs online access)
                // but worth doing for good measure
                return runQueued(taskForTerraformSubCommand(planSubcommand(false, true), "terraform plan (and update resources and drift)"));
            }
        } catch (Exception e) {
            throw Exceptions.propagateAnnotated("Error running terraform plan (json)", e);
        }
    }

    default String runOutputTask(boolean doRefresh) {
        if (doRefresh) DynamicTasks.queue(refreshTaskWithName("Refresh state to gather output", false));
        return runQueued( taskForTerraformSubCommand("output -no-color -json", "terraform output") );
    }

    default void runApplyTask() {
        runQueued(taskForTerraformSubCommand(applySubcommand(), "terraform apply"));
        getEntity().sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, Instant.now());
        // previously removed children here, but (1) there might be children we shouldn't remove; and (2) the synch should take care of that
        // now _caller_ should force a new plan instead
    }

    default Task<String> taskForTerraformSubCommand(final String terraformSubCommand) {
        return taskForTerraformSubCommand(terraformSubCommand, "terraform "+terraformSubCommand);
    }

    default Task<String> taskForTerraformSubCommand(final String terraformSubCommand, String name) {
        return newCommandTaskFactory(true,
                makeCommandInTerraformActiveDir(prependTerraformExecutable(terraformSubCommand)))
                .summary(name)
                .newTask().asTask();
    }

    default Task<String> refreshTaskWithName(final String name, boolean required) {
        Task<String> t = taskForTerraformSubCommand(applyRefreshOnly(), name);
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
     * Converts text into a stream of TF text contents or ZIP contents, wrapped in a {@code KnownSizeInputStream}.
     * Or convert URL into  {@code InputStream}.
     * @return
     */
    default InputStream getConfiguration() {
        Supplier<InputStream> streamSource = getEntity().getConfig(CONFIGURATION_STREAM_SOURCE);
        if (streamSource!=null) return streamSource.get();

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
        final String backupPath = activePath + getBackupDirRelativeToActiveDir();

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

    default String getBackupDirRelativeToActiveDir() {
        return "../backup/";
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

        boolean ignoreZipStateConflicts = Boolean.TRUE.equals(getEntity().config().get(TF_STATE_CONFLICTS_IN_ZIP_IGNORED));
        DynamicTasks.queue(newCommandTaskFactory(true, Strings.lines(
                "if grep -q \"No errors detected\" <<< $(unzip -t "+ cfgPath +" ); then",
                "  echo ZIP file detected. Unzipping it.",
                "  cd "+getTerraformActiveDir(),
                "  mv " + cfgPath + " ../latest_configuration.zip",
                "  if (unzip -l ../latest_configuration.zip .\\* > /dev/null) ; then echo Dot files not permitted in ZIP ; exit 1 ; fi",
                // TODO could have config to say whether -n (ignore *.tfstate files in zip which overwrite), as below,
                // or to fail if tfstate in zip and also on disk (previous behaviour, but not intentional)
                "  unzip "+ (ignoreZipStateConflicts ? "-n " : "") + "../latest_configuration.zip",
                "fi"))
                .summary("Preparing configuration (unzip if necessary)..."));

        runTerraformInitAndVerifyResults();
        DynamicTasks.waitForLast();
    }

    default void launch() {
        ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.PRE_APPLY_WORKFLOW);
        ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.PRE_PLAN_WORKFLOW);

        // previously we did extensive plan/checks before apply (above); but this was slow and noisy in the UI, so prefer below
        retryUntilLockAvailable("apply", () -> { runApplyTask(); return null; });
        ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.POST_APPLY_WORKFLOW);


        // do a plan just after launch, to populate everything
        TaskBuilder<Object> tb = Tasks.builder()
                .displayName("Update model and sensors after apply")
                .body(() -> {
                    // replan to update drift and populate the model, then set output;
                    // previously we used -refresh=false but that doesn't speed it up as much as we would hope;
                    // we also sometimes find there is additional state change found on refresh, so worth doing usually
                    ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).planInternal(true);
                });
        runQueued(tb.build());

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
            runQueued( taskForTerraformSubCommand("apply -destroy -auto-approve -no-color", "terraform destroy") );
            ((TerraformConfigurationImpl) Entities.deproxy(getEntity())).runWorkflow(TerraformConfiguration.POST_DESTROY_WORKFLOW);

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
