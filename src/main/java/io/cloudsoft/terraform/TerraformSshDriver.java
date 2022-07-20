package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
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
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.cloudsoft.terraform.TerraformCommons.TFVARS_FILE_URL;
import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_PATH;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformSshDriver.class);
    public static final String WHICH_TERRAFORM_COMMAND = "which terraform";

    private Boolean terraformAlreadyAvailable;
    private Boolean terraformInPath;

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
        entity.sensors().set(Attributes.LOG_FILE_LOCATION, this.getLogFileLocation());
    }

    public ProcessTaskFactory<String> newCommandTaskFactory(boolean withEnvVars, String command) {
        ProcessTaskFactory<String> tf = SshTasks.newSshExecTaskFactory(getMachine(), command).requiringZeroAndReturningStdout();
        if (withEnvVars) tf.environmentVariables(getShellEnvironment());
        return tf;
    }

    @Override
    public void copyTo(InputStream tfStream, String target) {
        getMachine().copyTo(tfStream, target);
    }

    public String getTerraformActiveDir() {
        return getRunDir() + "/" + "active/";
    }

    public String makeTerraformCommand(String argument) {
        return format("cd %s && %s/terraform %s", getTerraformActiveDir(), getInstallDir(), argument);
    }

    @Override
    public boolean isRunning() {
        return true;
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

    @Override // the one from {@code AbstractSoftwareProcessSshDriver}
    public Map<String, String> getShellEnvironment() {
        return TerraformDriver.super.getShellEnvironment();
    }

    @Override public void launch() { TerraformDriver.super.launch(); }
    @Override public void stop() { TerraformDriver.super.stop(); }

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

    // Order of execution during AMP deploy: step 3 - zip up the current configuration files if any, unzip the new configuration files, run `terraform init -input=false`
    @Override
    public void customize() {
        DynamicTasks.queue(Tasks.create("Standard customization", () -> {
            newScript(CUSTOMIZING).execute();
        }));

        TerraformDriver.super.customize();
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
                String terraformExecDir = removeCommandFromPathAndClean(entity.getConfig(TERRAFORM_PATH));
                setExpandedInstallDir(terraformExecDir);
                setInstallDir(terraformExecDir);
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
