package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformSshDriver.class);
    private final String EMPTY_TF_CFG_WARN ="Terraform initialized in an empty directory!";

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
        final String ARM_ARCH_PATTERNS = "(arm|aarch)\\w*";
        OsDetails os = getLocation().getOsDetails();
        // If no details, assume 64-bit Linux
        if (os == null) return "linux_amd64";
        // If not Mac, assume Linux
        String osType = os.isMac() ? "darwin" : "linux";
        String archType = os.is64bit() ?
                Pattern.matches(ARM_ARCH_PATTERNS, os.getArch().toLowerCase()) ? "arm64" : "amd64":
                Pattern.matches(ARM_ARCH_PATTERNS, os.getArch().toLowerCase()) ? "arm" : "386";

        return osType + "_" + archType;
    }

    // Order of execution during AMP deploy: step 1 - set properties from the configuration on the entity and create terraform install directory
    @Override
    public void preInstall() {
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
        newScript(CUSTOMIZING).execute();
        clean();
        InputStream configuration = getConfiguration();
        // copy terraform configuration file or zip
        getMachine().copyTo(configuration, getConfigurationFilePath());
        copyTfVars();

        final String cfgPath= getConfigurationFilePath();
        Task<Integer> unzipTask = SshTasks.newSshExecTaskFactory(getMachine(),
                        "if grep -q \"No errors detected\" <<< $(unzip -t "+ cfgPath +" ); then "
                                + "mv " + cfgPath + " " + cfgPath + ".zip && cd " + getRunDir() + " &&"
                                + "unzip " + cfgPath + ".zip ; fi")
                .requiringExitCodeZero()
                .environmentVariables(getShellEnvironment())
                .summary("Preparing configuration (unzip of necessary)...")
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
                .add(unzipTask)
                .add(initTask)
                .add(verifyTask)
                .build());
        DynamicTasks.waitForLast();
    }

    @Override
    public void launch() {
        final Map<String,Object> planLog = runJsonPlanTask();
        Task<Object> verifyPlanTask = Tasks.create("Verify Plan", () -> {
            if(planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.ERROR) {
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
                .displayName("Creating the planned infrastructure")
                .add(verifyPlanTask)
                .add(checkAndApply)
                .add(refreshTaskWithName("Refreshing Terraform state")).build());
        DynamicTasks.waitForLast();
    }

    @Override // used for polling as well
    public Map<String, Object> runJsonPlanTask() {
        Task<String> planTask = DynamicTasks.queue(jsonPlanTaskWithName("Creating the plan => 'tfplan'"));
        DynamicTasks.waitForLast();
        String result;
        try {
            result = planTask.get();
            return StateParser.parsePlanLogEntries(result);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan -json`!", e);
        }
    }

    // Needed for extracting pure Terraform output for the tf.plan sensor
    @Override
    public String runPlanTask() {
        Task<String> planTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), planCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Inspecting terraform plan changes")
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();
        try {
            return planTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    @Override
    public String runOutputTask() {
        Task<String> outputTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), outputCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Retrieving terraform outputs")
                .returning(p -> p.getStdout()).newTask()
                .asTask());
        DynamicTasks.waitForLast();
        try {
           return outputTask.get(); // TODO Should we allow this task to err ?
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    @Override
    public void runApplyTask() {
       DynamicTasks.queue(applyTaskWithName("Applying terraform plan"));
       DynamicTasks.waitForLast();
       entity.sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, new SimpleDateFormat("EEE, d MMM yyyy, HH:mm:ss").format(Date.from(Instant.now())));
       entity.getChildren().forEach(entity::removeChild);
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

    private Task refreshTaskWithName(final String name) {
        return SshTasks.newSshExecTaskFactory(getMachine(), refreshCommand())
                .environmentVariables(getShellEnvironment())
                .summary(name)
                .requiringZeroAndReturningStdout()
                .newTask()
                .asTask();
    }

    @Override
    public String getEnvironmentDir() {
        String baseDir = super.getRunDir();
        String workingDirectory = getWorkingDirectory();
        return Strings.isEmpty(workingDirectory) ?
                baseDir :
                baseDir + "/" + workingDirectory;
    }

    public String getWorkingDirectory() {
        String workingDir = entity.getConfig(TerraformConfiguration.WORKING_DIRECTORY);
        return workingDir.startsWith("/") ?
                removeInitialSlashes(workingDir) :
                workingDir;
    }

    private String removeInitialSlashes(String workingDir) {
        //todojd improve + test
        while(workingDir.startsWith("/")){
            workingDir = workingDir.substring(1);
        }
        return workingDir;
    }
}
