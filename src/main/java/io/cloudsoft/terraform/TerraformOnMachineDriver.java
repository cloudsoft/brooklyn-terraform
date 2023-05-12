package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_PATH;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

public abstract class TerraformOnMachineDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformOnMachineDriver.class);

    protected Boolean terraformAlreadyAvailable;
    protected Boolean terraformInPath;

    public TerraformOnMachineDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    public abstract ProcessTaskFactory<String> newCommandTaskFactory(boolean withEnvVars, String command);

    @Override
    public void copyTo(InputStream tfStream, String target) {
        getMachine().copyTo(tfStream, target);
    }

    public String getTerraformActiveDir() {
        return getRunDir() + "/" + "active/";
    }

    transient String cachedHomeDir = null;
    @Override
    public String computeHomeDir(boolean clearCache) {
        if (clearCache || cachedHomeDir==null) {
            cachedHomeDir = DynamicTasks.queue(newCommandTaskFactory(false, "cd ~ && pwd").requiringZeroAndReturningStdout()).get();
        }
        return cachedHomeDir;
    }

    public String getTerraformExecutable() {
        String terraformExplicitPath = entity.getConfig(TerraformConfiguration.TERRAFORM_PATH);
        if (Strings.isNonBlank(terraformExplicitPath)) return terraformExplicitPath;
        return getDefaultTerraformExecutable();
    }

    public abstract String getDefaultTerraformExecutable();

    public String makeTerraformCommand(String argument) {
        return format("cd %s && %s %s", getTerraformActiveDir(), getDefaultTerraformExecutable(), argument);
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
        if (terraformAlreadyAvailable()) {
            setExpandedInstallDir("");
            return;
        }
        if (getEntity().getConfig(TERRAFORM_PATH)!=null) throw new IllegalStateException("Requested to use terraform from path '"+getEntity().getConfig(TERRAFORM_PATH)+"' but it was not found");
        downloadTerraform();
    }

    protected void downloadTerraform() {
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
        setExpandedInstallDir(getInstallDir());
    }

    @Override
    public void customize() {
        entity.sensors().set(Attributes.LOG_FILE_LOCATION, this.getLogFileLocation());
        TerraformDriver.super.customize();
    }

    /** use 'which' to find terraform, at the specified location if TERRAFORM_PATH or else on the default PATH */
    protected final boolean terraformAlreadyAvailable() {
        if (terraformInPath == null) {
            terraformInPath = false;
            String terraformExplicitPath = entity.getConfig(TerraformConfiguration.TERRAFORM_PATH);
            String terraformWithPath = Strings.isBlank(terraformExplicitPath) ? "terraform" : terraformExplicitPath;

            Task<Boolean> terraformLocalPathTask = newCommandTaskFactory(true, "which "+terraformWithPath)
                    .allowingNonZeroExitCode()
                    .returningIsExitCodeZero()
                    .summary("Checking presence of Terraform in the system")
                    .newTask()
                    .asTask();

            try {
                terraformInPath = DynamicTasks.queue(terraformLocalPathTask).get();
            } catch (InterruptedException | ExecutionException e) {
                throw Exceptions.propagate(e);
            }
        }
        return terraformInPath;
    }

    protected String removeCommandFromPathAndClean(String path) {
        String cleanPath = StringUtils.removeEnd(path, "\n");
        cleanPath = StringUtils.removeEnd(cleanPath, "terraform");
        cleanPath = StringUtils.removeEnd(cleanPath, "/");
        return cleanPath;
    }
}
