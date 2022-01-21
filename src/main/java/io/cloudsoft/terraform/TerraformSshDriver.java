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
        Task<String> destroyTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), destroyCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Destroying terraform deployment.")
                .returning(p -> p.getStdout()).newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (destroyTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform destroy`! ");
            // TODO decide where we put the output from here, in case of error
        }
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
        String archType = os.is64bit() ? "amd64" : "386";

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

        Task<Object> initTask = DynamicTasks.queue(Tasks.builder()
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
        if (initTask.asTask().isError()) {
            throw new IllegalStateException("Error cleaning the terraform workspace. ");
        }
    }

    // Order of execution during AMP deploy: step 3 - zip up the current configuration files if any, unzip the new configuration files, run `terraform init -input=false`
    @Override
    public void customize() {
        final String empty_dir ="Terraform initialized in an empty directory!";
        newScript(CUSTOMIZING).execute();
        clean();
        InputStream configuration = getConfiguration();
        // copy terraform configuration file(s)
        getMachine().copyTo(configuration, getConfigurationFilePath());
        copyTfVars();

        final String cfgPath= getConfigurationFilePath();

        Task<Object> initTask = DynamicTasks.queue(Tasks.builder()
                        .displayName("Initializing terraform workspace")
                .add(SshTasks.newSshExecTaskFactory(getMachine(),
                                "if grep -q \"No errors detected\" <<< $(unzip -t "+ cfgPath +" ); then "
                                        + "mv " + cfgPath + " " + cfgPath + ".zip && cd " + getRunDir() + " &&"
                                         + "unzip " + cfgPath + ".zip ; fi")
                        .requiringExitCodeZero()
                        .environmentVariables(getShellEnvironment())
                        .summary("Preparing configuration (unzip of necessary)...")
                        .newTask()
                        .asTask())
                .add(SshTasks.newSshExecTaskFactory(getMachine(), initCommand())
                        .requiringZeroAndReturningStdout()
                        .environmentVariables(getShellEnvironment())
                        .summary("Initializing terraform infrastructure")
                        .newTask()
                        .asTask())
                .build());
        DynamicTasks.waitForLast();

        try {
            final StringBuilder message = new StringBuilder();
            List<Object> result = (List<Object>) initTask.get();
            for(Object o : result) {
                if(!(o instanceof Integer)) {
                    message.append(o.toString());
                }
            }
            DynamicTasks.queue(
                    Tasks.create("Verifying Terraform Workspace", () -> {
                        if(message.toString().contains(EMPTY_TF_CFG_WARN)) {
                            throw new IllegalStateException("Invalid or missing Terraform configuration." + message);
                        }
                    }).asTask());
            DynamicTasks.waitForLast();

        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform init -input=false`!", e);
        }
    }

    @Override
    public void launch() {
        Map<String,Object> planLog = runJsonPlanTask();
        boolean deploymentExists = planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.SYNC;
        if(deploymentExists) {
            LOG.debug("Terraform plan exists!!");
        } else {
            runApplyTask();
            runJsonPlanTask();
            runLightApplyTask();
        }
    }

    /**
     *
     * @return {@code true} if deployment already exists
     */
    public Map<String, Object> runJsonPlanTask() {
        Task<String> planTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), jsonPlanCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Initializing terraform plan")
                .returning(ProcessTaskWrapper::getStdout)
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();
        String result;
        try {
            result = planTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan -json`!", e);
        }
        Map<String,Object> planLog = StateParser.parsePlanLogEntries(result);
        return  planLog;
    }

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
        Task<String> applyTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), applyCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Applying terraform plan")
                .requiringZeroAndReturningStdout()
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (applyTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform apply`!");
        }
        entity.sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, new SimpleDateFormat("EEE, d MMM yyyy, HH:mm:ss").format(Date.from(Instant.now())));
    }

    private void runLightApplyTask() {
        Task<String> applyTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), lightApplyCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Applying `terraform apply -refresh-only`")
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (applyTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform apply -refresh-only`!");
        }
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
}
