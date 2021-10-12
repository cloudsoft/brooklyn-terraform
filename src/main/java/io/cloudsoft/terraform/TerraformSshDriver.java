package io.cloudsoft.terraform;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.lifecycle.ScriptHelper;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);

    Map<String, String> envVars = MutableMap.of(); // TODO set these

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
        entity.sensors().set(Attributes.LOG_FILE_LOCATION, this.getLogFileLocation());
    }

    @Override
    public String init() {
        return makeTerraformCommand("init -input=false");
    }

    @Override
    public String plan() {
        return makeTerraformCommand("plan -out=tfplan -no-color");
    }

    @Override
    public String apply() {
        return makeTerraformCommand("apply -no-color -input=false tfplan");
    }

    @Override
    public String show() {
        return makeTerraformCommand("show -no-color");
    }

    @Override
    public String refresh() {
        return makeTerraformCommand("refresh -input=false -no-color");
    }

    @Override
    public String output() {
        return makeTerraformCommand("output -no-color --json -lock=false");
    }

    @Override
    public int destroy() {
        LOG.debug(" <T> 'destroy -auto-approve -no-color' ");
        ScriptHelper stopScript = newScript(STOPPING)
                .body.append(makeTerraformCommand("destroy -auto-approve -no-color"))
                .environmentVariablesReset(getShellEnvironment())
                .noExtraOutput()
                .gatherOutput();
        int result = stopScript.execute();
        if (result != 0) {
            throw new IllegalStateException("Error executing Terraform destroy: " + stopScript.getResultStderr());
        }
        return result;
    }

    @Override
    public void stop() {
        LOG.debug(" STOP effector calling 'terraform destroy' ");
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

    public String getInstallFilename() {
        return format("terraform_%s_%s.zip", getVersion(), getOsTag());
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this, ImmutableMap.of("filename", getInstallFilename()));
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("terraform_%s_%s", getVersion(), getOsTag()))));
        entity.sensors().set(Attributes.DOWNLOAD_URL, TERRAFORM_DOWNLOAD_URL
                .replace("${version}", getVersion())
                .replace("${driver.osTag}", getOsTag()));
    }

    @Override
    public void install() {
        LOG.debug(" -> Downloading and installing Terraform.");
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = new LinkedList<>();
        commands.add(BashCommands.INSTALL_ZIP);
        commands.add(BashCommands.INSTALL_UNZIP);
        commands.add(BashCommands.INSTALL_CURL);
        // Hashicorp server requires at least TLSv1.2
        commands.addAll(commandsToDownloadUrlsAsWithMinimumTlsVersion(urls, saveAs, "1.2"));
        commands.add(format("unzip %s", saveAs));

        newScript(INSTALLING).body.append(commands).execute();
    }

    @Override
    public void customize() {
        LOG.debug(" Copy terraform configuration file. ");
        newScript(CUSTOMIZING).execute();
        InputStream configuration = getConfiguration();
        // copy terraform configuration file(s)
        getMachine().copyTo(configuration, getConfigurationFilePath());

        LOG.debug(" <T> 'terraform init -input=false' ");
        Task<String> initTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), init())
                .environmentVariables(envVars)
                .requiringExitCodeZero()
                .summary("Initializing terraform infrastructure")
                .requiringZeroAndReturningStdout().newTask()
                .asTask());
        DynamicTasks.waitForLast();

        if (initTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform init`! ");
        }
    }

    @Override
    public void launch() {
        Task<String> planTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), plan())
                .environmentVariables(envVars)
                .requiringExitCodeZero()
                .summary("Initializing terraform plan")
                .requiringZeroAndReturningStdout().newTask()
                .asTask());

        DynamicTasks.waitForLast();

        if (planTask.asTask().isError()) {
            throw new IllegalStateException("Error executing `terraform plan`! ");
        }

        try {
            String result = planTask.get();
            LOG.debug("<T> `terraform plan` result: {}", result);
            if (result.contains("No Changes.")) {
                // deployment exists
                // trigger refresh !?
                LOG.debug("Terraform plan exists!!");
            } else {
                Task<String> applyTask = DynamicTasks.queue(SshTasks.newSshExecTaskFactory(getMachine(), apply())
                        .environmentVariables(envVars)
                        .requiringExitCodeZero()
                        .summary("Applying terraform plan")
                        .requiringZeroAndReturningStdout().newTask()
                        .asTask());

                DynamicTasks.waitForLast();

                if (applyTask.asTask().isError()) {
                    throw new IllegalStateException("Error executing Terraform plan!!");
                }
                entity.sensors().set(TerraformConfiguration.CONFIGURATION_IS_APPLIED, true);
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
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

    private String getConfigurationFilePath() {
        return getRunDir() + "/configuration.tf";
    }

    private String getStateFilePath() {
        return getRunDir() + "/terraform.tfstate";
    }

    @Override
    public String makeTerraformCommand(String argument) {
        return format("cd %s && %s/terraform %s", getRunDir(), getInstallDir(), argument);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getState() throws IOException {
        String state = DynamicTasks.queue(SshTasks.newSshFetchTaskFactory(getLocation(), getStateFilePath())).asTask().getUnchecked();
        return ImmutableMap.copyOf(new ObjectMapper().readValue(state, LinkedHashMap.class));
    }
}
