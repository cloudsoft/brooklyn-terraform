package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.json.ShellEnvironmentSerializer;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskTags;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_PATH;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformSshDriver.class);
    public static final String WHICH_TERRAFORM_COMMAND = "which terraform";
    private final String EMPTY_TF_CFG_WARN = "Terraform initialized in an empty directory!";

    private Boolean terraformAlreadyAvailable;
    private Boolean terraformInPath;

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
        entity.sensors().set(Attributes.LOG_FILE_LOCATION, this.getLogFileLocation());
    }

    @Override
    public int runDestroyTask() {
        DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), destroyCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Destroying terraform deployment.")
                .returning(p -> p.getStdout()).newTask()
                .asTask());
        DynamicTasks.waitForLast();
        return 0;
    }

    @Override
    public int destroy() {
        return runDestroyTask();
    }

    @Override
    public void stop() {
        destroy();
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    protected String getLogFileLocation() {
        return getStateFilePath();
    }

    public String getOsTag() {
        OsDetails os = getLocation().getOsDetails();
        // If no details, assume 64-bit Linux
        if (os == null) return "linux_amd64";
        // If not Mac, assume Linux
        String osType = os.isMac() ? "darwin" : "linux";
        String archType = os.is64bit() ?
                os.isArm() ? "arm64" : "amd64":
                os.isArm() ? "arm" : "386";

        return osType + "_" + archType;
    }

    // Order of execution during AMP deploy: step 1 - set properties from the configuration on the entity and create terraform install directory
    @Override
    public void preInstall() {
        if (terraformAlreadyAvailable()) return;
        final String installFileName = format("terraform_%s_%s.zip", getVersion(), getOsTag());
        resolver = Entities.newDownloader(this, ImmutableMap.of("filename", installFileName));
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("terraform_%s_%s", getVersion(), getOsTag()))));
        entity.sensors().set(Attributes.DOWNLOAD_URL, TERRAFORM_DOWNLOAD_URL
                .replace("${version}", getVersion())
                .replace("${driver.osTag}", getOsTag()));
    }

    // Order of execution during AMP deploy: step 2 - download and install (unzip) the terraform executable
    @Override
    public void install() {
        if (terraformAlreadyAvailable()) return;
        DynamicTasks.queue(Tasks.create("Downloading Terraform " + getVersion(), () -> {
                        List<String> urls = resolver.getTargets();
                        String saveAs = resolver.getFilename();

                        List<String> commands = new LinkedList<>();
                        commands.add(BashCommands.INSTALL_ZIP);
                        commands.add(BashCommands.INSTALL_UNZIP);
                        commands.add(BashCommands.INSTALL_CURL);
                        // Hashicorp server requires at least TLSv1.2
                        commands.addAll(commandsToDownloadUrlsAsWithMinimumTlsVersion(urls, saveAs, "1.2"));
                        commands.add(format("unzip -o %s", saveAs));

                        newScript(INSTALLING).body.append(commands).execute();
                }).asTask());
        DynamicTasks.waitForLast();
    }

    private void clean() {
        final String runPath = getRunDir();
        DynamicTasks.queue(Tasks.builder()
                .displayName("Clean terraform workspace")
                .add(SshTasks.newSshExecTaskFactory(getMachine(),
                                "rm -rf /tmp/backup; mkdir /tmp/backup; cd " +runPath +";" +
                                        " mv * /tmp/backup; mv /tmp/backup/*.tfstate .")
                        .environmentVariables(getShellEnvironment())
                        .summary("Moves existing configuration files to /tmp/backup.")
                        .returning(p -> p.getStdout())
                        .newTask()
                        .asTask())
                .build());
        DynamicTasks.waitForLast();
    }

    // Order of execution during AMP deploy: step 3 - zip up the current configuration files if any, unzip the new configuration files, run `terraform init -input=false`
    @Override
    public void customize() {
        final String cfgPath= getConfigurationFilePath();
        Task<Object> copyTask = Tasks.create("Copy configuration file(s)", () -> {
            newScript(CUSTOMIZING).execute();
            clean();
            InputStream configuration = getConfiguration();
            // copy terraform configuration file or zip
            getMachine().copyTo(configuration, cfgPath);
            copyTfVars();
        }).asTask();
        Task<Integer> unzipTask = SshTasks.newSshExecTaskFactory(getMachine(),
                        "if grep -q \"No errors detected\" <<< $(unzip -t "+ cfgPath +" ); then "
                                + "mv " + cfgPath + " " + cfgPath + ".zip && cd " + getRunDir() + " &&"
                                + "unzip " + cfgPath + ".zip ; fi")
                .requiringExitCodeZero()
                .environmentVariables(getShellEnvironment())
                .summary("Preparing configuration (unzip if necessary)...")
                .newTask()
                .asTask();
        Task<String> initTask = SshTasks.newSshExecTaskFactory(getMachine(), initCommand())
                .requiringZeroAndReturningStdout()
                .environmentVariables(getShellEnvironment())
                .summary("Initializing terraform infrastructure")
                .newTask()
                .asTask();

        Task<Object> verifyTask =  Tasks.create("Verifying Terraform Workspace", () -> {
            try {
                String result =  initTask.get();
                if(result.contains(EMPTY_TF_CFG_WARN)) {
                    throw new IllegalStateException("Invalid or missing Terraform configuration." + result);
                }

            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException("Cannot retrieve result of command `terraform init -input=false`!", e);
            }
        }).asTask();
        DynamicTasks.queue(Tasks.builder()
                        .displayName("Initializing terraform workspace")
                .add(copyTask)
                .add(unzipTask)
                .add(initTask)
                .add(verifyTask)
                .build());
        DynamicTasks.waitForLast();
    }

    @Override
    public void launch() {
        final Map<String,Object> planLog = runJsonPlanTask();
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

        DynamicTasks.queue(Tasks.builder()
                .displayName("Verify and apply terraform")
                .add(verifyPlanTask)
                .add(checkAndApply)
                .add(refreshTaskWithName("Refresh Terraform state", false)).build());
        DynamicTasks.waitForLast();
    }

    @Override // used for polling as well
    public Map<String, Object> runJsonPlanTask() {
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

    // Needed for extracting pure Terraform output for the tf.plan sensor
    @Override
    public String runPlanTask() {
        Task<String> planTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), planCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Analyse and create terraform plan (human readable change report)")
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();
        try {
            return planTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Error running terraform plan", e);
        }
    }

    @Override
    public String runOutputTask() {
        DynamicTasks.queue(refreshTaskWithName("Gather terraform output", false));
        Task<String> outputTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), outputCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Retrieving terraform outputs")
                .returning(p -> p.getStdout()).newTask()
                .asTask());
        DynamicTasks.waitForLast();
        try {
           return outputTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Error gathering terraform output", e);
        }
    }

    @Override
    public void runApplyTask() {
       DynamicTasks.queue(applyTaskWithName("Applying terraform plan"));
       DynamicTasks.waitForLast();
       entity.sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, new SimpleDateFormat("EEE, d MMM yyyy, HH:mm:ss").format(Date.from(Instant.now())));
       // previously removed children here, but (1) there might be children we shouldn't remove; and (2) the synch should take care of that
       // now force a new plan instead
       ((TerraformConfiguration)entity).plan();
    }

    /**
     *
     * @return {@code String} containing json state of the infrastructure
     */
    @Override
    public String runShowTask() {
        Task<String> showTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), showCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Retrieve the most recent state snapshot")
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();

        try {
            return showTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    /**
     * Converts text into configuration.tf file and wrap it in a {@code KnownSizeInputStream}.
     * Or convert URL into  {@code InputStream}.
     * @return
     */
    private InputStream getConfiguration() {
        final String configurationUrl = entity.getConfig(TerraformConfiguration.CONFIGURATION_URL);
        if (Strings.isNonBlank(configurationUrl)) {
            return new ResourceUtils(entity).getResourceFromUrl(configurationUrl);
        }
        final String configurationContents = entity.getConfig(TerraformConfiguration.CONFIGURATION_CONTENTS);
        if (Strings.isNonBlank(configurationContents)) {
            return KnownSizeInputStream.of(configurationContents);
        }
        throw new IllegalStateException("Could not resolve Terraform configuration from " +
                TerraformConfiguration.CONFIGURATION_URL.getName() + " or " + TerraformConfiguration.CONFIGURATION_CONTENTS.getName());
    }

    /**
     * If a `terraform.tfvars` file is present in the bundle is copied in the terraform workspace
     */
    private void  copyTfVars(){
        final String varsURL = entity.getConfig(TerraformConfiguration.TFVARS_FILE_URL);
        if (Strings.isNonBlank(varsURL)) {
            InputStream tfStream =  new ResourceUtils(entity).getResourceFromUrl(varsURL);
            getMachine().copyTo(tfStream, getTfVarsFilePath());
        }
    }

    public Map<String, String> getShellEnvironment() {
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

    private Task jsonPlanTaskWithName(final String name){
        return SshTasks.newSshExecTaskFactory(getMachine(), jsonPlanCommand())
                .environmentVariables(getShellEnvironment())
                .summary(name)
                .returning(ProcessTaskWrapper::getStdout)
                .newTask()
                .asTask();
    }

    private Task applyTaskWithName(final String name) {
        return SshTasks.newSshExecTaskFactory(getMachine(), applyCommand())
                .environmentVariables(getShellEnvironment())
                .summary(name)
                .requiringZeroAndReturningStdout()
                .newTask()
                .asTask();
    }

    private Task refreshTaskWithName(final String name, boolean required) {
        Task<String> t = SshTasks.newSshExecTaskFactory(getMachine(), refreshCommand())
                .environmentVariables(getShellEnvironment())
                .summary(name)
                .requiringZeroAndReturningStdout()
                .newTask()
                .asTask();
        if (!required) TaskTags.markInessential(t);
        return t;
    }

    private boolean terraformAlreadyAvailable() {
        if (terraformAlreadyAvailable == null) {
            final String explicitPath = entity.getConfig(TerraformConfiguration.TERRAFORM_PATH);
            if (Strings.isNonBlank(explicitPath)) {
                // Check the explicit path provided
                terraformAlreadyAvailable = new File(explicitPath).exists() || new File(explicitPath + File.pathSeparator + "terraform").exists();
                if(!terraformAlreadyAvailable){
                    throw new IllegalArgumentException("Terraform not found at location indicated in config key `"+ TERRAFORM_PATH.getName()+"`: "+explicitPath);
                }
                String runDir = removeCommandFromPathAndClean(entity.getConfig(TERRAFORM_PATH));
                setExpandedInstallDir(runDir);
                setInstallDir(runDir);
            } else
                // try to find Terraform in the system if the config allow it
                terraformAlreadyAvailable = entity.getConfig(TerraformConfiguration.LOOK_FOR_TERRAFORM_INSTALLED) && terraformInPath();
        }
        return terraformAlreadyAvailable;
    }

    private boolean terraformInPath() {
        if (terraformInPath == null) {
            terraformInPath = false;
            Task<String> terraformLocalPathTask = SshTasks.newSshExecTaskFactory(getMachine(), WHICH_TERRAFORM_COMMAND)
                    .requiringZeroAndReturningStdout()
                    .environmentVariables(getShellEnvironment())
                    .summary("Searching for Terraform in the system.")
                    .newTask()
                    .asTask();
            DynamicTasks.queue(terraformLocalPathTask);
            DynamicTasks.waitForLast();
            try {
                String output = terraformLocalPathTask.get();
                if (Strings.isNonEmpty(output) && output.toLowerCase().contains("terraform")) {
                    output = removeCommandFromPathAndClean(output);
                    setExpandedInstallDir(output);
                    setInstallDir(output);
                    terraformInPath = true;
                }
            } catch (InterruptedException | ExecutionException e) {
                LOG.debug("Terraform not found in path");
            }
        }
        return terraformInPath;
    }

    private String removeCommandFromPathAndClean(String path) {
        String cleanPath = StringUtils.removeEnd(path, "\n");
        cleanPath = StringUtils.removeEnd(cleanPath, "terraform");
        cleanPath = StringUtils.removeEnd(cleanPath, "/");
        return cleanPath;
    }
}
