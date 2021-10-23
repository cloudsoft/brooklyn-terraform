package io.cloudsoft.terraform;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.parser.PlanLogEntry;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
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
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformSshDriver.class);

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
        entity.sensors().set(TerraformConfiguration.CONFIGURATION_IS_APPLIED, false);
        return 0;
    }

    @Override
    public int runDestroyTargetTask(String target) {
        Task<String> destroyTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(),target)
                .environmentVariables(getShellEnvironment())
                .summary("Destroying terraform resource.")
                .returning(p -> p.getStdout()).newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (destroyTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform destroy` on resource! ");
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

    @Override
    public void preInstall() {
        final String installFileName = format("terraform_%s_%s.zip", getVersion(), getOsTag());
        resolver = Entities.newDownloader(this, ImmutableMap.of("filename", installFileName));
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("terraform_%s_%s", getVersion(), getOsTag()))));
        entity.sensors().set(Attributes.DOWNLOAD_URL, TERRAFORM_DOWNLOAD_URL
                .replace("${version}", getVersion())
                .replace("${driver.osTag}", getOsTag()));
    }

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

    @Override
    public void customize() {
        newScript(CUSTOMIZING).execute();
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
                        .environmentVariables(getShellEnvironment())
                        .summary("Preparing configuration (unzip of necessary)...")
                        .requiringExitCodeZero().newTask()
                        .asTask())
                .add(SshTasks.newSshExecTaskFactory(getMachine(), initCommand())
                        .environmentVariables(getShellEnvironment())
                        .summary("Initializing terraform infrastructure")
                        .requiringZeroAndReturningStdout().newTask()
                        .asTask())
                .build());
        DynamicTasks.waitForLast();

        if (initTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform init`! ");
        }
    }

    @Override
    public void launch() {
        Map<String,Object> planLog = runJsonPlanTask();
        PlanLogEntry.Provider provider = (PlanLogEntry.Provider) planLog.get(PLAN_PROVIDER);
        boolean deploymentExists = planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.SYNC;
        if(deploymentExists) {
            LOG.debug("Terraform plan exists!!");
        } else {
            runApplyTask();
            // workaround for vsphere
            if (provider == PlanLogEntry.Provider.VSPHERE) {
                runJsonPlanTask();
                runApplyTask();
            }
        }
    }

    @Override
    public void postLaunch() {
        final String state = runShowTask();
        StateParser.parseResources(state).forEach((resourceName, resourceContents) ->  {
            Map<String,Object> contentsMap = (Map<String,Object>) resourceContents;
            this.entity.addChild(
                    EntitySpec.create(ManagedResource.class)
                            .configure(ManagedResource.STATE_CONTENTS, contentsMap)
                            .configure(ManagedResource.TYPE, contentsMap.get("resource.type").toString())
                            .configure(ManagedResource.PROVIDER, contentsMap.get("resource.provider").toString())
                            .configure(ManagedResource.ADDRESS, contentsMap.get("resource.address").toString())
                            .configure(ManagedResource.NAME, contentsMap.get("resource.name").toString())
            );
        });
    }

    /**
     *
     * @return {@code true} if deployment already exists
     */
    public Map<String, Object> runJsonPlanTask() {
        Task<String> planTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), jsonPlanCommand())
                .environmentVariables(getShellEnvironment())
                .summary("Initializing terraform plan")
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();
        String result;
        try {
            result = planTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan -json`!", e);
        }
        return  StateParser.parsePlanLogEntries(result);
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
                .returning(p -> p.getStdout())
                .newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (applyTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform apply`!");
        }
        entity.sensors().set(TerraformConfiguration.CONFIGURATION_IS_APPLIED, true);
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
